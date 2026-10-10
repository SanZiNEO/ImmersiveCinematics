# clock-abstraction.md — 时钟抽象（单调秒 + double 运行时）

> 状态：🟢 步骤 1 已落地（2026-10-10，Q4 = B4-P1 ∪ 本步骤 1，记录见 §4）；步骤 2–5 未执行。方向已确认（2026-10-09 用户决策）。
> 关联：`script-model.md` G4「时间精度」、`parallel-playback.md` §7 步骤 5（预览独立时钟）、`temporal-interpolation.md`（tick/渲染帧解耦）、`state-tracking.md`（账本与时钟边界）。
> 约定：本文档不写伪代码，写实现思路与每一步的交付物；先定义时钟底层，再谈调用整合（先底层后应用）。

---

## 0. 决策记录（2026-10-09）

用户对「time」设计的两项拍板：

1. **时钟基准 = 抽象 Clock + 单调秒**：把现有 `clockSource`（`DoubleSupplier`）提升为一等 `Clock` 抽象——游戏 = MC 单调时钟、预览 = 手动播放头、测试 = 假时钟；运行时统一 double 秒。账本/触发对齐继续用 tick 序号（现状已对，不动）。
2. **精度类型 = 运行 double + 存储 float**：运行时/计算/插值全程 double，只在读写脚本字段（`Clip`/`Keyframe` 的 float）边界转换；不动 script schema 与 JSON 序列化。

---

## 1. 背景与问题

现状三套时间语义并存、精度不一致：

| 层 | 现状实现 | 基准 | 问题 |
|---|---|---|---|
| 客户端脚本时钟 | `CameraManager.gameTimeSeconds`（double，`System.nanoTime()` 累加墙钟差，暂停冻结） | 墙钟秒 | 单例全局、无抽象 |
| elapsed 换算 | `ScriptPlayer.getElapsedSeconds() = (float)(clock - start)` | 墙钟秒→float | double 在返回处被截断成 float |
| 预览播放头 | `CameraManager.previewTime`（float，float 累加） | 秒 | float 累加漂移 |
| 服务端账本 | `ScriptEventManager`：`(tickCount - startTick - pausedTicks)/20f` | tick 序号 | 正确（对齐 MC），保持不变 |
| 存储 | `Clip.startTime/duration`、`Keyframe.time`、`Timeline.*` 全 float | 秒 | 按决策保持 float |

已知缺陷（评估阶段已定位）：
- **float 截断**：`getElapsedSeconds()` 返回 float，小时级脚本 ULP 逼近 1ms，音频/关键帧同步开始可闻。
- **绝对时间 vs elapsed 语义混用**：`CameraManager.onRenderFrame` 把绝对 `gameTime`（float）传给 `player.hasActiveCameraTrack()`，后者按 elapsed 做宏观循环的圈内局部时间换算（循环 = 按 `macro_loop_count` 重复执行，非时间折叠）——跨脚本并行（`gameTimeSeconds` 不复位、`startGameTimeSeconds≠0`）时算错。
- **死参数**：`ScriptPlayer.onRenderFrame(double gameTimeSeconds)` 的参数从头到尾未使用（`:436-439` 直接重算 `getElapsedSeconds()`）。
- **overlay 假 delta**：`OverlayManager.update(1f/20f)` 写死 20fps，非 20fps 下淡入淡出速度失真。

---

## 2. 事实核查（MC 1.20.1 + 现状代码定位）

### 2.1 MC 1.20.1 时间机制（Vineflower 反编译 `minecraft-merged` 命名 jar 确认）

- **客户端 `Timer`**（`net.minecraft.client.Timer`）：`partialTick`（`[0,1)` 帧间插值系数）、`tickDelta`（本帧 tick 数）；`advanceTime(Util.getMillis())` 用墙钟差推 tick 数，整数部分=需执行的逻辑 tick。`Minecraft.getFrameTime()` 返回 `partialTick`。
- **客户端主循环**（`Minecraft.runTick`）：`int i = timer.advanceTime(...)`，`for(j < min(10, i)) tick()`（每帧最多补 10 tick）；渲染传 `timer.partialTick`；暂停时冻结在 `pausePartialTick`。
- **服务端循环**（`MinecraftServer.runServer`）：`nextTickTime` 用 `Util.getMillis()` 相对累加（每次 `+50L`，无长期漂移）；卡顿落后 >2s 时跳过 m 个 tick（`nextTickTime += m*50L`，`tickCount` 仍 +1）；`getTickCount()` = 纯 tick 序号（int）。
- **单调时钟**：`Util.timeSource = System::nanoTime`（可注入 `LongSupplier`）；`getMillis() = getNanos()/1e6`。MC 全程单调 `nanoTime`，非 epoch 墙钟。
- **1.20.1 无 tick 速率管理**（`/tick` 命令与 `ServerTickRateManager` 是 1.20.2+ 才有）——1.20.1 下 `tick/20` 恒等于秒。**多版本目标（common 层）在 1.20.2+ 需重新审视「tick/20」假设**，本条暂不改服务端，仅在 §5 影响面登记。

### 2.2 现状代码定位

时钟字段与累加（`camera/CameraManager.java`）：
- `gameTimeSeconds`（double，`:57`）；`previewTime`（float，`:84`）；`lastRealNanos` / `lastPreviewRealNanos`（long）。
- 游戏时钟累加 `gameTimeSeconds += (double)(now - lastRealNanos)/1e9`（`:695`）。
- 预览时钟累加 `previewTime += (float)((now - lastPreviewRealNanos)/1e9)`（`:705`）——**float 累加**。

时钟读取：
- `getGameTimeSeconds()` → double（`:811`）；`getPreviewTimeSeconds()` → `previewMode ? previewTime : gameTimeSeconds`（`:823`）；`previewClockSeconds()` → 返回 float `previewTime`（`:611-613`）。

`ScriptPlayer` 时钟（`script/ScriptPlayer.java`）：
- `clockSource`（`DoubleSupplier`，`:62`）默认 `CameraManager.INSTANCE::getGameTimeSeconds`；`setClockSource`（`:661`）；预览注入 `this::previewClockSeconds`（`CameraManager.java:586`）。
- `startGameTimeSeconds`（double，`:54`）；`getElapsedSeconds()`（`:651-653`）返回 float。
- `alignTime(float, double)`（`:486-488`）。

float 截断点（`CameraManager.onRenderFrame`，`:722-765`）：
- `float gameTime = (float) gameTimeSeconds`（`:722`）；`float previewClock = previewTime`（`:723`）；`float instanceClock`（`:736`）；`float instanceTime`（`:754`）。
- `player.onRenderFrame(instanceTime)`（`:763`，死参数）；`player.hasActiveCameraTrack(instanceTime)`（`:765`，绝对时间当 elapsed）。

服务端账本（`trigger/server/ScriptEventManager.java`）：`elapsed = (currentTick - pb.startTick - pb.totalPausedTicks)/20f`（`:197`）——保持不动。

存储 float：`script/Clip.java`（`:16-17` startTime/duration）、`script/Keyframe.java`（`:14` time）、`script/Timeline.java`（`:16,22,25`）——按决策保持 float。

---

## 3. 方案设计

### 3.1 Clock 抽象（底层工具，单调秒）

引入一等 `Clock` 接口（`@FunctionalInterface`，单方法 `double seconds()`），取代裸 `DoubleSupplier`：

- `GameClock`：游戏共享虚拟时钟。承载现在的 `gameTimeSeconds` 单调累加（暂停冻结、末实例退出归零）。逻辑从 `CameraManager` 内联字段抽出为独立可测单元。
- `PreviewClock`：预览播放头。承载现在的 `previewTime`，改为 **double** 累加；seek（`setTime`）写、play/pause 推进/冻结。
- （后续）`ManualClock`/`FakeClock`：测试假时钟，本版仅保留接口位，不强制实现。

关键约束：
- `ScriptPlayer.clockSource` 类型从 `DoubleSupplier` 改为 `Clock`（默认 `CameraManager.INSTANCE::getGameTimeSeconds`，预览注入 `PreviewClock`）。
- `CameraManager` 对外仍保留 `getGameTimeSeconds()` / `getPreviewTimeSeconds()` 作为兼容读数（内部改为委托两个 `Clock`），避免大范围改调用方；`previewClockSeconds()` 收敛进 `PreviewClock`。

### 3.2 double 运行时 / float 存储边界

- **运行时**：时钟读数、elapsed、宏观循环区间计算、关键帧插值、`alignTime`、音频 `repositionAudio` 的 time 入参——全程 double。
- **存储**：`Clip.startTime/duration`、`Keyframe.time`、`Timeline.totalDuration/loopStart/loopEnd`、`macroLoop*` 缓存字段——**保持 float**（脚本 JSON 字段口径不变）；读取时 `(double)` 宽化，仅在「由 double 写回 float 字段」的边界（如 `start` 时快照 `startGameTimeSeconds` 之外的地方）显式转换并注释理由。
- **消除 float 截断点**：删掉 `onRenderFrame` 里 `float gameTime/previewClock/instanceClock/instanceTime` 四个中间 float，整条链路用 double。

### 3.3 语义统一（elapsed 唯一分发口径）

- 规则：**一切对轨道/渲染/玩家的分发时间一律是 elapsed（相对脚本起点的秒）**；绝对时钟只在 `start` 时快照为 `startGameTimeSeconds`，此后不对外暴露。
- 修正 `hasActiveCameraTrack`：入参由「绝对 gameTime」改为「该实例 elapsed」，消除跨脚本并行的算错隐患。
- 删除 `ScriptPlayer.onRenderFrame(double)` 的死参数语义：要么让它真正携带 elapsed（并把内部 `getElapsedSeconds()` 改为入参驱动），要么移除参数——二选一，倾向「参数承载 elapsed，消除每帧重复相减」。

### 3.4 跨端边界（账本不掺和渲染时钟）

- 服务端账本/触发去重继续用 **tick 序号**（`getTickCount`），不改为秒——对齐 MC 世界语义、暂停/卡顿下严格一致。
- 客户端脚本时钟（秒）与服务端账本（tick）**解耦**：客户端不向上报秒数，服务端自己按 tick 推 elapsed（现状 `ScriptEventManager` 已是如此）。文档明确此边界，防止未来「统一成秒」的错误倾向。
- 1.20.2+ 的 tick 速率可调会破坏 `tick/20` 假设——本条在 §5 登记为多版本待办，不在 0.3.6 处理。

---

## 4. 落地步骤与交付物（先底层后应用）

**步骤 1｜Clock 抽象落地（底层）** ✅ 已落地（2026-10-10，Q4 = B4-P1 ∪ 本步骤 1）
- 新增 `Clock` 接口；新增 `GameClock` / `PreviewClock` 两个实现（预览从 float 改 double 累加）。
- `CameraManager` 内联时钟字段收敛到两个 `Clock`；`getGameTimeSeconds()` / `getPreviewTimeSeconds()` 改为委托。
- 交付物：`Clock` 接口 + 两实现 + `CameraManager` 委托接线；`compileJava` 通过、无调用方编译错误。

落地记录（2026-10-10，与 B4-P1 合并执行，见 `camera-core-split.md` §三 P1）：
- 新增 `util/Clock.java`（`@FunctionalInterface`，`double seconds()`）、`util/GameClock.java`（`advance(boolean frozen, long nowNanos)` / `reset()`；`lastRealNanos = 0` 的冻结技巧收进类内）、`util/PreviewClock.java`（`advance` / `freeze()` / `seek(double)`；读数 double 累加）。包位置按 §6 待定项取 `util/`（与 `TimeInterpolation` 同包）。
- `CameraManager`：内联字段 → 两个时钟实例（`gameClock` / `previewClock`）；`onRenderFrame` 两处内联累加 → `advance(...)`（游戏时钟冻结口径不变）；`deactivateNow` 末实例退出的 `gameTimeSeconds = 0; lastRealNanos = 0;` → `gameClock.reset()`；`exitPreview` / `emergencyStop` / `resume` 的 `lastPreviewRealNanos = 0` → `previewClock.freeze()`；`previewClockSeconds()` 删除（预览实例直接 `setClockSource(previewClock)`）；`getGameTimeSeconds()` / `getPreviewTimeSeconds()` 保留为委托读数（调用方零改动）。
- `ScriptPlayer.clockSource`：`DoubleSupplier` → `Clock`（默认 `CameraManager.INSTANCE::getGameTimeSeconds` 不变；`setClockSource(Clock)`）。
- 边界：`previewTime` 变 double 后，`alignTime` / `repositionAudio` / `start(preExecuteAt)` 三个仍收 float 的入参处显式窄化——窄化值与原 float 读数一致（Sterbenz：`previewHead - (float)previewHead` 精确），double 化归步骤 2。
- 验证（Q4 报告详列）：compileJava 通过；harness `E:/tmp/ic-b4p1`（38 项全过）——游戏时钟与旧内联公式 200k 帧逐位一致、冻结 / 复位等值；预览时钟 1 小时 float 版累计误差 2.793 s vs double 版 1.7e-8 s；注入切换实测（默认 = 游戏时钟、预览 = 播放头、两时钟独立）；validator / icv2 / icgl / quadrant 实机捕获回归全绿。
- 未做：步骤 2–5（elapsed double 化 / 去死参数 / overlay delta / float 存储边界契约）与 §6 的 `ManualClock` 测试桩。

**步骤 2｜elapsed 链路 double 化**
- `ScriptPlayer.getElapsedSeconds()` 返回 double（或新增 `double` 版本并逐步替换调用点）。
- 删除 `onRenderFrame` 四个 float 中间量；`onRenderFrame` 参数承载 elapsed；`alignTime` / `repositionAudio` 入参改 double。
- 交付物：运行时链路无 float 截断；`compileJava` 通过。

**步骤 3｜语义统一 + 去死参数**
- `hasActiveCameraTrack` 改收 elapsed；跨脚本并行下宏观循环折返判定改用实例 elapsed 而非绝对时钟。
- 交付物：语义一致；交叉验证「跨脚本并行 + 宏观循环」不再算错。

**步骤 4｜overlay delta 真实化（顺带修复）**
- `OverlayManager.update` 改收真实帧间隔（渲染帧 delta），替代写死的 `1f/20f`。
- 交付物：非 20fps 下 overlay 淡入淡出帧率无关。

**步骤 5｜float 存储边界文档化 + 精度契约**
- 在 `script-model.md` G4 处回写「已落地」口径：运行 double、存储 float、读时宽化、写回显式转换。
- 交付物：G4 状态更新；`Clip`/`Keyframe`/`Timeline` 字段注释补「float 存储、运行时 double」契约说明。

（可选）**步骤 6｜ManualClock 测试桩**：如需单测时钟语义，再补 `ManualClock` + 最小测试；不在本批必做。

---

## 5. 影响面与回归清单

改动文件（预计）：
- 新增：`util/Clock.java`（或 `camera/Clock.java`）、`camera/GameClock.java`、`camera/PreviewClock.java`。
- 修改：`camera/CameraManager.java`（时钟收敛 + 去 float 截断 + elapsed 语义）、`script/ScriptPlayer.java`（clockSource 类型、elapsed double、去死参数、宏观循环）、`script/PlayerMoveController.java`（elapsed double）、`overlay/OverlayManager.java`（真实 delta）、`client/lane/ScriptLaneDriver.java` 与 `script/*TrackPlayer.java`（elapsed double 传播）、`script/ScriptPlayer` 调用方若干。

回归场景：
1. 游戏内播放（单实例）——关键帧插值、音频同步、宏观循环。
2. 预览 seek / play / pause / 拖播放头——播放头 double 累加无漂移。
3. 跨脚本并行 + 宏观循环叠加——hasActiveCameraTrack 用 elapsed 后不再算错。
4. 暂停/恢复、holdAtEnd、无限循环片段。
5. 非 20fps（解锁帧率/低帧率）overlay 淡入淡出速度。
6. 服务端账本（跳过投票、触发去重）——tick 序号路径不受影响（未改）。

风险：时钟语义是核心链路，改动需「写→验→报」小步推进；步骤 1–3 每步编译 + 冒烟，不跨步堆积。

## 6. 待定项

- `Clock` 接口放 `util/` 还是 `camera/` 包（跟随 `TimeInterpolation` 在 `util/` 的惯例，倾向 `util/`）。
- `getElapsedSeconds()` 是「改返回类型为 double」还是「新增 double 方法、旧 float 方法保留过渡」——倾向直接改 double（版本原则：不维护兼容层）。
- 1.20.2+ tick 速率可调对服务端 `tick/20` 的影响：登记为多版本待办，0.3.6 不处理。
