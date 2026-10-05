# 0.3.6 多相机渲染（PIP / 分屏 / 叠化）方案

**状态**: 📋 方案，未实现
**目标版本**: 0.3.6

> 本文讨论“同一时刻渲染多个相机画面”以及“叠化（dissolve）”功能。
> 0.3.5 不做此功能；0.3.6 专门攻克。
>
> **分工**：本文只负责**渲染底层**——怎么把多个相机画面渲染成纹理（渲染管线、FBO、性能、模组兼容）。
> - 多张画面怎么铺到屏幕（取材 / 目标区域 / 不透明度的关键帧化）→ [画面合成](./camera-composition.md)
> - 相机从哪来（多脚本实例并行、lane 概念）→ [并行播放](./parallel-playback.md)

---

## 1. 需求定义

### 1.1 PIP / 分屏

- 不是“带边框的小窗 UI”
- 而是：同一帧内渲染多个相机画面并合成到屏幕
- 例如：左侧显示相机 1，右侧显示相机 2
- 类似监视器墙 / 分屏监控

### 1.2 叠化（Dissolve / Crossfade）

- 不是 morph（相机状态平滑过渡）
- 而是：过渡期内两张画面互相淡化叠加
- 上一个画面 alpha 从 1 → 0，下一个画面 alpha 从 0 → 1
- 剪辑软件里的“叠化”效果

---

## 2. 底层能力

两者共用同一个底层能力：

> **多相机同时渲染到多个 framebuffer，再合成到屏幕。**

- PIP / 分屏 = 每帧渲染 N 个相机，按布局合成
- 叠化 = 过渡期渲染 2 个相机，按 alpha 合成

---

## 3. 技术路线

### 3.1 不是自己搭渲染器

- 复用原版世界渲染管线：`GameRenderer.renderLevel` / `LevelRenderer.renderLevel`
- 用第二个 `Camera` 再调用一次原版世界渲染
- 输出到独立的 `RenderTarget`（FBO）
- 最后把多张纹理合成到屏幕

### 3.2 每帧流程（双相机）

```text
1. 准备相机 A、相机 B（位置/朝向来自脚本路径）
2. 渲染 A → FBO_A
3. 渲染 B → FBO_B
4. 恢复主 framebuffer
5. 合成：
   - 分屏：左右各画一张
   - 叠化：A 画完，B 按 alpha 叠加
6. 继续渲染 GUI / 主画面
```

### 3.3 叠化提前量（预热）

问题：
- 叠化开始那一刻，A 和 B 的画面必须同时存在
- B 不能到叠化开始才第一次渲染，否则第一帧没准备好

解决：
- 在叠化开始前提前渲染 B（预热）
- 预热开始时间 = 叠化开始时间 - 预热时长
- 因为脚本时间线已知，可以精确计算

注意：
- 不是渲染“未来的世界”——世界是动态的，未来帧不存在
- 是提前让 B 的 framebuffer 进入有效状态（warm-up）

---

## 4. 关键难点

| 难点 | 说明 |
|---|---|
| 全局状态切换/恢复 | 当前 framebuffer、Camera、投影/视图矩阵、视口、雾、着色器、深度缓冲、frustum |
| 第二个 Camera | 需要独立 Camera 实例，位置/朝向来自我们的路径 |
| 第二个 Frustum | 副相机要有自己的视锥体，否则裁剪错误 |
| 副画面内容 | 通常只要世界，不要第一人称手/HUD |
| 渲染顺序 | 第二遍在主世界渲染之后、GUI 之前，还是先渲染第二遍再合成，需要定 |
| 光影/模组兼容 | 很多模组假设每帧只调一次 `renderLevel`，二次调用可能冲突 |
| 剔除 / 视锥 | 旋转视图矩阵后重建视锥会触发原版 `Frustum.offsetToFullyIncludeCameraCube` 病态膨胀（jstack 实证挂死，60 帧窗口 4 分钟跑不完）——第二遍不能用主视锥重建，必须用自己的剔除方案 |
| 性能 | 两次世界渲染 ≈ 成本翻倍；**正式合成不降分辨率**（低分辨率只用于编辑器预览传输） |

---

## 5. 脚本 / 数据模型

> **已被取代**：相机来源（多脚本实例 × 每实例多 lane）见[并行播放](./parallel-playback.md)；
> 上屏布局（分屏 / 叠化 / PIP 统一为“取材区域 / 目标区域 / 不透明度”的关键帧参数）见[画面合成](./camera-composition.md)。
> 本文不再维护数据模型草案，只保留渲染底层。

---

## 6. 性能策略

- **原则：不设硬上限，只给推荐**——lane 数由作者决定；我们给成本模型与实测数据（推荐值），帧率不做保证，压力由作者自行评估
- **实测（`perf/second-pass-probe`，轻场景 1.0x 上限口径）**：每多一个 lane ≈ **+0.56ms** 世界渲染（≈ 0.85× 主 `renderLevel`）；N=5 时世界渲染 ≈ 5.5×、帧间隔 1.2→4.0ms——线性，无超线性偏离
- **推荐（不是限制）**：可用 lane 数 ≈ (帧预算 − 基线帧时间) ÷ 单 lane 成本。轻场景单 lane ≈ +0.56ms（60fps 预算 16.6ms 下很宽裕）；重场景单 lane ≈ 0.85 × 主 `renderLevel`（主渲染 5ms 时 ≈ +4.3ms/lane，2–3 个 lane 就吃掉 8–13ms）——**超过推荐值不禁止，帧率自负**
- **分辨率：正式合成走原分辨率**（与主画面同分辨率，靠取材区域裁切再铺屏）；**低分辨率只用于编辑器预览传输**（省带宽）
- 叠化只在过渡期开双渲染，平时单渲染
- 分屏模式长期双渲染，必须做内容 / 视距档位（**不降分辨率**）
- 可选项：副画面跳过实体渲染 / 降低视距 / 关闭粒子

### 6.1 降耗设计（借鉴 Iris `ShadowRenderer` 第二视角渲染模式，自 0.4.0 G2 并入）

| 机制 | 说明 |
|------|------|
| 独立视锥 | 副相机矩阵 → 独立 frustum setup——窄视锥天然剔除 |
| 只渲染区块层 | 只走 chunk layer（solid / cutout / cutoutMipped / translucent），不整段 renderLevel |
| 内容开关 | 副画面实体渲染可选（默认关）——跳实体 / 粒子 / 天空 / 天气 |
| 编辑器预览传输 | 帧推流用低分辨率（省带宽）；**正式合成不降分辨率** |
| 独立渲染缓冲 | 独立 RenderBuffers + 结束后恢复——不污染主渲染 |
| 状态保存/恢复 | 剔除缓存与云纹理状态 save / restore |

Iris 阴影是**每帧**第二遍渲染且可接受——同量级证明副画面渲染可行。
框架不设相机数上限（见[并行播放](./parallel-playback.md)）；以上档位是给作者控制渲染压力的手段。

---

## 7. 兼容性风险

- 光影（Iris/Oculus）：
  - 它们自己会做多次渲染，说明多次渲染可行
  - 但第三方二次调用可能绕过光影的合成，或触发重复特效
  - 需要专门调研：能否在光影环境下拿到“已处理后的画面”
- 性能模组（Sodium）：
  - 渲染状态管理更严格，二次调用前必须确认状态保存/恢复方式

### 7.1 渲染优化模组兼容策略（自 0.4.0 G2 并入）

- **事实**：Sodium `@Overwrite` 原版区块渲染（`renderLayer` → `SodiumWorldRenderer.drawChunkLayer`；`setupTerrain` 要求 Frustum 实现其 `ViewportProvider` 接口）；Oculus = Iris 的 Forge 移植（同构），Forge 端对应物为 Embeddium。副画面第二遍裸调原版区块 API 在 Sodium 下**不可靠**（视锥接口不匹配 / 缺渲染上下文）；Iris 为此维护了约 610 行的管线适配。
- **策略（推荐）**：副画面渲染走原版 API + **运行时检测 Sodium / Embeddium / Oculus** → 存在时副画面自动禁用 + warn 日志（提示关闭对应模组以使用多相机画面）；无优化模组时正常。不做 Iris 级完整适配（成本高）；需求提升后再评估升级。
- 另见 §11：Sodium / Embeddium 的渲染中心来自 `Camera` / `Frustum`，`CameraMixin` 路线已验证，可作为适配时的另一条参考路径。

---

## 8. 实施步骤（0.3.6）

1. 调研：原版 `GameRenderer.renderLevel` 可重入性、状态保存/恢复
2. 原型：单机双 RenderTarget + 二次渲染，先不做光影兼容
3. 多 lane 渲染：N 个相机各渲染到独立 RenderTarget（上屏布局见[画面合成](./camera-composition.md)）
4. 叠化预热：叠化前提前渲染下一 lane，保证首帧有效（预热调度见[画面合成](./camera-composition.md)）
5. 兼容：Iris/Oculus/Sodium 实测
6. 性能：原分辨率副画面 + 内容档位 + 帧耗时统计
7. 文档：SCRIPT_FORMAT / AI_SCRIPTING_GUIDE

---

## 9. 开放问题

- ~~多相机是否走“多条 CAMERA 轨道”还是“单轨道多 camera_id”？~~
  **已回答**：多脚本实例 × 每实例多 lane，不设上限——见[并行播放](./parallel-playback.md)。
- ~~叠化是否只支持两相机，还是 N 相机？~~ / ~~分屏是否要支持任意布局？~~
  **已回答**：分屏 / 叠化 / PIP 统一为合成参数（取材区域 / 目标区域 / 不透明度）的特例，lane 数不设限——见[画面合成](./camera-composition.md)。
- 副画面是否需要支持 look_at / tangent 朝向？
- 是否允许叠化期间主相机继续移动？
- 预热时长是固定值还是脚本可配？

---

## 10. 结论

- 0.3.5 不做
- 0.3.6 作为核心功能攻克
- 先做原型验证二次渲染，再谈上屏合成与编辑器 UI

---

## 11. 参考：Sodium / Rubidium / Embeddium 源码

0.3.5 已验证一个重要事实：

- Sodium / Rubidium / Embeddium 的渲染中心来自 `Camera` / `Frustum`。
- 我们通过 `CameraMixin` 把虚拟相机写进 `Camera`，它们的 `Viewport` / `setupTerrain` 就会自动以虚拟相机为中心渲染。
- 因此 0.3.6 做 PIP / 分屏 / 叠化时，可以优先考虑“复用它们的 Camera/Frustum 管线”，而不是自己再硬改 `LevelRenderer.setupRender`。

参考源码位置：

- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/mixin/core/render/world/WorldRendererMixin.java`
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/client/render/viewport/Viewport.java`
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/mixin/core/render/frustum/FrustumMixin.java`
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/client/render/SodiumWorldRenderer.java`

后续做副相机 / 多 RenderTarget / 光影兼容调研时，这些实现可以作为重要参考。
