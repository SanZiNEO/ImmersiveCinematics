# 0.3.6 时间插值：防卡顿与帧间平滑（已完成）

> 已完成（B 轮 2026-10-11 落地，归档于 complete/0.3.6/）。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、公式、迁移步骤等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](camera-state-plan.md)
> - [数学函数模型](math-models.md)
> - [过渡](transition.md)
> - [迟滞](hysteresis.md)

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

> 现行口径：接口 = `util/TimeInterpolation`（§7.3）；缓冲深度、外推策略 = 第一版不做（§7.2）。

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

- 相机自身是否需要 prev/current 快照 → **已裁：不需要（§7.1）**
- partialTick / alpha 的来源与统一
- 固定步长 vs 渲染帧 delta
- 历史缓冲深度 → **已裁：第一版不做（§7.2）**
- 外推是否默认开启，最大外推时间 → **已裁：第一版不做（§7.2）**
- 角度、数值域的插值规则
- 与数学函数模型（阻尼 / 缓动）的边界
- 确定性 / 重放一致性
- 性能：每帧是否零分配
- 与过渡、覆盖链的先后关系 → **已裁（§7.4）**

---

## 7. 裁定（B17 结案，2026-10-11）

> 本节四项未决项已由主代理裁定并结案，结论即现行口径，实现照此。证据见 §8 证据附录。

### 7.1 相机状态 prev/current 快照 → 不需要

- **结论**：相机轨道求值以**渲染帧时钟**（`GameClock` / `PreviewClock`）为输入、每渲染帧精确重算，lane 渲染读精确 `CameraState`，**不存在 20Hz 台阶**——不需要相机侧 prev/current 快照，不引入 partialTick 插值层。
- **机制**：帧驱动入口 `GameRendererMixin.onRenderFrameStart`（`GameRenderer.render` 的 HEAD，世界渲染之前）→ `CameraManager.onRenderFrame()`（时钟推进 → 逐实例 `ScriptPlayer.onRenderFrame(double)` → 帧末 `refreshCameraState()` 取覆盖链输出快照）；时间源 = `GameClock`（`System.nanoTime()` 单调秒、暂停冻结）/ `PreviewClock`，即相机轨道求值本身以渲染帧时钟为输入；lane 渲染读自己的精确快照（`CameraMixin.onSetup` 读 `lane.state().position()/yaw()/pitch()`，注释明写「不需要 partialTick 插值」）。
- **逃生口**：若某**非渲染消费方**（听者 / 预加载 / 触发）以 tick 频率读相机并出现台阶，在该读取点按需用 `TimeInterpolation`——属该消费方边界，不在相机侧加快照层。
- **证据**：见 §8 证据附录（B16-c 默认关 840 帧 raw-bits 逐字节 / B1-b 缝对拍 2403 行 / 实机三会话几何逐位一致 / **实机三档帧率逐帧转储无台阶**——§8.1「实机无台阶抽验」）。

### 7.2 历史缓冲 / 外推 → 第一版不做（开关留位）

- **结论**：第一版**不做**历史缓冲与外推，保持现状——不预设缓冲深度、不预设最大外推时间、不引入外推开关。
- **再立项条件**：出现以 tick 频率消费相机、并因此产生可见台阶的消费方，或出现网络抖动需求时再立项；届时参数（缓冲深度 / 最大外推秒）执行时定。
- **依据**：外推会破坏「确定性 / 重放一致」（§6）与覆盖链零分配契约（`camera-state-plan.md` 覆盖链定稿 §0），故第一版不做。

### 7.3 内部 API → 0.3.6 只留位不开放

- **结论**：0.3.6 只做内部统一入口（`util/TimeInterpolation`），**不开放外部 API**。
- **依据**：`math-models.md` 定稿 10（0.3.6 不开放外部注册，`MathModelRegistry.register` 保持内部可见）+ `camera-state-plan.md` 覆盖链定稿 §11（外部 API 开放时机 = 内部稳定 + 覆盖链落地 + 至少一个版本观察期，`plans/0.4.0` 起评估）。

### 7.4 与覆盖链 / 过渡（B18）的先后关系

- **覆盖链 → 时间插值 / 缓冲 → 渲染读取**：覆盖链先出最终 `CameraState`（`CameraCoverChain.snapshot()`），时间插值 / 缓冲（若将来有）在**链输出之后、渲染读取之前**。
- **过渡（B18）= 独立层，包装链的输入 / 输出**（`camera-state-plan.md` 覆盖链定稿 §5 / `transition.md` §6「独立过渡层」），与本层**无重叠**。
- **逃生口**：若将来过渡与时间插值需要**共享同一缓冲 / 快照**，先上报再定，不在本层单方面实现。

---

## 8. 落地顺序（方向）

（落地记录：步骤 1-2 已完成（2026-10-07）——统一时间插值工具 `util/TimeInterpolation`（lerp/rotLerp/position/实体级采样，复用原版 prev/current 快照），CameraTrackPlayer 三个散落插值方法收拢迁移（60 万次逐位对照零差异）。**步骤 3-6 已结案（B17，2026-10-11）**——步骤 3 相机快照 = 结案「不需要」（渲染帧时钟求值 + 逐帧精确重算 + lane 读精确 `CameraState`，无 20Hz 台阶，见 §7.1；实机三档抽验见 §8.1）；步骤 4 历史缓冲 / 外推 = 第一版不做（结案 + 再立项条件，见 §7.2）；步骤 5 模型接入 = 只做时序侧（复用 B16-c 六通道实例，每 lane 每帧单次 step，禁止一帧推进两次——`math-models.md` 定稿 7.2；不实现阻尼 / 缓动——定稿 8）；步骤 6 内部 API = 0.3.6 只留位不开放（见 §7.3）；与覆盖链 / 过渡（B18）的先后关系已文档化（见 §7.4）。）

1. 定义统一时间插值概念 —— ✅ 完成（步骤 1）
2. 统一实体位置 / 角度插值 —— ✅ 完成（步骤 2）
3. 评估相机状态 prev/current 快照 —— ✅ 结案：不需要（步骤 3，§7.1）
4. 按需加入历史缓冲 / 外推 —— ✅ 结案：第一版不做（步骤 4，§7.2）
5. 接入数学函数模型 —— ✅ 只做时序侧（步骤 5，§7.4 / `math-models.md` 定稿 7.2、8）
6. 内部稳定后考虑外部 API —— ✅ 0.3.6 只留位不开放（步骤 6，§7.3）

---

## 8.1 证据附录：tick / render 解耦对拍（B17）

> 无生产代码改动（B17 为结案型交付）；解耦对拍沿用既有证据链，下列引用可核（提交号 / 类符号 / 行号以当前工作树为准）。

**证据链（tick 20Hz 与渲染帧解耦下相机参数平滑）**：

| 证据 | 内容 | 出处 |
|---|---|---|
| B16-c 默认关逐位对拍 | 默认关（无运动模型）下 840 帧 raw-bits **逐字节一致**（含重叠窗 / morph / 呼吸）；validator A/B 292 行逐字节 | B16-c 提交（`implementation-progress.md` 2026-10-11 续4） |
| B1-b 缝对拍 | 写入 → 链选择 → 快照全路径 2403 行**逐字节一致**（含 NaN/±Inf/-0.0/MIN/MAX 直通 = 链上无 clamp/normalize） | B1-b 提交（`implementation-progress.md` 2026-10-11 续） |
| 实机三会话几何 | 默认关全参回归案几何与历史三会话**逐位一致**（B16-c 会话 3） | B16-c 提交（同上） |

**「无 20Hz 台阶」机制论证（各环节在哪个类）**：

1. **渲染帧时钟求值**：帧驱动入口 `GameRendererMixin.onRenderFrameStart`（`GameRenderer.render` 的 HEAD，世界渲染之前）→ `CameraManager.onRenderFrame()`；时间源 = `GameClock`（`System.nanoTime()` 单调秒、暂停冻结）/ `PreviewClock`（`util/`）。相机轨道求值**输入即渲染帧时钟**，不经过 20Hz tick 累积器。
2. **每渲染帧精确重算**：`CameraManager.onRenderFrame()` 逐实例调 `ScriptPlayer.onRenderFrame(double)` → 各 `TrackPlayer.onRenderFrame(float)`；`CameraTrackPlayer` 每帧求值后 `writeGlobal` 收口 `CameraManager.setScriptState`（`CameraStateHolder` 不可变镜像）；帧末 `refreshCameraState()` 取覆盖链输出（`CameraCoverChain.snapshot()`）。
3. **lane 读精确 `CameraState`**：渲染侧不读 20Hz tick 值——`CameraMixin.onSetup` 读 `lane.state().position()/yaw()/pitch()` 直接 `setPosition/setRotation`（注释明写「每帧已由 onRenderFrame 精确重算，不需要 partialTick 插值」）；`GameRendererMixin.onGetFov` 用 lane 自己的 fov/zoom。
4. **实体 partialTick 采样（独立通道）**：实体三通道走 `util/TimeInterpolation`（`entityPosition` / `entityBodyYaw` / `entityPitch`，`partialTick()` = `Minecraft.getInstance().getFrameTime()`）——实体位置只在 tick 更新，渲染帧按原版 prev/current 快照（`xo/yo/zo`、`yRotO/xRotO`）采样；这是**实体**的平滑，与相机自身帧驱动精确值互不冲突。

结论：相机参数在渲染帧上逐帧精确取值（非 20Hz 台阶值），故无台阶；实体侧由 `TimeInterpolation` 按 partialTick 采样，同样无台阶。

---

### 实机无台阶抽验（2026-10-11，T18）

> 补 B17 验收 5 的实机一环：60 / 120 / 解锁三档帧率下相机参数是否平滑（无 20Hz 台阶）。口径 = **数值连续性**（逐帧转储统一快照六参数 + 脚本时间 + 客户端 tick 计数），不是像素 / 观感。

**做法**：fixture `cinematics/tests/camera/test_camera_smooth_motion.json`（validator `[OK]`；六参数全程严格单调——段 1 = 12s 线性匀速、段 2 = 8s 贝塞尔路径 + 线性角度 / 光学，**不挂 `motion_*`**（默认关，避免运动模型掩盖台阶）；`block_keyboard` / `block_mouse` = true 冻结玩家输入）部署到 `fabric/run/immersive_cinematics/scripts/`；`options.txt` 临时 `maxFps` 60 / 120 / 260 + `enableVsync:false`（跑完逐字节还原）；临时探针 = `CameraManager.onRenderFrame` 帧末一行（统一快照六参数 + 脚本时间 + tick 计数 + 活跃标志；`ICINEMATICS_TRACE=<路径>` 门控，**跑完已删、不入库**）。三档均 `NATURAL_END elapsed=20.00`（60 档 20.01）自然播完、无异常、无模型告警。

| 档（`maxFps`） | 实测帧率 | 转储帧数 | tick 率 | 同 tick 内参数已变的帧对 | 相邻帧全等最长平台 | 帧间 yaw Δ 中位数 |
|---|---|---|---|---|---|---|
| 60 | 57.6 fps | 1153 | 20.54/s | 750 / 1147 = **65.4%** | **1 帧** | 0.1382° |
| 120 | 110.9 fps | 2218 | 20.55/s | 1815 / 2212 = **82.1%** | **1 帧** | 0.0747° |
| 260（解锁） | 304.3 fps | 6087 | 20.55/s | 5684 / 6080 = **93.5%** | **1 帧** | 0.0255° |

- **台阶判据与结论**：若相机由逻辑 tick（20Hz / 50ms）驱动，参数在 50ms 窗口内恒定 → 60 / 120 / 260fps 下分别出现「每 3 / 6 / ~15 帧一次跳变」的连续全等平台，且帧间 Δ 与帧率无关（中位数恒 = 速率 × 50ms）。实测：三档**最长全等平台 = 1 帧**，且 65.4% / 82.1% / 93.5% 的相邻帧对在**同一 tick 内**（`Δtick = 0`）参数已经变化；帧间 yaw Δ 中位数随帧率成反比（0.1382 / 0.0747 / 0.0255，比值 1.85 / 2.92 ≈ 帧率比 1.93 / 2.74）。**结论 = 无台阶**。
- **唯一的一对相邻全等帧**：三档都落在时间轴结束处（60：e 19.991→20.008；120：19.992→20.001；260：19.999→20.001，`Δtick = 0`）——20s 时间轴播完后的**末值保持**（`NATURAL_END` → `deactivateNow`，见 `client.log`），非台阶。
- **逐帧解析核对（每档独立）**：段 1 八参数线性 / 倍率域拟合斜率 = 解析率（偏差 ≤ 1e-8），最大残差 ≤ 8e-6（位置 ≤ 1e-6 方块；zoom 走倍率域对数插值，log 残差 ≤ 1.1e-7——与 `KeyframeInterpolator.interpolateZoom` 口径一致）；段 2 位置落在解析三次贝塞尔上（弧长参数化，点到曲线距离 ≤ 1.2e-5 方块），角度 / 光学最大残差 ≤ 8e-6。
- **跨档同一时间轴**：公共 0.25s 网格（80 点）上最大偏差——yaw 8.0e-6°、pitch 3.3e-6°、roll 1.3e-6°、fov 1.1e-5°、zoom 1.7e-7、位置 6.4e-3 方块（三档基座逐位相同：`(-4.963488, -60.000000, 16.541797)`）——同一脚本时刻的相机参数与帧率无关（残差量级 = float 精度 / 弧长 LUT 精度）。
- **同帧链输出**：全部 9458 个播放帧上，写入缓冲（脚本导演本帧求值）与覆盖链输出快照（渲染侧读取）**逐位相同**（`cur != snap` 帧数 = 0，`snap = null` 帧数 = 0）——链上无量化、无二次采样。
- **产物**：`fabric/run/lane-captures-b17/{run60,run120,run260}/{trace.txt,client.log}` + harness 与分析输出 `fabric/run/lane-captures-b17/{run_client_b17.sh,analyze_b17.py,analysis-{summary,continuity,model,compare}.txt}`（`fabric/run/` 已 gitignore；harness 原件在仓库外 `E:/tmp/b17run/`，此处为归档副本）。
- **环境还原**：三档跑前 / 跑后 `options.txt` 逐字节相同、`lane-captures/` 逐文件相同（各档 `diff -r` 无差异）、`scripts/` 目录与客户端进程已清零。
- **未做**：像素 / 观感侧（本抽验只做数值连续性）；60 / 120 两档实测 57.6 / 110.9 fps 未满档（开发客户端开销；判据看帧率差而非档位名义值）。

---

## 9. 相关文档

- [相机状态与覆盖链](camera-state-plan.md)
- [数学函数模型](math-models.md)
- [过渡](transition.md)
- [迟滞](hysteresis.md)

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **路径策略两条路径不一致 —— ✅ 已修复（2026-10-09 回写）**：`script/PathStrategies` 静态块现注册 `linear` + `bezier` 两条（PathStrategies.java:36-41），类 Javadoc :15-18 与实现一致；`PathStrategies.get(type)`（:69-79）仅在该 type 未注册时 `LOGGER.warn` 并回落 `DEFAULT_TYPE = "linear"`，`bezier` 经动态查表不再回落。`CameraTrackPlayer` 仍持独立实例 `bezierStrategy`（CameraTrackPlayer.java:27，实例自带 LUT 缓存），在 :898、:942 按需选用（`follow` 时改用 `PathStrategies.get("linear")`）；原 4 参死代码入口 `KeyframeInterpolator.interpolatePosition(Keyframe, Keyframe, float, Clip)` 已不存在（现仅 5 参重载 :170，仓内无调用者）。

---

## 事实核查（2026-10-07）

> 依据：MC 1.20.1 sources.jar（`.gradle/loom-cache/.../minecraft-merged-d95c7b3016-...-sources.jar`，行号以该 jar 解出物为准）、本仓 `common/` 源码。设计方向（接口形态 / 缓冲深度 / 外推策略）的现行口径见 §7（B17 结案）。

### ① 核实为真

| 断言 | 证据 |
|---|---|
| 逻辑 tick 固定 20Hz | `Minecraft.java:291` `private final Timer timer = new Timer(20.0f, 0L)`；`Timer.java:14` 声明 / `:17` 赋值 `msPerTick = 1000.0f / f` → 50ms |
| tick 与渲染帧分离、partialTick 由累积器给出 | `Minecraft.runTick(boolean)`：`timer.advanceTime(Util.getMillis())` → `Math.min(10, i)` 次 `tick()` → `gameRenderer.render(timer.partialTick, …)`（Minecraft.java:998/1003-1005/1033）；`Timer.advanceTime`（Timer.java:21-28） |
| partialTick 来源 = `Minecraft.getFrameTime()` | `Minecraft.java:2289-2291` `getFrameTime()` → `this.timer.partialTick`；渲染入口 `GameRenderer.render(float partialTick, long nanoTime, boolean renderLevel)`（GameRenderer.java:870），`renderLevel(float,long,PoseStack)`（:1036） |
| 实体位置/角度插值已实现但散落在 `CameraTrackPlayer` | `entityPosInterp` :1443、`entityBodyYawInterp` :1428、`entityPitchInterp` :1437（均 `private static`），`pt` 取 `Minecraft.getInstance().getFrameTime()` |
| 相机帧驱动直写、无 prev/current 快照 | `CameraTrackPlayer` :312-313、:908-909 调 `setPositionDirect` / `setAllDirect`；`CameraPath.setPositionDirect`（:37-40）`current=target=pos`；`CameraProperties.Value.setDirect`（:38-40）`current=target=start=v`；`CameraMixin.onSetup` :118-123 原样写入；`CameraPath`/`CameraProperties` 类注释自述"不再使用 partialTick 插值" |
| 目标选择刷新频率约 1s | `CameraTrackPlayer.selectorPolicy` :1146/:1148 `selector_refresh` 缺省 `1.0f`；:1058 `refreshMs = max(50, scanSeconds*1000)` |
| 已知缺陷：`PathStrategies` 只注册 `linear` | ~~`PathStrategies.java:36-39` 静态块仅 `register("linear", …)`~~ —— **2026-10-09 已过时**：静态块现注册 `linear` + `bezier`（`PathStrategies.java:36-41`） |
| 已知缺陷：`bezier` 由 `CameraTrackPlayer` 直接 `new` | 仍成立（非缺陷）：`CameraTrackPlayer.java:27` `new BezierPathStrategy()`（独立实例 + LUT 缓存）；:898、:942 按需选用 |
| 已知缺陷：动态查表对 `bezier` 回落 `linear` | **2026-10-09 已消除**：`PathStrategies.get()`（:69-79）仅未注册时回落 `linear`，`bezier` 已注册；仓内 `PathStrategies.get` 仅以字面量 `"linear"` 调用（`CameraTrackPlayer.java:898、:942`） |
| 原版 `Entity.lerpTo`/`lerpMotion` 语义 | `Entity.lerpTo`（Entity.java:1870）基类 = `setPos + setRot`；`lerpMotion`（:1950）= `setDeltaMovement`；`LivingEntity` 覆写 `lerpTo`（LivingEntity.java:2496-2503）与 `aiStep()` 应用（:2283-2292）；`RemotePlayer` 覆写 `lerpMotion`（RemotePlayer.java:79-82）与 `aiStep()` 应用（:65-68） |
| 网络位置同步（LERPPOS 类语义） | `ClientboundMoveEntityPacket`（Pos/PosRot/Rot 子类）→ `ClientPacketListener.handleMoveEntity`（:563-584）中 `entity.lerpTo(..., 3, false)`（调用在 :576 / :580，步数硬编码 3）；`handleRotateMob` → `lerpHeadTo(f, 3)`（:587-593） |

### ② 已修正

| 旧说法 | 新事实 + 证据 |
|---|---|
| §5"目标选择刷新频率（约 1s）"未指明常量位置 | 核查确认常量只在 `CameraTrackPlayer.selectorPolicy`（`selector_refresh` 缺省 1.0f，:1146/:1148）；`EntitySelectorResolver` 是**一次性**解析器，只有 `MAX_SELECTOR_LENGTH=512`、`MAX_RESULTS=32` 两个常量（EntitySelectorResolver.java:28-29），**无刷新间隔常量**。文档已改为明确归属。 |
| 已知缺陷只写"`bezier` 由 `CameraTrackPlayer` 直接 `new`"，未点明是哪条查表入口 | 补全为 `KeyframeInterpolator.interpolatePosition(Keyframe,Keyframe,float,Clip)`（4 参）:122-126，并注明该入口**无调用者**（死代码）。**2026-10-09 回写**：该 4 参入口已删除（现仅 5 参 `interpolatePosition(…, PathStrategy)`，:170，仍无调用者）。 |
| 已知缺陷未记录"Javadoc 与实现不符" | `PathStrategies` 类 Javadoc :15-18 声称注册 `"bezier" → BezierPathStrategy`，静态块**当时**实际只注册 `linear`。**2026-10-09 回写**：`bezier` 已注册（`PathStrategies.java:36-41`），Javadoc 与实现一致。 |

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
- 设计项（统一接口形态、缓冲深度、外推默认开关与最大外推时间、确定性 / 重放一致性）：现行口径见 §7（B17 结案，2026-10-11）——接口形态已落地（`util/TimeInterpolation`，步骤 1-2）、缓冲 / 外推第一版不做（§7.2）；确定性由 `math-models.md` 定稿 9 的逐位一致口径约束。
