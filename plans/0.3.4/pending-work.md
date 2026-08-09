# 0.3.4 剩余事项 — 问题清单与解决方案

**创建日期**: 2026-08-09  
**状态**: ✅ **已全部实施完毕（2026-08-09 当日）**——除明确标注"待讨论/不做/未来"的项  
**对照**: `plans/complete/0.3.4/unfinished-items.md`（归档版清单）的 B 组、C1、E 组

---

## 状态总览（2026-08-09 实施完毕）

| 项 | 内容 | 状态 |
|----|------|------|
| B1 | advancement 进度触发器接线（PLAYER_ADVANCEMENT，按进度 id 匹配，双线重复模型） | ✅ **已实施**（PLAYER_ADVANCEMENT + AdvancementTracker，求值器改事件携带 id 匹配） |
| B2 | 使用状态机完整覆盖（instant/consume/release/interrupt 4 事件） | ✅ **已实施**（ItemUseMixin：completeUsingItem→consume、releaseUsingItem 按 UseAnim 分流 release/interrupt；instant use 实现方式偏差：经 EntityEvent.ADD + ThrowableItemProjectile 判定，替代原方案的 Item.use @RETURN——MCP 确认 Item.use Mixin 捕不到投掷物，1.20.1 无 ProjectileItem 接口；烟花/末影之眼不覆盖，文档已标注） |
| B3 | item_on_interact 右键二合一（物品+目标+target_type，不做左键，空手修复） | ✅ **已实施**（RIGHT_CLICK_BLOCK/INTERACT_ENTITY 接线 + target_type + 空手 `""`） |
| B4 | custom 触发器删除（命令 play 已覆盖跨模组触发） | ✅ **已实施**（注册/求值器/tracker/编辑器/lang/文档/示例脚本全清） |
| B5 | 触发器状态同步链路（触发/完成广播 + JOIN 补发） | ✅ **已实施**（fireTrigger / onScriptFinished / PLAYER_JOIN 三处发送） |
| C1 | 播放队列（priority 队列排序/容量 8/自动接播：不可打断一律排队、可打断立即替换） | ✅ **已实施**（ScriptQueue 容量 8 + 决策树 + 自动接播；用户确认：优先级不能大于打断；queueable 与客户端查询命令已移除） |
| E1-E5 | 遗留小项 | ✅ E1/E3/E4 已实施；E2 按计划放弃；E5 收尾：letterbox/EVENT clip 级简写兼容按用户决定**不保留**（兼容代码已删，统一关键帧级）；按钮/滚动/快速退出已验证；player selector 未测（无联机环境）、ESC 重开无预期不测 |
| — | **新增：xp 经验 / dimension 驻留 / observation 观察** | ✅ **已实施**（讨论后纳入本批：xp 轮询、dimension 驻留复用求值器、observation 服务端射线 5 tick） |
| — | **新增：item_pickup / item_drop**（事件源已确认独立事件） | ✅ **已实施**（PICKUP_ITEM_POST / DROP_ITEM 接线 + PickupDropTracker） |
| — | **新增候选：stat 统计 / item_smelt 熔炼** | ⏸ 未纳入（待讨论，排期另定） |
| — | 场景条件扩展（kill/interact/craft 等事件时刻记录维度/群系/位置） | 🔶 **entity_kill 已实施**（KillRecord 记录击杀时刻维度/群系/坐标）；interact/craft/pickup/drop 场景条件未做（覆盖范围待确认，本批只做 entity_kill 先行） |
| — | 物品检测平行线模型（事件型+快照型不互斥） | ✅ 原则定稿并落地（pickup/drop/craft/use/consume/release/instant + inventory change 全部上线） |

---

## 一、触发器接线（B1-B4 + B5）— 注册了但永不触发

现状总览：16 个触发器类型中 `login / entity_kill / block_interact / entity_interact / item_craft / item_use / dimension_change` 已由 `ServerEventHandler` 接线；
**`advancement / item_consume / item_on_interact / custom` 注册了但无事件源**；触发器状态同步链路（S2CTriggerStateSyncPacket）无发送点。

### B1. advancement（进度触发器）— **最终语义确认（2026-08-09）**

**现状**：注册 `EVENT_DRIVEN`，求值器 `evaluateAdvancement`（查 `getOrStartProgress(adv).isDone()`）已实现；`ServerEventHandler` 注释"检查 PlayerEvent 是否有 ADVANCEMENT 事件"。

**调研（Architectury 9.2.14 源码）**：✅ 自带 `PlayerEvent.PLAYER_ADVANCEMENT`（`award(ServerPlayer, Advancement)`，等价 Forge AdvancementEvent）——**无需 Mixin**。

**语义确认**：
- **我们只监测"玩家获得了 id 为 X 的进度"这一事件**——不检测进度内部的判定触发器（CriteriaTriggers 层面不碰），进度是攻击触发/合成触发/探索触发那是进度定义方的事
- 判定 = **事件携带的进度 id** 与条件匹配（`"advancement": "mymod:defeat_boss"`，支持通配 `mod:*`）——原版进度与模组进度（数据包/代码注册）走同一个 PlayerAdvancements 体系，零适配覆盖
- **重复触发 = 两条独立控制线，互不干扰**：
  - 脚本侧：`repeatable` 标志（我们的触发引擎控制是否允许再次触发）
  - 进度侧：进度能否**再次获得**（原版机制：进度完成一次后不再 award；进度定义方 `/advancement revoke` 收回后可再次获得、再次触发事件）
  - **两边都允许才重复**：进度可再次获得（那边 revoke）+ 脚本 repeatable=true → 重复触发；进度一次性（那边不 revoke）→ 事件只发生一次，脚本只播一次（repeatable 不影响事件发生次数）
  - 我们只负责发事件，进度重复与否由进度定义方决定

**接线**：
```java
PlayerEvent.PLAYER_ADVANCEMENT.register((player, advancement) -> {
    TriggerEngine.INSTANCE.onGameEvent("advancement", (ServerPlayer) player);
});
```

### B2. item_consume + 使用状态机完整覆盖（物品行为事件统一方案）

**现状**：注册了 `EVENT_DRIVEN`；`UseItemTracker.recordConsumed` 存在但**无调用点**（RIGHT_CLICK_ITEM 只记录 `recordUsed`）。

**调研（MCP 反编译原版使用状态机）**——`Item.use()` 入口分五态：

```
右键使用（Item.use()）
├─ ① 瞬间使用（无状态机，use() 内直接完成）：投掷物（雪球/蛋/珍珠/喷溅药水）
│    [SnowballItem.use 证据：use() 内 throwItem + awardStat(ITEM_USED) + shrink]
├─ ② 开始使用（startUsingItem）：getUseDuration > 0 → 物品锁定
├─ ③ 用尽完成（completeUsingItem → finishUsingItem）：非 useOnRelease 物品
│    食物/药水/牛奶（EAT_DURATION 32/16）→ Player.eat → awardStat(ITEM_USED)
├─ ④ 松手完成（releaseUsingItem → releaseUsing）：useOnRelease=true 物品
│    弓（松手瞬间蓄力≥0.1 发射 + awardStat(ITEM_USED)）/弩/三叉戟/望远镜
│    [BowItem.releaseUsing 证据：长按不算、松手算一次使用]
└─ ⑤ 中断（提前松手，非 useOnRelease）：releaseUsing（默认空）→ 不算使用
```

**方案（一个 Mixin 类 + 原版标志精确分流，类型互不干扰）**：

| Mixin 注入点 | 捕获状态 | 判定 |
|--------------|----------|------|
| `Item.use()` @RETURN | ① 瞬间 | `getUseDuration()==0` 且返回结果非 PASS → `item_instant_use` |
| `LivingEntity.startUsingItem()` @HEAD | ② 开始 | 只记录进行中状态（不触发） |
| `LivingEntity.completeUsingItem()` @HEAD | ③ 用尽 | `useOnRelease()==false` → `item_consume` |
| `LivingEntity.releaseUsingItem()` @HEAD | ④ 松手 | `useOnRelease()==true` → `item_release`（弓/弩/三叉戟/望远镜） |
| 同上 | ⑤ 中断 | `useOnRelease()==false` → `item_use_interrupt`（吃一半松手） |

**干扰防护**：吃食物点一下 → 只记录②不触发；吃完 → ③ consume；吃一半松手 → ⑤ 中断（**不会**判成弓发射）；弓拉弦 → 只记录，松手 → ④ release；雪球 → ① 瞬间（duration=0 不进状态机，consume/release 永远捕不到）。判定全部用原版标志（`useOnRelease`/`getUseDuration`），**兼容贴合原版状态机的所有模组**（模组食物→用尽、模组蓄力武器→松手、模组投掷物→瞬间；不贴合原版的模组不在兼容承诺内）。

**记录**：全部在服务端（`!level.isClientSide`），`UseItemTracker` 记录 `itemId + hand（getUsedItemHand）+ 场景（维度/坐标，供场景条件扩展）`。完成事件：`onGameEvent("item_consume"/"item_release"/"item_instant_use"/"item_use_interrupt", player)`。

**触发器映射**：`item_consume`（③）/ `item_release`（④）/ `item_instant_use`（①）/ `item_use_interrupt`（⑤，可选）；`item_use`（现有 RIGHT_CLICK_ITEM）保留为"右键入口"通用语义。

### B3. item_on_interact（物品 + 目标交互触发器）— **最终语义确认（2026-08-09）**

**现状**：求值器 `evaluateItemOnInteract` 与 `InteractTracker.recordInteractionItem` 已实现；`ServerEventHandler` 的 `RIGHT_CLICK_BLOCK / LEFT_CLICK_BLOCK / INTERACT_ENTITY` 处理器已接线 block_interact / entity_interact 且**已调用 recordInteractionItem**——只差 `onGameEvent("item_on_interact")`。

**讨论结论**：
- **只监听两个右键事件**：`RIGHT_CLICK_BLOCK`（右键方块：放置/useOn/空手点）+ `INTERACT_ENTITY`（右键实体：喂食/剪羊毛/交易/牵绳）
- **不做左键**：左键攻击实体（ATTACK_ENTITY）不做——战斗打一下突然放脚本很怪；`LEFT_CLICK_BLOCK` 保持现状服务 block_interact，不参与 item_on_interact 记录/触发
- **二合一判定**：手持物品 id（A）+ 右键对准目标 id（B）+ 可选 `target_type`（block/entity，防方块实体同 id 混淆）同时匹配
- **空手残留 bug**：`recordInteractionItem` 对空手跳过记录导致残留上一次物品——修复为空手记录 `""`，`item` 条件支持 `""` 匹配空手

**接线**（ServerEventHandler 两个处理器内各加 1 行）：
```java
// RIGHT_CLICK_BLOCK / INTERACT_ENTITY 处理器内（record 已就位）：
TriggerEngine.INSTANCE.onGameEvent("item_on_interact", serverPlayer);
```
（LEFT_CLICK_BLOCK 不接。）

**条件格式**：
```json
{
  "type": "item_on_interact",
  "conditions": {
    "item": "minecraft:shears",
    "target": "minecraft:sheep",
    "target_type": "entity"
  }
}
```

### B4. custom（自定义事件触发器）— **决定：删除**

**现状**：`CustomEventTracker.fire()` 无调用方；`evaluateCustom` 查 `hasFired(player, event_id)`（永久集合）。

**讨论结论（2026-08-09）**：
- 触发脚本的跨模组需求**已被命令覆盖**：第三方模组执行 `/icinematics play <file>`（`server.getCommands().performPrefixedCommand(...)`）即可，无需开放 API
- 开放 API 让"模组套模组触发脚本"语义很怪，且与命令功能重叠
- custom 无调用方 = 死功能（脚本写 `custom` 条件永远不触发，误导）
- **决定：删除 `custom` 触发器类型**（注册、`evaluateCustom`、`CustomEventTracker`、schema/编辑器条目一并移除）
- 若未来有第三方联动需求：文档提供"第三方模组执行命令触发脚本"示例即可，不再引入 API

### B5. 触发器状态同步链路（S2CTriggerStateSyncPacket）

**现状**：`S2CTriggerStateSyncPacket.send(triggered, completed)` 与客户端缓存 `ClientTriggerStateCache` 存在，**服务端从不发送、客户端无人读取**。

**方案**：
1. 触发成功时（TriggerEngine 命中并求值通过、记录触发状态后）→ 读取 `TriggerStateStore` 当前 triggered/completed 集合 → `S2CTriggerStateSyncPacket.send(player, ...)`
2. 脚本完成时（ScriptEventManager 完成回调 / ClientScriptNotifier 对应服务端处理处）→ 更新 completed 集 → 广播
3. 玩家 JOIN 时补发一次（对齐当前状态）
用途：客户端编辑器/UI 显示脚本状态（已触发/已完成）。

---

## 二、播放队列完整方案（C1 / repair_plan_E E2）

**现状**：`CameraManager.playScript()` 仅单槽 `pendingScript`（不可打断时 return false 丢弃；`deactivateNow` 自动接播 pending）。无 priority / 队列容量。
**依赖检查**：E1 虚拟时钟 double 精度修复 **已实施**（`CameraManager.gameTimeSeconds` 为 `double`）✓ 队列无缝衔接依赖就绪。

**方案（2026-08-09 用户确认定稿：优先级不能大于打断）**：

```
新脚本请求 → 当前无播放? → 直接播放
          → 有播放: 当前 interruptible? → 立即替换（无渐出，被打断就替换）
                  → 否则（不可打断）→ 一律入队（priority 降序 + FIFO，容量 8，满则拒绝）
deactivateNow() 末尾 → pendingScript / 队列非空则自动接播
```

| 改动 | 内容 |
|------|------|
| `ScriptMeta` / schema `meta` | 加 `priority`（int，默认 0）；~~queueable~~ 已按用户确认移除（一律排队，参数无控制作用） |
| 新建 `ScriptQueue` | `PriorityQueue<QueuedScript>`，容量 8，priority 降序同优先 FIFO |
| `CameraManager.playScript` | 可打断 → 立即替换（deactivateNow 直接切换）；不可打断 → 一律入队，满则拒绝 |
| `CameraManager.deactivateNow` | 停用后自动接播 pendingScript / 队列 |

~~`CinematicCommand` `/icinematics queue` 状态查询~~ 已按用户要求移除（客户端命令根与服务端命令冲突，且播放中无法使用）。

注意：入队触发条件校验**不做**——播放请求到达客户端时无触发器上下文（条件在服务端），E2 规格的提醒项不可实现。

---

## 三、遗留小项（E1-E5）

| # | 项 | 现状 | 方案 | 优先级 |
|---|----|------|------|--------|
| E1 | MenuBarArea 魔法数 | 硬编码坐标/尺寸 | 提取常量（清理类） | 低 |
| E2 | UITextInput 光标移动/插入/Ctrl+C/V | 无 | 原计划标注"优先级低，可不做"——建议**不做**或仅做方向键光标 | 可放弃 |
| E3 | letterbox ease-in-out 平滑 | 关键帧线性插值 | `KeyframeInterpolator` 为 letterbox 加 easeInOut 曲线函数（或复用 smooth 样条） | 低 |
| E4 | MathUtil Hermite 基函数残留 | 死代码（速度曲线引擎已删） | 直接删除 h00/h10/h01/h11 | 低（顺手） |
| E5 | 0.3.2 测试清单回归 | 功能已实现缺回归 | 手动回归：ESC 重开保持、letterbox 旧格式兼容、command player selector 等 | 中 |

---

## 四、触发器分类体系（v1 草案，2026-08-09）

> 目标：归类全部触发器（现有 16 种 + 新增候选），明确组合能力与冒烟测试矩阵。
> 参考：FTB-Quests 任务类型（xp / dimension 驻留 / observation 观察）与 Architectury 事件模块能力。

### A 类 · 状态检测（POLLING 轮询——"当前是否满足"）

| 触发器 | 条件 | 状态 |
|--------|------|------|
| `location` 位置 | dimension / corner1+2 矩形 / position+radius 球 | ✅ 已有 |
| `biome` 群系 | 通配（* / mod:* / 部分匹配） | ✅ 已有 |
| `structure` 结构 | structure + radius（扫描已加载结构引用） | ✅ 已有 |
| `gamestage` 阶段 | stage（反射 GameStageHelper） | ✅ 已有 |
| `inventory` 背包状态 | items + mode(and/or)——**拥有检测** | ✅ 已有 |
| `dimension` 驻留维度 | 当前维度（状态型，与 change 事件互补） | 🔸 新增候选（location 已有 dimension 条件，独立更直观） |
| `xp` 经验 | level / total（`Player.experienceLevel`/`totalExperience` 公开字段，已 MCP 确认） | 🆕 新增 |
| `stat` 统计 | stat id + 数值（`player.getStats()`，MCP 确认 awardStat/getStats 存在） | 🆕 新增 |

### B 类 · 物品行为（EVENT_DRIVEN 事件——"发生了什么"，与 A 类独立）

| 触发器 | 事件源 | 状态 |
|--------|--------|------|
| `inventory change` 背包增/减 | **快照对比**（increase/decrease，POLLING 但语义为"变化"） | ✅ 已有（change 字段） |
| `item_craft` 合成 | Architectury `PlayerEvent.CRAFT_ITEM` | ✅ 已有 |
| `item_use` 使用 | `InteractionEvent.RIGHT_CLICK_ITEM`（右键入口，通用语义） | ✅ 已有 |
| `item_consume` 用尽 | Mixin `completeUsingItem` + `useOnRelease==false`（吃/喝完成，独立于背包——行为维度） | 🔸 待接线（B2 状态机方案） |
| `item_release` 松手 | Mixin `releaseUsingItem` + `useOnRelease==true`（弓/弩/三叉戟/望远镜发射） | 🔸 待接线（B2 状态机方案） |
| `item_instant_use` 瞬间 | Mixin `Item.use` @RETURN + `duration==0` + 非 PASS（投掷物） | 🔸 待接线（B2 状态机方案） |
| `item_use_interrupt` 中断（可选） | Mixin `releaseUsingItem` + `useOnRelease==false`（吃一半松手） | 🔸 待接线（B2 状态机方案） |
| `item_on_interact` 物品+目标 | 右键方块/右键实体处理器各加 1 行（左键不参与；含空手记录修复与 target_type 区分） | 🔸 待接线（B3） |
| `item_pickup` 获得 | `PlayerEvent.PICKUP_ITEM_POST` | 🆕 新增 |
| `item_drop` 丢弃 | `PlayerEvent.DROP_ITEM` | 🆕 新增 |
| `item_smelt` 熔炼（可选） | `PlayerEvent.SMELT_ITEM` | 🆕 可选 |

**获得/丢弃 = 双通道互补**：事件型（pickup/drop/craft/smelt 即时精确）+ 快照型（inventory change 兜底——覆盖交易、箱子等非事件路径）。

### C 类 · 交互行为（EVENT_DRIVEN）

| 触发器 | 事件源 | 状态 |
|--------|--------|------|
| `entity_interact` 右键实体 | `InteractionEvent.INTERACT_ENTITY` | ✅ 已有 |
| `block_interact` 左/右键方块 | `RIGHT_CLICK_BLOCK` / `LEFT_CLICK_BLOCK` | ✅ 已有 |
| `entity_kill` 击杀 | 实体死亡事件（or/and 多目标，累计模式） | ✅ 已有 |
| `observation` 观察 | **服务端射线**：`Level.clip`（方块）+ 实体 AABB 射线（准星注视目标） | 🆕 新增（FTB ObservationTask 同款语义，服务端实现避免客户端上报） |

### D 类 · 状态变化（EVENT_DRIVEN）

| 触发器 | 事件源 | 状态 |
|--------|--------|------|
| `login` 进服 | 玩家 JOIN | ✅ 已有 |
| `dimension_change` 切换维度 | `PlayerEvent.CHANGE_DIMENSION` | ✅ 已有 |
| `advancement` 进度 | `PlayerEvent.PLAYER_ADVANCEMENT`（Architectury 自带，无需 Mixin） | 🔸 待接线（B1） |
| ~~`custom` 自定义~~ | — | ❌ **已决定删除**（B4：无调用方，命令 play 已覆盖跨模组触发） |

### E 类 · 组合能力（横向）

1. **多触发器 OR**（现状）：脚本 triggers 数组各自独立注册，任一满足即播放
2. **场景条件扩展**：事件型触发器记录**事件时刻**的场景（维度/群系/位置）——`KillTracker.record` 扩展为同时记录 dimension+pos；kill/interact/craft/pickup/drop 的 conditions 加可选 `dimension` / `biome` / `location` 字段（例：`entity_kill` + `"dimension": "minecraft:the_nether"` = "在下界杀怪"）。记录在事件发生那一刻，非"现在"
3. **脚本级 AND**（可选未来）：全部触发器满足才触发标志——有竞态（事件型与轮询型不同步），需求明确再做

### E+ 物品检测设计原则（平行线模型，2026-08-09 确认）

- **物品检测不是单一事件**，而是多条**平行检测线**，互不互斥、可互补，创作者按需自由选择绑定：

| 检测线 | 类型 | 语义 | 时机 |
|--------|------|------|------|
| `item_pickup` | 事件型 | 碰到掉落物拾取 | 即时、精确（捡了什么） |
| `item_drop` | 事件型 | 丢出物品 | 即时、精确（丢了什么） |
| `item_craft` / `item_use` / `item_consume` / `item_release` / `item_instant_use` | 事件型 | 行为 | 即时、精确 |
| `inventory change` | 快照型 | 背包数量增/减 | 轮询、粗粒度（覆盖交易/箱子等事件盲区） |
| `inventory`（状态） | 快照型 | 拥有检测 | 轮询、当前是否拥有 |

- 创作者可以只绑事件线（精确场景），也可以只绑快照线（兜底场景），也可以**多线并用**（同脚本多触发器）
- **脚本重复触发与否由创作者脚本定义负责**（repeatable 标志 + 触发频率 + 进度/事件侧的重复能力）——引擎只提供事件与求值，不做重复限制之外的干预
- 高自由度定义是本体系目标：**不把检测线设计成互斥单选**

### F 类 / G1 / G2 · 跨维度运镜 + 相机实例队列 + 画中画

> 已移入 0.4.0 排期文档：\`plans/0.4.0/camera-queue-pip-dimension.md\`（2026-08-09）。
> G3 维度驻留触发器已实施（见状态总览）；G4 relative 片段级基准不排期，记录见下。

#### G3 维度驻留触发器（新增候选 — 与相机体系的关系已厘清）

- **语义**：状态型检测"玩家当前处于 X 维度"（POLLING，独立于 `dimension_change` 事件型）
- **与 F 类/G 类的关系**：相机跨维度（预加载落地后）不改变"玩家当前维度"语义——触发器照常检测玩家侧状态，**互不冲突**
- **产品原则**：功能提供（与 location/biome/structure 同属"特定场景检测"族，创作者自由组合），**用法是创作者的事**——我们不做用法教育

#### G4 relative 基准语义 — 片段级基准（2026-08-09 决定）

**现状**：`ScriptPlayer.originPos = mc.player.position()`——**脚本级基准**（整个脚本触发瞬间一次快照），所有 clip 共用。问题：片段间玩家位置变动（传送/外力/矿车）→ 后续 clip 的 relative 全部错位。

**决定（方案 2：片段级基准）**：
- **每个 clip 首次激活时记录玩家位置为 relative 基准**（`CameraTrackPlayer` 维护 `clipOrigin`，clip 切换时记录）
- **loop 循环**：每轮循环刷新基准（玩家可能已移动）
- **morph 过渡**：B 在过渡窗口激活 → 基准取激活瞬间玩家位置
- **编辑器预览**：播放头跳转按激活 clip 取基准（预览时玩家不动，稳定）
- **实现点**：`evalKeyframeWorldPos` 的 relative 分支从脚本级 `originPos` 改为当前 clip 的 `clipOrigin`

**语义更新**：`relative = 相对"当前片段开始时的玩家位置"`；单片段脚本 = 相对触发点（与现状等价，全部存量脚本不受影响）。

**注意**：跨片段连续运镜路径（A→B 一条线跨多 clip）在片段级基准下会断裂——这类脚本用 `absolute` 坐标或 morph 过渡衔接表达。

**传送过场表达**（G 类记录）：`follow: entity @p` 实时跟随玩家位置（含传送跳变），是"玩家位置变化"场景的统一答案——relative 保持快照语义（片段级），不做实时跟随（避免与 follow 重复）。

### 冒烟测试矩阵

每类至少一个验证脚本（`/icinematics validate` 零问题 + 实际触发验证）：

| 测试脚本 | 覆盖 |
|----------|------|
| `test_trigger_pickup.json` | item_pickup（获得物品事件型） |
| `test_trigger_drop.json` | item_drop（丢弃事件型） |
| `test_trigger_inventory_change.json` | inventory change increase/decrease（快照型） |
| `test_trigger_xp.json` | xp（状态型） |
| `test_trigger_observation.json` | observation（注视） |
| `test_trigger_kill_dimension.json` | entity_kill + dimension 场景组合（E2） |
| `test_trigger_advancement.json` | advancement（接线后） |
| `test_trigger_item_release.json` | item_release（弓松手发射，状态机方案） |
| `test_trigger_item_instant.json` | item_instant_use（投掷，状态机方案） |

### 实施顺序

1. **删除 custom**（注册/求值器/tracker/schema/编辑器条目，B4 结论）
2. 接线 3 个（B3 item_on_interact → B1 advancement → B2 使用状态机 Mixin）
3. 新增 5 个（xp / dimension 驻留 / item_pickup / item_drop / observation）
4. 场景条件扩展（KillTracker 先行）
5. 冒烟测试矩阵
6. B5 状态同步广播

---

## 实施建议顺序

1. **B3 item_on_interact**（3 行改动，零风险）→ 2. **B1 advancement**（1 个注册）→ 3. **B2 使用状态机 Mixin**（1 个 Mixin 类 + 4 个事件：instant/consume/release/interrupt）→ 4. **B5 状态同步**（2 处广播）→ 5. **B4 custom 命令触发**（1 个子命令）→ 6. **C1 播放队列**（中等工程）→ 7. E 组遗留
