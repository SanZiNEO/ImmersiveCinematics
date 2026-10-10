# 0.3.6 相机状态与覆盖链设计（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、公式、JSON、迁移步骤等执行时再定
> - **覆盖链（§5 / §8 / §9）已定稿：见「覆盖链定稿（2026-10-10，B1-a ∪ B2 结案）」小节**（§8 / §9 条目已就地标注「已定」）
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
4. ~~`CameraPath` / `CameraProperties` 同时有“当前值”和 staged 过渡值两套职责~~ → **已被 §4 取代（staged 已删除，2026-10-07 落地；2026-10-09 回写）**：两对象现只剩“当前值”一套——`CameraPath.java:14-15` 仅 `currentPosition`，`CameraProperties.AnimValue`（:26-33）仅 `current`；全 camera 包 `targetPosition/startPosition/transitionDuration/transitionProgress/overrideFrom/staged` 零命中。
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

单帧 `GameRenderer.render` 内的实际顺序为
**HEAD：`CameraManager.onRenderFrame()`**（帧驱动 + lane 注册；2026-10-07 主相机替换链退役后挂点从 `CameraMixin.onSetup` 迁到 `LaneRendererMixin`（`renderLevel` RETURN），同日为消除「视图中心 / 遮挡决策滞后一帧」再前移到 `GameRendererMixin` 的 `render` HEAD，每帧一次）
→ `renderLevel` 内：`getFov`（:1286）→ `camera.setup`（:1308）→ `prepareCullFrustum`（roll，:1324）→ `LevelRenderer.renderLevel`（`setupRender` 视图中心，:1325）→ RETURN：`LaneRenderer.render()`（lane 渲染 + 合成）。

因此刷新点为：

| 刷新点 | 作用 |
|---|---|
| `onRenderFrame()` 帧末 | 本帧最新值；覆盖 onRenderFrame 之后调用的全部读侧（lane 收集、听者、预加载、预览 HUD） |
| `onRenderFrame()` 开头 `!active` 分支 | 置 null（`refreshCameraState()` 在 `!active` 时即置 null） |
| `deactivateNow()` 末尾 | 停用即失效；且该处末尾可能接播 `pendingScript`/队列（`active` 又为 true），须重建为接播后的最新值——否则本帧读侧读到 null 而原实现读到接播脚本首帧值 |
| `previewSetCamera()` / `setCameraDirect()` | **直写穿透**：直控直写必须立即对读侧可见，否则预览 HUD / 飞控读数滞后一帧 |

**读侧切换清单（0.3.6 两次落地）**：

| 阶段 | 文件 | 改动 |
|---|---|---|
| 统一状态（已落地） | `mixin/CameraMixin.java` / `GameRendererMixin.java` / `LevelRendererMixin.java` | 改读 `mgr.getCameraState()`（`state.position()/yaw()/pitch()/roll()/fov()/zoom()`），保留各自 guard |
| **主相机替换链退役（2026-10-07）** | `mixin/CameraMixin.java` / `GameRendererMixin.java` / `LevelRendererMixin.java` | **渲染侧读点全部删除**：lane 相机读自己的 `CameraLane` 快照（`LaneRenderer` 逐 lane 渲染）；主相机不再被接管。`LevelRendererMixin` 的视图中心改写改为 lane 驱动（本帧最上层 lane 的相机位置，整帧单一中心；无 lane = 原版玩家坐标） |
| 同上 | `AudioListenerController` / `PreloadRequester` / `WebPreviewScreen` | 保留 `getCameraState()`（值 = 顶层实例顶层活跃 clip 六参数，与最上层 lane 同源同值）；听者相机另加 `cameraListener()` 代理（原版听者相机随主链退役变成玩家相机） |

验证：`sh gradlew compileJava` 通过；`grep -r "CameraPath\|CameraProperties" mixin/` 零命中（渲染 Mixin 不再接触内部可变对象）。

> **补充（原型实测）**：多相机（多 lane）时，除了这 6 个参数，**渲染状态**（可见区块集合 / 遮挡剔除 / frustum）也要按 lane 独立——目前它们在 `LevelRenderer` 上是单份共享状态，多 lane 会互相重建（表现为画面来回闪）；原型用"整帧统一决定"过渡，正式实现要每 lane 各自维护。见 `quadrant-prototype-results.md` §3.5。

---

## 7. 迁移方向（不锁步骤）

- `CameraManager`：逐步收缩到生命周期 / 编排 / API 门面
- `CameraTrackPlayer`：变成 Base Provider
- 编辑器直控 / 飞行：变成 Base Provider
- Mixin / 渲染层：只读统一状态
- **多相机落地后（2026-10-07 架构推论）**：主画面也是全屏 lane（见[并行播放](./parallel-playback.md) §3.3）——现有"单相机替换"链（`CameraMixin` 主相机分支、`GameRendererMixin` getFov / roll、`LevelRendererMixin` 视图中心、`CinematicOcclusion` 整帧决策）成为过渡实现，最终不再被调用 / 删除；相机状态（`CameraPath` / `CameraProperties` 六参数）转为**每个 lane 一份**，渲染钩子改为按 lane 应用（**2026-10-10 校正**：「每 lane 一份」已由 `CameraLane` / `CameraState` 快照实现，不再需要 per-lane 的 `CameraPath` / `CameraProperties` 可变对象——见定稿 §13）
  - **✅ 已落地（2026-10-07）**：主相机分支 / 主相机 getFov / roll 钩子已删除；视图中心改写收窄为 lane 专用（读正在渲染的 lane 自己的相机位置）；`CinematicOcclusion` 保留但输入改为 lane 相机（整帧统一仍必需——可见集合共享单份）；帧驱动挂点迁到 `LaneRendererMixin`；听者相机改由 `AudioListenerController.cameraListener()` 提供。逐点清单见[并行播放](./parallel-playback.md) §3.3 落地记录。
  - **lane 相机状态：已结案（2026-10-10，B2 核对）——不需要每 lane 一份 `CameraPath` / `CameraProperties`**，已被 `CameraLane` / `CameraState` 快照模型取代：渲染侧逐 lane 读自己的快照，全局 `activePath` / `activeProperties` 收窄为「顶层 clip 写入缓冲 + 统一快照取值来源」（听者 / 预加载 / 预览 HUD / 方向性预加载）。证据与逐点结论见下方「覆盖链定稿」§13。
  - **未做**：每 lane 独立可见集合 / 遮挡剔除状态（**与 B2 无关**——那是渲染状态不是相机状态；见 §6 补充与 `multi-camera-rendering.md` §12.8-B，归 B3）。
- `CameraPath` / `CameraProperties`：**已定（2026-10-10，见定稿 §11）**：保留为包内「当前值写入缓冲」（`camera-core-split.md` §〇 CORE 行），不被状态 buffer 取代；覆盖链的每帧工作区是独立的 `CameraStateBuffer`（定稿 §12）。
- 外部 API：Phase 2/3，内部稳定后再评估

**已落地（2026-10-07）：读侧全切统一状态、写侧收口**。所有"读相机状态"的调用点一律经 `CameraManager.getCameraState()`（不可变快照；`null` = 不活跃，各读点保留原有 guard/回落语义）；所有"写相机状态"的路径写完即刷新快照——写侧收口到 `CameraManager` 门面（`onRenderFrame` 帧末、`deactivateNow` / `startScriptInternal`、`previewSetCamera` / `setCameraDirect`（含位置重载）），`FlightController` 不再直写内部对象。`getPath()` / `getProperties()` 存活引用仅剩 `CameraManager` 内部（生产者）+ `CameraTrackPlayer`（玩家写入，经门面取内部对象）+ `proto/QuadrantProto`（原型，排除）。**Base Provider 优先级链与完整封装（`CameraPath`/`CameraProperties` 包可见性收紧）留给并行播放实例模型任务**。

---

## 8. 可能的问题

- 优先级层级怎么分（Base 与 Modifier 是否各自独立） → **已定（2026-10-10，见定稿 §1）**
- Base Override 是否需要 `suppressModifiers` → **已定（2026-10-10，见定稿 §3）**
- 每个通道默认合成方式是什么 → **机制已定、通道缺省表待拍板（2026-10-10，见定稿 §4 与 §14①）**
- 是否需要 blend / transition（与过渡文档联动） → **已定（2026-10-10，见定稿 §5）**
- Modifier 之后是否统一 clamp / normalize → **已定（2026-10-10，见定稿 §6）**
- 无 Base 活跃时回退到玩家相机还是保持最后状态 → **已定（2026-10-10，见定稿 §7）**
- 性能：每帧状态对象是否零分配 → **已定（2026-10-10，见定稿 §0 硬约束 2 与 §8）**
- 兼容：现有脚本字段和播放行为不能受影响 → **已定（2026-10-10，见定稿 §0 硬约束 1 与 §9）**
- 外部 API 的稳定性、版本、线程语义 → **已定（2026-10-10，见定稿 §10）**

---

## 9. 待定

- ~~统一状态接口的最终形态~~ → **已定（2026-10-07）**：`camera/CameraState.java` 不可变 record（六参数平铺），不活跃用 `null` 快照；详见 §6「接口定稿」。
- Base / Modifier 接口的最终形态 → **已定（2026-10-10，见定稿 §12 接口草案）**
- 优先级数值 → **已定（2026-10-10，见定稿 §2 档位表 + §11）**
- 状态 buffer 设计 → **已定（2026-10-10，见定稿 §11；工作区 `CameraStateBuffer` + 保留 `CameraPath`/`CameraProperties` 为写入缓冲）**
- ~~staged 删除的具体范围~~ → **已定（2026-10-07，已落地）**：见 §4；§3 第 4 条的“当前值 + staged 两套”旧口径随之作废（2026-10-09 回写）。
- 外部 API 开放时机 → **已定（2026-10-10，见定稿 §11）**
- `zoom` 是否保留独立参数，还是只暴露 effectiveFov → **已定（2026-10-10，见定稿 §11；保留独立参数）**

---

## 覆盖链定稿（2026-10-10，B1-a ∪ B2 结案）

> 本小节结清 §8 九条与 §9 未结项，并回写 B2（每 lane 一份 `CameraPath` / `CameraProperties` 核对）结论。§8/§9 里的「见定稿 §N」均指本小节；历史原文一律保留（就地标注「已定」）。
> 依据：本文 §5（覆盖链方向，已确认）/ §6 接口定稿 / §7 写侧收口 / §10 覆盖目标；[相机核心拆分](./camera-core-split.md) §〇 / §2.1 / §2.2 / §2.4 / §五；[过渡](./transition.md) §1 / §4 / §6 / §9 与事实核查③；[并行播放](./parallel-playback.md) §3.1 / §3.3 / §3.4；[时钟抽象](./clock-abstraction.md) §3.1 / §3.2 / §4 步骤 1。
> 行号取 2026-10-10 工作区（**会漂移**，且 B4-P0 正在删死代码）——引用一律「文档 + 小节」+「`类:行`」双标，行号仅作定位线索。
> 结论分三类：**定**（有依据，实现照此）｜**待拍板**（真二选一，见 §14）｜**留位**（本轮不实现，接口先留）。

### 0. 硬约束（不变量，B1 全链适用）

1. **旧脚本零回归**：脚本 JSON schema 与字段语义不变（`Clip` / `Keyframe` / `Timeline` 字段、`transition` / `orient` / `interpolation` 等枚举值不动）；`CameraTrackPlayer` 以「脚本导演 Provider」接入后，单实例播放的六参数逐帧值与接入前一致；validator 0 新增 issue；**§10 覆盖目标逐项回归**（脚本播放 / 插值 / roll·fov·zoom / cam breath / 编辑器预览·直控 / 飞行 / 音频听者 / 区块预加载 / 选择器锁定——验收口径见 `camera-core-split.md` §四）。
2. **覆盖链本体每帧零分配**：Base 选择 + Modifier 合成全程在预分配的 `CameraStateBuffer` 上就地读写（§12），不新建对象、不装箱、不用 `Stream`、不每帧建 List。**唯一分配点** = 状态快照 record 的产出，沿用 §6 既有口径（每帧 1 份全局 + 每 lane 每 clip 1 份），覆盖链**不新增**分配。
3. **写侧仍收口**：所有写入经 `CameraManager` 门面（§7「写侧收口」），覆盖链不新增直写内部对象的路径；`getPath()` / `getProperties()` 的可见性收紧归 B4（`camera-core-split.md` §2.4）。

### 1. 链形态与优先级域（§8-1）

**定**：Base Chain 与 Modifier Chain 是两条**独立优先级域**，数值互不比较、不设「全局优先级」。

- Base 选择：高优先级活跃层胜；**同优先级 = 后注册者胜**（后来者居上——与 `parallel-playback.md` §3.3 听者「启动最晚的活跃实例」、§3.4 叠放「后来者居上」同向）。
- Modifier 应用序：按优先级**升序**应用（数值小者先、大者后——高优先级落在最终值上）；同优先级 = 先注册者先应用。
- 链间顺序固定 = §5.3：`Base Chain → Modifier Chain → 最终 CameraState → 渲染钩子`。两条链按固定顺序串联，数值不需要跨链可比。

### 2. Base Provider 名单与档位（§9「优先级数值」）

**定**：名单 = 4 层（`transition.md` 事实核查③ 的现状来源；`camera-core-split.md` §〇 COVER 行）：

| 档位 | Provider | 现状来源 | 现状写入路径 |
|---|---|---|---|
| 400 | 外部接管 | 暂无公开入口（`transition.md` 事实核查③：「外部」暂无公开输入入口） | B4-P5 留位（`camera-core-split.md` §2.4） |
| 300 | 飞行 | `control/FlightController` | `FlightController.java:295` → `CameraManager.setCameraDirect(Vec3, 6)`（`CameraManager.java:989-992`） |
| 200 | 编辑器直控 | `webui/WebEditorApi.java:280` | 5 参数直写（不动位置）：`CameraManager.setCameraDirect(float×5)`（`CameraManager.java:977-980`） |
| 100 | 脚本导演 | `script/CameraTrackPlayer` | `renderMorph`（`CameraTrackPlayer.java:365-366`）与 `writeAttributes`（`:1057-1058`） |

- 档位间隔 100 供未来插入；**数值本身不承载语义，只承载相对序**，实现时可调（相对序见 §14②）。
- 现状对位（「脚本导演压不过直控 / 飞行」由**两种不同机制**实现，且现状并不统一——这是链接管要消除的时序依赖）：
  - 飞行 = 显式标志：`FlightModeManager.enter` 置 `setPreviewDirectControl(true)`（`FlightModeManager.java:56`）→ `CameraTrackPlayer.onRenderFrame` 在 `isPreviewDirectControl()` 时直接返回、跳过轨道写入（`CameraTrackPlayer.java:166-167`）；退出 / 停播时清标志（`FlightModeManager.java:66,75`、`CameraManager.java:860`）。编辑器取景走的就是这条路（`WebEditorApi.handleEnterFlightMode` → `WebPreviewScreen.enterFlightMode`）。
  - 编辑器直控 = 纯直写入口：`setCameraDirect(float×5)`（`WebEditorApi.java:280` ← `editor.setCamera`）**不置直控标志**，只写写入缓冲 + 刷新快照。预览暂停也**不暂停播放器**（`CameraManager.pause()` 仅置 `previewPaused`，`CameraManager.java:467-471`）——轨道播放器仍按冻结的预览时钟每帧写入，所以直写值在同一帧内立即可见（§6 直写穿透），但**下一帧会被轨道写入覆盖**。本定稿不判定这是否算缺陷（现状前端是否仍在发 `editor.setCamera` 未核实）；链接管后统一规则会消除该时序依赖。
  - 链接管后统一为一条规则：**直控态 = 该层活跃 → 脚本导演层不被选中**（取代上述两套机制）；直控态退出后回到脚本导演。
- **部分通道接管不进 Base**：§5.1 已确认「Base 层天然整帧覆盖，不做部分覆盖」——只接管某通道的需求（如外部只改 fov）用 **Modifier + 通道掩码 + `REPLACE`** 表达（§5.2）。编辑器直控的「5 参数直写」由该层内部持有位置（进入直控态时捕获当前位置，此后位置不变）→ 对链输出仍是整帧 6 参数，与现状「位置不被 5 参数写入触碰」等价。

### 3. `suppressModifiers`（§8-2）

**定**：需要，作为 Base 层的可选字段（接口 `default false`，§12）。语义 = 该层胜出时本帧**跳过 Modifier 链**，链输出 = 该层原始 6 参数。缺省 `false`：预览 / 直控照常显示最终合成结果（所见即所得）；字段是给外部接管 / 精确直控留的逃生门，第一版无内置层置 `true`。

### 4. 合成模式与缺省（§8-3）

**定**：Modifier 字段 = 优先级 + 通道掩码 + 合成模式（§5.2）；合成模式已确认 `REPLACE` / `ADD` / `MULTIPLY`。

- 通道掩码 = **6 位**：`position`（整体一位，不分 x / y / z）+ `yaw` + `pitch` + `roll` + `fov` + `zoom`。理由：与 §2 的分组（位置核心 = x,y,z 一组）和 §6 record 形态（`position` 是单个 `Vec3`）一致；`ADD` 语义下偏移类效果写整分量即可（只动 y 的震屏写 `(0, dy, 0)`），不需要逐轴掩码（逐轴粒度见 §14④）。
- 合成 = 逐通道：`REPLACE` 直写该通道；`ADD` 相加（角度相加后按环绕语义交给消费侧）；`MULTIPLY` 相乘。
- **缺省合成模式**：`REPLACE` **必须显式声明**（作缺省会让 Modifier 退化成第二个 Base，破坏 §5.1 / §5.2 分工）；`ADD` / `MULTIPLY` 的通道缺省表见 §14①（倾向：position / yaw / pitch / roll → `ADD`，fov / zoom → `MULTIPLY`）。
- 单一 `mode` 作用于该 Modifier 的整个掩码（§5.2 字段口径），不做「逐通道多模式」；需要不同模式的通道 → 拆成多个 Modifier。

### 5. blend / transition（§8-4）

**定**：B1 不实现过渡；第一版 Base 切换 = **Cut**（瞬时 = `transition.md` §1 的「duration = 0 的特例」）。

- 过渡属应用层（`transition.md` §1「属于应用层」），落地顺序排在链之后（`transition.md` §9 步骤 5「接入 Base / Modifier 链」；`next-phase-batches.md` B18 = 过渡步骤 3-8，依赖 B1）。
- 过渡**不放进 Base Provider 内部**：Base 层接口只产一个整帧状态（§5.1），而 A→B 混合需要两个状态；也**不放进 Modifier**：Modifier 字段是「掩码 + 模式」的部分修改（§5.2），装不下整帧双状态权重。→ 过渡是**独立层**，包装链的输入 / 输出（`transition.md` §6 三候选中的「独立过渡层」）。
- B1 只保证链输出可被过渡层包装（接口留位），不在 B1 交付。

### 6. clamp / normalize（§8-5）

**定**：链上**不做**统一 clamp / normalize。

- 现状无任何中间钳制：`CameraProperties.AnimValue.setDirect` 直赋（`CameraProperties.java:30-32`）；唯一安全钳制在渲染边界 `GameRendererMixin.ic$effectiveFov`（`fov / zoom` 后钳到 `[0.1, 170]`，`GameRendererMixin.java:126-131`）——**保持不变**。
- 理由：链上钳制会让「分通道合成」变成顺序相关（先 ADD 再钳 ≠ 先钳再 ADD），并破坏角度环绕语义（yaw 超 360° 合法，环绕是消费侧的事）。
- NaN / ±Inf 守卫放在**链入口**（Modifier 写入值进链前，复用 `MathUtil.sanitizeFloatLogged` 的既有口径，`MathUtil.java:223`；脚本字段侧先例 `Clip.java:54-57`），不新增第二套钳制。
- 各输入源自己的范围约束留在各自层（如飞行 `FlightController.java:237-240` 的 fov∈[30,110] / zoom∈[0.5,100]），不下放到链。

### 7. 无 Base 活跃时的回退（§8-6）

**定**：回退**玩家相机**（= 现状，零回归）。

- 依据：`parallel-playback.md` §3.3「没有任何 lane 时 = 原版视角」+ 落地表「视图中心无活跃 lane 时回落原版玩家坐标」；`getCameraState()` 的 `null` = 不活跃口径（§6 接口定稿）已让读侧各自 guard 回落（`AudioListenerController.java:78-81` 回落 `playerCamera()`）。
- 「保持最后状态」**不采用**：会让停播后画面冻结在虚拟相机，与「零 lane = 原版视角」冲突；「停在末尾」已由 `hold_at_end`（实例不结束，`parallel-playback.md` §3.1 生命周期按实例）表达，不需要第二种机制。

### 8. 每帧零分配（§8-7）

**定**：见 §0 硬约束 2。落地口径：

- `CameraCoverChain.evaluate(...)` 每帧一次：Base 选择 = 遍历按优先级预排好的注册表 + `isBaseActive()` 比较（不建列表、不排序、不分配）；Modifier 应用在同一个 `CameraStateBuffer` 上就地读写。
- `CameraStateBuffer` = 链的唯一工作区（位置 `double`×3 + 其余 5 个 `float`），链持有、跨帧复用；`CameraState`（record，不可变）只在快照产出点创建。
- 注册表只在注册 / 注销时改动（不在帧内）；不用 `Stream` / 装箱 / `Optional`。
- 验收：读码 + 需要时压测（`next-phase-batches.md` B1 验收口径）。

### 9. 兼容（§8-8）

**定**：见 §0 硬约束 1。补充：`CameraTrackPlayer` 的内部求值（关键帧插值、look_at / follow、morph、cam breath、路径策略）**零改动**——覆盖链在它之外；无 Modifier 时链输出 = 脚本导演层输出 = 现状全局值（逐帧等值）。旧脚本不因链的存在改变字段含义或播放行为。

### 10. 外部 API：稳定性 / 版本 / 线程语义（§8-9）

**定**：

- **线程**：相机状态读写只在**客户端主线程**（tick 与渲染同线程：帧驱动挂 `GameRendererMixin` 的 `render` HEAD，tick 挂 `ClientEventHandler`）。跨线程调用方必须 marshal 到主线程——既有先例：`WebSocketSession.java:41-42`「所有 CameraManager/播放器操作必须在 Minecraft 主线程执行」→ `Minecraft.getInstance().execute(...)`。外部 API 不做跨线程同步、不承诺线程安全（跨线程调用 = 调用方责任）。
- **稳定性**：0.3.6 不承诺外部稳定性。B4-P5 只落只读面（`CameraApi`：`isActive` / `hasActiveCameraClip` / `getCameraState`）+ Base Provider 接口留位（`camera-core-split.md` §2.4）；事件、写入口、Modifier 注册不开放（§1「内部优先，API 顺带」）。
- **版本**：API 随模组版本走；破坏性变更记 changelog 并在 `plans/` 标注。0.3.6 内可自由改（尚未对外承诺）。

### 11. §9 其余未结项

| 项 | 结论 |
|---|---|
| Base / Modifier 接口的最终形态 | **定**：见 §12 草案（B1-b / B1-c 实现；B4-P5 留位）。 |
| 优先级数值 | **定**：Base = §2 档位表（100 间隔）；Modifier 不设固定档位表——只定「升序应用、同值按注册顺序」的序语义 + 缺省 `0`（效果数量与来源未知，固定档位表无意义）；内置示例（震屏等）的具体数值在 B1-d 定。 |
| 状态 buffer 设计 | **定**：覆盖链工作区 = `CameraStateBuffer`（§12）；`CameraPath` / `CameraProperties` **保留**为「当前值写入缓冲」（`camera-core-split.md` §〇 CORE 行「降为包内写入缓冲」），**不被取代**——两者职责不同：写入缓冲 = 当前值（快照取值来源），工作区 = 每帧合成中间值。 |
| 外部 API 开放时机 | **定**：0.3.6 只留位不开放；开放条件 = 内部稳定 + 覆盖链落地（B1-b/c）+ 至少一个版本观察期（`plans/0.4.0` 起评估）——§1、§7「外部 API：Phase 2/3」、`camera-core-split.md` §2.4 / §五。 |
| `zoom` 是否保留独立参数 / 只暴露 effectiveFov | **定**：**保留独立参数**。`zoom` 是既有脚本字段，且 §10 覆盖目标点名「roll / fov / zoom」必须继续可用；`CameraState` 是六参数平铺 record（§6 接口定稿），删 `zoom` = 改已定接口形态。`effectiveFov = fov / zoom` 只作**派生读数**（渲染口径 `GameRendererMixin.java:126-131`）供需要「一个数」的消费方使用，不取代两个独立参数。 |

### 12. 接口形状草案（B1-b/c 实现 + B4-P5 留位）

```java
// ===== api/（对外留位：camera-core-split §2.4「Base Provider 接口留位」；B4-P5 落地，B1-b 接链）=====

/** Base 层：决定“这一帧的基础相机状态”；每层产出整帧六参数（§5.1）。 */
public interface BaseCameraProvider {
    /** 本层本帧是否活跃；链按优先级从高到低询问，取第一个活跃层。 */
    boolean isBaseActive();
    /** 高者胜；同优先级 = 后注册者胜。内置档位见定稿 §2。 */
    int basePriority();
    /** 整帧六参数；仅在 isBaseActive() 为 true 时被调用。 */
    CameraState provideBaseState();
    /** true = 本帧跳过 Modifier 链；缺省 false（定稿 §3）。 */
    default boolean suppressesModifiers() { return false; }
}

// ===== camera/cover/（内部：camera-core-split §〇 COVER 行）=====

/** 通道位（6 位；position 整体一位）。 */
public final class CameraChannels {
    public static final int POSITION = 1 << 0, YAW = 1 << 1, PITCH = 1 << 2,
                            ROLL = 1 << 3, FOV = 1 << 4, ZOOM = 1 << 5;
    private CameraChannels() {}
}

/** 合成模式（§5.2 已确认）。 */
public enum ComposeMode { REPLACE, ADD, MULTIPLY }

/** Modifier：在 Base 状态之上按掩码做部分修改（§5.2）。 */
public interface CameraModifier {
    /** 应用序：升序（小者先应用）；同值 = 先注册者先应用。 */
    int modifierPriority();
    /** 生效通道位掩码（CameraChannels 的位）。 */
    int channelMask();
    /** 掩码内通道的合成方式；null = 通道缺省（定稿 §4 / §14①）。 */
    ComposeMode composeMode();
    /** 把本层要合成的六参数写入 out（只保证掩码内通道有效）。 */
    void writeValue(CameraStateBuffer out);
}

/** 零分配工作区（核心包内；跨帧复用）。 */
public final class CameraStateBuffer {
    double px, py, pz;
    float yaw, pitch, roll, fov, zoom;
    public void set(CameraState s);
    public CameraState toSnapshot();   // 唯一分配点（与 §6 既有口径同频）
}

/** 覆盖链本体：每帧 select → compose；注册表按优先级预排序，evaluate 零新增分配。 */
public final class CameraCoverChain {
    public void register(BaseCameraProvider provider);   // 注册顺序 = 同优先级次序
    public void unregister(BaseCameraProvider provider);
    public void register(CameraModifier modifier);
    public void unregister(CameraModifier modifier);
    public void evaluate(CameraStateBuffer out);
}
```

**落点**（`camera-core-split.md` §〇 硬规则 1：`camera/core/` 零依赖外围）：`CameraStateBuffer` 放核心包；`CameraCoverChain` / `CameraModifier` / `ComposeMode` / `CameraChannels` 放 `camera/cover/`；`BaseCameraProvider` 放 `api/`（外部可实现，链直接消费、不复制一层）。`CameraState` 的归属（`api/` 或核心包）沿用 `camera-core-split.md` §〇「执行时定」，两种归属都不改上述签名。

**Provider 返回值与零分配**：`provideBaseState()` 返回的 record 必须与该层**已有的**快照产出复用（脚本导演 = 它本帧的 lane 快照；直控层 = 写入时缓存的 record，只在直写时更新），链只读、不复制、不额外 new——否则每帧多一次分配，违反 §0 硬约束 2。

**接线点（B1-b 用）**：

- 帧内：`CameraManager.onRenderFrame()` 驱动完全部实例（写完写入缓冲）之后、`refreshCameraState()`（`CameraManager.java:951-956`）之前调 `chain.evaluate(buffer)` → 快照取链输出（无活跃 Base = `null`，§6 口径不变）。
- 直写穿透：`setCameraDirect` 两个重载（`CameraManager.java:977-980` / `:989-992`）写**直控层**后立即 `evaluate + refresh`，保持 §6「写后立即可见」（不等下一帧）。注：旧 5 参数入口 `previewSetCamera` 已随 B4-P0 删除（`camera-core-split.md` §2.3 第 3 条），§6 的历史「直写入口」清单以 `setCameraDirect` 为准。
- 多 lane：链作用于**主相机状态**（= 顶层实例顶层活跃 clip 那份，与 §6 快照、最上层 lane 同源同值）；次级 lane 的相机状态来自各自 `CameraLane` 快照，不经过链（作用范围见 §14③）。

### 13. B2 结案：每 lane 一份 `CameraPath` / `CameraProperties`

**结论：不需要做——已被 `CameraLane` / `CameraState` 快照模型取代**（`camera-core-split.md` §五 B2 行核对结案）。

**写侧**（全局 `activePath` / `activeProperties` 的全部写入者）：

- 脚本导演（两条路径，都是「顶层」那份）：`CameraTrackPlayer.writeAttributes`（`CameraTrackPlayer.java:1032-1063`）——`capture == false`（顶层活跃 clip）时写全局（`:1057-1058`）；`capture == true`（重叠窗口下层 clip 的捕获求值）**只产快照、不写全局**（`:1028-1029`、`:1056`）。morph 路径 `renderMorph` 同样写全局（`:365-366`）。
- 直控 / 飞行：`CameraManager.setCameraDirect(Vec3, 6)`（`CameraManager.java:989-992`）← `control/FlightController.java:295`；`CameraManager.setCameraDirect(5)`（`:977-980`）← `webui/WebEditorApi.java:280`（旧 `previewSetCamera` 已随 B4-P0 删除，`camera-core-split.md` §2.3 第 3 条）。
- 清空：`CameraManager.reset()`（`:994-997`）。
- **渲染 Mixin 零写入零引用**（`mixin/` 全包 `CameraPath` / `CameraProperties` 零命中，§6 读侧切换清单）。

**读侧**：

- 渲染：**不读全局**——lane 渲染读自己的 `CameraLane.state()`（`client/lane/LaneRenderer.java:359`；`mixin/CameraMixin.java:71` 的 lane 分支；`mixin/GameRendererMixin.java:121-122`）。
- 统一快照：`CameraManager.refreshCameraState()`（`CameraManager.java:951-956`）→ `getCameraState()`（`:946-948`）→ 3 个非渲染消费方：`script/AudioListenerController.java:51,78`（听者位置 / 听者相机）、`trigger/client/PreloadRequester.java:76`（预加载中心）、`webui/WebPreviewScreen.java:150`（预览 HUD）。
- 方向性预加载：`CameraManager.getCameraYaw()`（`:970-973`）直读 `activeProperties` → `PreloadRequester.java:102,115,178`。
- 诊断：`CameraManager.tick()` 读 `activePath.getPosition()`（实体计数日志，`:900-906`）。

**lane 侧快照模型（已在位）**：

- 类型：`script/CameraLane.java:20` = `record CameraLane(CameraState state, Clip clip, float clipLocalTime)`。
- 生产：`CameraTrackPlayer.laneSnapshots`（`:40-43`，每帧清空重建）；顶层 clip；重叠下层 `captureLowerLanes`（`:391-392` 起，`capture = true`，文档明写「不写全局」`:377`）；morph。
- 消费：`ScriptPlayer.java:414-417`（逐 lane 按 `clipLocalTime` 采样调色）；`ScriptLaneDriver.java:127` → `LaneRenderer.setLane(index, lane.state(), ...)`（`LaneRenderer.java:237-238`）→ 逐 lane `camera.setup` 读该 lane 的 `CameraState`（`:359`）。

**为什么不需要 per-lane 可变对象**：

1. 唯一「每 lane 值不同」的场景（渲染）已逐 lane 读不可变快照，且 `mixin/` 零引用内部可变对象；
2. 全局两个对象的角色已收窄为「顶层实例顶层 clip 的写入缓冲 + 统一快照取值来源」，其消费方（听者 / 预加载 / 预览 HUD / 方向性预加载）要的正是「最上层相机那一份值」（`parallel-playback.md` §3.3：听者唯一、后来者居上），不是 per-lane 值；
3. 未来若某消费方需要 per-lane 值，读 lane 表即可（`CameraTrackPlayer.getLaneSnapshots()`，`:137-139`；lane 注册表），不需要新增 per-lane 可变对象——新增反而与快照模型形成两套真相。

**连带**：`CameraPath` / `CameraProperties` 保留为包内「当前值写入缓冲」（`camera-core-split.md` §〇 CORE 行），由 `CameraStateHolder` 持有（B4-P1，§15）；「被状态 buffer 取代」**不采用**（§11 状态 buffer 行）。§6 补充里的**渲染状态**（可见区块集合 / 遮挡剔除）与 B2 无关，仍待 B3。

**结构无关性**：本结案只依赖「谁写全局、谁读全局、lane 读什么」三件事，与 B4-P4 的 `LaneSnapshotCollector` 抽取无关（那是 B 组 lane 捕获的等值搬迁）；B4-P4 落地后结论不变，无需重开。

### 14. 待用户拍板

① **Modifier 通道缺省合成模式**（§4）

- 候选 A（倾向）：`position` / `yaw` / `pitch` / `roll` 缺省 `ADD`、`fov` / `zoom` 缺省 `MULTIPLY`、`REPLACE` 必须显式——中性元（0 / 1）与两类效果的直觉一致（偏移类写 0 无效果、缩放类写 1 无效果）。
- 候选 B：全通道缺省 `ADD`，`MULTIPLY` 与 `REPLACE` 一律显式声明——字段语义最简，但 fov / zoom 的缩放类效果每次都要写 `mode`。
- 影响面：只影响「作者不写 `mode` 时」的默认值；两种候选都不影响已显式声明的写法，实现期改一张表即可。

② **飞行 vs 编辑器直控的相对序**（§2 档位表 300 / 200）

- 候选 A（倾向）：飞行 300 > 编辑器直控 200——飞行是显式「接管态」（进入即暂停播放 + 置直控标志 + 锁鼠标，`FlightModeManager.java:55-58`）。
- 候选 B：编辑器直控 300 > 飞行 200——直控是编辑器正在进行的操作，飞行只是工具态。
- 影响面：二者现状互斥（飞行进入即锁鼠标，编辑器拖拽不可同时发生），此相对序**无现状语义**，纯定义；无论选哪个都不改变现有可观察行为。

③ **Modifier 的作用范围**（§12 接线点）

- 候选 A（倾向）：只作用于**主相机状态**（链输出 = 顶层相机那份；与 §5.1 / §5.2 / §6 的单一 Base 口径一致，实现最简）。
- 候选 B：作用于**全部 lane**（每条 lane 的相机各自叠加同一 Modifier 集，震屏等效果同时摇所有画面）——需要链与 lane 收集耦合，超出 B1 最小面。
- 影响面：多相机场景下「震屏是否同时摇副画面」的观感差异；候选 A 下仍可后续扩为 B（在 lane 收集处套用同一 Modifier 集），不是一次性锁死。

④ **通道掩码粒度**（§4）

- 候选 A（倾向）：6 位（`position` 整体一位）——与 §2 分组、§6 record 形态一致；`ADD` 语义下偏移类效果不需要逐轴掩码。
- 候选 B：8 位（`x` / `y` / `z` 分位）——支持「只 `REPLACE` 某个轴」的 Modifier；掩码与实现面更大。
- 影响面：`channelMask` 是 Modifier 声明的一部分，落地后再改 = 破坏性变更（Modifier 是内部接口，B1-c 之前改零成本）。

### 15. 供 B4-P1 / B4-P2 的接口形状（与 `clock-abstraction.md` 步骤 1 合并）

> 依据：`camera-core-split.md` §2.1（G3 双时钟 → `PlaybackClock`、G5 统一快照 → `CameraStateHolder`）+ `clock-abstraction.md` §3.1 / §3.2 / §4 步骤 1（Clock 抽象 + double 运行时）。两者改同一批字段，合并为一次改动（`next-phase-batches.md` §四 合并点）。
> 范围：`PlaybackClock` 与 `CameraStateHolder` 都归 **B4-P1**（§2.1 首拆）；B4-P2 的四个类（`PlaybackRegistry` / `PreviewChannel` / `PlaybackLedger` / `PlaybackLifecycle`）是等值搬迁，不引入新接口形状，本小节不覆盖。

**字段搬迁清单**（现状 → 新归属）：

| 现状字段 / 方法（`CameraManager.java`） | 新归属 | 备注 |
|---|---|---|
| `gameTimeSeconds` / `lastRealNanos`（游戏共享虚拟时钟累加） | `GameClock` | 逻辑等值搬迁（暂停冻结）；`lastRealNanos = 0` 的冻结技巧收进 `GameClock` |
| `previewTime`（float 累加，`:688`）/ `lastPreviewRealNanos` | `PreviewClock` | **float → double**（`clock-abstraction.md` §3.1） |
| `ScriptPlayer.clockSource`（`DoubleSupplier`） | `Clock`（接口类型） | 默认 = 游戏时钟；预览注入 `PreviewClock`（`clock-abstraction.md` §3.1） |
| `activePath` / `activeProperties` / `cameraState` | `CameraStateHolder` | 写入口收为包内（`camera-core-split.md` §2.4） |
| `getGameTimeSeconds()` / `getPreviewTimeSeconds()` / `previewClockSeconds()` | 保留为**委托读数** | 不改调用方（`clock-abstraction.md` §3.1）；`previewClockSeconds` 收敛进 `PreviewClock` |

**签名草案**：

```java
// ===== 时钟（clock-abstraction §3.1；包位置沿用 §6 待定项，倾向 util/）=====

/** 单调秒时钟（@FunctionalInterface 单方法）。 */
public interface Clock { double seconds(); }

/** 游戏共享虚拟时钟：暂停冻结、末实例退出归零。 */
public final class GameClock implements Clock {
    public void advance(boolean frozen, long nowNanos);  // 帧内推进（frozen = 暂停冻结）
    public void reset();                                 // 末实例退出归零
    @Override public double seconds();
}

/** 预览播放头：double 累加；seek 写、play / pause 推进 / 冻结。 */
public final class PreviewClock implements Clock {
    public void advance(boolean paused, long nowNanos);
    public void seek(double seconds);
    public void reset();
    @Override public double seconds();
}

// ===== 相机状态持有者（camera-core-split §2.1 G5）=====

/** 全局相机状态持有者：写入缓冲 + 统一快照（§6 接口定稿口径）。 */
public final class CameraStateHolder {
    CameraPath path();               // 包内：写入缓冲（可见性收紧归 B4-P5）
    CameraProperties properties();   // 包内
    void setDirect(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom);
    void setDirect(float yaw, float pitch, float roll, float fov, float zoom);  // 位置不动（编辑器直控 5 参数）
    CameraState snapshot();          // null = 无活跃相机（§6）
    void refresh();                  // 由写入缓冲重建快照（现状 refreshCameraState 语义）
    float cameraYaw(float fallbackYaw);  // 现状 getCameraYaw 的回落口径
    boolean hasActive();
    void reset();
}
```

**命名合并**：`PlaybackClock`（`camera-core-split.md` §2.1，2026-10-08）与 `Clock` + `GameClock` / `PreviewClock`（`clock-abstraction.md` §3.1，2026-10-09 用户已拍板方向）指同一批字段（G3 双时钟）。合并执行时**以 `clock-abstraction.md` 为准**（接口 + 两实现，粒度更细）；`PlaybackClock` 不再单独建类——否则会出现第三套时钟语义。若任务书需要单一入口名，用 `Clock`。

**接线与不变量**：`CameraManager` 保留 `getCameraState()` / `getGameTimeSeconds()` / `getPreviewTimeSeconds()` 等门面读数并改为委托；`ScriptPlayer.clockSource` 类型改 `Clock`（`clock-abstraction.md` §6 待定项：直接改 double，不维护兼容层）。B4-P1 先按现状口径搬迁（`refresh()` 从写入缓冲重建快照）；**B1-b 接链后**改为「链输出 → 快照」（一次小改，签名不变）——所以 P1 不必等 B1。

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
- **§3.4 两个对象同时承载“当前值 + staged 过渡值”** — 核查当时为真；**2026-10-09 回写：已过时（staged 已删除，见 §4）**。当时 `CameraPath` 有 `currentPosition` 与 `targetPosition/startPosition/transitionDuration/transitionProgress`、`CameraProperties.AnimValue` 有 `current/target/start/duration/progress`（`CameraProperties.java:33-40`）；删除后 `CameraPath.java:14-15` 仅剩 `currentPosition`、`CameraProperties.AnimValue`（:26-33）仅剩 `current`，全 camera 包 `targetPosition/startPosition/transitionDuration/transitionProgress/overrideFrom/staged` 零命中。
- **§3.5 Mixin / 渲染层直接依赖具体类** — 真。`mixin/CameraMixin.java:119-122` 调 `mgr.getPath().getPosition()` / `mgr.getProperties().getYaw()/getPitch()`；`GameRendererMixin.java:42,105` 调 `getProperties().getFov()/getZoom()/getRoll()`；`LevelRendererMixin.java:56` 调 `getPath().getPosition()`。均经 `CameraManager.getPath()/getProperties()` 返回的具体 `CameraPath`/`CameraProperties` 实例。
- **§4 staged 死代码（4 条全部为真）**
  - `stageTarget*` / `commitStagedState` / `isStagedReady` 无调用者 — 真。全仓 grep 仅命中 `camera/CameraManager.java:353-394` 的定义处；无任何 java 调用点。
  - `stagedReady` 永远不为 true — 真（运行时）。仅在无调用者的 `stageTarget*` 内被置 `true`（`CameraManager.java:355,360,365,370,375,380`），故运行时恒为 `false`；`tick()` 的 staged 分支（`:562-566`）永不进入。
  - `CameraPath`/`CameraProperties` 的 target / tick / overrideFrom 只被 staged 分支引用 — 真。`CameraPath.setTargetPosition/tick/overrideFrom`、`CameraProperties.setTarget*/tick/overrideFrom` 的唯一调用点分别是 `CameraManager.stageTarget*`（:354,359,364,369,374,379）、`CameraManager.tick()` 的 `if(stagedReady)` 分支（:563-565）、`CameraManager.commitStagedState()`（:386-387）。
  - `TransitionType` 只有自身引用 — 真。全仓 grep 仅命中 `script/TransitionType.java` 自身；`Clip.isMorph()` 用字符串 `"morph"`，`ScriptValidator` 也校验字符串，未引用该枚举。
- **§4 编辑器走 direct 写值、不用 staged** — 真。`webui/WebEditorApi.java:250` 用 `setCameraDirect(...)`（0.3.6 前游戏内编辑器 `editor/EditorScreen.java:691-693` 用 `setPreviewDirectControl(true)+previewSetCamera(...)`、`:921-923` 飞行记录读 `cam.getPath()/getProperties()`，该类已随退役删除）。全仓无 `stageTarget*` 调用。
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
