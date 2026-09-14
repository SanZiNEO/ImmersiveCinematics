# ImmersiveCinematics 0.3.5 功能增量清单

> **对比基准**：提交 `c564762`（2026-08-13 17:59，版本 **0.3.4**，Architectury 架构）
> **当前状态**：**0.3.5** + WebUI 编辑器（MultiLoader 架构）
> **改动规模**：132 个提交 / 632 个文件 / +40629 −6658 行
>
> 本文按**代码包**组织，供视频文案参考。
> 标记：🆕 全新 · ⬆️ 增强 · 🔧 修复 · ⚠️ 已知限制

---

## 目录

| 模块 | 包路径 | 一句话 |
|---|---|---|
| [一、架构与平台](#一架构与平台) | `common/` `forge/` `fabric/` | 去掉 Architectury 前置 |
| [二、相机与播放](#二相机与播放camera) | `camera/` | 呼吸扰动 v2、动态 yaw 基准、播放队列 |
| [三、脚本与轨道](#三脚本与轨道script) | `script/` | 文件夹组织、前置依赖、音频重构、GIF、预设 |
| [四、触发器](#四触发器triggerserver) | `trigger/server/` | 23 种触发器、前置条件、双通道引擎 |
| [五、区块预加载](#五区块预加载与相机区域triggerserver) | `trigger/server/` | 虚拟相机中心、状态边界差集 |
| [六、网络](#六网络triggernetwork) | `trigger/network/` | 13 个封包、NetworkGuard |
| [七、覆盖层](#七覆盖层overlay) | `overlay/` | 五类层、中心锚点、两级字幕缩放 |
| [八、运行时控制](#八运行时控制control) | `control/` | 18 项行为标志、跳过投票、飞控 |
| [九、编辑器（游戏内）](#九编辑器--游戏内editor) | `editor/` | 面板重构、字段控件驱动、飞行取景 |
| [十、编辑器（WebUI）](#十编辑器--webuiwebui--editor-前端-全新) | `webui/` + `editor/` | 🆕 独立桌面编辑器 |
| [十一、命令](#十一命令command) | `command/` | /icinematics |
| [十二、兼容性](#十二兼容性mixin) | `mixin/` | 渲染模组兼容 |

---

## 一、架构与平台

| 项 | 内容 |
|---|---|
| 🆕 **去除 Architectury** | 从「需要 Architectury 第三方前置」重构为 **MultiLoader** 三模块（common / forge / fabric）。**玩家不再需要额外安装前置模组**，直接丢 jar 即可 |
| 🆕 **Mixin 配置插件** | 新增 `ImmersiveCinematicsMixinPlugin`（Forge / Fabric 各自实现），按平台与环境条件**跳过冲突 Mixin**；不再使用 Java 反射 |
| ⬆️ 平台要求 | Forge 47.x+ / Fabric Loader **0.16.10+** / Minecraft 1.20.1 |
| ⬆️ 构建产物 | `ImmersiveCinematics-forge-1.20.1-0.3.5.jar`、`ImmersiveCinematics-fabric-1.20.1-0.3.5.jar` |

---

## 二、相机与播放（`camera/`）

### 🆕 呼吸扰动 v2
- `cam_breath_type` 四种类型：`perlin`（默认）/ `perlin_axis` / `sine` / `trauma`
- `cam_breath_speed` 控制频率；`trauma` 专属 `cam_breath_trauma` / `cam_breath_decay`
- **确定性**：同 seed + 同时间 → 同抖动，可复现
- 旧字段 `enabled / intensity / seed` 完全兼容

### 🆕 动态 yaw 基准
- `yaw_base`：`world` / `entity` / `line`，`pitch_base` 同理
- `yaw` 变为**相对偏移**，与 `look_at` 互斥
- 基准空间系支持 `fwd / up / right`

### 🆕 look_at 相对目标
- `look_at_target` 对象：绝对点 / 触发点偏移 / 实体偏移 / 坐标偏移
- 兼容旧的 `look_at_target_x/y/z` 字段

### 🆕 镜头追踪
- `follow=entity`：跟随实体，`dx/dy/dz` 为相对实体脚底偏移
- `look_at=coordinate`：注视固定坐标或**结构几何中心**
- `look_at=entity`：注视实体正中心，`look_at_selector` 支持选择器
- 目标切换时按两端世界坐标**插值平滑过渡**

### 🆕 播放队列
- `ScriptQueue` 容量 8
- **不可打断**脚本播放时，新请求排队（priority 降序 + FIFO），结束后自动接播
- **可打断**脚本被请求时立即替换（无渐出）
- `priority` 只用于队列内排序，不能抢占打断规则

### ⬆️ 虚拟时钟
- `gameTimeSeconds` 用 `System.nanoTime()` 真实时间累加，不受游戏内时间影响
- 游戏暂停（配置允许）或编辑器预览暂停时**冻结**
- 暂停/恢复时向服务端发送暂停包并同步暂停音频

### 🆕 时间头唯一源（09-09）
- 时间头与播放状态统一由**游戏端 `playback.state` 回推**，前端不再直接写
- 消除鼠标拖动与回推互相抢时间的问题
- `CameraManager.setTime` 立即同步内部时钟，修复 seek 后读到旧时间导致的回跳

### ⬆️ 相机属性解耦
- 位置（`CameraPath`）与朝向/光学（`CameraProperties`）完全分离
- 每个属性独立跟踪过渡：直接设置（瞬移）或 目标值 + 时长
- 角度用环绕插值（lerpAngle），标量用线性插值
- staged 暂存通道：`stageTarget*` 写入暂存，`commitStagedState()` 原子提交

### 🔧 翻滚角（roll）修复
- 改为绕**相机视线轴**旋转：任何朝向下 `roll > 0` 均为屏幕顺时针（画面向右倒）

---

## 三、脚本与轨道（`script/`）

### 🆕 脚本文件夹组织
- `scripts/` 支持**子文件夹递归加载**（深度 ≤ 5）
- 编辑器与命令显示相对路径（如 `chapter1/boss_fight.json`）
- `id` 仍全局唯一，子目录只影响文件组织

### 🆕 触发器前置依赖
- `triggers[].requires`：前置脚本 id 数组（AND 语义）
- 语义为「**播放完成**」：开始播放且结束播放（跳过 / 打断 / 自然播完都算）
- 支持对象型 `{ "type": "script_played" / "script_started" / "script_completed", "script": "id" }`
- 支持**自定义前置类型**注册；校验器提示失效引用

### 🆕 Schema Java 元数据化
- `schema.json` 文件 → Java 侧 `FieldDef` / `TrackSchemas` / `MetaSchemas` / `SchemaRegistry`
- 类型、默认值、必填、枚举、分组全部在 Java 定义，**单一权威**
- 新增 `SchemaExporter`：导出标准 JSON schema 给 WebUI 动态表单
- 新增 `FieldControl`：按字段类型决定控件（bool→开关 / tristate→三态 / enum≤3→循环 / enum>3→下拉）

### ⬆️ 音频体系重构
- `meta.listener`：听者模式 `player` / `camera`
- AUDIO 轨道回归**原版 SoundEngine**，相对/绝对位置语义 + 默认衰减
- 环境音（群系 / 水下 / 气泡柱 / animateTick）在 camera 模式下**采样到相机位置**
- 编辑器音频联动重写
- 🔧 **失败音频只报一次**（09-09）：缺失或无法加载的 clip 记录一次后跳过，不再每帧重试刷异常

### 🆕 GIF Overlay
- `stbi_load_gif` 拆帧 + 单帧 DynamicTexture 轮播
- 带内存上限与释放

### ⬆️ 循环增强
- `loop_mode` 支持 `repeat`（重复）与 `pingpong`（往复折返，关键帧只写半程）
- 无限循环（`loop: true` + `loop_count: -1`）使脚本**永不自然结束**（唯一退出 = 手动跳过 / 打断）

### ⬆️ 覆盖层锚点统一为中心
- `x/y` = 0.5 即屏幕正中，不再手算左上角偏移

### ⬆️ 字幕两级缩放
- `font_scale`：字号倍数（矩阵缩放，与 MC `/title` 大字同机制）
- `scale_x/y`：在固定字号基础上做百分比缩放
- 最终 = 两者叠加

### 🆕 预设系统
- 参数 schema + 生成函数注册
- 初版预设库（环绕轨道等），生成结果可直接编辑

### 其它
- 6 种轨道：CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT / OVERLAY
- 贝塞尔路径：2 个控制点 + 弧长参数化（`ArcLengthLUT` 德卡斯特里奥自适应细分）→ **匀速曲线运动**
- 片段过渡：CUT 硬切（staged 原子提交）/ MORPH 交叉淡化

---

## 四、触发器（`trigger/server/`）

### 23 种触发器

**轮询类（8）**：`location`、`biome`、`inventory`、`structure`、`gamestage`、`xp`、`dimension`、`observation`

**事件驱动类（15）**：`advancement`、`entity_kill`、`entity_interact`、`dimension_change`、`login`、`item_craft`、`item_use`、`item_consume`、`item_release`、`item_instant_use`、`item_use_interrupt`、`block_interact`、`item_on_interact`、`item_pickup`、`item_drop`

### 🆕 前置条件「播放完成」语义
- 从旧的「触发过」改为「**播放完成**」
- 支持自定义前置类型注册（`PrerequisiteRegistry` / `BuiltinPrerequisites`）
- 依赖检查在去重之前，未解锁时不消耗触发

### 🔧 `item_use_interrupt` 修复
- 松手时记录物品，修复该触发器**永不匹配**的问题

### 其它机制
- 双通道引擎：事件驱动（`eventIndex`）+ 轮询（`pollBuckets`，按间隔分桶）
- `on_enter` / `exit_buffer`：只在进入区域时触发，缓冲避免边界抖动
- `delay` 延迟触发、去重与防重、`repeatable` 语义
- 状态同步：触发 / 完成 / 玩家加入时补发 `S2CTriggerStateSyncPacket`

---

## 五、区块预加载与相机区域（`trigger/server/`）

### 🆕 虚拟相机中心（09-01，最大重构）
- **完全接入原版 `ChunkMap` 差集**：向 `ChunkMap` 注入「玩家 UUID → 虚拟相机 SectionPos」
- 复用原版 `DistanceManager` 方形加载 ticket 与 `isChunkInRange` 的客户端发送 / 遗忘差集
- **删除**自建链路（desired / playerCovered / sentCamera / resync / forget）与**假人方案**
- 效果：远处运镜时区块 / 实体正常加载，画面不卡、不闪、实体数正常

### ⬆️ 状态边界统一差集
- 每次相机中心变化计算 `desired − playerCovered − 已持有`
- 不再使用 far/near 距离门控
- 客户端按视口上报：有活跃 CAMERA 片段上报相机位置，空档上报玩家位置；无 CAMERA 轨道不触发

### 🆕 预热与释放复用
- 下一片段**预热**（`MODE_PREWARM`，按提前量预载首帧区域）
- 脚本结束**释放差集复用**：`playerNeed ∩ sentCameraChunks` 保留，其余补 forget 并补发玩家区

### 其它
- 配置平台持久化：ForgeConfigSpec / Fabric JSON，改配置跨重启生效
- `CameraAnchorManager` 纯坐标锚点（不创建世界实体），供区块 / 刷怪 / 实体同步查询

---

## 六、网络（`trigger/network/`）

- 13 个封包：7 个 S2C + 6 个 C2S
- 🆕 `NetworkGuard`：所有 C2S 发包统一防护，玩家随时退出 / 断线**不再闪退**
- 暂停链路双向握手 + `AckTracker` 超时重发（2s / 最多 3 次）
- 区块预加载链路：`preload_req` / `preload_pos` / `preload_result`

---

## 七、覆盖层（`overlay/`）

- 五类层：画幅比黑边（Letterbox）/ 全屏遮罩（Fade）/ 图片（Image，支持 GIF）/ 字幕（Subtitle）/ 画中画（Pip）
- zIndex 调度，多层叠加
- ⬆️ 锚点统一为中心（0.5 = 居中）
- ⬆️ 字幕两级缩放
- ⚠️ `PipLayer` 目前是占位框（白边 + 半透明黑底），实际画面待后续版本
- ⚠️ MC 透明度补全坑：`Font.adjustColor()` 会把 alpha 0~3 补成不透明 → 渲染层用 `alpha < 4` 跳过规避

---

## 八、运行时控制（`control/`）

- **18 项行为标志**：可跳过 / 可打断 / 末帧保持 / 屏蔽键盘 / 屏蔽鼠标 / 屏蔽生物 AI / 7 项独立 HUD 开关 / 隐藏手臂 / 抑制视角摆动 / 抑制画面扭曲（`suppress_distortion` 独立开关）/ 渲染玩家模型 / 暂停时冻结
- **跳过**：长按 C（可配）跳过，跳过 HUD 显示按键图标 + 长按进度环
- **跳过投票**：多人服务器显示投票进度；脚本级 `skip_vote_ratio`（10~100）独立配置
- ⬆️ **输入屏蔽**：改用中继层 + 公开 API，移除对私有字段的访问
- 🆕 `FlightModeManager`：飞控会话统一入口（进入 / 退出 / 取消 / 每帧 tick / 键盘鼠标转发 / 光学 reset），与编辑器解耦，**WebUI 与游戏内编辑器共用**
- ⬆️ **优雅交接**：播放退出时按当前物理按键状态重同步键盘 + 鼠标，避免「按键失效直到松开重按」

---

## 九、编辑器 · 游戏内（`editor/`）

- 全屏编辑器，960×540 参考分辨率等比缩放，四区布局
- 多轨道时间轴：clip 拖动 / 裁剪、关键帧拖动、框选、吸附、缩放
- 撤销重做（50 步）+ 剪贴板（按轨道类型自动匹配粘贴）
- 完整快捷键：Enter 播放选中 clip、Ctrl+A/C/V/X/Z/Y、方向键微调、F 缩放至全部可见 等
- ⬆️ **面板重构**：左侧 7 个 Tab（脚本列表 / 脚本属性 / Clip 属性 / 关键帧属性 / 轨道列表 / 触发器 / 预设）
- ⬆️ **字段控件按类型驱动**（`FieldControl`）：bool→开关、tristate→三态、enum≤3→循环、enum>3→下拉
- ⬆️ **事件树重构**：命中统一绝对屏幕坐标、滚动容器语义化、容器裁剪
- ⬆️ **播放控制**：播放 / 暂停合并为单按钮 toggle；终止按钮改为**重置播放头到第一帧**（保持预览激活）
- 🆕 **飞行取景**：F6 + WASD / 鼠标操控相机，可记录 / 取消
- 预览区：16:9 保持宽高比 + 实时捕获游戏画面

---

## 十、编辑器 · WebUI（`webui/` + `editor/` 前端）🆕 全新

### 游戏端（`webui/`，8 个类）

| 类 | 职责 |
|---|---|
| `WebEditorServer` | 本地 WebSocket 服务端，**只绑定 127.0.0.1**，支持双向文本 JSON + 二进制帧流 |
| `WebEditorApi` | 消息路由，**19 个命令** |
| `ScriptFileService` | 独立脚本文件服务（两个编辑器共用） |
| `WebRegistryService` | 注册表 / 自动补全数据源，即时查询 MC 注册表 |
| `WebPreviewScreen` | 预览屏幕 + 飞控 HUD（键位提示） |
| `WebFrameCapture` | 专用低分辨率帧捕获（16:9 小 FBO + `glReadPixels`） |
| `WebFrameStreamer` | RGBA 帧经 WebSocket 发送（读帧在渲染线程、发送在 worker 线程，落后只保留最新帧） |
| `WebSocketSession` | 单客户端会话管理 |

**19 个命令**

- 脚本：`script.list` / `load` / `save` / `delete` / `new` / `validate`
- 注册表：`registry.query` / `get`
- Schema：`schema.get`
- 播放：`editor.seek` / `play` / `pause` / `stop` / `setCamera` / `pushScript`
- 飞控：`editor.enter_flight_mode` / `exit_flight_mode` / `cancel_flight_mode`
- 握手：`hello`

### 前端（`editor/`，Electron + Vue 3）

- 独立桌面程序，1920×1080 窗口，深色主题，无边框 + 自绘标题栏
- 三栏布局：左侧面板（脚本 / 轨道 / 预设）· 中间预览 · 右侧面板（属性 / Clip / 关键帧 / 触发器）
- 多轨道时间轴：拖动会话（前端驱动 + 16ms 节流）、统一滚动容器、轨道头吸左、播放头贯穿
- 属性面板按 schema 动态生成表单（bool / number / string / enum / tristate / position / map / 贝塞尔曲线 / 注册表字符串等）
- 触发器编辑器：10 种类型专属编辑器 + 注册表自动补全
- 预览区：OrbitGizmo 相机控制 + 飞控浮层
- 🆕 **飞控模式**：前端发指令，玩家在游戏内用 WASD 取景，实时回传位置 / 朝向 / FOV / Zoom
- 🆕 **离线演示模式**：无游戏连接时可浏览界面
- 时间头以**游戏端为唯一源**

---

## 十一、命令（`command/`）

| 命令 | 说明 |
|---|---|
| `/icinematics play <文件> [玩家]` | 播放脚本，支持 `@a` / `@p` 选择器 |
| `/icinematics stop [玩家]` | 停止播放 |
| `/icinematics reload` | 重新加载脚本并重建触发器索引（不再同步到世界存档） |
| `/icinematics validate <文件>` | 脚本静态校验，一次输出完整问题清单 |

- 命令标识改为「**目录:文件名**」冒号格式（如 `chapter1:boss_fight`）
- Tab 自动补全，路径遍历防护

---

## 十二、兼容性（`mixin/`）

- 🔧 **渲染优化模组兼容**：检测到 Sodium / Rubidium / Embeddium 时跳过 `LevelRendererMixin`，由它们的 Camera/Frustum 管线接管渲染中心；`CameraMixin` 仍驱动虚拟相机，远端画面渲染保留
- 🔧 **Forge 普通 jar 启动修复**：`SoundEngineMixin` 拆分为平台专属实现，Forge 不再依赖 / 内置 MixinExtras
- 🔧 **安全化三处 `@Redirect`**：`SoundManagerMixin` → `@ModifyArg`、`LevelRendererMixin` → `@ModifyVariable`、`BubbleColumnAmbientSoundHandlerMixin` → `@ModifyArg`，降低与其他模组注入冲突概率
- 🔧 **扭曲屏蔽改实现**：不再 `@Redirect` `Mth.lerp`，改为播放期间临时设置原版 `screenEffectScale` 为 0（解决与 SecurityCraft 冲突）
- 🆕 脚本 `meta` 开关 `suppress_distortion`：独立控制屏幕扭曲屏蔽

---

## 附：时间线

| 日期 | 内容 |
|---|---|
| 08-17 | 跳过投票脚本级比例 `skip_vote_ratio` |
| 08-20 | **0.3.5 发布**：预加载 / 运镜 / 音频 / 编辑器 / 预设 / GIF / 去 Architectury |
| 08-22 | 渲染模组兼容、Forge 打包修复 |
| 08-29~31 | 触发器前置条件、预加载差集、空片段处理、编辑器收尾 |
| 09-01 | 虚拟相机中心接入原版 ChunkMap、**WebUI 编辑器启动** |
| 09-05 | WebUI：触发器编辑器重构、F9 快捷键、时间轴修复 |
| 09-09 | 时间头唯一源、独立飞控模块、时间轴滚动、图标体系 |
