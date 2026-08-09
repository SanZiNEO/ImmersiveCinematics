# 0.4.0 排期:跨维度运镜 + 相机实例队列 + 画中画（F / G1 / G2）

**创建日期**: 2026-08-09（由 `plans/0.3.4/pending-work.md` 的 F 类 / G1 / G2 章节移入）
**状态**: ⏳ 0.4.0 排期，未实施
**关联**: `camera-chunk-preload.md`（区块预加载，跨维度依赖）、`README.md`（0.4.0 远期方向总览）

---

## F 类 · 跨维度运镜（片段级维度声明）

**目标效果**：clip A 在主世界从远处飞向传送门 → clip B 切换后**从下界传送门出发向前飞**——跨维度运镜由**片段边界**切维度：

```json
// CAMERA clip 级维度声明（预留字段，当前不生效）
{ "start_time": 0, "duration": 10, "dimension": "minecraft:overworld",  ... },
{ "start_time": 10, "duration": 12, "dimension": "minecraft:the_nether", ... }
```

**与现有体系的关系（三层）**：

| 层 | 字段/机制 | 现状 | 未来 |
|----|-----------|------|------|
| 脚本级 | `meta.dimension`（已解析存储，无校验） | 预留 | 播放前校验玩家维度匹配脚本声明 |
| **片段级** | **CAMERA clip `dimension`（新预留）** | 无 | 声明片段在哪个维度进行；维度切换点 = clip 边界（配合 morph/cut 转场与过渡处理） |
| 运行时 | 相机永远在玩家当前维度（MC 客户端单维度渲染） | 现状 | 区块预加载落地后：黑幕 → 目标维度预加载 → 切换 → 新维度运镜 |

**依赖**：片段级维度切换依赖 0.4.0 区块预加载（`camera-chunk-preload.md` 跨维度 ticket 管理）与维度切换流程；与维度驻留触发器互不冲突（触发器检测玩家当前维度，语义不随预加载改变）。

---

## G1 · 相机实例队列（多相机实例）— 低成本，状态层

**动机**：`CameraManager` 单例 + 单 `ScriptPlayer` + 单槽 `pendingScript`——同一时刻一套虚拟相机状态覆盖主视角。"只有一个相机先后播多个脚本"对 crossfade / 维度切换 / 预加载提前量不够。

**架构**：CameraManager 持有**相机实例列表**，每实例 = 独立 `ScriptPlayer` + 轨道状态 + path/properties；**渲染时选一个"主输出"实例覆盖主视角**，其余实例后台运行：

- **后台预热（提前量）**：B 片段在 A 播放期间提前跑时间线、预解码音频、配合服务端区块 ticket 预加载（预加载本身是服务端机制，与相机实例解耦；实例预热 = 轨道状态提前计算）
- **crossfade**：两实例同时输出 → 视角 blend
- **维度切换**：F 类片段级维度配合实例切换
- **PiP 第二视角来源**：非主输出实例渲染到离屏

**成本**：纯逻辑状态（虚拟相机），零渲染开销。演进路径：C1 脚本队列（0.3.4 已实施）→ 实例队列（0.4.0）。

---

## G2 · 画中画（PiP）— 功能做，默认关闭

**形态**：任意区域叠加第二视角画面（小窗、半屏分屏均可）——OVERLAY 层已是屏幕百分比坐标，任意区域天然支持：主视角 = 主输出实例（全屏），第二视角 = 另一实例渲染到离屏纹理 → OVERLAY 层贴图。

**技术事实（MCP 反编译 `GameRenderer.renderLevel`）**：世界渲染只有一套视锥/相机矩阵，**第二视角必须第二遍世界渲染**；"一次渲染提取多视角"原版管线不支持；Vulkan（未来版本）优化每遍常数，不改变"双视角双遍"语义。

**降耗设计（借鉴 Iris `ShadowRenderer` 第二视角渲染模式，代码证据）**：

| 机制 | 说明 |
|------|------|
| 独立视锥 | PiP 相机矩阵 → `invokeSetupRender(pipCamera, pipFrustum)`——窄视锥天然剔除 |
| 只渲染区块层 | `invokeRenderChunkLayer` × 4（solid/cutout/cutoutMipped/translucent）——不整段 renderLevel |
| 内容开关 | `render_entities` 可选（默认关）——跳实体/粒子/天空/天气 |
| 低分辨率 FBO | `resolution_scale` 可配（默认 0.5x） |
| 独立渲染缓冲 | 独立 `RenderBuffers` + 结束后恢复——不污染主渲染 |
| 状态保存/恢复 | CullingDataCache save/restore + 云纹理状态 |

Iris 阴影是**每帧**第二遍渲染且可接受——同量级证明可行。

**产品策略**：**功能做，默认关闭**——脚本显式开启（OVERLAY 层类型 `pip` 且指定相机实例/参数）才渲染第二遍；文档标注"开启明显增加渲染开销，建议慎用"。参数：`resolution_scale` / `render_entities` / `render_distance`（渲染距离可缩）。

**渲染优化模组兼容（2026-08-09 调研，sodium-1.20.1-stable / Oculus-1.20.1）**：

- **事实**：Sodium `@Overwrite` 原版区块渲染（`renderLayer` → `SodiumWorldRenderer.drawChunkLayer`；`setupTerrain` 要求 Frustum 实现其 `ViewportProvider` 接口）；Oculus = Iris 的 Forge 移植（同构），Forge 端对应物为 Embeddium。PiP 第二遍裸调原版区块 API 在 Sodium 下**不可靠**（视锥接口不匹配/缺渲染上下文）；Iris 为此维护 `SodiumTerrainPipeline`（约 610 行管线适配）。
- **策略（推荐）**：PiP 走原版 API + **运行时检测 Sodium/Embeddium/Oculus** → 存在时 PiP 第二遍自动禁用 + warn 日志（"请关闭 Sodium 以使用画中画"）；无优化模组时正常。不采用 Iris 级完整适配（成本高，与"默认关闭"定位不符）；未来 PiP 需求提升再升级。
