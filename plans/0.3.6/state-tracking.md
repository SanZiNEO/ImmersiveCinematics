# 0.3.6 脚本状态机与状态追踪：布尔点状事件判定 → 事实追踪（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 事实类型清单、字段名、查询接口、存储格式、JSON、迁移步骤等**执行时再定**
> - 一个文档一个点：本文只谈**状态怎么记、怎么查**；条件怎么写、怎么组合见 [触发器条件体系](./trigger-conditions.md)
> - 不写伪代码
>
> 出处：`pending-discussions.md` §〇-1（2026-10-08 用户裁决）、`implementation-progress.md` P1（计划）/ P1-impl（按本计划实现）。
>
> 相关文档：
> - [触发器条件体系](./trigger-conditions.md)（前置条件 / 组合器；其 §5 待定项由本文承接）
> - [并行播放](./parallel-playback.md) §3.5 / §3.7（同脚本单实例、实例账本）
> - [等待点轨道](./wait-point-track.md)（事件源；消费方）
> - [脚本循环](./script-loop.md)（“播完”的定义）
> - [画面转场](./scene-transition.md)（跳过 / 打断）
> - [模组架构图](./mod-architecture-diagram.md)（退出原因五值）
> - [脚本模型](./script-model.md)（“一份脚本定义 × N 份运行时状态”）
> - `docs/TRIGGER_TYPES.md`（`requires` / §27）、`docs/SCRIPT_FORMAT.md`

---

## 1. 定位与背景

**已确认（用户方向，2026-10-08 原话转述）**：把原本「**布尔值点状事件判定**」升级为「**事实追踪**」；先写这份计划，**后续优化基于它**。

本文谈的是**记账层**——系统如何记住“发生过什么”，以及谁能按什么口径查。它不挂在触发器类型、条件组合、画面表现任何一条线上，而是它们共同的底座：

| 层 | 谁 | 现状形态 |
|---|---|---|
| 条件怎么写、怎么组合 | 触发器类型 / 组合器 | 已有独立计划（`trigger-conditions.md`） |
| **状态怎么记、怎么查** | **本文** | **持久布尔标记 + “最近一次”单槽 + 实例账本，三套并存（§2.9 拆开为五份记录）** |
| 效果怎么表现 | 画面 / 音频 / 轨道 / 命令 | 各自主线文档 |

**为什么要动**：现有记录只回答“是 / 否”，而且同一批信号被记在三处、键不同、生命周期不同（详见 §2.9）。后续要做的事都卡在这上面：

- **等待点事件树**（[wait-point-track.md](./wait-point-track.md)）要“等一个具体事件出现”，事件不是布尔；
- **跳过 / 打断的口径**（`trigger-conditions.md` §5 待定第 2 条）要能表达“播完是否含跳过”，布尔层把五种退出原因压成了一个 `true`；
- **并行播放**（[parallel-playback.md](./parallel-playback.md) §3.5 / §3.7）已经按实例记账，而状态层还按“玩家 × 脚本”记账，两套口径并存；
- **“为什么触发”的展示 / 审计**（编辑器、命令、日志）在布尔层无处取证。

**边界（不做）**：本文不改条件求值算法、不新增触发器类型、不动渲染与音频；客户端仍然只上报信号、只做展示，判定全在服务端（现状即如此）。条件组合的字段形态归 `trigger-conditions.md`，本文只提供它脚下的查询原语。

---

## 2. 现状调查（读码给证据）

### 2.1 触发流水线：两条入口

**事件驱动**：`ServerEventHandler` 的原生事件回调先往 tracker 记一笔“最近一次”，再调 `TriggerEngine.onGameEvent(事件类型 id, 玩家)`；`TriggerEngine` 按事件类型 id 查 `eventIndex`，对每条注册逐项判定（`TriggerEngine.onGameEvent`，`eventIndex` 字段）。事件类型 id 是字符串（`"advancement"` / `"entity_kill"` / `"block_interact"` …），由平台层回调传入。

**轮询**：`TriggerEngine.onServerTick` 自增 `tickCounter`，按 `pollBuckets`（间隔 tick → 注册列表）逐桶、逐玩家判定；轮询间隔是**触发器类型级**常量（`TriggerType` 注册时给定，`ListenStrategy.POLLING`）。`ListenStrategy` 只有 `EVENT_DRIVEN` / `POLLING` 两值。

两条入口在各自求值后汇合到同一段：`prerequisitesMet` → `shouldSkip` → 求值 → （on_enter 时）`checkEnterState` → `fireTrigger`。**即：无论事件还是轮询，最终都落到同一套布尔记账上。**

### 2.2 布尔标记层：`TriggerStateStore` / `PlayerTriggerState`

`TriggerStateStore` 是**按玩家**的内存表 + SNBT 文件持久化（`initialize` 定根目录为世界存档下的 `immersive_cinematics/trigger_state`，`STORE_PATH` / `VERSION = 1` 常量；文件名为玩家 UUID）。每个玩家一份 `PlayerTriggerState`，里面只有三份集合：

| 集合 | 语义 | 写入者 |
|---|---|---|
| `triggeredScripts`（脚本 id → 触发器 id 集合） | 该脚本的这条触发器“触发过” | `markTriggered`（由 `TriggerEngine.fireTrigger` 调） |
| `startedScripts` | 该脚本“开始播放过” | `markScriptStarted`（由 `TriggerEngine.onPlaybackStarted` 调） |
| `completedScripts` | 该脚本“结束播放过” | `markScriptCompleted`（由 `TriggerEngine.onScriptFinished` 调） |

关键事实：

- **`markTriggered` 的返回值就是去重机制**：`PlayerTriggerState.markTriggered` 返回“是否首次”，`TriggerEngine.fireTrigger` 拿它做 `if (!isNew && !reg.isRepeatable()) return;`。
- **`hasPlayed` 是推导出来的布尔**，不是记录：`startedScripts.contains(id) && completedScripts.contains(id)`（`PlayerTriggerState.hasPlayed`）。
- **没有次数、没有时间、没有实例、没有退出原因**：三个集合都是 `Set<String>`，`dirty` 标记只服务于“要不要写盘”。
- **读面基本是空的**：`hasAnyTriggered` / `getTriggeredIds` / `resetScript` / `resetAll`（`TriggerStateStore` 同名方法）全仓**无调用者**（注释自称“保留给旧用法”）；`resetAll` 也没有任何命令或事件接线。即：布尔层的读只有 `isTriggered`（去重）与三个内置前置条件（`hasPlayed` / `isScriptStarted` / `isScriptCompleted`）这几条。
- **持久化生命周期**：`loadForPlayer`（玩家加入）、`unloadForPlayer`（玩家退出，先 `saveIfChanged`）、`saveAll`（服务器停止 / 世界保存），写入走临时文件 + 原子替换（`savePlayer`），序列化见 `serialize` / `deserialize`。

### 2.3 生命周期信号从哪来：started / completed 的来路与丢掉的字段

- **开始**：客户端真正开播后发 `C2SPlaybackStartedPacket`（`started=true` 才记账，排队 / 被拒 / 编辑器预览带 `started=false`，类注释写明理由）→ `TriggerEngine.onPlaybackStarted(player, scriptId, instanceId)` → `markScriptStarted` **并且** `ScriptEventManager.startPlayback`（同一信号写两处）。
- **结束**：客户端 `ClientScriptNotifier.notifyScriptFinished(scriptId, instanceId, reason)` 发 `C2SScriptFinishedPacket`（带实例 id 与 `CompletionReason`）→ `TriggerEngine.onScriptFinished(player, scriptId, instanceId, reason)`：
  1. `TriggerStateStore.INSTANCE.markScriptCompleted(player.getUUID(), scriptId)` —— **`instanceId` 与 `reason` 在手上，但一个都没传进去**；
  2. 给该玩家发一次全量状态同步包；
  3. 转交 `ScriptEventManager.onScriptFinished(player, scriptId, instanceId, reason)`（实例账本才用得上这两个字段）。
- **退出原因有两套枚举**：客户端侧 `ExitReason`（`FORCE_QUIT` / `SYSTEM_STOP` / `INTERRUPTED` / `USER_SKIP` / `NATURAL_END`，由 `CameraManager.requestExit` 映射成）与随包上报的 `CompletionReason`（`FORCE_QUIT` / `STOPPED` / `INTERRUPTED` / `SKIPPED` / `FINISHED`）。服务端**只在一个地方读它**：`ScriptEventManager.onPlayerFinished` 判 `reason == SKIPPED` 记跳过票；其余全部坍缩成 `completedScripts.add(scriptId)`。

### 2.4 前置条件口径：三个内置类型都是布尔查询

`PrerequisiteRegistry` 是类型 → 求值器的注册表（内置三个 + 其它模组可注册 `modid:name`；未知类型记一条 warn 并按“不满足”处理）。`BuiltinPrerequisites.registerAll` 注册的三个类型，**全部只是 `TriggerStateStore` 布尔读法的包装**：

| 类型 | 实现 | 口径 |
|---|---|---|
| `script_played` | `hasPlayed` | 开始播放 **且** 结束播放 |
| `script_started` | `isScriptStarted` | 只看开始 |
| `script_completed` | `isScriptCompleted` | 只看结束 |

**“播完”的现状口径是“任何退出原因都算”**——`PlayerTriggerState.hasPlayed` 的注释、`BuiltinPrerequisites` 的注释、`TriggerRequirement.scriptPlayed` 的注释三处一致；文档侧同口径（`docs/TRIGGER_TYPES.md` 通用字段 `requires` 行、`docs/SCRIPT_FORMAT.md` 同）：**跳过 / 打断 / 自然播完在存储里不可区分**。旧写法 `requires: ["script_a"]` 在解析期被转成 `script_played`（`TriggerRequirement.scriptPlayed`）。

判定时机：`TriggerEngine.prerequisitesMet` 对 `requires` 逐条求值，**任一 false 即 false**（AND），且排在 `shouldSkip` 之前——注释写明“依赖未解锁时连去重逻辑都不需要碰”。

### 2.5 状态机：on_enter / exit_buffer / repeatable / delay

- **`enterStates`**：`TriggerEngine` 内的 `Map<UUID, Map<String, Boolean>>`，键是 `脚本 id + ":" + 触发器 id`，值是“上次是否在区域内”的**裸布尔**。`checkEnterState` 由调用方每轮必调（否则“离开复位”分支不可达，`feedback-0.3.5/05` 的修复）；有 `exitConditions`（外扩区域）时走“保持已进入、完全离开才复位”。**纯内存、不持久化、不在 `saveAll` 范围内**——重启即“没进去过”。
- **`shouldSkip`**：两段门控——① 该玩家是否正在播放**本触发器指向的脚本**（`ScriptEventManager.isPlayerPlayingScript`，同脚本口径，跨脚本不阻塞）；② 非 `repeatable` 且 `isTriggered` 已为真。
- **`fireTrigger`**：先 `markTriggered`，非首次且非 `repeatable` 直接返回；否则发一次状态同步包，再按 `delayMs` 决定立即执行还是入 `delayedFires`。
- **延迟触发的去重是对象同一性**：`processDelayedFires` 里用 `df.reg() == reg` 比引用（注释解释：防止 repeatable + delay 在区域内反复入队导致连播）。这不是记录，是队列内的临时判重。
- **触发器身份是拼出来的**：`ScriptManager.registerAllTriggers` 构造注册时，触发器 id = `td.getType() + "_" + meta.getId()`。而 `TriggerDefinition` **没有 id 字段**（只有 type / conditions / repeatable / delay / onEnter / exitBuffer / requires），`meta.triggers` 是 JSON **数组**（`ScriptParser` 逐个解析）——**同一个脚本里写两条同类型触发器，两条共用同一把触发器 id**。

### 2.6 实例账本：`ScriptEventManager`（半个账本）

`ScriptEventManager.scriptPlaybacks` 是 `脚本 id → 实例 id → ScriptPlayback` 两级键（§3.7 落地）。每条 `ScriptPlayback` 带：观看者集合、跳过投票者集合、本场生效的跳过投票比例（脚本 meta 覆盖 ?? 全局配置，创建时解析一次）、已执行关键帧集合（`(clipIndex << 16) | kfIndex` 的整数集）、开始 tick、事件 clip 列表、暂停态与累计暂停 tick。

- **它是内存账本，不持久化**，生命周期 = 实例生命周期：观看者集合空即摘除该实例，脚本下无实例即移除脚本条目。
- **同一个信号写两处**：开始 / 结束都同时落 `TriggerStateStore`（持久布尔）与这里（实例账本）——两套真相、两套键（玩家 × 脚本 vs 脚本 × 实例）、两套生命周期。
- **实例 id 只在命令路径有值**：`S2CPlayScriptPacket` 的注释说明“最简做法：把 `refId` 复用为 `instanceId`”；而触发器路径 `StartPlaybackAction.sendTo` 走的是**两参 `send(player, json)`**（不带 refId）→ 实例 id 为空串 → 该脚本的全部观看者共用退化键 `(scriptId, "")`（包注释明写）。
- **跳过投票**：`onPlayerFinished` 里 `SKIPPED` → 加入 `skipVoters`；`needed = ceil((viewers + skipVoters) × ratio / 100)`，达标则给剩余观看者发 `S2CStopScriptPacket`；未达标则 `broadcastSkipVote` 给观看者发票数更新包。
- **事件时间线**：`onServerTick` 按 `(当前 tick − 开始 tick − 累计暂停 tick) / 20` 求 elapsed，逐实例扫事件 clip 的关键帧，未执行过的执行并记进 `triggeredKeyframes`（按实例的“已执行”集合）。
- **已知缺陷（代码读出，未在游戏内复现）**：`ScriptPlayback.server` 字段声明后**从未赋值**（构造器不接收、创建处不设置），而 `broadcastSkipVote` 读 `pb.server.getPlayerList()` —— 只要有玩家投出跳过票且**票数未达标**，这条路径就会空指针。它正好落在本文要收编的账本里，记录备查。

### 2.7 事件侧 tracker：八个“最近一次”单槽

`Evaluators` 内嵌 8 个 tracker（`KillTracker` / `AdvancementTracker` / `DimensionTracker` / `InteractTracker` / `CraftTracker` / `PickupDropTracker` / `UseItemTracker` / `InventoryTracker`），形态统一为 `Map<UUID, 值>`，语义统一为**“最近一次点事件”**：

- 求值器读的就是这个“最近一次”：`evaluateAdvancement` 读 `getLastAdvancement`；`evaluateInteract` / `evaluateBlockInteract` / `evaluateItemOnInteract` 读 `getLastInteraction`；`evaluateItemCraft` / `evaluateItemUse` / `evaluateItemConsume` / `evaluateItemRelease` / `evaluateItemUseInterrupt` / `evaluateItemInstantUse` / `evaluateItemDrop` 各读一个单值 map。
- **两个例外已经是“事实集合”**：`KillTracker.allKills`（击杀记录集合，`entity_kill` 的 `and` 模式读它）与 `PickupDropTracker.pickedUpSet`（注释：“玩家本会话捡起过的全部物品 id 集合”）。`KillTracker.KillRecord` 甚至带了击杀时刻的维度 / 群系 / 坐标——**这就是事实记录的雏形**（`matchesScene` 按击杀时刻的场景判定，而不是玩家当前位置）。
- **单槽会过期**：`DimensionTracker.getLastFrom` 自己的注释承认“dimension_change 触发时同步写入，**轮询型共用时可能过期**”。
- **生命周期**：纯内存，`ServerEventHandler.onPlayerQuit` 逐个 `clear(uuid)`；重启 / 重进即清零。
- **同一 tick 多事件会互相覆盖**：tracker 是 `put` 语义，同一 tick 内两次同类事件只留最后一次（例如同 tick 击杀两个实体、同 tick 拿两个进度）。

### 2.8 客户端镜像：只写不读

`S2CTriggerStateSyncPacket` 携带 `triggeredScripts` + `completedScripts`（**不含 `startedScripts`**），在三处发送：`fireTrigger`（触发成功）、`onScriptFinished`（脚本结束）、`ServerEventHandler.onPlayerJoin`（加入）。客户端 `ClientTriggerStateCache.handleSync` 整包替换本地缓存。

但它的读方法 `isTriggered` / `isScriptCompleted` / `getTriggeredTriggers` / `getCompletedScripts` **全仓无调用者**（`docs/modules/network.md` 只记了链路本身“客户端整包替换本地缓存”）。即：这是一条**已经接线、但没人读**的同步链路——UI 面（“为什么触发 / 触发过什么”）目前是空的。

### 2.9 现状总表：五份记录、四种生命周期

| # | 状态 | 形态 | 存储位置 | 生命周期 | 谁能读 |
|---|---|---|---|---|---|
| 1 | 触发器“触发过” | 脚本 → 触发器 id 集合 | `TriggerStateStore`（SNBT） | 跨存档 | `shouldSkip`、客户端镜像 |
| 2 | 脚本“开始 / 结束过” | 两个 id 集合（可推导 `hasPlayed`） | 同上 | 跨存档 | 三个内置前置条件 |
| 3 | 区域进入状态 | `脚本:触发器` → 布尔 | `TriggerEngine.enterStates` | 进程内（重启即失） | `checkEnterState` |
| 4 | 事件“最近一次” | 8 组 `UUID → 值` 单槽 | `Evaluators` 的 tracker | 会话内（退出即清） | 各事件类求值器 |
| 5 | 实例账本 | 脚本 × 实例 → 观看者 / 票 / 已执行关键帧 / 暂停态 | `ScriptEventManager.scriptPlaybacks` | 实例生命周期（内存） | 跳过投票、事件时间线、`shouldSkip` 门控① |

五份记录、四种生命周期（跨存档 / 进程内 / 会话内 / 实例内），键各不相同，语义互相重叠——**这是本文要收敛的对象**。

---

## 3. 问题分析

### P1 历史丢失：只知道“发生过”，不知道“何时 / 几次 / 哪个实例 / 什么结果”

`hasPlayed` 只能回答“是否”，无法回答“玩过几遍”“上一次是什么时候”“上一次是跳过还是播完”。任何“至少看过 N 次”“最近 10 秒内完成过”的写法都无法表达——因为没有地方存这些。

### P2 口径坍缩：五种退出原因 → 一个布尔

`TriggerEngine.onScriptFinished` 手上就有 `reason`（`CompletionReason`，五值）与 `instanceId`，但 `markScriptCompleted(player, scriptId)` 只收一个脚本 id（§2.3）。结果：**跳过 / 打断 / 自然播完在存储里完全等价**，文档只能写成“任何退出原因都算”（§2.4），`trigger-conditions.md` §5 的待定项（“播完”是否含跳过 / 打断）在当前结构下**无法表达，也就无法选择**。

### P3 实例信息被丢弃：账本按实例，状态按脚本

实例 id 已经贯通网络包与账本（§3.7 已落地），却在落状态那一步被扔掉；触发器路径的实例 id 恒为空串（§2.6）。于是同一个“结束”信号，账本按 `(脚本, 实例)` 记、状态按 `(玩家, 脚本)` 记，并行播放（同脚本多实例被放开时）会出现两套互相矛盾的“事实”。

### P4 触发器身份不足以承载事实

触发器 id = `类型_脚本 id`，`TriggerDefinition` 无 id 字段（§2.5）。后果（可由代码直接推出）：

- **非 repeatable 下，同脚本同类型的两条触发器只有第一条会触发**——两条注册共用同一把去重键，第二条在 `shouldSkip` 阶段就被第一条的标记挡住；
- **`enterStates` 的键同样共用**——两条同类型 `location` 触发器的“进入 / 离开”状态互相踩；
- 因此“这条触发器被触发过”这个事实**挂不到具体的那一条触发器声明上**。要做“按触发器的事实”，先得给触发器声明一个独立身份。

### P5 时序竞争：点事件不可组合、单槽会过期

- tracker 的 `put` 语义让**同 tick 多事件互相覆盖**（§2.7）；
- `DimensionTracker.getLastFrom` 的注释自认“轮询型共用时可能过期”——把“最近一次”当成“当前状态”用；
- 事件类与轮询类混在同一个组合器里时（`trigger-conditions.md` §8），底层没有时间戳可供区分“刚刚”与“很久以前”，所以现在只能靠**在类型层面禁用**（事件类不可入组合）来兜底。

### P6 不可追溯 / 不可解释

没有任何地方记录“这次触发是因为哪几条条件成立、哪条事实当时为真”。客户端同步包里只有两个集合（§2.8），UI 无法展示“为什么触发”；日志只有一行 `Firing trigger ...`。

### P7 两套（实为三套）真相并存

同一个信号写两处（§2.6），`enterStates` 是第三处（§2.9）。三者的键、生命周期、消费方都不同，改动时容易只改一处（`enterStates` 不持久化这一点，就是 `feedback-0.3.5/05` 那次修复的成因土壤）。

### P8 生命周期没有定义

“什么状态跟着存档走、什么状态跟着会话走、什么状态跟着实例走”目前是**实现副产物**（用了持久存储就持久、用了 `Map` 就进程内、退出时清了就会话内），而不是设计决定。`enterStates` 不持久化导致重启后 `on_enter` 可以再触发一次——这类差异没有被写下来过。

### P9 读面空心

`hasAnyTriggered` / `getTriggeredIds` / `resetScript` / `resetAll` 无调用者（§2.2），客户端缓存四个读方法无调用者（§2.8）——布尔层既不够用，也已经有一部分没人用了。**迁移时这些是删除项，不是兼容项。**

### P10 无法支撑后续

- **等待点**（[wait-point-track.md](./wait-point-track.md)）：等的是“某个事件出现”，不是布尔翻转；`any` / `all_of` 事件树需要“区分是哪条事实”；
- **跳过 / 打断口径**（`trigger-conditions.md` §5）：需要结果可区分；
- **并行播放**：需要按实例口径；
- **编辑器 / UI**：“触发过什么、为什么”需要可读的记录，而不是一个集合。

---

## 4. 目标形态（方向）

### 4.1 事实 = 一条带维度的记录

**核心转变**：状态不再存成布尔，而是存成**事实记录**——每条记录回答“谁、在什么时间、关于哪个对象、发生了什么、结果如何”。维度（不是最终字段名，字段名执行时定）：

| 维度 | 说明 | 现状对应物 |
|---|---|---|
| 主体 | 事实归属谁（玩家） | `PlayerTriggerState` 的 `Map<UUID, …>` 外键 |
| 类型 | 发生了什么：播放开始 / 播放结束 / 跳过 / 打断 / 进入区域 / 离开区域 / 触发器命中 / 等待点回报 / 投票 … | `triggeredScripts` 的集合语义 + 三个内置前置条件 + `CompletionReason` |
| 对象 | 关于谁：脚本 id、**触发器身份**（需新增）、区域、目标 | `triggeredScripts` 的第二级键 |
| 时间 | 服务端 tick | 现在**完全没有** |
| 实例 | 播放实例 id（空串 = 退化实例） | 账本里有、状态层没有 |
| 结果 / 参数 | 退出原因、计数、附加数据（如击杀记录的维度 / 群系 / 坐标） | `CompletionReason`、`KillRecord` |

`KillTracker.KillRecord` 与 `pickedUpSet` 已经是这个形态的雏形（§2.7）——方向是**把特例一般化**，而不是发明新东西。

### 4.2 判定 = 对事实集做查询

“是不是触发过”只是查询的一种（**存在性**）。方向上的查询原语（清单执行时定）：

- **存在性**：发生过某类型的事实吗（= 今天所有布尔标记）；
- **计数**：发生过几次（今天完全无法表达）；
- **最近一次**：最后一次是什么时候、结果是什么（今天只有“单槽最近一次”，且会过期、会被覆盖）；
- **时间窗**：最近 N tick / N 秒内有没有（今天无法表达）；
- **分组 / 过滤**：按实例、按触发器身份、按结果筛选（今天无法表达）。

布尔标记不再是存储形态，而是查询的一个退化结果；`enterStates` 那种“当前是否在区域内”是**瞬时状态**，不是历史事实，二者要在模型里分开（见 §5.2）。

### 4.3 与总原则对齐

- 与 README 的**效果原则**一致：效果写成系统可追踪的数据——**状态同理**：状态写成可查询的事实数据，而不是散落在五处的布尔与单槽。
- 与**版本原则**一致：直接替换布尔标记，不写兼容层、不双写、不留旧读法（§5.4）。
- 与**先底层后应用**一致：先定事实与查询（底层），再让前置条件 / 触发器 / 等待点 / 编辑器来消费（应用层）。

### 4.4 明确不做（边界）

- 不做通用事件总线 / 跨模组事件 API（`wait-point-track.md` §7 的事，不在本文）；
- 不做条件求值算法与组合器字段（归 `trigger-conditions.md`）；
- 不做客户端判定（判定仍在服务端）；
- 不做“全量审计日志落盘”（需要时是事实层的一个消费方，不是事实层本身）。

---

## 5. 设计方向

### 5.1 事实的写入点（收敛到少数“记账口”）

事实只应在**信号产生处**写入，且写入点数量要收敛。现状对应关系：

| 记账口 | 现状位置 | 要写的事实（方向） |
|---|---|---|
| 播放开始 | `TriggerEngine.onPlaybackStarted` | 开始（脚本 + 实例） |
| 播放结束 | `TriggerEngine.onScriptFinished` | 结束（脚本 + 实例 + **退出原因**） |
| 触发器命中 | `TriggerEngine.fireTrigger` | 命中（脚本 + **触发器身份** + 时间） |
| 区域进入 / 离开 | `TriggerEngine.checkEnterState` | 进入 / 离开（触发器身份 + 时间），或明确不记为事实（见待定） |
| 事件侧信号 | `ServerEventHandler.onXxx` → tracker.record | 对应事件事实（替换单槽） |
| 跳过投票 | `ScriptEventManager.onPlayerFinished` | 投票（玩家 + 实例 + 时间） |
| 等待点回报 | 未实现（`wait-point-track.md`） | 回报事实（预留） |

原则：**信号的“当前态”与“历史事实”分开**——观看者集合（谁此刻在看）是当前态，不是事实；跳过票、已执行关键帧、播放起止是历史事实。混淆这两类是 P7 的根因。

### 5.2 存储与生命周期（把“四种生命周期”变成明确的两类）

- **每条事实自己声明持久性**：`会话内`（进程内、退出即清）或 `跨存档`（进 SNBT）。不是全局一刀切，也不再是“实现副产物”。
- 持久化沿用现有形态（按玩家的文件、版本号、dirty 标记、原子替换），版本号随格式升级；按**版本原则**，旧文件不写兼容读取层（旧数据怎么处理是待定项）。
- 瞬时状态（`enterStates` 这类）**不混进事实层**：它是“当前是否在区域内”，生命周期跟着会话 / 实例走，需要时单独定生命周期并写明。
- 内存上限：事实条数需要**保留策略**（时间窗淘汰 / 条数上限 / 类型分级），按类型分别定；长跑服务器不能无限增长。

### 5.3 查询接口与消费面

| 消费方 | 现状读法 | 方向 |
|---|---|---|
| 前置条件（`script_played` / `script_started` / `script_completed`） | 三个布尔读法 | 事实查询：存在性 / 计数 / 最近一次 + 结果（口径可配：含不含跳过） |
| 触发器去重（`shouldSkip` 第二段） | `isTriggered` 布尔 | “该触发器身份有命中事实吗” |
| `on_enter` 状态机 | `enterStates` 布尔 | 瞬时状态保留或改由进入 / 离开事实推导（待定） |
| 跳过投票 | 账本里的投票者集合 | 由投票事实派生 |
| 事件时间线 | 账本里的已执行关键帧集合 | 按实例的已执行事实（或明确保留为当前态） |
| 等待点 | 无 | 等“某类事实出现”（时间窗 / 存在性） |
| 命令 / 编辑器 / UI | 无（客户端缓存没人读） | 查询 + 展示“触发过什么 / 为什么触发” |

“为什么触发”的做法（方向）：命中时把**参与求值的事实与条件结果**一并记下（哪条事实为真、哪条条件此刻成立），供 UI / 日志消费——这是 P6 的解法，具体形态执行时定。

### 5.4 与现有实现的迁移（版本原则：直接替换，不留兼容层）

- **布尔标记层**：`PlayerTriggerState` 的三份集合、`hasPlayed` / `markTriggered` / `isTriggered` 由事实存储与查询替代；`hasAnyTriggered` / `getTriggeredIds` / `resetScript` / `resetAll` 等无调用者的读法**直接删除**（P9）。
- **实例账本**：历史性数据（跳过票、已执行关键帧、播放起止）收编进事实层；**当前态数据（观看者集合、暂停态、累计暂停 tick）留在账本**——账本不消失，只是不再兼任第二套历史。收编时顺带修掉 §2.6 的空指针缺陷。
- **事件 tracker**：8 个单槽改为“写事实 + 按查询取用”；`KillRecord` / `pickedUpSet` 这两个已有的集合形态作为模板一般化。
- **触发器身份**：`TriggerDefinition` 需要一个独立身份（脚本内声明 id，或按声明序生成），否则 P4 无法解；解析 / 校验 / schema 都要跟（旧脚本零迁移的处理方式见待定项）。
- **网络包与客户端缓存**：`S2CTriggerStateSyncPacket` 的载荷随事实层调整（同步什么、增量还是全量执行时定）；客户端缓存的空读面要么接上 UI，要么删。
- **不保留双写**：迁移完成后，同一信号只写一处（事实层），不再“状态层 + 账本”各写一遍。

### 5.5 性能

- **查询成本**：事实层在**每 tick 的轮询路径**上被查（每个轮询桶 × 每个玩家 × 每条注册），查询必须有索引 / 短路，**不能是全表扫描**；同一轮里多次查询要能复用同一份快照。
- **写入成本**：轮询命中、事件回调都在服务端 tick 内；写入要避免可避免的分配与复制（每 tick 零分配是既有基调），事实条数增长曲线要可控。
- **持久化成本**：写盘频率（现有 dirty + 原子替换）与文件体积要能随事实条数增长收敛（保留策略是前提）。
- **既有优化不能退化**：`shouldSkip` 的“正在播放”门控、`delayedFires` 的引用判重、`pollBuckets` 的类型级分桶都在热路径上。

### 5.6 确定性

- **时间基准统一到服务端 tick**（`TriggerEngine` 与 `ScriptEventManager` 都在服务端 tick 上；客户端只上报信号）。
- **暂停 / 加速**：账本已经用“累计暂停 tick”把 elapsed 从墙钟 tick 里剥出来（`handlePause` / `totalPausedTicks`）——事实层的“事件时间”用**原始 tick 还是有效 tick**必须明确并统一（待定）。
- **预览实例**：编辑器预览不上报（客户端预览实例跳过上报），因此**预览不产生事实**；这条要写进事实层的写入规则，而不是靠调用方自觉。
- **多人服**：事实主体是玩家；广播播放（`StartPlaybackAction` 的 `target=all` / `all_except_trigger`）下，同一次播放请求对应多个主体——是“每个玩家各记一条”还是“一条事实多主体”，执行时定（见可能的问题）。

---

## 6. 落地步骤 / 可能的问题 / 待定项

### 6.1 分期步骤（每步一个可交付物）

| # | 步骤 | 交付物（可验证） |
|---|---|---|
| 1 | **事实模型 + 会话内存储 + 查询原语**（最小版本，见 §6.2） | 事实记录与查询可用；三个内置前置条件改由事实查询实现，行为与今天等价 |
| 2 | **触发器身份 + 命中事实** | 触发器声明有独立身份；`shouldSkip` / `fireTrigger` 的去重改为事实查询；同脚本同类型的两条触发器可各自独立触发 |
| 3 | **播放生命周期事实** | 结束事实带实例 id 与退出原因；跳过 / 打断 / 自然播完可区分；“播完”口径由查询参数表达 |
| 4 | **实例账本收编** | 历史性数据进事实层，账本只留当前态；跳过投票与事件时间线行为不变；§2.6 的空指针缺陷修掉 |
| 5 | **事件 tracker 收编** | 8 个单槽改为事实写入 + 查询；同 tick 多事件不再互相覆盖；`KillRecord` 场景判定行为不变 |
| 6 | **持久化与生命周期定稿** | 每条事实的持久性写明；SNBT 格式升级；保留 / 淘汰策略落地；重启后持久事实仍在 |
| 7 | **消费面**（依赖 [wait-point-track.md](./wait-point-track.md) 与编辑器阶段） | 等待点等“事实出现”；命令 / UI 可查“触发过什么 / 为什么触发” |

步骤 1–3 是**主链**（布尔 → 事实的替换完成即可停止并验收）；4–5 是收编（把另外两套真相并进来）；6 是持久化定稿；7 是应用层，跟着等待点与编辑器排期。

### 6.2 最小版本

**范围**：只做事实的记录 + 存在性查询 + 会话内存储；把 `script_played` / `script_started` / `script_completed` 三个内置前置条件切到事实查询上，**行为与今天逐点等价**（含“任何退出原因都算”的口径），其余一律不动。

**验收**：旧脚本零回归（现有前置条件语义不变）；事实查询可用且不引入每 tick 全表扫描；查询结果可观测（命令或测试脚本）——存在性为必需，计数 / 最近一次 / 时间窗按实现进度提供。

**明确不做**：触发器身份（步骤 2）、持久化格式（步骤 6）、账本收编（步骤 4）都不进最小版本。

### 6.3 可能的问题

- **事实条数增长**：高频事件（`location` 类轮询、`item_*`）在长跑服务器上会累积大量事实——保留策略不落地就会变成内存与写盘问题。
- **查询成本落在热路径**：每 tick × 每玩家 × 每条注册的查询，如果实现成线性扫描会直接吃掉轮询预算；索引形态是这一步的技术核心。
- **触发器身份会动脚本格式**：`meta.triggers` 要能表达身份（新字段或按声明序），涉及解析、校验、schema、编辑器四处；旧脚本的处理方式必须先定。
- **账本收编的边界容易划错**：观看者集合、暂停态、累计暂停 tick 是**当前态**，塞进事实层会立刻产生“怎么删除过期事实”的伪问题。
- **持久化升级与已有存档**：版本原则要求不留兼容层，但玩家世界里的旧 SNBT 文件已经存在——升级动作需要一次明确取舍（丢弃 / 一次性转换 / 版本号识别后忽略）。
- **暂停语义**：事实时间用原始 tick 还是有效 tick，会直接影响“最近 N 秒”的查询结果与跨实例比较。
- **多人广播**：一次播放请求对应多个玩家主体时的事实归属（每人一条 vs 一条多主体）会影响查询与 UI 展示。
- **客户端同步形态**：全量包随事实增长会变大；增量同步引入状态同步一致性（丢包 / 重连）问题——两条路的取舍执行时定。
- **删除旧读法的连带影响**：`hasAnyTriggered` / `resetScript` / `resetAll` 与客户端缓存读方法虽然当前无调用者，删除前仍需再确认一次（含编辑器侧与外部模组可能的使用）。

### 6.4 待定项

- 事实类型清单与命名（第一批做哪些；`entered_region` / `triggered` / `voted` 之类是否都进）。
- 事实的字段形态与持久化表示（内部记录形态、SNBT 表示、版本号策略）。
- 查询原语的清单与形态（存在性 / 计数 / 最近一次 / 时间窗 / 分组的接口边界，以及“查询快照”的粒度）。
- 时间基准（原始 tick vs 有效 tick；暂停期间是否继续记事实）。
- 触发器身份的来源与形态（脚本内声明 id / 按声明序生成；旧脚本零迁移的做法）。
- `enterStates` 的去留：保留为瞬时状态，还是改由进入 / 离开事实推导。
- 事实的保留 / 淘汰策略与内存上限（按类型分级？）。
- 旧 SNBT 存档的处理（丢弃 / 转换 / 识别版本后忽略）。
- “为什么触发”的记录粒度（记条件结果、记事实引用，还是两者都记）。
- `S2CTriggerStateSyncPacket` 与客户端缓存的最终形态（全量 / 增量；缓存接 UI 还是删除）。
- 与 `trigger-conditions.md` §5 待定项的归属：本计划提供“口径可表达”的底层，**具体口径（播完含不含跳过 / 打断）在落地时定**。
- 事实层是否对外暴露（其它模组注册自定义事实类型，对应 `PrerequisiteRegistry` 的既有可扩展性）。

---

## 7. 相关文档与口径关系

| 文档 | 关系 |
|---|---|
| [trigger-conditions.md](./trigger-conditions.md) | **本计划是它的底层**：组合器里的前置条件是锁存语义（“发生过即真”），这个“锁存”就是事实查询的存在性形式；其 §5 待定第 2 条（前置条件 ↔ 脚本状态的口径、状态归属、并行播放按玩家 × 脚本还是按实例）由本计划承接——底层先能表达，口径才能选 |
| [parallel-playback.md](./parallel-playback.md) §3.5 / §3.7 | 实例账本与“同脚本同玩家单实例”是本计划的上游：账本已经按 `(脚本, 实例)` 记账，状态层还是 `(玩家, 脚本)`；本计划步骤 3–4 把两者对齐（§3.7 记的“触发状态机按实例维护 → 未采用”随之重审） |
| [wait-point-track.md](./wait-point-track.md) | 主要消费方：等待点等的是“某个事实出现”，不是布尔翻转；其 §4.3 的事件树（`any` / `all_of` / `branch`）需要“区分是哪条事实” |
| [script-loop.md](./script-loop.md) | “播完”的定义（宏观末端、永不结束片段、有限圈数自然结束）决定 `finished` 事实的判定时机；无限循环下“结束”事实的产生条件要与之对齐 |
| [scene-transition.md](./scene-transition.md) | 跳过 / 打断是该文档的开放交互点（“跳过途中转场 = 立即硬切还是加速完成”）；本计划提供“跳过 / 打断”作为事实结果的**可区分性**，不决定转场表现 |
| [mod-architecture-diagram.md](./mod-architecture-diagram.md) | “退出原因（自然结束 / 跳过 / 打断 / 强退 / 系统停止）”与 `CompletionReason` / `ExitReason` 对应；本计划把这条链路的末端从布尔改成结果值 |
| [script-model.md](./script-model.md) | “一份脚本定义 × N 份运行时状态”是本计划的前提：实例是状态的维度之一 |
| [implementation-progress.md](./implementation-progress.md) | P1（本文档）/ P1-impl（按本计划实现） |
| [pending-discussions.md](./pending-discussions.md) | §〇-1 用户裁决原文 |
| `docs/TRIGGER_TYPES.md` / `docs/SCRIPT_FORMAT.md` | `requires` 与三个内置前置条件的文档口径（“任何退出原因都算”）在口径可选后需回改 |

**口径冲突点（记录在案，未改他人文档）**：

- `trigger-conditions.md` 说组合器里的事件类条件“带『最近发生过』语义，放进组合会变成『很久以前发生过也算』的误判”，因此**在类型层面禁用**；本计划的 P5 指出这是底层没有时间戳的**替代方案**——事实层落地后，这条禁用可以改为**按时间窗限定**（届时该文档需回改）。
- `parallel-playback.md` §3.7 记“触发状态机按实例维护：**未采用**”，其依据是“状态机键保持（玩家 + 脚本 + 触发器），与同脚本单实例一致”；本计划步骤 2–3 引入触发器身份与实例维度后，这条结论需要重审（同脚本单实例的口径本身不变）。

---

## 代码证据索引（2026-10-08）

> 依据：本仓库工作区源码（`common/src/main/java/...`）。行号取自 2026-10-08 工作区，会漂移，引用以**文件 + 方法名**为主。

### ① 核实为真的断言

| 断言 | 证据 |
|---|---|
| 两条触发入口（事件驱动 / 轮询），事件类型 id 为字符串 | `TriggerEngine.onGameEvent`（:83，查 `eventIndex`）、`TriggerEngine.onServerTick`（:102，`tickCounter % interval` + `pollBuckets`）；`ListenStrategy` 仅 `EVENT_DRIVEN` / `POLLING` 两值 |
| 两条入口汇合到同一套判定与记账 | `TriggerEngine.onGameEvent` 与 `onServerTick` 均调 `prerequisitesMet` → `shouldSkip` → `evaluateSafely` →（on_enter）`checkEnterState` → `fireTrigger` |
| 状态按玩家、SNBT 持久化到世界存档 | `TriggerStateStore.initialize`（:36，`server.getWorldPath(LevelResource.ROOT).resolve(STORE_PATH)`）、`STORE_PATH` / `VERSION`（:28-29）、`loadForPlayer`（:113）、`unloadForPlayer`（:131）、`saveIfChanged`（:136）、`saveAll`（:143）、`savePlayer`（:153，临时文件 + `ATOMIC_MOVE`）、`serialize`（:171）/ `deserialize`（:200） |
| 三份集合 = 全部状态 | `PlayerTriggerState` 字段（:10-13）；`serialize` 写 `triggered_scripts` / `started_scripts` / `completed_scripts`（`TriggerStateStore.serialize`） |
| `markTriggered` 返回值即去重机制 | `PlayerTriggerState.markTriggered`（:51）返回 `isNew`；`TriggerEngine.fireTrigger`（:219）`if (!isNew && !reg.isRepeatable()) return;`；`TriggerEngine.shouldSkip`（:208）非 repeatable 时查 `isTriggered` |
| `hasPlayed` = started ∧ completed（推导布尔，无次数 / 时间 / 实例 / 原因） | `PlayerTriggerState.hasPlayed`（:37）；`TriggerStateStore.hasPlayed`（:64） |
| 无调用者的读法（布尔层读面空心） | `TriggerStateStore.hasAnyTriggered`（:70）/ `getTriggeredIds`（:75）/ `resetScript`（:101）/ `resetAll`（:106）：全仓 grep 无调用者；`ClientTriggerStateCache.isTriggered`（:23）/ `isScriptCompleted`（:28）/ `getTriggeredTriggers`（:32）/ `getCompletedScripts`（:36）同样无调用者 |
| 开始信号链路 | `CameraManager.reportPlaybackStarted`（:627）发 `C2SPlaybackStartedPacket`（`started=true`）→ `C2SPlaybackStartedPacket.handle`（:62-66，`started=false` 时明确“账本不变”）→ `TriggerEngine.onPlaybackStarted`（:172）→ `markScriptStarted` + `ScriptEventManager.startPlayback` |
| 结束信号链路 + `reason` / `instanceId` 被状态层丢弃 | `ClientScriptNotifier.notifyScriptFinished`（:12）→ `C2SScriptFinishedPacket.handle`（:48-51）→ `TriggerEngine.onScriptFinished`（:161，只调 `markScriptCompleted(player.getUUID(), scriptId)`，随后才把 `instanceId` / `reason` 转交账本） |
| 退出原因两套枚举 | `ExitReason`（`FORCE_QUIT` / `SYSTEM_STOP` / `INTERRUPTED` / `USER_SKIP` / `NATURAL_END`）与 `CompletionReason`（`FORCE_QUIT` / `STOPPED` / `INTERRUPTED` / `SKIPPED` / `FINISHED`）；映射在 `CameraManager.requestExit(instance, reason)`（:227） |
| 服务端只在一处读 `reason`（判 SKIPPED） | `ScriptEventManager.onPlayerFinished`（:83，`reason == SKIPPED` 加票 / 判达标） |
| 三个内置前置条件都是布尔包装 | `BuiltinPrerequisites.registerAll`（`script_played` → `TriggerStateStore.hasPlayed`；`script_started` → `isScriptStarted`；`script_completed` → `isScriptCompleted`）；`PrerequisiteRegistry.register` / `evaluate`（未知类型 warn 并按不满足处理） |
| `requires` = AND，且在 `shouldSkip` 之前 | `TriggerEngine.prerequisitesMet`（:187） |
| 旧写法 `requires: ["script_a"]` → `script_played` | `TriggerRequirement.scriptPlayed` |
| 文档口径“跳过 / 打断 / 自然播完都算” | `docs/TRIGGER_TYPES.md` 通用字段 `requires` 行、§27；`docs/SCRIPT_FORMAT.md`（`requires` 行）；代码注释三处一致（`PlayerTriggerState.hasPlayed`、`BuiltinPrerequisites`、`TriggerRequirement`） |
| `enterStates` 是进程内裸布尔、键为 `脚本:触发器` | `TriggerEngine.enterStates`（:30）、`checkEnterState`（:265）；不在 `saveAll` / `serialize` 范围内 |
| 延迟触发去重是引用比较，不是记录 | `TriggerEngine.fireTrigger`（:219，`df.reg() == reg` 入队判重）、`processDelayedFires`（:132） |
| 触发器 id 是 `类型_脚本 id` 拼出来的，`TriggerDefinition` 无 id 字段 | `ScriptManager.registerAllTriggers`（:90，`td.getType() + "_" + meta.getId()`，:128 构造 `TriggerRegistration`）；`TriggerDefinition` 字段（type / conditions / repeatable / delay / onEnter / exitBuffer / requires）；`meta.triggers` 为 JSON 数组（`ScriptParser.parseTriggerDefinition`，:618） |
| 校验器不检测同类型触发器重复 | `ScriptValidator.validateTriggerRequires`（:598）与 `validateTriggerConditions`（:657）只校验结构与引用（类型已知 / 条件结构 / `requires` 指向 / 自引用） |
| 实例账本两级键、内存、实例生命周期 | `ScriptEventManager.scriptPlaybacks`（:39）、`addViewer`（:43）、`playback`（:70）、`removePlayback`（:76）、`onServerTick`（:178，viewers 空即移除） |
| 跳过投票按实例记账 | `ScriptEventManager.onPlayerFinished`（:83，`skipVoters`、`needed = ceil((viewers+skipVoters) × ratio/100)`、达标发 `S2CStopScriptPacket`）、`broadcastSkipVote`（:119）、`skipVoteRatio` 解析（:43-57，脚本 meta ?? `Config.skipVoteRatio`） |
| 事件时间线按实例、已执行关键帧是整数集合 | `ScriptEventManager.onServerTick`（:178，`elapsed = (tick − startTick − totalPausedTicks)/20`、`triggeredKeyframes` 的 `(clipIndex<<16)|kfIndex`）、`ScriptPlayback.triggeredKeyframes` |
| 实例 id 只在命令路径有值，触发器路径为空串 | `S2CPlayScriptPacket` 类注释（“命令路径把 refId 直接复用为 instanceId；无 refId 的调用点（触发器）实例 id 留空”）与构造器（:33-37）；`StartPlaybackAction.sendTo`（:67，调两参 `send(player, json)`） |
| 账本暂停语义用累计暂停 tick | `ScriptEventManager.handlePause`（:242，`pauseStartTick` / `totalPausedTicks`） |
| 8 个事件 tracker = “最近一次”单槽，退出时清空 | `Evaluators`：`KillTracker`（:627）、`AdvancementTracker`（:655）、`DimensionTracker`（:666）、`InteractTracker`（:681）、`CraftTracker`（:712）、`PickupDropTracker`（:723）、`UseItemTracker`（:753）、`InventoryTracker`（:798）；清空点 `ServerEventHandler.onPlayerQuit`（:58-70） |
| 两个 tracker 已是“事实集合”雏形 | `KillTracker.allKills`（`KillRecord` 带维度 / 群系 / 坐标；`evaluateEntityKill` 的 `and` 模式读它，:119；`matchesScene` 按击杀时刻判定）；`PickupDropTracker.pickedUpSet`（注释“本会话捡起过的全部物品 id 集合”，:723） |
| 单槽会过期（自认） | `DimensionTracker.getLastFrom` 注释：“轮询型共用时可能过期”（:666） |
| 客户端同步链路只写不读 | `S2CTriggerStateSyncPacket.send`（:83）三处调用（`TriggerEngine.fireTrigger` :228、`TriggerEngine.onScriptFinished` :166、`ServerEventHandler.onPlayerJoin` :53）；`ClientTriggerStateCache.handleSync`（:18）；包不含 `startedScripts` |
| 预览不产生账本 | `CameraManager` 预览实例跳过上报（:858-861 注释与分支） |

### ② 已知缺陷（代码读出的，未在游戏内复现）

| 缺陷 | 证据 |
|---|---|
| `ScriptPlayback.server` 声明后从未赋值，`broadcastSkipVote` 读它 → 跳过票未达标时空指针 | `ScriptEventManager.ScriptPlayback` 字段（:295）、构造器（:302-310，不接 `server`）、`broadcastSkipVote`（:126，`pb.server.getPlayerList()`）；调用链 `onPlayerFinished`（:83）→ `broadcastSkipVote`（:119） |

> 说明：该缺陷落在本文步骤 4（账本收编）的范围内，收编时一并修掉；本文只记录，不改代码。

### ③ 未验证 / 未实现

- **等待点事实源**：`TrackType` 无 `WAIT_POINT`、全仓无 `wait_point` / `WaitPoint` 的 Java 命中（`wait-point-track.md` 事实核查已记），故“等待点等事实”是**未实现**，本文只列为消费方。
- **事实层本身零实现**：全仓无事实 / 事实追踪相关代码，本文为方向稿。
- **客户端缓存的 UI 消费方**：`docs/modules/network.md` 只记了同步链路本身；工作区内（含编辑器前端）未找到任何读取 `ClientTriggerStateCache` 的位置（`plans/complete/trigger_system_plan_v4.md` 的“仅用于 UI 显示”是旧设计表述）→ **未验证**（可能属编辑器阶段规划项）。
- **事实条数的实际增长曲线 / 查询成本**：需要实现后实测，本文只给性能约束方向。
