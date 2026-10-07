# 0.3.6 时间插值：防卡顿与帧间平滑（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、公式、迁移步骤等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [数学函数模型](./math-models.md)
> - [过渡](./transition.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位（已确认）

**时间插值**解决的是：

> 逻辑 tick 与渲染帧频率不一致时，如何让相机运动在渲染帧上连续、平滑，防止卡顿和抽帧。

- 逻辑 tick：固定 20Hz（`Minecraft` 中 `private final Timer timer = new Timer(20.0f, 0L)`，Minecraft.java:291；`Timer.msPerTick = 1000.0f / f`，即 50ms/tick）
- 渲染帧：60 / 120 / 144 / 更高
- 实体位置、目标位置、动画状态只在 tick 更新
- 直接使用最新 tick 值会出现台阶感 / 抖动

时间插值**不改变状态语义**，只做帧间采样平滑。

> MC 侧证据（tick 与渲染在同一主循环但频率解耦）：`Minecraft.runTick(boolean)` 先 `int i = this.timer.advanceTime(Util.getMillis())` 得到本帧应执行的 tick 数，`for (int j = 0; j < Math.min(10, i); ++j) this.tick();` 执行逻辑 tick，随后才 `this.gameRenderer.render(this.pause ? this.pausePartialTick : this.timer.partialTick, l, bl)` 渲染（Minecraft.java:980-1033）。partialTick 由 `Timer.advanceTime` 的累积器给出（Timer.java:21-28）。**1.20.1 不存在 `TickRateManager`**（该 API 为后续版本引入，本仓源码与 MC 源码中均无引用），即本版本 tick 率不可动态调整。

---

## 2. 与过渡的区别（已确认）

| | 时间插值 | 过渡 |
|---|---|---|
| 解决的问题 | 帧间不连续、卡顿、抽帧 | A→B 状态变化 |
| 时间尺度 | 毫秒级 | 秒级 |
| 输入 | prev + current + partialTick | 状态 A + 状态 B + 时长 + 曲线 |
| 目的 | 平滑、防抽帧 | 丰富运镜、非瞬时切镜头 |
| 是否改变状态 | 不改变 | 从 A 变成 B |

---

## 3. 卡顿来源（方向）

- tick / render 频率不一致
- 实体位置只在 tick 更新
- 目标切换 / 状态跳变
- 网络同步抖动
- 帧时间波动
- 不同子系统更新频率不同

---

## 4. 处理方向

### 4.1 Prev / Current 快照

保存上一逻辑帧和当前逻辑帧，渲染时按 partialTick 插值。

> 事实（原版已有同构机制，可作为约定而非另造）：`Entity.setOldPosAndRot()`（Entity.java:1306）把当前坐标写入 `xo/yo/zo`、当前朝向写入 `yRotO/xRotO`（字段声明 Entity.java:172-174、181-182；注意另有一组遗留字段 `xOld/yOld/zOld`，Entity.java:201-203，`setOldPosAndRot` 同时写两组，本仓插值用的是 `xo/yo/zo`）。该方法由客户端每 tick 在 `ClientLevel.tickNonPassenger` 中先于 `entity.tick()` 调用（ClientLevel.java:256）。渲染侧按 partialTick 读取：`Entity.getPosition(float)`（Entity.java:1447，`Mth.lerp(f, xo, getX())`）、`Entity.getViewXRot(float)` / `getViewYRot(float)`（Entity.java:1400 / 1407），`Camera.setup` 用 `Mth.lerp(partialTick, entity.xo, entity.getX())` + `entity.getViewYRot(partialTick) / entity.getViewXRot(partialTick)` 定位相机（Camera.java:52-53）。
>
> 即"prev/current 快照 + partialTick"是原版实体渲染的既有约定；本仓对**实体**已复用它（见 §5），对**相机自身**则选择了另一条路（帧驱动直写，见 §5）。

### 4.2 数值类型

- 位置：线性 / Hermite / 历史缓冲
- 角度：最短路径 / 环绕插值
- fov / zoom：按数值域处理
- 动态目标：实体插值位置

> 事实（原版数值域惯例）：位置用 `Mth.lerp(partialTick, prev, current)`；角度用 `Mth.rotLerp(partialTick, prev, current)`（内部按最短路径处理）。本仓 `CameraTrackPlayer` 对实体三通道即按此惯例实现：位置/俯仰用 `Mth.lerp`，身体/头部偏航用 `Mth.rotLerp`（CameraTrackPlayer.java:1428-1450）。

### 4.3 时间处理

- 固定步长 + 累积器（方向之一）
- 历史缓冲（网络抖动 / 延迟补偿）
- 外推（低延迟但有风险）

> 事实（原版做法，仅作参考，不代表本仓已决定采用）：
> - `Timer.advanceTime(long)` 就是"固定步长 + 累积器"：`tickDelta = (now - lastMs) / msPerTick`，`partialTick += tickDelta` 后取整得本帧 tick 数（Timer.java:21-28）。
> - 单帧补 tick 有硬上限：`Math.min(10, i)`（Minecraft.java:1003），超出部分被丢弃——这是原版对"卡顿后追帧"的既有处理，可与"外推是否开启"一并评估。
> - 网络同步侧原版自带插值/延迟补偿：`ClientboundMoveEntityPacket` 由 `ClientPacketListener.handleMoveEntity` 转成 `entity.lerpTo(x, y, z, yRot, xRot, 3, false)`（步数固定 3，ClientPacketListener.java:563-586）；`Entity.lerpTo` 基类实现是直接 `setPos/setRot`（Entity.java:1870），`LivingEntity` 覆写为写入 `lerpX/…/lerpSteps` 并在 `aiStep()` 中按步递减插值（LivingEntity.java:2496-2503、2283-2292）；`lerpMotion` 基类为 `setDeltaMovement`（Entity.java:1950），`RemotePlayer` 覆写为 `lerpDeltaMovement` + `updateInterval()+1` 步（RemotePlayer.java:79-82、65-68）。

### 4.4 统一入口

方向是提供统一的时间插值接口，避免每个 TrackPlayer 自己写。

> 具体接口、缓冲深度、外推策略执行时再定。

---

## 5. 当前现状（方向）

> 本节已按代码逐条核对（2026-10-07），补入具体类/方法名与行号；结论方向未变。

- 实体位置 / 角度插值已有实现，但散落在 `CameraTrackPlayer`（均为 `private static` 方法）：
  - `entityPosInterp(Entity)`（CameraTrackPlayer.java:1443）——`Mth.lerp(pt, e.xo, e.getX())` 三轴
  - `entityBodyYawInterp(Entity)`（:1428）——LivingEntity 走 `Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot)`，否则 `Mth.rotLerp(pt, e.yRotO, e.getYRot())`
  - `entityPitchInterp(Entity)`（:1437）——`Mth.lerp(pt, e.xRotO, e.getXRot())`
  - 三者的 `pt` 均取自 `Minecraft.getInstance().getFrameTime()`（= `Timer.partialTick`，Minecraft.java:2289）
  - 调用点：follow 位置（:335）、facing_* 基准（:433）、look_at 目标点（:645）、look_at_target（:731）、line 基准两端（:872-873）
- 相机本身目前是帧驱动直写，没有统一 prev/current 快照：`CameraTrackPlayer.onRenderFrame()` 每渲染帧 `getPath().setPositionDirect(pos)` + `getProperties().setAllDirect(yaw,pitch,roll,fov,zoom)`（:312-313、:908-909）；`CameraPath.setPositionDirect` 直接 `currentPosition = targetPosition = pos`（CameraPath.java:37-40），`CameraProperties.Value.setDirect` 直接 `current = target = start = v`（CameraProperties.java:38-40）；`CameraMixin.onSetup` 再把 `getPosition()/getYaw()/getPitch()` 原样 `setPosition/setRotation`（CameraMixin.java:118-123）。驱动入口：`CameraMixin.onSetup` HEAD 每帧调 `CameraManager.onRenderFrame()`（CameraMixin.java:97）→ `ScriptPlayer.onRenderFrame(double)` → 各 `TrackPlayer.onRenderFrame(float)`（ScriptPlayer.java:344-373）。
- 目标选择刷新频率（约 1s）与渲染帧频率差异大：默认常量在 `CameraTrackPlayer.selectorPolicy`，`selector_refresh` 缺省 `1.0f` 秒（:1146、:1148），换算 `refreshMs` 于 :1058；`EntitySelectorResolver` 只是一次性 selector 解析器（`resolve(...)`），**不含刷新间隔常量**。
- 没有统一的时间插值接口：除原版回调形参（`GameRenderer.render`、`Gui.render`、`Camera.setup` 等的 `partialTick`）外，仓内主动做插值的只有上列 `CameraTrackPlayer` 实体三通道，以及编辑器的播放头插值 `TimelineArea.renderPlayheadTime(float)`（TimelineArea.java:127-128，仅 UI 播放头，与相机无关）。

---

## 6. 可能的问题

- 相机自身是否需要 prev/current 快照
- partialTick / alpha 的来源与统一
- 固定步长 vs 渲染帧 delta
- 历史缓冲深度
- 外推是否默认开启，最大外推时间
- 角度、数值域的插值规则
- 与数学函数模型（阻尼 / 缓动）的边界
- 确定性 / 重放一致性
- 性能：每帧是否零分配
- 与过渡、覆盖链的先后关系

---

## 7. 待定

- 统一接口形态
- 快照 / 缓冲设计
- 外推策略
- 实体插值迁移范围
- 相机状态快照是否进入统一状态体系
- 外部 API 开放时机

---

## 8. 落地顺序（方向）

1. 定义统一时间插值概念
2. 统一实体位置 / 角度插值
3. 评估相机状态 prev/current 快照
4. 按需加入历史缓冲 / 外推
5. 接入数学函数模型
6. 内部稳定后考虑外部 API

---

## 9. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [数学函数模型](./math-models.md)
- [过渡](./transition.md)
- [迟滞](./hysteresis.md)

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **路径策略两条路径不一致**：`script/PathStrategies` 注册表实际只 `register("linear")`（PathStrategies.java:36-39 静态块，唯一一条注册；类 Javadoc 第 15-18 行却声称已注册 `"bezier"`，与实现不符）；`bezier` 由 `CameraTrackPlayer` 直接 `new BezierPathStrategy()`（CameraTrackPlayer.java:23），在 :753、:797 直接选用；动态查表入口对 `bezier` 会回落 `linear`——`KeyframeInterpolator.interpolatePosition(Keyframe, Keyframe, float, Clip)`（KeyframeInterpolator.java:122-126）走 `PathStrategies.get(curveType)`，注册表无 `"bezier"` 即 `LOGGER.warn` 后返回默认 `linear`（PathStrategies.java:67-77）。补充：该 4 参入口目前**无调用者**（仓内 `interpolatePosition(` 仅出现其定义与 5 参重载），属死代码，实际运行路径不会触发该回落。

---

## 事实核查（2026-10-07）

> 依据：MC 1.20.1 sources.jar（`.gradle/loom-cache/.../minecraft-merged-d95c7b3016-...-sources.jar`，行号以该 jar 解出物为准）、本仓 `common/` 源码。设计方向（接口形态 / 缓冲深度 / 外推策略）**未改动**，仍为待定。

### ① 核实为真

| 断言 | 证据 |
|---|---|
| 逻辑 tick 固定 20Hz | `Minecraft.java:291` `private final Timer timer = new Timer(20.0f, 0L)`；`Timer.java:14` 声明 / `:17` 赋值 `msPerTick = 1000.0f / f` → 50ms |
| tick 与渲染帧分离、partialTick 由累积器给出 | `Minecraft.runTick(boolean)`：`timer.advanceTime(Util.getMillis())` → `Math.min(10, i)` 次 `tick()` → `gameRenderer.render(timer.partialTick, …)`（Minecraft.java:998/1003-1005/1033）；`Timer.advanceTime`（Timer.java:21-28） |
| partialTick 来源 = `Minecraft.getFrameTime()` | `Minecraft.java:2289-2291` `getFrameTime()` → `this.timer.partialTick`；渲染入口 `GameRenderer.render(float partialTick, long nanoTime, boolean renderLevel)`（GameRenderer.java:870），`renderLevel(float,long,PoseStack)`（:1036） |
| 实体位置/角度插值已实现但散落在 `CameraTrackPlayer` | `entityPosInterp` :1443、`entityBodyYawInterp` :1428、`entityPitchInterp` :1437（均 `private static`），`pt` 取 `Minecraft.getInstance().getFrameTime()` |
| 相机帧驱动直写、无 prev/current 快照 | `CameraTrackPlayer` :312-313、:908-909 调 `setPositionDirect` / `setAllDirect`；`CameraPath.setPositionDirect`（:37-40）`current=target=pos`；`CameraProperties.Value.setDirect`（:38-40）`current=target=start=v`；`CameraMixin.onSetup` :118-123 原样写入；`CameraPath`/`CameraProperties` 类注释自述"不再使用 partialTick 插值" |
| 目标选择刷新频率约 1s | `CameraTrackPlayer.selectorPolicy` :1146/:1148 `selector_refresh` 缺省 `1.0f`；:1058 `refreshMs = max(50, scanSeconds*1000)` |
| 已知缺陷：`PathStrategies` 只注册 `linear` | `PathStrategies.java:36-39` 静态块仅 `register("linear", …)` |
| 已知缺陷：`bezier` 由 `CameraTrackPlayer` 直接 `new` | `CameraTrackPlayer.java:23` `new BezierPathStrategy()`；:753、:797 直接使用该实例 |
| 已知缺陷：动态查表对 `bezier` 回落 `linear` | `KeyframeInterpolator.java:122-126` → `PathStrategies.get(curveType)` → `PathStrategies.java:67-77` 未命中即 warn + 返回 `DEFAULT_TYPE="linear"` |
| 原版 `Entity.lerpTo`/`lerpMotion` 语义 | `Entity.lerpTo`（Entity.java:1870）基类 = `setPos + setRot`；`lerpMotion`（:1950）= `setDeltaMovement`；`LivingEntity` 覆写 `lerpTo`（LivingEntity.java:2496-2503）与 `aiStep()` 应用（:2283-2292）；`RemotePlayer` 覆写 `lerpMotion`（RemotePlayer.java:79-82）与 `aiStep()` 应用（:65-68） |
| 网络位置同步（LERPPOS 类语义） | `ClientboundMoveEntityPacket`（Pos/PosRot/Rot 子类）→ `ClientPacketListener.handleMoveEntity`（:563-584）中 `entity.lerpTo(..., 3, false)`（调用在 :576 / :580，步数硬编码 3）；`handleRotateMob` → `lerpHeadTo(f, 3)`（:587-593） |

### ② 已修正

| 旧说法 | 新事实 + 证据 |
|---|---|
| §5"目标选择刷新频率（约 1s）"未指明常量位置 | 核查确认常量只在 `CameraTrackPlayer.selectorPolicy`（`selector_refresh` 缺省 1.0f，:1146/:1148）；`EntitySelectorResolver` 是**一次性**解析器，只有 `MAX_SELECTOR_LENGTH=512`、`MAX_RESULTS=32` 两个常量（EntitySelectorResolver.java:28-29），**无刷新间隔常量**。文档已改为明确归属。 |
| 已知缺陷只写"`bezier` 由 `CameraTrackPlayer` 直接 `new`"，未点明是哪条查表入口 | 补全为 `KeyframeInterpolator.interpolatePosition(Keyframe,Keyframe,float,Clip)`（4 参）:122-126，并注明该入口**无调用者**（死代码）。 |
| 已知缺陷未记录"Javadoc 与实现不符" | `PathStrategies` 类 Javadoc :15-18 声称注册 `"bezier" → BezierPathStrategy`，静态块实际只注册 `linear`。 |

> 冲突裁决：未发现文档断言与代码互相矛盾到需要时间裁决的项；所涉代码文件最后修改时间均早于文档（`CameraTrackPlayer.java` 2026-09-17、`PathStrategies.java` 2026-07-25、`KeyframeInterpolator.java` 2026-08-20 vs 文档 2026-10-06），且核查结果与文档方向一致，无需按"晚修改者为准"改判。

### ③ 补全的信息

- **原版已有的 prev/current 机制**：`Entity.setOldPosAndRot()`（Entity.java:1306）写入 `xo/yo/zo` + `yRotO/xRotO`（声明 :172-174、:181-182），由 `ClientLevel.tickNonPassenger` 每 tick 先于 `entity.tick()` 调用（ClientLevel.java:256）；渲染侧 `Entity.getPosition(float)`（:1447）、`getViewXRot/getViewYRot(float)`（:1400/:1407）、`Camera.setup`（Camera.java:52-53）。即 §4.1 方向在原版有现成同构实现。
- **遗留字段**：Entity 同时存在 `xOld/yOld/zOld`（:201-203），`setOldPosAndRot` 两组都写；本仓插值使用 `xo/yo/zo`。
- **单帧补 tick 上限**：`Math.min(10, i)`（Minecraft.java:1003）。
- **1.20.1 无 `TickRateManager`**：MC 源码与本仓源码中均无该类引用（`TickRateManager` 为后续版本引入），tick 率不可动态调整。
- **网络插值步数**：位置/朝向包固定 3 步（`ClientPacketListener.handleMoveEntity` :576/:580），`RemotePlayer.lerpMotion` 用 `getType().updateInterval() + 1` 步（RemotePlayer.java:81）。
- **编辑器侧另一处散落插值**：`TimelineArea.renderPlayheadTime(float)`（TimelineArea.java:127-128，prev/current 播放头插值，仅 UI，与相机无关）——可佐证 §4.4"避免每个 TrackPlayer 自己写"的动机，但它不属相机时间插值范围。

### ④ 无法核实（未验证）

- 帧率相关量化结论（60/120/144fps 下的台阶感程度、抽帧观感）——属运行时观感，未在游戏内实测。
- §3"帧时间波动""不同子系统更新频率不同"未定位到具体子系统代码，**未验证**。
- 设计项（统一接口形态、缓冲深度、外推默认开关与最大外推时间、确定性/重放一致性）仍为待定，**未验证、未改判**。
