# trigger（触发器系统）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/trigger/server/`

功能树：

- **23 种触发器类型**
  - ✅ 入口类 `ImmersiveCinematics.registerTriggerTypes()` 注册 23 种触发器：location、biome、inventory、structure、gamestage、xp、dimension、observation（轮询）+ advancement、entity_kill、entity_interact、dimension_change、login、item_craft、item_use、item_consume、item_release、item_instant_use、item_use_interrupt、block_interact、item_on_interact、item_pickup、item_drop（事件驱动）（`TriggerRegistry`、`TriggerType`）
  - ✅ `TriggerType` 定义触发器元数据：id、监听策略（轮询/事件驱动）、轮询间隔、条件求值器（`TriggerType`）
  - ✅ `TriggerRegistry` 提供注册/查询/清空触发器类型（`TriggerRegistry`）
- **双通道触发引擎**
  - ✅ `TriggerEngine` 单例管理全部注册项，按策略建立索引：事件驱动按类型 id 建 `eventIndex`，轮询按间隔建 `pollBuckets`（`TriggerEngine`）
  - ✅ 事件驱动入口 `onGameEvent()` 由服务端事件回调调用，对匹配注册项求值并触发（`TriggerEngine`）
  - ✅ 轮询入口 `onServerTick()` 按 tick 间隔分桶遍历所有在线玩家并求值（`TriggerEngine`）
  - ✅ 支持延迟触发：`delay`（秒）转换为 tick 数，到期后执行动作（`TriggerEngine`）
  - ✅ 去重与防重：播放同一脚本的玩家跳过触发；非 repeatable 触发器已触发过则不再触发（`TriggerEngine`、`TriggerStateStore`）
  - ✅ 支持 `on_enter` 进入检测：只在玩家从区域外进入时触发，配合 `exit_buffer` 扩展离开判定，防止区域边界抖动反复触发（`TriggerEngine`、`Evaluators.expandConditions`）
  - ✅ 支持**前置依赖 `requires`**：触发器声明前置脚本 id 列表（AND），全部前置脚本"触发过"（跳过/打断/播完都算）才允许触发——按剧情线逐级解锁；依赖检查在 shouldSkip 之前（未解锁时连去重都不碰）；解锁后 repeatable 语义照旧（`TriggerEngine.prerequisitesMet`、`TriggerStateStore.hasAnyTriggered`）
  - ✅ 触发后标记状态并依次执行注册的动作列表（`TriggerEngine`）
- **触发器注册模型**
  - ✅ `TriggerRegistration` 封装一次注册：脚本 id、触发器 id、类型、条件、退出条件、动作列表、repeatable/delay/on_enter/exit_buffer（`TriggerRegistration`）
  - ✅ `ScriptManager.registerAllTriggers()` 将脚本内嵌触发器批量转成注册项，动作统一为 `StartPlaybackAction`（`ScriptManager`）
- **条件求值器**
  - ✅ `Evaluators` 为每种触发器提供静态求值方法（`Evaluators`）
  - ✅ location：维度匹配 + 方块区域（corner1/corner2）或 圆心+半径 判断（`Evaluators`）
  - ✅ biome/structure：按注册 id 模式匹配当前群系/所处结构，structure 支持半径范围扫描（`Evaluators`）
  - ✅ inventory：物品列表匹配（and/or 模式），支持 `change=increase/decrease` 快照对比检测数量变化（`Evaluators`）
  - ✅ gamestage：通过反射调用 `GameStageHelper.hasStage` 判断游戏阶段（模组未装时返回 false）（`Evaluators`）
  - ✅ advancement：检测玩家是否已完成指定进度（`Evaluators`）
  - ✅ entity_kill：最近击杀/累计击杀匹配，支持多实体 and/or 模式（`Evaluators`）
  - ✅ entity_interact/block_interact/item_on_interact：匹配最近一次交互目标（实体/方块）与手持物品（`Evaluators`）
  - ✅ item_craft/item_use/item_consume：匹配最近一次合成/使用/消耗的物品（`Evaluators`）
  - ✅ dimension_change：匹配玩家当前所在维度（`Evaluators`）
  - ✅ login：恒为真，由登录事件驱动（`Evaluators`）
  - ✅ 提供 per-player 内存追踪器：KillTracker、InteractTracker、CraftTracker、UseItemTracker、InventoryTracker，玩家退出时清理（`Evaluators`）
  - ✅ id 模式匹配支持 `*` 通配、`mod:*` 前缀、裸名包含匹配（`Evaluators.matchesId`）
- **触发器动作**
  - ✅ `TriggerAction` 动作接口，动作在玩家上下文执行（`TriggerAction`）
  - ✅ `StartPlaybackAction`：取脚本原始 JSON 通过 `S2CPlayScriptPacket` 发送给玩家开始播放（`StartPlaybackAction`）
  - ✅ `StopPlaybackAction`：通过 `S2CStopScriptPacket` 通知客户端停止指定脚本（`StopPlaybackAction`）
  - ✅ `PlaySoundAction`：以 MASTER 音源向玩家发送 `ClientboundSoundPacket` 播放音效（`PlaySoundAction`）
  - ✅ `ExecuteCommandAction`：以玩家命令源（权限 4、抑制输出）执行命令，支持 `&&` 命令链（`ExecuteCommandAction`）
- **脚本事件管理（服务端播放会话）**
  - ✅ `ScriptEventManager` 单例按脚本 id 维护播放会话（观看者集合、跳过投票集合、已触发关键帧集合）（`ScriptEventManager`）
  - ✅ 播放开始时登记观看者，支持多玩家同播一个脚本（`ScriptEventManager`）
  - ✅ 每 tick 推进会话时间：有效流逝时间扣除暂停累计，按 EVENT 轨道关键帧时间多点触发命令（已触发关键帧去重）（`ScriptEventManager`）
  - ✅ 命令以观看者身份执行，支持 `&&` 命令链、权限等级 4、抑制输出（`ScriptEventManager`）
  - ✅ 支持暂停/恢复信号：记录暂停起止 tick，恢复时累计暂停时长，暂停期间不触发关键帧（`ScriptEventManager`）
  - ✅ 跳过投票：玩家跳过计入投票，达到 `skipVoteRatio` 阈值时向其余观看者广播 `S2CStopScriptPacket` 强制停止，未达标时广播 `S2CSkipVoteUpdatePacket` 更新票数（`ScriptEventManager`、`Config`）
  - ✅ 提供会话状态查询：isScriptActive/isPlayerPlayingScript/isFullyComplete/getRemainingViewers（`ScriptEventManager`）
- **触发器状态存储**
  - ✅ `TriggerStateStore` 单例按玩家持久化触发状态，存储于世界存档 `immersive_cinematics/trigger_state/<uuid>.snbt`（SNBT 文本格式，临时文件+原子替换写入）（`TriggerStateStore`）
  - ✅ 玩家登录时加载、退出时保存并卸载、服务端停止/世界保存时全量保存（`TriggerStateStore`）
  - ✅ `PlayerTriggerState` 维护每玩家的 已触发触发器集合（脚本→触发器 id）与已完成脚本集合，带脏标记避免无变化写入（`PlayerTriggerState`）
  - ✅ 提供查询与变更 API：isTriggered/markTriggered、resetScript/resetAll、isScriptCompleted/markScriptCompleted（`TriggerStateStore`、`PlayerTriggerState`）

## 已知问题

- `item_consume`/`item_release`/`item_use_interrupt` 依赖 `ItemUseMixin`（LivingEntity 注入），运行时需确认 Mixin 应用成功（defaultRequire=1，失败即启动崩溃）
