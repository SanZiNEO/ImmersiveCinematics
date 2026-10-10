# 相机核心拆分与外部 API（B4 扩容）计划

> 生成：2026-10-08（用户裁决：B4 扩容——API 拆分 + 相机核心按架构图拆干净，审计负面项一并整理；**功能要求不变**，必须干净简洁）。
> 依据：[架构图](./mod-architecture-diagram.md)（CAM/COVER/RENDER/FRAMES/TRACK/COORDSRC/DIRSRC 子图 = 目标分层）、[相机状态与覆盖链](./camera-state-plan.md) §1/§2/§6/§7/§10、2026-10-08 相机核心盘点（CameraManager 8 组职责 / CameraTrackPlayer 7 组职责 / 外部访问点全表 / 死代码核实）。
> 执行时机：按队列在批次 B 开工时执行（可提前，由用户裁决）；执行类任务照工作规则串行。

## 〇、目标形态：核心居中，外围围绕核心转

架构图 CAM 子图「**输入源 SRC → 相机核心 CORE → 输出 DEST**」就是目标结构。核心 = 六参数：

| 组 | 参数 |
|---|---|
| 主要核心 · 位置 | `x, y, z` |
| 主要核心 · 朝向 | `yaw, pitch` |
| 次要核心 | `roll, fov, zoom` |

**硬规则**：
1. 核心零依赖外围（`camera/core/` 不 import 输入源/覆盖链/输出/门面）；
2. 外围只经核心读写（输入源产出位姿喂核心、覆盖链在核心值上加工、输出只读核心快照），不许一层套一层；
3. 统一快照 `CameraState`（record）是唯一读侧出口（camera-state-plan §6 接口定稿）。

| 层（架构图子图） | 职责 | 目标落点 | 来源（现状） |
|---|---|---|---|
| CORE 相机核心 | 六参数 + 统一快照 + 写入缓冲 | `camera/core/`：`CameraState`（迁 `api/` 或核心包，执行时定）、`CameraPath`/`CameraProperties` 降为包内写入缓冲 | 现 `camera/` |
| SRC 输入源（COORDSRC / DIRSRC / FRAMES / TRACK） | 坐标源、方向源、参考系、追踪 → 目标位姿 | `camera/source/`：`EntityTargetResolver`、`WorldPointLocator`、位姿求值 | `CameraTrackPlayer` C/D/E 组 |
| COVER 覆盖链（Base → Modifier → CSTATE） | Base Provider（脚本导演/编辑器直控/飞行/外部接管）→ Modifier → 统一状态 | **本轮只立接口留位**，接链归 B1（**接口形状已定稿 2026-10-10**：`camera-state-plan.md`「覆盖链定稿」§2 名单档位 + §12 接口草案） | 未来 B1 |
| 门面/编排（PB.CMGR） | 生命周期 / 编排 / 门面 | `CameraManager`（收缩后薄门面） | `CameraManager` G2/G7 |
| DEST 输出（RENDER / MULTI / 消费方） | 渲染钩子、lane 快照、听者/预加载/预览 HUD | 现状不动（已零接触内部可变对象），只读核心 | `mixin/`、`client/lane/` |
| api 包 | 对外稳定面 | `common/.../api/`（B4 本体） | 新建 |

原则（camera-state-plan §1）：**内部优先，API 顺带**——API 面是内部边界干净后的自然产物，不反过来主导内部设计。

## 一、审计负面项 → 处置总表

| 负面项（2026-10-08 审计 + 盘点） | 处置 |
|---|---|
| `CameraManager` 1016 行上帝对象（8 组职责一锅烩） | 拆 6 类 + 薄门面（§2.1） |
| `CameraTrackPlayer` 1570 行混入选择器解析层 | 拆 4 类（§2.2） |
| 死代码 6 处 + `KeyframeInterpolator.interpolatePosition`（5 参） | 直接删（§2.3） |
| API 未拆分（全仓无 api 包；`CameraPath`/`CameraProperties` public 暴露、`getPath()/getProperties()` 仍 public） | api 包 + 可见性收紧（§2.4） |
| `CinematicOcclusion`（整帧 smartCull=false 补偿） | **不动**——它是「每 lane 独立可见集合」未做的缓解，B3' 落地后删，本计划不碰 |
| 3 个无调用者 TriggerAction + Trigger*Store 读方法死代码 | 不在相机范围——归 C4（动作面收敛）/触发器批次 |

## 二、拆分清单

### 2.1 CameraManager（1016 行）→ 薄门面 + 6 类

| 新类 | 吸收（盘点职责组） | 说明 |
|---|---|---|
| `PlaybackClock` | G3 双时钟 | 游戏共享虚拟时钟 + 预览播放头；纯状态，**首拆（最低风险）**。**命名已合并（2026-10-10）**：以 `clock-abstraction.md` §3.1 的 `Clock` + `GameClock` / `PreviewClock` 为准，不单独建 `PlaybackClock` 类（见 `camera-state-plan.md`「覆盖链定稿」§15）。**已落地（2026-10-10，Q4）**：`CameraManager` 持有两实例并做注入切换（游戏实例 = 游戏时钟、预览实例 = 预览播放头；委托读数 `getGameTimeSeconds()` / `getPreviewTimeSeconds()` 调用方零改动） |
| `CameraStateHolder` | G5 统一快照 | activePath/activeProperties/cameraState + refreshCameraState；核心写入缓冲，**首拆**。**已落地（2026-10-10，Q4）**：字段与 `refresh` 迁入，写入口（`path()` / `properties()` / `setDirect`）收为包内；快照刷新时机逐点不变（帧末 / `!active` 置 null / `deactivateNow` 末尾含接播重建 / 直写穿透） |
| `PlaybackRegistry` | G1 实例列表与查询 | instances/topInstance/instancePlaying/行为并集/帧缓存 |
| `PreviewChannel` | G4 预览通道 | pushScript/setTime/resume/pause/stop/exitPreview/直控标志 |
| `PlaybackLedger` | G6 网络账本 | C2S 回执、暂停握手（含 pauseTransition 检测） |
| `PlaybackLifecycle` | G2 + G8 生命周期/队列 | 启动/退出/停止/待接播/scriptQueue |
| `CameraManager`（留） | G7 帧驱动 | `onRenderFrame` 编排 + 门面转发，目标 ≤ 150 行 |

### 2.2 CameraTrackPlayer（1570 行）→ 编排 + 4 类

| 新类 | 吸收 | 说明 |
|---|---|---|
| `EntityTargetResolver` | D 组（:1192-1565）+ TargetLock/PointState/SelectorPolicy/SELECTOR_CALLPOINTS/MISS_RETRY_MS/describeSelector | selector-model 的落点；与插值零耦合（仅 `resolveEntity` 一个入口）。**只搬不改行为**——已知缺口（CALLPOINTS 缺 yaw_base_from/to、缓存键不含调用点、@a/@r/@n 不解析）留 B10-B15（B10–B14 已全部补齐）。**B15 后（2026-10-10）**：通用机制（解析双路 / 目标锁与切换 / 择一 / 锚点取位 / 平滑 / 捕获留底）迁 `selector/` 包的 `EntitySelectorService` + `SelectorPolicy` + `SelectorSchema`（定义单源 = 调用点名单 / 锚点四值集 / 择一三值集 / `<字段>_<调用点>` 模式）；本类收为相机侧消费适配（关键帧字段读取 + 回落链 + 点源五形态 + 世界上下文注入），公开方法签名不变，消费方零改动（见 `selector-model.md` §4.6 落地形态） |
| `CameraKeyframeEvaluator` | C 组 | 六参数求值/插值/混合（纯计算） |
| `LaneSnapshotCollector` | B 组 lane 捕获 | captureLowerLanes/copyTargetLocksInto/laneSnapshots |
| `WorldPointLocator` | E 组 | 结构/方块定位 + 两级缓存（IO） |
| `CameraTrackPlayer`（留） | A 组 + onRenderFrame | TrackPlayer 接口实现 + 编排 |

### 2.3 死代码删除（功能不变，纯删除，逐条带证据）

1. `CameraManager.hasPendingScript()`（:324-326，无调用者）
2. `CameraManager.deactivate()` 无参 public（:199-201，无调用者；私有 `deactivate(PlaybackInstance)` 保留）
3. `CameraManager.previewSetCamera(float×5)`（:645-649，旧调用者已随游戏内编辑器退役删除）
4. `CameraTrackPlayer.blendVec3`（:1142-1149，无调用者）
5. `CameraTrackPlayer.cachedTarget`/`cachedTargetResolvedAt`（:1152-1153，无读写）
6. `CameraTrackPlayer.lastClipIndex`（:29 及 4 处赋值点，无读取）
7. `KeyframeInterpolator.interpolatePosition` 5 参（:170-179，无调用者）

### 2.4 API 包（B4 本体）与可见性收紧

- 新建 `common/src/main/java/com/immersivecinematics/immersive_cinematics/api/`：**最小稳定面** = `CameraApi`（只读查询：`isActive` / `hasActiveCameraClip` / `getCameraState`）+ `CameraState`（倾向迁入 api 包）+ **Base Provider 接口留位**（**接口形状已定稿 2026-10-10**：`camera-state-plan.md`「覆盖链定稿」§12 草案——`BaseCameraProvider`（`isBaseActive` / `basePriority` / `provideBaseState` / `suppressesModifiers`）；本轮只定义不接链）。事件（实例开始/结束）暂缓——内部稳定后再评估（camera-state-plan §7）。
- 收紧内部实现：`CameraPath`/`CameraProperties` 降为包内可见（渲染 Mixin 已零引用，仅 CameraManager + CameraTrackPlayer 触碰）；`getPath()`/`getProperties()` 收为包内写入口（CameraTrackPlayer 写侧改经 `CameraStateHolder` 的包内接口）。
- 外部调用方（`WebEditorApi`、`FlightController`、听者/预加载/预览 HUD）改经 api 面，行为不变。

## 三、迁移顺序（每步编译过、行为不变、可独立提交）

| 步 | 内容 | 验收 |
|---|---|---|
| P0 | 删死代码（§2.3 七条） | compile + validator + icv2/icgl 复跑 |
| P1 | `PlaybackClock` + `CameraStateHolder` ✅ **已落地（2026-10-10，Q4）**：时钟按 `clock-abstraction.md` §4 步骤 1 落为 `util/Clock` + `util/GameClock` / `util/PreviewClock`（`PlaybackClock` 命名合并，见 `camera-state-plan.md`「覆盖链定稿」§15；`CameraManager` 持有两实例并做注入切换）；`CameraStateHolder`（`camera/`，包内可见）= 写入缓冲 `CameraPath` / `CameraProperties` + 统一快照 `refresh(boolean active)`，`CameraManager.getPath()` / `getProperties()` / `getCameraState()` 保留为委托门面（可见性收紧仍归 P5） | 同上 + quadrant 实机回归 |
| P2 | `PlaybackRegistry` / `PreviewChannel` / `PlaybackLedger` / `PlaybackLifecycle` | 同上 + 预览/接播路径实机 |
| P3 | `EntityTargetResolver` | 同上 + 选择器脚本回归（tests/trigger + camera follow） |
| P4 | `CameraKeyframeEvaluator` / `LaneSnapshotCollector` / `WorldPointLocator` | 同上 + quadrant 实机回归 |
| P5 | api 包 + 可见性收紧 | 同上 + API 面冒烟 + `grep` 边界检查 |

每步照工作规则：一个子任务一个子代理、执行类串行、任务书引用本文段落。

## 四、验收标准（功能不变 × 干净简洁）

**功能不变**：camera-state-plan §10 覆盖目标清单逐项回归——脚本播放（位置/look_at/follow/yaw_base/pitch_base）、插值（linear/smooth/bezier/tangent/morph）、roll/fov/zoom、cam breath、编辑器预览/直控、飞行模式、音频听者、区块预加载、选择器锁定策略；validator 0 新增 issue；quadrant 实机像素回归不变。

**干净简洁**：
- `CameraManager` ≤ 150 行（门面）、`CameraTrackPlayer` ≤ 200 行（编排）；
- 每类单职责；死代码 0；
- 边界：`grep CameraPath\|CameraProperties` 在 `mixin/`、`api/` 零命中；`camera/core/` 不 import 任何外围包；api 包无内部类型泄漏。

## 五、与队列的关系

- 本计划 = **B4 的实现方案**（B4 行扩记）；Base Provider 接链留给 B1（覆盖链），接口先留位。
- B2（每 lane 独立 CameraPath/CameraProperties 核对）：**已结案（2026-10-10）——不需要做**。结论 = 每 lane 的相机状态已由 `CameraLane`（`record CameraLane(CameraState, Clip, float)`，`script/CameraLane.java:20`）快照模型承载，渲染侧逐 lane 读自己的快照（`client/lane/LaneRenderer.java:359`、`client/lane/ScriptLaneDriver.java:127`），`mixin/` 零引用 `CameraPath`/`CameraProperties`；全局 `activePath`/`activeProperties` 只剩「顶层实例顶层 clip 写入缓冲 + 统一快照取值来源」一个角色（写：`CameraTrackPlayer.writeAttributes` / `renderMorph`；读：`CameraManager.refreshCameraState` → 听者/预加载/预览 HUD + `getCameraYaw`）。逐点证据与「为什么不需要 per-lane 可变对象」见 `camera-state-plan.md`「覆盖链定稿（2026-10-10）」§13。本结案不依赖 P1/P4 新结构（只依赖「谁写全局、谁读全局、lane 读什么」），P4 的 `LaneSnapshotCollector` 落地后结论不变、无需重开；`CameraStateHolder` 落地时按「保留 `CameraPath`/`CameraProperties` 为包内写入缓冲」执行（`§〇` CORE 行），不做 per-lane 复制。
- B10-B15（selector 系列）：在 `EntityTargetResolver` 上做（含已知缺口修复）。
- B3'（每 lane 独立可见集合）落地后删 `CinematicOcclusion`。
- C4 接管触发器死代码（3 个 TriggerAction + 读方法），不混入本计划。
