# 0.3.6 触发器系统的连续性：离散采样 → 连续语义（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等**执行时再定**
> - 一个文档一个点：本文只谈**条件的判定在时间轴上怎么算**（采样 → 连续）；条件怎么写、怎么组合见 [触发器条件体系](./trigger-conditions.md)，状态怎么记、怎么查见 [脚本状态机与状态追踪](./state-tracking.md)
> - 不写伪代码
>
> 出处：`pending-discussions.md` §〇（2026-10-08 用户裁决）、`implementation-progress.md` P3 / P3-impl——「**现状 = 全采样但不连续（离散轮询）；目标 = 做成连续的（与状态追踪同类思路；用户点名，独立成篇）**」。
>
> 相关文档：
> - [触发器条件体系](./trigger-conditions.md)（组合器 / 前置条件 / 检测频率可配）
> - [脚本状态机与状态追踪](./state-tracking.md)（带时间戳的事实与查询；本文的窗口原语依赖它）
> - [反馈 04：触发器 → 命令延迟构成](./feedback-0.3.5/04-trigger-latency.md)（采样频率现状与用户已做的调整）
> - [反馈 05：on_enter 每局只触发一次](./feedback-0.3.5/05-on-enter-not-repeatable.md)（进入状态机）
> - [时间插值（相机底层）](./temporal-interpolation.md)、`util/TimeInterpolation`（渲染侧的同类问题：tick → 帧间采样）
> - `docs/TRIGGER_TYPES.md`、`docs/modules/trigger.md`（类型、间隔与状态机的文档口径）

---

## 1. 定位与背景

**已确认（用户方向，2026-10-08）**：现状是**全采样但不连续**——所有触发条件都被做成「到点求值一次」（轮询按类型间隔、事件按到达瞬间），系统里没有「两个采样点之间」的概念；目标是**做成连续的**，与状态追踪同属「底层模型升级」这一类，独立成篇，后续按本文实现（P3-impl）。

三个词在本文的口径：

| 词 | 含义 |
|---|---|
| **采样** | 在离散时刻对玩家状态求值一次（`TriggerEngine.onServerTick` 的轮询点 / `TriggerEngine.onGameEvent` 的事件点） |
| **连续** | 条件在**相邻两个采样点之间**的为真情况也算数（「扫过」不被丢掉），并能区分「扫过」与「停留」、给出「进入 / 离开」的边沿 |
| **连续性缺口** | 采样点之间的信息丢失：漏检、只看到净变化、同 tick 内顺序不可分辨 |

**为什么要动**（三条，都是现状的直接后果）：

1. **漏检**：位置类默认 20 tick（1 秒）采一次、朝向类 5 tick（0.25 秒）采一次（`ImmersiveCinematics.registerTriggerTypes`；`docs/TRIGGER_TYPES.md` 各类型标注「每 N ticks 检测一次」）。玩家可以整段穿过触发区域、可以 0.25 秒内瞟一眼目标，而判定只发生在采样点。
2. **「提高频率」是另一条路，且已经走到头**：`feedback-0.3.5/04` 记录用户把 `triggerPollInterval_location` 从 20 调到 2（0.1 秒）；轮询下限 1 tick（0.05 秒），再往下没有空间——而**频率再高也只是把漏检窗口变窄，不能消除**（采样点始终是离散的）。连续性不靠加频率，靠「采样点之间补一段判定」。
3. **后续能力都卡在这上面**：`on_enter` / `exit_buffer` 的「进入 / 离开」、`trigger-conditions.md` 的需求 3（检测频率可配）、状态追踪的「时间窗查询」（`state-tracking.md` §4.2）都要求「时间轴上的区间」概念，而现在只有「此刻的布尔」。

**边界（不做）**：

- 不改条件字段形态与组合器结构（归 `trigger-conditions.md`）；本文只改**判定在时间轴上的语义**。
- 不改记录层（归 `state-tracking.md`）；本文只向它提需求（事件带时间戳、时间窗查询），不设计存储。
- 不做真·连续时间：服务端输入只有每 tick 一份（位置 / 朝向），「连续」只能是**相邻采样点之间的保守近似**（§4.3）。
- 不做客户端判定（判定仍在服务端）。

---

## 2. 现状：采样模型（读码给证据）

### 2.1 两条入口，都是「采样点」

| 入口 | 触发时机 | 求值时刻 | 证据 |
|---|---|---|---|
| **轮询** | 服务端 tick 末段 | `tickCounter % interval == 0` 的那一 tick，逐注册 × 逐在线玩家求值 | `TriggerEngine.onServerTick`；接线：`FabricEvents`（`ServerTickEvents.END_SERVER_TICK.register(ServerEventHandler::onServerTick)`）、`ForgeEvents.onServerTick`（`event.phase == Phase.END`） |
| **事件驱动** | 原生事件回调（击杀 / 交互 / 进度 / 维度切换 / 拾取 / 丢弃 / 使用物品 …） | 事件回调的瞬间 | `ServerEventHandler.onLivingDeath` / `onRightClickBlock` / `onPlayerAdvancement` / `onChangeDimension` / `onPickupItem` … → `TriggerEngine.onGameEvent(事件类型 id, 玩家)` |

两条入口汇合到同一段判定：`prerequisitesMet` → `shouldSkip` → `evaluateSafely` →（`on_enter` 时）`checkEnterState` → `fireTrigger`（`TriggerEngine`）。

### 2.2 轮询：类型级间隔 + 瞬时求值

- 间隔是**触发器类型级常量**，注册时从 `Config` 取值一次：`location` / `xp` / `dimension` 用 `Config.triggerPollIntervalLocation`（默认 20），`biome` 40，`inventory` / `structure` / `gamestage` 20，`observation` / `facing` / `all_of` / `any` 硬编码 5（`ImmersiveCinematics.registerTriggerTypes`、`TriggerType`）。
- 分桶键就是间隔（`TriggerEngine.rebuildIndex`：`pollBuckets.computeIfAbsent(interval, …)`）；同 tick 内多个桶的遍历顺序是 `Int2ObjectOpenHashMap` 的哈希顺序（**不保证按间隔有序**）。
- 求值器读的都是「**此刻**」的玩家状态：`evaluateLocation` 用 `player.getX()/getY()/getZ()`、`evaluateFacing` 用 `player.getXRot()/getYRot()`、`evaluateObservation` 现场做方块 / 实体射线、`evaluateInventory` 现场扫背包（`Evaluators`）。
- 成本模型 ≈ `Σ(注册数 × 在线玩家数 ÷ 间隔)` 次求值/tick——间隔同时充当**节流阀**。
- 间隔在注册时固化（`TriggerType` 持有 `pollInterval`；`registerTriggerTypes` 只在 `ImmersiveCinematics.init` 调用一次）→ 运行期改配置要重启才生效（与 `feedback-0.3.5/04` 记录的「重启游戏生效」一致）。

### 2.3 事件驱动：点事件 + 「最近一次」单槽

- 事件回调先写 tracker，再调 `onGameEvent`：`AdvancementTracker.record` / `KillTracker.record` / `InteractTracker.recordBlock|recordEntity` / `CraftTracker.record` / `UseItemTracker.recordUsed|recordConsumed|…` / `PickupDropTracker.recordPickup|recordDrop` / `DimensionTracker.record`（`ServerEventHandler` 各回调）。
- tracker 是 `Map<UUID, 值>` 的**单槽**（`put` 语义）：同一 tick 内同类事件互相覆盖（`KillTracker.lastKills`、`AdvancementTracker.lastAdvancements`、`UseItemTracker.lastUsed` …）；`DimensionTracker.getLastFrom` 的注释自认「dimension_change 触发时同步写入，**轮询型共用时可能过期**」。
- 求值器读「最近一次」：`evaluateAdvancement` → `getLastAdvancement`；`evaluateItemCraft` → `getLastCrafted`；`evaluateInteract` / `evaluateBlockInteract` / `evaluateItemOnInteract` → `getLastInteraction`（`Evaluators`）。
- 两个例外已经是**集合形态**：`KillTracker.allKills`（`entity_kill` 的 `and` 模式读它，`KillRecord` 带击杀时刻的维度 / 群系 / 坐标）、`PickupDropTracker.pickedUpSet`（注释：「玩家本会话捡起过的全部物品 id 集合」）。
- **没有时间戳**：所有 tracker 字段里没有任何 tick / 时间字段（`Evaluators` 各 tracker）。

### 2.4 状态机：on_enter / exit_buffer 只在采样点更新

- `enterStates`：`Map<UUID, Map<「脚本:触发器」, Boolean>>`，值是「上次采样是否在区域内」的**裸布尔**（`TriggerEngine.enterStates`）。
- `checkEnterState`：本次在区域内且上次不在 → 「新进入」；否则把当前值写回。`exit_buffer` 用 `Evaluators.expandConditions(conditions, buffer)` 外扩出的 `exitConditions`，完全离开外扩区才复位（`TriggerEngine.checkEnterState`；外扩条件在 `ScriptManager.registerAllTriggers` 构造）。
- 只在**采样点**被调用：轮询入口每次轮询调一次（含区域外复位——`feedback-0.3.5/05` 的修复）；事件驱动入口只在事件发生时调一次（`TriggerEngine.onGameEvent`）。
- 播放期间**冻结**：`shouldSkip` 判「玩家正在播放本触发器指向的脚本」时提前 `continue`，`checkEnterState` 不执行（`TriggerEngine.onServerTick`）；文档口径「播放期间不更新状态机，跳过镜头不会立刻重播」（`docs/TRIGGER_TYPES.md` 通用字段 `on_enter`、`docs/modules/trigger.md`）。
- `enterStates` 纯内存、不持久化、不在 `TriggerStateStore` 的存档范围内（重启即「没进去过」）。

### 2.5 时间轴上的其它采样点

| 位置 | 粒度 / 语义 | 证据 |
|---|---|---|
| 延迟触发 | `delayMs → ticks = max(1, delayMs/50)`，tick 粒度到期执行；同一触发器已在队列中则不再入队（引用判重） | `TriggerEngine.fireTrigger`、`TriggerEngine.processDelayedFires` |
| EVENT 轨关键帧 | 每 tick 扫一次；`elapsed = (tick − startTick − totalPausedTicks) / 20`；同一 tick 内到点的多个关键帧按「clip 顺序 × 关键帧顺序」执行（**不是按时间先后**） | `ScriptEventManager.onServerTick`、`handlePause` |
| 组合器 | 每轮用**同一时刻**的玩家状态把 list 内子条件重算一遍（无记忆、无窗口） | `Evaluators.evaluateAllOf` 注释、`evaluateCombinationChild` |
| 前置条件 | 锁存语义（发生过即真）——唯一的跨时间语义，但只有存在性、没有时间 | `PrerequisiteRegistry` / `BuiltinPrerequisites` |

### 2.6 采样模型总表

| # | 条件族 | 采样点 | 采样间隔 | 采样点之间 | 现有「跨时间」能力 |
|---|---|---|---|---|---|
| 1 | 位置（`location` 的 box / radius、`structure` 半径扫描） | 轮询 | 20 tick（配置 1–600） | **无判定** | 无 |
| 2 | 朝向（`facing`）/ 注视（`observation`） | 轮询 | 5 tick | **无判定** | 无 |
| 3 | 状态型（`xp` / `inventory` / `gamestage` / `dimension` / `biome`） | 轮询 | 20 / 40 tick | 只看到**净变化**（`change=increase/decrease` 的前后快照对比） | 无 |
| 4 | 事件型（`advancement` / `entity_kill` / `item_*` / 交互 / 维度切换 / 拾取 / 丢弃 / `login`） | 事件回调 | 事件到达瞬间 | **无**（单槽覆盖、无时间戳） | 「最近一次」单槽 + 两个集合特例 |
| 5 | 组合器（`all_of` / `any`） | 轮询 | 5 tick | 无（同一时刻语义） | 子条件可含锁存前置条件 |
| 6 | 前置条件（`requires` / 组合内） | 每次判定前查 | — | 存在性（发生过即真） | 存在性（W = ∞ 的退化） |

---

## 3. 不连续问题清单

> 以下都是从代码结构直接推出的问题（采样点之间没有判定、没有时间戳），**未在游戏内复现**；每条给「机制 / 证据 / 影响 / 复现方法」，复现方法也是落地步骤 1 的输入。

| # | 问题 | 机制 | 证据 | 影响 | 复现 / 验证方法 |
|---|---|---|---|---|---|
| N1 | **扫过漏检（位置）** | 区域判定只读采样瞬间坐标，两个采样点之间的整段位移不参与判定 | `Evaluators.evaluateLocation`（`player.getX()/getY()/getZ()`）、`evaluateStructure`；`TriggerEngine.onServerTick`（`tickCounter % interval`） | 骑马 / 鞘翅 / 激流 / 矿车 / 传送跨过区域不触发；间隔越大越明显 | 间隔 20：构造「相邻两 tick 的位置连线穿过 box、两个端点都在区域外」的位移（命令控制位置），观察是否触发 |
| N2 | **扫过漏检（朝向 / 注视）** | 同上，朝向也是采样点求值；`facing` / `observation` 间隔 5 tick = 0.25 秒 | `Evaluators.evaluateFacing`（`getXRot()/getYRot()`）、`evaluateObservation`（现场射线）；注册间隔 5 | 快速瞟一眼目标不触发；「看向进入」（`on_enter` + `observation`）更明显 | 快速转头扫过目标（< 0.25 秒）观察；或按采样点构造两次朝向（一次在区间内、一次不在） |
| N3 | **进入 / 离开边沿只在采样点存在** | `enterStates` 是「上次采样是否在区域内」的布尔，边沿 = 相邻两个采样点的布尔翻转 | `TriggerEngine.checkEnterState`、`TriggerEngine.enterStates` | 进入即离开（穿过）不产生边沿；`exit_buffer` 的「离开」同样只在采样点求值 | 与 N1 同场景 + `on_enter: true`，观察是否触发 |
| N4 | **事件单槽覆盖** | tracker 是 `put` 单槽，同类事件在同一 tick 内只留最后一个 | `Evaluators.KillTracker.lastKills`、`AdvancementTracker.lastAdvancements`、`UseItemTracker.lastUsed` 等 | 同 tick 两次击杀 / 两个进度只留最后一条；除 `and` 模式（读集合）外看不到全部 | 同一 tick 内造两次同类事件（命令给两个进度 / 击杀两个实体），检查触发次数 |
| N5 | **事件类不可入组合（无时间戳）** | 事件条件的语义是「最近发生过」，进组合会变成「很久以前发生过也算」；底层没有时间戳区分「刚刚」与「很久以前」 | `Evaluators.evaluateCombinationChild`（事件类一律视为不满足）、`ScriptValidator.validateCombinationConditions`（限 POLLING 子条件）、`docs/TRIGGER_TYPES.md` §25/§26 | 组合能力被类型层面禁用兜底；「击杀后 3 秒内进入区域」这类写法无法表达 | 读码即可确认（校验层会拒绝事件类子条件） |
| N6 | **组合器只有「同一时刻」** | 每轮把 list 内子条件用同一时刻状态重算，无记忆、无窗口 | `Evaluators.evaluateAllOf` 注释、`evaluateCombinationChild` | 不能表达「先后发生」（先 A 后 B），也不能表达「窗口内都发生过」 | 读码 + 构造 `all_of[location, xp]` 先满足其一的场景 |
| N7 | **同 tick 内顺序不可分辨** | ① `pollBuckets` 是哈希序（不同间隔的桶同 tick 命中时求值顺序不保证）；② EVENT 轨同 tick 多个到点关键帧按 clip / 关键帧顺序执行；③ 延迟触发按入队顺序执行 | `TriggerEngine.onServerTick`（遍历 `pollBuckets.int2ObjectEntrySet()`）、`ScriptEventManager.onServerTick`、`TriggerEngine.processDelayedFires` | 同 tick 内的「先后」在语义上不可控（「传送 + 播放」这类组合上可观察） | 造两个同 tick 到点的触发器（不同间隔），观察播放顺序是否稳定 |
| N8 | **变化检测只看到净额** | `inventory change=increase/decrease` 用「上次快照 vs 本次快照」比较 | `Evaluators.evaluateInventory`、`InventoryTracker.setSnapshot` | 采样间隔内「先增后减」（捡起又用掉）→ 净变化为 0，`increase` 漏检 | 间隔 20 tick 内捡起再丢掉 / 用掉该物品，观察 `increase` 是否触发 |
| N9 | **播放期间状态机冻结** | `shouldSkip` 在 `checkEnterState` 之前提前返回 | `TriggerEngine.onServerTick`（`if (shouldSkip(player, reg)) continue;`）、`TriggerEngine.onGameEvent` | 播放期间「进入又离开」不记；播放结束后第一次采样按冻结前的旧值判定（fb-05 修复后的**有意**行为，记录在此供连续性设计取舍） | 读码 + 播放中走出再走回，观察是否重播（文档口径：不会立刻重播） |

---

## 4. 连续语义目标（方向）

### 4.1 目标形态

把条件的真值从「采样点的布尔」升级为「**时间轴上的区间**」：

- 每个条件在每个采样点得到一个布尔采样值；
- 相邻采样点之间，用**轨迹近似**（位移段 / 朝向弧）补一次判定，使「扫过」可见（§5.2）；
- 判定从「此刻是否满足」升级为对区间的**查询**（原语见 §4.2）；
- 语义原语**按触发器可选**，默认档 = 现状（零回归），需要连续语义的触发器显式打开（档位见 §4.4）。

### 4.2 语义原语（方向；字段名执行时定）

| 原语 | 含义 | 解决的问题 | 现状对应物 |
|---|---|---|---|
| **存在**（any-instant） | 区间内任意时刻为真 → 算满足 | N1 / N2（扫过漏检） | 无（只有「此刻」） |
| **持续**（dwell） | 连续为真 ≥ D（tick）才算满足 | 扫过的**误报**（快速掠过也触发） | 无 |
| **边沿**（enter / leave） | 假→真 / 真→假 的那一刻 | N3（进入 / 离开边沿） | `on_enter` / `exit_buffer` 的采样版 |
| **窗口**（recent） | 最近 W tick 内为真过 | N5 / N6（事件与条件的时序关系） | 前置条件的「存在性」是 W = ∞ 的退化 |

### 4.3 「连续」的口径边界（重要）

服务端的输入只有每 tick 一份（客户端移动 / 朝向包每 tick 一个），因此：

- **能做的**：把相邻两个采样点之间的轨迹近似成一段（位置：线段；朝向：最短弧），并在这一段的**任意时刻**判定条件；
- **不能做的**：真·连续时间（没有亚 tick 输入）；也不能知道玩家在两个采样点之间「实际怎么走」（曲线、绕行、瞬移转向）；
- **结论**：连续性 = **保守近似**——宁可「扫过也算」，也不要「扫过丢掉」；误报由「持续 / 边沿」原语吸收（§4.4 档 C）。
- 渲染侧已有同族机制可参照：`util/TimeInterpolation`（tick ↔ 渲染帧的帧间采样，`partialTick` 来源是客户端 `Minecraft.getFrameTime()`，用于相机取景与实体朝向）；触发器侧没有对应物，且服务端没有 `partialTick`。

### 4.4 档位（策略选项；供落地时选）

| 档 | 语义 | 代价 / 风险 | 工作量（相对） |
|---|---|---|---|
| **A. 保持现状** | 采样点即真值；只做文档化（写清漏检窗口 = 采样间隔） | 零成本；漏检与「提高频率」的天花板都保留 | 0 |
| **B. 区间化（扫过即算）** | 存在原语：采样点之间补一段判定（位置扫掠 / 朝向弧） | 中：每玩家一份采样快照 + 按类型实现扫掠；**误报**（快速掠过也触发）需接受或由 C 收 | 中 |
| **C. 区间化 + 语义原语** | B + 持续 / 边沿 / 窗口，按触发器可选 | 高：需要真值区间记账与配置面；窗口原语依赖状态追踪的时间戳事实（P1） | 高 |
| **D. 部分适配** | 只在最痛的族上做（如位置 + `on_enter` 边沿；朝向 / 注视后置） | 低—中：按族分批，风险可控 | 低 |

**方向建议（执行时定）**：D → B → C 递进；档 C 的「窗口」与 P1 合流。

### 4.5 与「提高采样频率」的关系（已确认的口径）

- 采样间隔仍是**节流阀**：连续语义不要求把间隔降到 1 tick——间隔决定「最长多久采样一次」，连续语义决定「这次采样与上次采样之间算不算」。
- 两者可叠加但不等价：间隔 20 → 采样点之间最长 1 秒的轨迹近似；间隔 1 → 近似段变短、成本线性上升、**仍不能保证不丢**。
- `trigger-conditions.md` 需求 3（检测频率可配）与本文的「采样间隔」是同一参数的两种视角（节流 / 漏检窗口），落地时一起定字段。

---

## 5. 设计方向（方向；执行时再定）

### 5.1 采样快照：引擎自持一份「上次采样」

- 每玩家一份 `(x, y, z, yaw, pitch, tick)` 快照，在每次判定前更新（轮询桶与事件入口共用同一套采样点语义）。
- **不依赖原版** `xo/yo/zo`、`xOld/yOld/zOld`、`yRotO/xRotO` 作为「上一采样点」：这些字段被两处分别改写——实体 tick 起点（`ServerLevel.tickNonPassenger` → `Entity.setOldPosAndRot()`）与移动包应用路径（`ServerGamePacketListenerImpl.handleMovePlayer` → `Entity.absMoveTo(...)`，会把 `xo/yo/zo` 与 `yRotO/xRotO` 直接置成**新值**）；它们在采样点的实际值需要实测（落地步骤 1），自持快照则与我们的采样时刻严格对齐。
- 生命周期与 `Evaluators` 各 tracker 一致：玩家退出时清理（`ServerEventHandler.onPlayerQuit`）。
- 传送 / 维度切换 / 载具：快照带「采样点是否连续」的标记——传送后把该段标为「瞬移」，不参与扫掠（§8）。

### 5.2 轨迹近似：位置扫掠 + 朝向弧

- **位置**：段（上次采样点 → 本次采样点）与条件的几何体求交——box（`corner1/corner2`）、球（`position + radius`）；`structure` 的半径扫描同理（沿段取子步查结构）。
- **朝向**：`facing` 用「yaw 弧段 + pitch 区间」的联合判定（弧段取最短路径；跨 ±180° 沿用现有 `wrapDegrees` 语义，见 `Evaluators.evaluateFacing`）；`observation` 是射线，扫掠 = 子步重放（成本最高，见 §8）。
- **子步**：连续判定的精度 / 成本旋钮（子步数执行时定）；静止（无位移、无转向）时**零额外成本**（段长为 0 → 退化为单点判定）。

### 5.3 区间记账：把 `enterStates` 升级为「边沿状态」

- 现状 `enterStates` 是「上次采样是否在区域内」的布尔；连续语义需要「**上次判定为真的时刻**」（tick）才能算边沿与持续。
- 方向：每个（玩家 × 触发器）记 `最近一次为真的 tick`、`本次连续为真的起点 tick`、`最近一次边沿`；`on_enter` = 本次为真且上次为假（或上次为真已早于离开判定）；`dwell` = 本次真且 `tick − 起点 ≥ D`。
- 播放期间冻结（N9）的取舍要显式定：连续语义下「播放期间扫过」是否补记（现状：不记；文档口径「跳过镜头不会立刻重播」）。

### 5.4 事件侧：时间戳（与 P1 合流）

- 事件 tracker 的「单槽」与「无时间戳」是 N4 / N5 / N6 的共同根因；解法（方向）：事件记成**带 tick 的事实**，判定侧按查询取用——即 `state-tracking.md` §5.1（记账口）与 §5.3（查询面）。
- 本文对 P1 的需求：① 每条事件事实带服务端 tick；② 同一 tick 多事件不互相覆盖；③ 提供「最近 W tick 内是否有某类事实」的查询。
- 在 P1 落地前，本文的最小版本不依赖它（只做 §5.1–§5.3 的几何 / 边沿连续化）。

### 5.5 组合器：从「同一时刻」到「同一窗口」（方向，后置）

- 现状：`all_of` / `any` 用同一时刻的状态求值（`evaluateAllOf` 注释明写）。
- 方向：给组合器一个可配的**窗口**（W）——「窗口内 A 与 B 都满足过」（`all_of` 窗口版）、「窗口内任一满足过」（`any` 窗口版）；W = 0 即现状语义。
- 事件类子条件的解禁（N5）依赖窗口与时间戳：有窗口 + 时间戳后，「很久以前发生过也算」变成「窗口内发生过才算」（`state-tracking.md` §7 的「口径冲突点」已记录这条）。

### 5.6 成本与预算

- 连续判定只加在**有几何 / 朝向语义的类型**上；状态型（`xp` / `inventory` / `gamestage` / `dimension` / `biome`）的「连续化」是 N8 的净变化问题——方向是**变化事件化**（归 P1 的事件记账），不是扫掠。
- 每 tick 只对**本 tick 该采样的桶**做（沿用现有分桶节流）；扫掠成本 ∝ 位移段上的几何求交次数，静止时为零。
- 快照内存：每玩家 6 个数值 + 少量 tick 字段；触发器侧边沿状态 ∝ 注册数 × 玩家数（与现有 `enterStates` 同量级）。

---

## 6. 与状态追踪（P1）的关系

| 层 | 文档 | 回答的问题 | 现状 |
|---|---|---|---|
| 记录 / 查询（事实层） | `state-tracking.md` | 「发生过什么、几次、何时、哪个实例」 | 五份记录、四种生命周期 |
| **时间轴语义（判定层）** | **本文** | 「条件在时间轴上何时为真、算不算数、边沿在哪」 | 采样点即真值，无区间概念 |
| 条件怎么写 / 怎么组合 | `trigger-conditions.md` | 类型、组合器、前置条件、频率可配 | 已落地最小版本 |

- **同源**：P1 的 P5（点事件不可组合、单槽会过期）与本文的 N4 / N5 / N6 是同一根因（底层没有时间轴）的两个面——P1 负责**记下来**（带时间戳的事实），本文负责**怎么用**（窗口 / 顺序 / 边沿）。
- **依赖方向**：本文档 C（持续 / 窗口）依赖 P1 的时间戳事实；本文的最小版本（位置 / 朝向 / 边沿）**不依赖** P1，可先落地。
- **不重叠**：本文不改存储形态与查询接口（P1），不改条件字段（`trigger-conditions.md`）；P1 不定义「采样点之间怎么算」（本文）。
- **接口点**：① 事件事实的 tick 时间戳（P1 §4.1 的「时间」维度）；② 「最近 W tick 内是否有某类事实」查询（P1 §4.2 的「时间窗」原语）；③ 触发器命中事实的写入点（`fireTrigger`，P1 §5.1）——连续语义下一次「扫过」可能跨越多个采样点，去重与记账口径要在两边一致（记「命中一次」还是「进入一次」）。

---

## 7. 落地步骤（每步一个可交付物）

| # | 步骤 | 交付物（可验证） | 依赖 |
|---|---|---|---|
| 1 | **采样点实测**：在采样点记录每玩家 `(x,y,z,yaw,pitch)` 与 `xo/xOld/yRotO` 等原版字段，确认「每 tick 一份快照 + 相邻快照位移」的可行性，以及传送 / 载具 / 维度切换 / 玩家加入时的表现 | 实测报告（含传送、骑乘、鞘翅三种场景的数据与结论） | — |
| 2 | **最小版本：位置扫掠 + 进入边沿**（档 D 第一批）：`location`（box / radius）在相邻采样点之间的段判定；`on_enter` 的边沿改为「段内为真即算进入」；默认保持现状、按触发器显式开启 | 可用 + 测试脚本（`cinematics/tests/trigger/`）+ 验收记录（扫过必触发、老脚本零回归） | 1 |
| 3 | **朝向弧（`facing`）**：yaw 弧段 + pitch 区间；`observation` 扫掠先做**成本实测**再定是否做 | 可用 + 成本实测数据（子步数 vs 每 tick 耗时） | 2 |
| 4 | **语义原语（持续 / 窗口）与配置面**：`dwell` / `window` 参数、按触发器 / 按脚本配置；与 `trigger-conditions.md` 需求 3（频率可配）一起定字段 | 参数可用 + 校验 + 文档（`docs/TRIGGER_TYPES.md`） | 2、3、P1（窗口） |
| 5 | **事件侧时间戳与多事件**（与 P1 步骤 5 合流）：事件 tracker 的「最近一次」退场，改由事实查询供数 | 与 P1 的交付物合并验收 | P1 |
| 6 | **编辑器与文档同步**：WebUI 触发器编辑器的连续语义开关（编辑器阶段） | 编辑器 UI + 文档 | 4 |

**最小版本（方向）**：只做步骤 2——位置类的「扫过即算」+ `on_enter` 边沿连续化，默认关闭（老脚本零回归）。

**验收标准（草案）**：

- [ ] 段穿过区域必触发（含鞘翅 / 激流 / 传送三种位移）
- [ ] 静止在区域内不重复触发（边沿语义不退化）
- [ ] 默认档（未开启）行为与今天逐点一致（零回归）
- [ ] 成本可测：静止时零额外求值；移动时每采样点的扫掠耗时在预算内
- [ ] `dwell` 档下「快速掠过不触发、停留触发」（档 C 落地后）

---

## 8. 可能的问题

- **误报与语义漂移**：扫过即算会让「高速掠过」也触发——有些场景正是要这个（路过即播），有些不是（误触）。`dwell` 能吸收，但「多长的停留算数」是内容设计问题，不是技术问题。
- **轨迹近似本身不精确**：位移段是「起点 → 终点」的直线，玩家实际走的是曲线（贴着区域边缘绕行）→ 可能误报（段穿过但人没穿过）或漏检（段不穿但曲线穿过）。子步提高精度但不改变本质。
- **传送 / 载具 / 维度切换**：位移段可能是巨大的跳变（`/tp`、传送门、载具被推动）——必须显式标记为「不连续」，否则扫掠会把整条世界对角线当路径。
- **朝向弧的假设**：两个采样点之间玩家可能「绕远」转向（先转 180° 再转回来）或由外部强制转向；最短弧是近似。pitch 与 yaw 的联合扫掠（球面上的弧带）几何比位置段复杂。
- **`observation` 扫掠成本**：射线本身就要做方块 / 实体查询，子步重放会成倍放大；实体目标还会移动（采样点之间的实体位置同样不可知）。
- **冻结语义（N9）的取舍**：播放期间是否补记「扫过」，两种选择都有理由（补记 → 播放结束可能立刻重播；不补记 → 连续语义在播放期间有洞）。
- **与去重 / 单实例的交互**：连续语义下一次「扫过」可能跨越多个采样点；`fireTrigger` 的 `markTriggered` 去重、`shouldSkip` 的「正在播放」门控要定义清楚「一次扫过 = 一次触发」。
- **确定性 / 可重放**：判定依赖采样点序列（客户端包到达时机、服务器卡顿都会改变采样内容）→ 同一段玩家轨迹在不同负载下可能判定不同；测试脚本要用命令控制位置（而不是用输入控制）来构造稳定场景。
- **成本叠加**：区块预加载、实体同步、事件时间线都在同一 tick 上；连续判定的 CPU 要计入 tick 预算（尤其多人服）。
- **老脚本行为变化**：若默认档改为连续（而不是显式开启），所有位置 / 朝向触发器都会「更容易触发」——按版本原则可以接受，但必须作为一条明确的破坏性变更写进文档与校验提示。
- **配置面冲突**：`trigger-conditions.md` 需求 3（按触发器 / 按脚本的频率）与本文的 `dwell` / `window` 落在同一处配置面上，字段形态要一次定清，避免两套「时间参数」。

---

## 9. 待定项

- **默认档**：默认保持现状（显式开启连续）还是默认连续（老脚本行为变化）？——**用户拍板**。
- **原语清单与命名**：存在 / 持续 / 边沿 / 窗口是否全做；字段名（`dwell` / `window` / `mode` …）执行时定。
- **`dwell` 的单位与默认值**（tick 还是秒；给不给默认值）。
- **子步数与精度旋钮**：扫掠的子步数是否暴露给脚本作者，还是内部常量。
- **传送 / 载具的判定**：传送算不算「进入」（语义问题）；载具上的玩家位置取玩家还是载具。
- **`observation` 是否纳入连续化**（成本）；实体目标移动的近似口径。
- **冻结语义（N9）在连续语义下的取舍**（播放期间补记 / 不补记）。
- **组合器窗口**：`all_of` / `any` 是否升级为窗口语义、窗口是否可配、事件类子条件是否随之解禁（与 `trigger-conditions.md` §5、`state-tracking.md` §7 口径冲突点联动）。
- **与 P1 的接口形态**：事件事实的时间戳粒度（服务端只有 tick）；「最近 W tick」查询的接口边界（P1 §6.4 待定项的一部分）。
- **状态型条件（`xp` / `inventory`）的「净变化」问题**：走事件记账（P1）还是走「每次采样都记快照序列」（本文）。
- **编辑器呈现**：连续语义开关放在触发器编辑器的哪一层（触发器 / 条件 / 全局）。
- **性能预算与降级**：连续判定的 tick 预算上限；超预算时是否退化为采样点判定。

---

## 10. 相关文档

| 文档 | 关系 |
|---|---|
| [trigger-conditions.md](./trigger-conditions.md) | 条件体系与组合器；其需求 3「检测频率可配」与本文的采样间隔是同一参数 |
| [state-tracking.md](./state-tracking.md) | 事实记录与查询；本文的「窗口 / 顺序」依赖它的时间戳事实（§6） |
| [feedback-0.3.5/04-trigger-latency.md](./feedback-0.3.5/04-trigger-latency.md) | 采样频率现状（用户已把 location 间隔调到 2 tick）；延迟 ≠ 连续性，但两者都在「检测频率」这个面上 |
| [feedback-0.3.5/05-on-enter-not-repeatable.md](./feedback-0.3.5/05-on-enter-not-repeatable.md) | `on_enter` 状态机的修复；本文的边沿语义是它的连续版 |
| [wait-point-track.md](./wait-point-track.md) | 等待点等「事件出现」——需要时间窗语义（与本文档 C 同源） |
| [temporal-interpolation.md](./temporal-interpolation.md) / `util/TimeInterpolation` | 渲染侧的同类问题（tick → 帧间采样）；本文是服务端侧的对应物 |
| [implementation-progress.md](./implementation-progress.md) | P3（本文）/ P3-impl（按本文实现） |
| `docs/TRIGGER_TYPES.md` / `docs/modules/trigger.md` | 类型、间隔与状态机的文档口径；连续语义落地后需回改 |

---

## 代码证据索引（2026-10-08）

> 依据：本仓库工作区源码（`common/src/main/java/...`、`forge/src/main/java/...`、`fabric/src/main/java/...`）与 MC 1.20.1 源码（`build/mc-sources/`、`build/mc-decompile/`）。引用以**文件 + 方法名**为主。

### ① 核实为真的断言

| 断言 | 证据 |
|---|---|
| 两条触发入口（事件驱动 / 轮询），事件类型 id 为字符串 | `TriggerEngine.onGameEvent`（查 `eventIndex`）、`TriggerEngine.onServerTick`（`tickCounter % interval` + `pollBuckets`）；`ListenStrategy` 仅 `EVENT_DRIVEN` / `POLLING` |
| 两条入口汇合到同一段判定 | `TriggerEngine.onGameEvent` 与 `onServerTick` 均走 `prerequisitesMet` → `shouldSkip` → `evaluateSafely` →（`on_enter`）`checkEnterState` → `fireTrigger` |
| 轮询在服务端 tick 末段 | `FabricEvents`（`ServerTickEvents.END_SERVER_TICK.register(ServerEventHandler::onServerTick)`）、`ForgeEvents.onServerTick`（`event.phase == TickEvent.Phase.END`） |
| 轮询间隔是类型级常量、注册时从 `Config` 取值 | `ImmersiveCinematics.registerTriggerTypes`（`Config.triggerPollIntervalLocation` / `...Biome` / `...Inventory` / `...Structure` / `...Gamestage`；`observation` / `facing` / `all_of` / `any` 硬编码 5）；`TriggerType` 持有 `pollInterval`；`Config` 字段（默认 20 / 40 / 20 / 20 / 20） |
| 间隔在注册时固化（运行期改配置需重启） | `registerTriggerTypes` 只在 `ImmersiveCinematics.init` 调用（全仓唯一调用点）；`ForgeConfig`（`defineInRange("triggerPollInterval_location", 20, 1, 600)` 等）、`FabricConfig`（camelCase 键） |
| 分桶键是间隔、桶遍历为哈希序 | `TriggerEngine.rebuildIndex`（`pollBuckets.computeIfAbsent(interval, …)`）；`onServerTick` 遍历 `pollBuckets.int2ObjectEntrySet()`，容器为 `Int2ObjectOpenHashMap` |
| 轮询求值读「此刻」状态 | `Evaluators.evaluateLocation`（`player.getX()/getY()/getZ()`、`inBox` / `inRadius`）、`evaluateFacing`（`getXRot()/getYRot()` + `Mth.wrapDegrees`）、`evaluateObservation`（现场 `clip` / `ProjectileUtil.getEntityHitResult`）、`evaluateStructure`（`player.blockPosition()` + 半径扫描）、`evaluateInventory`（现场扫背包） |
| 事件回调先写 tracker 再调引擎 | `ServerEventHandler.onPlayerAdvancement` / `onLivingDeath` / `onRightClickBlock` / `onLeftClickBlock` / `onInteractEntity` / `onCraftItem` / `onRightClickItem` / `onChangeDimension` / `onPickupItem` / `onDropItem` / `onEntityAdded` 均 `Evaluators.*Tracker.record(...)` → `TriggerEngine.INSTANCE.onGameEvent(<类型 id>, player)` |
| tracker 是 `Map<UUID, 值>` 单槽（`put` 覆盖） | `Evaluators.KillTracker`（`lastKills` / `allKills`）、`AdvancementTracker`、`DimensionTracker`、`InteractTracker`、`CraftTracker`、`PickupDropTracker`、`UseItemTracker`、`InventoryTracker` 各字段与方法 |
| 两个 tracker 已是集合形态 | `KillTracker.allKills` + `KillRecord`（击杀时刻维度 / 群系 / 坐标；`evaluateEntityKill` 的 `and` 模式与 `matchesScene` 按记录判定）；`PickupDropTracker.pickedUpSet`（注释「本会话捡起过的全部物品 id 集合」） |
| 单槽会过期（自认） | `DimensionTracker.getLastFrom` 注释「dimension_change 触发时同步写入，轮询型共用时可能过期」 |
| tracker 无时间戳字段 | 各 tracker 字段仅 `Map<UUID, String/Set/Record/Map>`，无 tick / 时间字段 |
| tracker 生命周期 = 会话内，退出时清理 | `ServerEventHandler.onPlayerQuit` 逐个 `clear(uuid)` |
| `enterStates` 是进程内裸布尔、键为「脚本:触发器」 | `TriggerEngine.enterStates`、`TriggerEngine.checkEnterState`；不在 `TriggerStateStore` 序列化范围内 |
| 状态机只在采样点更新、播放期间冻结 | `TriggerEngine.onServerTick`（`shouldSkip` 在 `checkEnterState` 之前 `continue`）、`onGameEvent`（同序）；`docs/TRIGGER_TYPES.md` 通用字段 `on_enter`、`docs/modules/trigger.md` |
| `exit_buffer` 走外扩条件 | `ScriptManager.registerAllTriggers`（`Evaluators.expandConditions(conditions, td.getExitBuffer())`）、`Evaluators.expandConditions` / `expandAxis` |
| 延迟触发的 tick 截断与引用判重 | `TriggerEngine.fireTrigger`（`Math.max(1, delayMs / 50)`；`df.reg() == reg` 不重复入队）、`processDelayedFires` |
| EVENT 轨关键帧每 tick 扫、同 tick 按 clip/关键帧顺序执行 | `ScriptEventManager.onServerTick`（`elapsed = (currentTick − startTick − totalPausedTicks) / 20f`；`triggeredKeyframes` 去重；双层循环顺序） |
| 组合器是「同一时刻」求值、无记忆 | `Evaluators.evaluateAllOf` 注释（「每轮求值都用同一时刻的玩家状态把所有子条件重新算一遍（无记忆、无等待、短路）」）、`evaluateCombinationChild`（前置条件优先、事件类 / 嵌套组合器一律 false、非 POLLING 拒绝） |
| 事件类不可入组合（校验层） | `ScriptValidator.validateCombinationConditions`（子类型必须已注册且 `ListenStrategy.POLLING`）；`docs/TRIGGER_TYPES.md` §25 / §26 / §27 |
| 前置条件是锁存 / 存在性语义 | `PrerequisiteRegistry` / `BuiltinPrerequisites`（`script_played` → `hasPlayed` 等）、`TriggerEngine.prerequisitesMet`（AND，且在 `shouldSkip` 之前） |
| 触发器注册动作写死为「播放本脚本」 | `ScriptManager.registerAllTriggers`（`List.of(new StartPlaybackAction(meta.getId()))`） |
| 触发器 id = 类型 + 脚本 id（同类型多条共用身份） | `ScriptManager.registerAllTriggers`（`td.getType() + "_" + meta.getId()`）；`TriggerDefinition` 无 id 字段，`ScriptParser.parseTriggerDefinition` 只读 type / conditions / repeatable / delay / on_enter / exit_buffer / requires |
| 渲染侧已有帧间采样工具（客户端） | `util/TimeInterpolation`（`partialTick()` = `Minecraft.getInstance().getFrameTime()`；`entityPosition` 用 `e.xo/yo/zo` → 当前坐标；注释自述「上一 tick 快照复用原版既有机制」）；调用方 `CameraTrackPlayer`（`follow` / `look_at_target` / `yaw_base` 等） |
| MC 有上一 tick 快照字段，但被两处分别改写 | `Entity.xo/yo/zo`、`xOld/yOld/zOld`、`yRotO/xRotO`（`build/mc-sources/.../Entity.java`）；`Entity.setOldPosAndRot()`（四组字段全写为当前值）；`Entity.baseTick()`（`this.xRotO = this.getXRot(); this.yRotO = this.getYRot();`）；`Entity.absMoveTo(double,double,double)`（只把 `xo/yo/zo` 置为新位置）与 `absMoveTo(double,double,double,float,float)`（再把 `yRotO/xRotO` 置为新朝向）；`ServerLevel.tickNonPassenger`（实体 tick 前调 `entity.setOldPosAndRot()`）；`ServerGamePacketListenerImpl.handleMovePlayer`（正常路径 `player.move(...)` 后 `player.absMoveTo(d, e, f, g, h)`，传送 / 位置纠正路径同样走 `absMoveTo`）；`MinecraftServer.tickChildren`（先 levels，后 `getConnection().tick()`） |
| 类型级间隔与文档口径一致 | `docs/TRIGGER_TYPES.md`（location / xp / dimension / inventory / structure / gamestage 20 tick、biome 40 tick、observation / facing / all_of / any 5 tick 的「每 N ticks 检测一次」标注） |
| 用户已把 location 间隔调到 2 tick | `feedback-0.3.5/04-trigger-latency.md`（「已做（配置，非代码）」「重启游戏生效」「轮询下限 1 tick」） |

### ② 未验证 / 未实现

- **N1–N9 现象未在游戏内复现**：全部由代码结构推出（采样点之间无判定、无时间戳、容器遍历序），本文只给复现方法；实测归落地步骤 1–3。
- **原版字段在采样点的实际取值未实测**：`xo/yo/zo` 与 `xOld/yOld/zOld` / `yRotO/xRotO` 在同一 tick 内被多处改写（见上表），「哪个字段能稳定给出上一采样点」需要挂点打印确认（落地步骤 1）；本文的设计方向因此选择「引擎自持快照」。
- **`pollBuckets` 哈希序的实际表现未实测**：只从容器类型（`Int2ObjectOpenHashMap`）推出「不保证按间隔有序」。
- **扫掠 / 子步 / `observation` 扫掠的成本未实测**：无数据；落地步骤 3 以实测数据决定 `observation` 是否纳入。
- **连续语义本身零实现**：全仓无采样快照、扫掠、边沿 / 持续 / 窗口相关代码；本文为方向稿。
- **组合器窗口与「先后」语义未实现**：`all_of` / `any` 的窗口版、事件类子条件解禁都只是方向。
