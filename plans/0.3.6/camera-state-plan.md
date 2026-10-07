# 0.3.6 相机状态与覆盖链设计（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [数学函数模型](./math-models.md)
> - [时间插值](./temporal-interpolation.md)
> - [过渡](./transition.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位

**内部优先**：先把相机核心逻辑做干净，让后续扩展有稳定基础。
**API 顺带**：外部模组 API 是内部边界干净后的自然产物，不反过来主导内部设计。

---

## 2. 六参数模型（已确认）

相机最底层是 6 个参数，按组理解：

| 组 | 参数 |
|---|---|
| 位置核心 | `x, y, z` |
| 朝向核心 | `yaw, pitch` |
| 附加三个 | `roll, fov, zoom` |

后续所有相机功能都围绕这 6 个量展开。

对外期望形态：

- 平铺的只读状态接口（position / yaw / pitch / roll / fov / zoom）
- 内部可以分组（位置 / 朝向 / 附加）
- 读侧不直接暴露内部可变对象

---

## 3. 当前问题（方向）

1. 6 个参数分散在 `CameraPath` / `CameraProperties` 两个对象里，没有统一状态边界。
2. `CameraManager` 同时承担生命周期、脚本、预览、时钟、状态读写等多重职责。
3. 多个写入者直接写内部对象，谁最后写谁生效，没有优先级和所有权。
4. `CameraPath` / `CameraProperties` 同时有“当前值”和 staged 过渡值两套职责。
5. Mixin / 渲染层直接依赖具体类，未来扩展会继续扩散。
6. 外部 API 没有稳定入口。

---

## 4. staged 系统（已确认）

全仓检查确认 staged 是死代码：

- `CameraManager.stageTarget*` / `commitStagedState` / `isStagedReady` 无调用者
- `stagedReady` 永远不为 true
- `CameraPath` / `CameraProperties` 的 target / tick / overrideFrom 只被 staged 分支引用
- `TransitionType` 只有自身引用

编辑器不使用 staged：它是固定格式脚本生成器 + 脚本播放器 + HUD 预览，相机值走 direct 写值。

**0.3.6 方向：删除 staged 体系**，避免 direct 与 staged 两套状态模型并存。具体删除范围执行时再核对。

**已落地（2026-10-07）：staged 体系已删除**，编译通过，全仓无残留引用。删除清单：

- `CameraManager`：字段 `stagedProperties` / `stagedPath` / `stagedReady`；方法 `stageTargetPosition` / `stageTargetYaw` / `stageTargetPitch` / `stageTargetRoll` / `stageTargetFov` / `stageTargetZoom` / `commitStagedState` / `isStagedReady`；`tick()` 内的 staged 分支；`activate()` / `startScriptInternal()` / `reset()` 中的 staged 赋值；过时注释 `// ========== 预置状态写入（staged）— 仅供编辑器预览 ==========`。
- `CameraPath`：字段 `targetPosition` / `startPosition` / `transitionDuration` / `transitionProgress`；方法 `setTargetPosition` / `tick` / `overrideFrom`；`setPositionDirect()` / `reset()` 中相关赋值；类 javadoc 中的 staged 说明。
- `CameraProperties`：内部类 `AnimValue` 的 `target` / `start` / `duration` / `progress` 字段及 `setTarget` / `isAnimating`；方法 `setTargetYaw` / `setTargetPitch` / `setTargetRoll` / `setTargetFov` / `setTargetZoom` / `tick`（含 `tickAngle` / `tickScalar`）/ `overrideFrom`；未用的 `MathUtil` 导入；相关 staged 注释。
- `script/TransitionType.java`：整类删除（仅自身引用）。

---

## 5. 覆盖链方向（已确认）

采用两个逻辑链：

### 5.1 Base Chain

- 决定“这一帧的基础相机状态”由谁提供
- 每一层是整帧 6 参数
- 例子：脚本导演、编辑器直控、飞行模式、外部接管
- 规则方向：按优先级选择；同优先级按注册顺序；无活跃层时回退
- Base 层天然是整帧覆盖，不做部分覆盖

### 5.2 Modifier Chain

- 在 Base 状态之上做部分参数修改
- 每个 Modifier 方向字段：优先级、通道掩码、合成模式
- 合成模式（已确认）：`REPLACE` / `ADD` / `MULTIPLY`
- 例子：震屏、后坐力、Dolly Zoom、其他镜头效果

### 5.3 组合顺序（方向）

```text
Base Chain 产出基础状态
  → Modifier Chain 按优先级依次修改
  → 最终 CameraState
  → 渲染钩子读取
```

---

## 6. 渲染钩子方向

最终状态统一由 `CameraState` 读取，应用位置保持现状：

| 参数 | 应用层 |
|---|---|
| position | `CameraMixin` |
| yaw / pitch | `CameraMixin` |
| roll | `GameRendererMixin`（PoseStack） |
| fov / zoom | `GameRendererMixin.getFov` |

Mixin 不再直接依赖 `CameraPath` / `CameraProperties`。

### 接口定稿（2026-10-07）

**形态**：`camera/CameraState.java` 为不可变快照，采用 Java `record`（与本仓 `ScriptMeta.RuntimeBehavior`、`PresetParam` 等一致）：

```java
public record CameraState(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom) {}
```

- 六参数平铺只读；无 getter 前缀（record 访问器 `position()/yaw()/pitch()/roll()/fov()/zoom()`）。
- **不活跃的表示 = `null` 快照**（`CameraManager.getCameraState()` 无活跃相机时返回 `null`），不是"零值 CameraState"；因此实例存在时 `position()` 恒非 null。这样读侧原样保留各自的 `isActive` / `hasActiveCameraClip` / `isPreviewMode` 回落 guard，边界行为不变。
- 生成点：`CameraManager.refreshCameraState()`（`cameraState = active ? new CameraState(activePath.getPosition(), activeProperties.getYaw()/getPitch()/getRoll()/getFov()/getZoom()) : null`）。

**快照缓存点**（依据实测调用时序定稿）：

单帧 `GameRenderer.renderLevel` 内的实际顺序为
`getFov`（:1286，早于 camera.setup）→ `camera.setup`（:1308，触发 `CameraMixin.onSetup` → `CameraManager.onRenderFrame`）→ `prepareCullFrustum`（roll，:1324）→ `LevelRenderer.renderLevel`（`setupRender` 视图中心，:1325）。
`onRenderFrame()` 全仓唯一调用点是 `CameraMixin:97`，即每帧一次。

因此刷新点为：

| 刷新点 | 作用 |
|---|---|
| `onRenderFrame()` 帧末 | 本帧最新值；覆盖 onRenderFrame 之后调用的全部读侧（CameraMixin 读取、roll、setupRender） |
| `onRenderFrame()` 开头 `!active` 分支 | 置 null（`refreshCameraState()` 在 `!active` 时即置 null） |
| `deactivateNow()` 末尾 | 停用即失效；且该处末尾可能接播 `pendingScript`/队列（`active` 又为 true），须重建为接播后的最新值——否则本帧 Mixin 读到 null 而原实现读到接播脚本首帧值 |
| `previewSetCamera()` / `setCameraDirect()` | **直写穿透**：`getFov` 在 `onRenderFrame` 之前调用，直控直写必须立即可见，否则本帧投影 FOV 滞后一帧（与改造前不一致） |

`getFov` 早于本帧 `onRenderFrame`：它读到的快照 = 上一帧 `onRenderFrame` 的值，与改造前读"上一帧写入的 live 值"完全等价（脚本写入只发生在 `onRenderFrame` 内）。同帧内多次读取同一份快照，天然一致。

**Mixin 切换清单**（本任务已落地）：

| 文件 | 改动 |
|---|---|
| `mixin/CameraMixin.java` | 删除 `getPath().getPosition()` / `getProperties().getYaw()/getPitch()`；改读 `mgr.getCameraState()`（`state.position()/yaw()/pitch()`）；`isActive` / `hasActiveCameraClip` guard 原样保留，新增 `state == null` 防御回落 |
| `mixin/GameRendererMixin.java` | `onGetFov`：`state.fov()/state.zoom()`；roll 钩子：`state.roll()`；`isActive && hasActiveCameraClip` guard 原样保留 |
| `mixin/LevelRendererMixin.java` | `cinematicViewCenter()`：改读 `state.position()`；`isActive` / `isPreviewMode` guard 原样保留 |

验证：`sh gradlew compileJava` 通过；`grep -r "CameraPath\|CameraProperties" mixin/` 零命中。`proto/QuadrantProto` 原型分支仍用其自有的 per-camera `CameraPath`/`CameraProperties`（不在本任务范围）。

> **补充（原型实测）**：多相机（多 lane）时，除了这 6 个参数，**渲染状态**（可见区块集合 / 遮挡剔除 / frustum）也要按 lane 独立——目前它们在 `LevelRenderer` 上是单份共享状态，多 lane 会互相重建（表现为画面来回闪）；原型用"整帧统一决定"过渡，正式实现要每 lane 各自维护。见 `quadrant-prototype-results.md` §3.5。

---

## 7. 迁移方向（不锁步骤）

- `CameraManager`：逐步收缩到生命周期 / 编排 / API 门面
- `CameraTrackPlayer`：变成 Base Provider
- 编辑器直控 / 飞行：变成 Base Provider
- Mixin / 渲染层：只读统一状态
- **多相机落地后（2026-10-07 架构推论）**：主画面也是全屏 lane（见[并行播放](./parallel-playback.md) §3.3）——现有"单相机替换"链（`CameraMixin` 主相机分支、`GameRendererMixin` getFov / roll、`LevelRendererMixin` 视图中心、`CinematicOcclusion` 整帧决策）成为过渡实现，最终不再被调用 / 删除；相机状态（`CameraPath` / `CameraProperties` 六参数）转为**每个 lane 一份**，渲染钩子改为按 lane 应用
- `CameraPath` / `CameraProperties`：可能保留为内部实现，也可能被状态 buffer 取代
- 外部 API：Phase 2/3，内部稳定后再评估

**已落地（2026-10-07）：读侧全切统一状态、写侧收口**。所有"读相机状态"的调用点一律经 `CameraManager.getCameraState()`（不可变快照；`null` = 不活跃，各读点保留原有 guard/回落语义）；所有"写相机状态"的路径写完即刷新快照——写侧收口到 `CameraManager` 门面（`onRenderFrame` 帧末、`deactivateNow` / `startScriptInternal`、`previewSetCamera` / `setCameraDirect`（含位置重载）），`FlightController` 不再直写内部对象。`getPath()` / `getProperties()` 存活引用仅剩 `CameraManager` 内部（生产者）+ `CameraTrackPlayer`（玩家写入，经门面取内部对象）+ `proto/QuadrantProto`（原型，排除）。**Base Provider 优先级链与完整封装（`CameraPath`/`CameraProperties` 包可见性收紧）留给并行播放实例模型任务**。

---

## 8. 可能的问题

- 优先级层级怎么分（Base 与 Modifier 是否各自独立）
- Base Override 是否需要 `suppressModifiers`
- 每个通道默认合成方式是什么
- 是否需要 blend / transition（与过渡文档联动）
- Modifier 之后是否统一 clamp / normalize
- 无 Base 活跃时回退到玩家相机还是保持最后状态
- 性能：每帧状态对象是否零分配
- 兼容：现有脚本字段和播放行为不能受影响
- 外部 API 的稳定性、版本、线程语义

---

## 9. 待定

- ~~统一状态接口的最终形态~~ → **已定（2026-10-07）**：`camera/CameraState.java` 不可变 record（六参数平铺），不活跃用 `null` 快照；详见 §6「接口定稿」。
- Base / Modifier 接口的最终形态
- 优先级数值
- 状态 buffer 设计
- ~~staged 删除的具体范围~~ → **已定（2026-10-07，已落地）**：见 §4。
- 外部 API 开放时机
- `zoom` 是否保留独立参数，还是只暴露 effectiveFov

---

## 10. 覆盖目标

0.3.6 重构后，现有能力必须继续正常：

- 脚本播放：位置、look_at、follow、yaw_base / pitch_base
- 插值：linear / smooth / bezier / tangent / morph
- roll / fov / zoom
- cam breath
- 编辑器预览 / 直控
- 飞行模式
- 音频听者
- 区块预加载
- 选择器锁定策略

---

## 11. 相关文档

- [数学函数模型](./math-models.md)
- [时间插值](./temporal-interpolation.md)
- [过渡](./transition.md)
- [迟滞](./hysteresis.md)

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **退出过场首帧视角跳变**：非飞行屏蔽期 `MouseHandlerMixin.onMove` 不拦截 → vanilla 累积 `accumulatedDX/DY`，而 `turnPlayer` 被 BLOCK cancel 时不清零、`CinematicController.syncInputStateAfterExit` 也未清理（现有注释只覆盖飞行态）。**✅ 已修复（2026-10-07）**：新增 `MouseHandlerAccessor`（`@Accessor` 直写 accumulatedDX/DY，无反射），`syncInputStateAfterExit` 开头无条件清零。

---

## 事实核查（2026-10-07）

> 核查范围：本文所有事实性断言（现状描述 / 源码行为 / 类名 / 路径 / 已验证结论 / 已知缺陷）。依据：本仓库源码 + MC 1.20.1 反编译源码。未改设计方向。

### ① 核实为真的断言

- **§3.1 六参数分散在两个对象** — 真。`CameraPath.java` 持有位置 `Vec3 currentPosition`（x,y,z）；`CameraProperties.java` 持有 `yaw/pitch/roll/fov/zoom` 五个 `AnimValue`。证据：`camera/CameraPath.java:18`、`camera/CameraProperties.java:60-64`。
- **§3.2 CameraManager 承担多重职责** — 真。`CameraManager.java` 同时含：生命周期（`activate/deactivate/requestExit/deactivateNow`）、脚本（`playScript/startScriptInternal`）、预览（`previewMode/previewPaused/previewSetCamera/setPreviewDirectControl`）、时钟（`gameTimeSeconds/lastRealNanos/getGameTimeSeconds`）、状态读写（`getProperties/getPath/setCameraDirect/reset`）、帧回调（`onRenderFrame/tick`）。
- **§3.3 多个写入者直写内部对象** — 真。`CameraTrackPlayer.java:312-313,908-909` 直写 `cameraManager.getPath().setPositionDirect(...)` / `getProperties().setAllDirect(...)`；`CameraManager.previewSetCamera`（:349）与 `setCameraDirect`（:600）直写 `activeProperties.setAllDirect`；`proto/QuadrantProto.java:243-244` 直写 `pc.path()/pc.props()`。无优先级/所有权机制。
- **§3.4 两个对象同时承载“当前值 + staged 过渡值”** — 真。`CameraPath` 有 `currentPosition` 与 `targetPosition/startPosition/transitionDuration/transitionProgress`；`CameraProperties.AnimValue` 有 `current/target/start/duration/progress`（`CameraProperties.java:33-40`）。
- **§3.5 Mixin / 渲染层直接依赖具体类** — 真。`mixin/CameraMixin.java:119-122` 调 `mgr.getPath().getPosition()` / `mgr.getProperties().getYaw()/getPitch()`；`GameRendererMixin.java:42,105` 调 `getProperties().getFov()/getZoom()/getRoll()`；`LevelRendererMixin.java:56` 调 `getPath().getPosition()`。均经 `CameraManager.getPath()/getProperties()` 返回的具体 `CameraPath`/`CameraProperties` 实例。
- **§4 staged 死代码（4 条全部为真）**
  - `stageTarget*` / `commitStagedState` / `isStagedReady` 无调用者 — 真。全仓 grep 仅命中 `camera/CameraManager.java:353-394` 的定义处；无任何 java 调用点。
  - `stagedReady` 永远不为 true — 真（运行时）。仅在无调用者的 `stageTarget*` 内被置 `true`（`CameraManager.java:355,360,365,370,375,380`），故运行时恒为 `false`；`tick()` 的 staged 分支（`:562-566`）永不进入。
  - `CameraPath`/`CameraProperties` 的 target / tick / overrideFrom 只被 staged 分支引用 — 真。`CameraPath.setTargetPosition/tick/overrideFrom`、`CameraProperties.setTarget*/tick/overrideFrom` 的唯一调用点分别是 `CameraManager.stageTarget*`（:354,359,364,369,374,379）、`CameraManager.tick()` 的 `if(stagedReady)` 分支（:563-565）、`CameraManager.commitStagedState()`（:386-387）。
  - `TransitionType` 只有自身引用 — 真。全仓 grep 仅命中 `script/TransitionType.java` 自身；`Clip.isMorph()` 用字符串 `"morph"`，`ScriptValidator` 也校验字符串，未引用该枚举。
- **§4 编辑器走 direct 写值、不用 staged** — 真。`editor/EditorScreen.java:691-693` 用 `setPreviewDirectControl(true)+previewSetCamera(...)`，`:921-923` 飞行记录读 `cam.getPath()/getProperties()`，`webui/WebEditorApi.java:250` 用 `setCameraDirect(...)`。全仓无 `stageTarget*` 调用。
- **§6 渲染钩子应用点（表格 4 行全部为真）** — position/yaw/pitch → `mixin/CameraMixin.java:119-123`（`setPosition`/`setRotation`）；roll → `mixin/GameRendererMixin.java:102-108`（`onBeforePrepareCullFrustum`，绕视线轴改 PoseStack）；fov/zoom → `mixin/GameRendererMixin.java:40-43`（`ic$effectiveFov(getFov(), getZoom())`）。
- **§6 补充：LevelRenderer 上渲染状态为单份共享** — 真（本仓库侧证据）。`mixin/LevelRendererMixin.java` 用 `@ModifyVariable setupRender` 把玩家坐标替换为相机坐标（视图中心/ViewArea，:35-45），并以 `immersivecinematics$occlusionToggled/Restore` 两个 `@Unique` 字段在整帧 HEAD/RETURN 间翻转相机遮挡（可见性/视锥，:70-100）——即整帧统一决定，非 per-lane。
- **已知缺陷（退出过场首帧视角跳变）三条子断言全部为真**
  - `MouseHandlerMixin.onMove` 非飞行期不拦截 — 真。`mixin/MouseHandlerMixin.java:62-67`：`if (!FlightModeManager.INSTANCE.isActive()) { …; return; }`，不 cancel。
  - vanilla 累积 `accumulatedDX/DY` 且 `turnPlayer` 被 cancel 时不清零 — 真。vanilla `MouseHandler.onMove` 先 `accumulatedDX += …` 再调 `turnPlayer()`（`build/mc-sources/net/minecraft/client/MouseHandler.java:237-242`），清零在 `turnPlayer()` 内部（:276-277）；本模组 `MouseHandlerMixin.onTurnPlayer`（:113-119）在 HEAD `ci.cancel()`，故清零被跳过。
  - `CinematicController.syncInputStateAfterExit` 未清理累积量、注释只覆盖飞行态 — 真。`control/CinematicController.java:133-134`：注释明写“飞行时 onMove 在 vanilla 累积前已被中继层拦截…退出时保持原样即可”，未清 `accumulatedDX/DY`。
- **§10 覆盖目标清单现状（逐项有类/字段支撑）** — 真：`look_at/follow` 为关键帧级字段（`schema/TrackSchemas.java:60-63`，`CameraTrackPlayer` 处理）；`linear/smooth`（`interpolation` 枚举，`TrackSchemas.java:32`）、`bezier`（`curve`+`BezierPathStrategy`）、`tangent`（`orient` 枚举+`TangentOrientation.java`）、`morph`（`transition` 枚举+`CameraTrackPlayer.renderMorph`）；`roll/fov/zoom`（`CameraProperties`）；`cam breath`（`script/BreathDisturbance.java` + `cam_breath_*` 字段）；编辑器预览/直控（`EditorScreen`+`previewSetCamera`）；飞行（`control/FlightModeManager`）；音频听者（`script/AudioListenerController`、`trigger/client/AudioListenerReporter`）；区块预加载（`trigger/server/ChunkPreloadManager`、`trigger/client/PreloadRequester`）；选择器锁定（`selector_refresh`/`selector_switch_while_alive`/`selector_switch_interval` + `CameraTrackPlayer.TargetLock`）。

### ② 已修正的断言

- 无事实性错误需修正。§4 第 2 条“`stagedReady` 永远不为 true”在**运行时**成立，但源码层面 `stageTarget*` 确有 `stagedReady = true` 赋值——已在上文①按“运行时恒为 false（因赋值者无调用者）”精确化，避免被误读为“源码里没有赋值”。

### ③ 补全的信息

- **§4 补全**：`CameraManager.java:351` 在 `stageTarget*` 上方留有注释 `// ========== 预置状态写入（staged）— 仅供编辑器预览 ==========`，但编辑器实际未调用（见①）。该注释是**过时意图**，非当前行为。
- **§4 补全**：`CameraManager.tick()`（由 `handler/ClientEventHandler.java:72` 每客户端 tick 调用）中的 staged 驱动为 `if (stagedReady) { stagedProperties.tick(1/20f); stagedPath.tick(1/20f); }`（:562-566）；因 `stagedReady` 恒 false，该分支为死代码。
- **§6 补全**：LevelRenderer 侧的渲染状态共享证据为 `LevelRendererMixin` 的两个 `@Unique` 整帧字段（`immersivecinematics$occlusionToggled`/`immersivecinematics$occlusionRestore`），符合“整帧统一决定”的描述。
- **§3.6 补全**：common 源码中未发现任何公开 API 包/接口（包清单仅 camera/mixin/proto/trigger/script/editor/… 等内部包），与“外部 API 没有稳定入口”一致。

### ④ 无法核实的断言

- **§3.6 “外部 API 没有稳定入口”** — 标“未验证”：以“全仓未见 API 包/接口”作合理推断，无法严格证明不存在未来/第三方接入点。
- **§6 补充中引用的 `quadrant-prototype-results.md §3.5`** — 不在本次核查范围（属受限文档），未核对。

### 冲突裁决记录

- 唯一潜在不一致：`CameraManager.java` 的 staged 注释“仅供编辑器预览” vs 本文 §4“编辑器不使用 staged”。二者非行为冲突（代码行为=无调用者，与本文一致）。按 `git log -1 --format=%cI` 取修改时间：`plans/0.3.6/camera-state-plan.md` = **2026-10-06T21:16:28+08:00**，`common/.../camera/CameraManager.java` = **2026-09-09T10:42:54+08:00**；本文较晚，以本文描述为准，代码注释记为过时。
- 跨文档冲突：无（本文与其他 0.3.6 文档无事实冲突需上报）。
