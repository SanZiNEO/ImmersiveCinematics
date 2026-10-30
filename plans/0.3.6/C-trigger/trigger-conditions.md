# 触发条件：组合、朝向区间与检测频率

> 状态：实现中（条件组合、朝向区间、检测频率已落地；触发器动作面、触发器内部判定统一与事件类时间窗未落地）
> 范围：`meta.triggers[]` 的条件模型（单一条件、组合器、前置条件）、朝向区间条件、轮询类触发器的检测频率
> 关联：`docs/TRIGGER_TYPES.md`、`docs/SCRIPT_FORMAT.md` §1c、`docs/modules/trigger.md`、`plans/0.3.6/C-trigger/condition-system.md`、`plans/0.3.6/C-trigger/playback-relations.md`
> 2026-10-30 口径更新：事件类条件可以进入组合器，但必须带时间窗/时间戳；无时间窗的「最近一次」单槽仍不参与组合。时间窗/时间戳归 `H-other/state-tracking.md` / `trigger-continuity.md`（未实现）；在其落地前，当前实现仍拒绝事件类子条件，这是过渡态，不是目标口径。

## 一、摘要

触发器体系的一次升级：条件从「一个触发器一类条件」变成可组合的条件模型——单一条件、多元组合器（任一满足 / 全部满足，数量不设上限，可嵌套）、前置条件（锁存）三类；事件类条件在带时间窗/时间戳时可作为子条件，组合器同时用于跨条件与触发器内部判定；新增朝向区间条件，按玩家原版视线参数判定；轮询类触发器的检测频率可按触发器与脚本分别设置，再回落到类型默认。升级保持旧脚本零迁移，编辑器与可视化编排不在本期。

## 二、为什么做

### 2.1 问题

- 一个触发器只能表达一类条件。「到某个位置且看向某处」要么拆成多个触发器、用一个中间脚本串起来，要么依赖触发器内部写死的多字段与逻辑；拆出来的中间脚本还要真的播一遍。
- 视线朝向没有独立条件。现有的注视条件只能判断「看着某个具体目标」，判断不了「大致朝北、上下各二十度」这种不需要目标的构图门。
- 同一脚本的多个触发器之间只有「任一满足」一种关系，没有「都满足才触发」；前置条件只能表达脚本状态，不能把两个触发器的条件组合起来。
- 检测频率按类型全局固定。少数需要快速响应的触发器只能跟着全局间隔走；把全局调快，同类型的所有触发器一起变贵。
- 组合条件若混入不带时间窗的「最近发生过」事件类条件，很久以前发生的事仍然算数，判定会失真；事件类条件需要带时间窗/时间戳才能参与组合，不能沿用无窗口的单槽语义。

### 2.2 目标

- 一个触发器能声明多个子条件，任一满足或全部满足即得出结论；子条件可再嵌套组合器，数量不设上限，层数有明确上限。
- 事件类条件在带时间窗/时间戳时可作为组合子条件；无时间窗的「最近一次」单槽不参与组合。
- 朝向区间按玩家自身视线角度判定，跨 ±180° 的区间、端点包含、俯仰上下限都有确定结果；可单独使用，也可与其它条件组合。
- 轮询间隔可在触发器上、脚本上分别覆盖，未写时回落到类型默认；事件驱动类型不受影响。
- 旧脚本不写新字段，行为与升级前一致。
- 校验能对组合结构、朝向端点、间隔取值给出可读报错。

### 2.3 非目标

- 不做事件树与可视化条件编排。
- 不做取反条件。
- 不做按求值代价重排子条件（顺序 = 声明顺序）。
- 不做游戏内编辑器与 WebUI 的条件编辑控件，本期只支持手写 JSON。
- 不做全局配置的热生效。
- 不做无时间窗的事件类子条件（会退化成「曾经发生过」）；带时间窗/时间戳的事件类条件不在本限制内。

## 三、方案

条件模型有三件东西。单一条件保持现有写法不动。组合器把一串子条件聚成一个条件：任一满足 = 有一个成立就得出结论，全部满足 = 每个都成立才得出结论；它是统一的组合机制，不再另造一套「与 / 或」。前置条件是「发生过即真」的锁存条件，归入条件的一种，和普通条件一起参与组合，原有写法保持兼容。事件类条件也可以作为子条件，但必须带时间窗/时间戳；无窗口的「最近一次」单槽不进组合。

朝向条件用两个角度端点定义一段视线范围：水平方向从起点顺时针扫到终点，跨过正北的区间自然表达，起止相同表示不限制水平方向；俯仰取两端之间。它只读玩家自己的视线，不需要目标、不需要射线命中，与注视条件互补——一个管「大致朝哪」，一个管「看着谁」。朝向可以单独成一个触发器，也可以放进组合。

检测频率：触发器上可写检测间隔，脚本上可写统一间隔，两者都不写时用类型默认值；只对轮询类触发器有效。间隔单位是 tick，取值范围 1 到 600。

同一个触发器内部的多字段判定也走同一套组合器，不再各写一套与逻辑。

触发器命中后只做两类事：控制脚本（播放 / 停止 / 暂停）与回报等待点。命令与声音不属于动作面——命令归脚本的事件轨，声音归音频轨。

边界：组合器每轮求值；轮询类按同一时刻状态，前置条件按锁存语义，事件类条件按声明的时间窗/时间戳查询事实；除此之外没有记忆、没有等待。子条件按声明顺序求值，结果确定即停。组合器与朝向都没有可外扩的空间条件，离开缓冲对它们无意义。子条件数量不设上限，嵌套层数含最外层最多八层。事件类时间窗待 H7/H8 落地；落地前当前实现仍拒绝无窗口事件类条件。

## 四、技术细节

### 4.1 核心模型

- **触发器类型**：类型 id、监听策略（轮询 / 事件驱动）、类型级默认间隔、条件求值器。朝向与两个组合器也按普通类型注册，复用同一条求值通道。
- **触发器定义**：解析产物，持有类型、条件原文、可重复性、延迟、进入语义、离开外扩、前置条件与触发器级间隔。
- **触发器注册项**：注册期的不可变快照，额外持有生效间隔与退出条件。
- **触发器引擎**：事件索引（按类型 id）、轮询分桶（按生效间隔）、进入状态机、延迟队列、前置条件检查。
- **组合器内核**：一个递归求值器，按列表顺序求值子条件，全部满足遇假即停、任一满足遇真即停；子条件可以是轮询类条件、前置条件、带时间窗/时间戳的事件类条件或另一个组合器；无时间窗的事件类条件仍拒绝。
- **朝向区间**：纯角度数学，抽在触发器与判定规则共用的几何核里。
- **前置条件注册表**：类型到判定函数的映射；内置「播放过 / 开始过 / 完成过」三种脚本状态前置。
- **触发状态存储**：按玩家记录已触发的触发器与已完成的脚本，落盘保存。

### 4.2 契约

- 解析期产出触发器定义；脚本注册时计算生效间隔（触发器级 → 脚本级 → 类型级，钳到至少 1），连同退出条件与前置条件建注册项，交给引擎；重载 = 整体重建。
- 引擎只在服务端主线程推进：轮询类按间隔分桶，事件驱动类由事件回调直接进入；注册项运行期只读。
- 进入状态按（玩家、脚本、触发器）记账，每次轮询都更新（含区域外复位）；不可重复触发器的已触发状态归玩家状态存储。
- 组合器求值不持有状态：一次调用内完成，所有轮询类子条件共享同一份玩家状态。
- 前置条件是触发前的门槛（全部满足），与组合器里的前置条件子项共用同一套判定与校验。

### 4.3 数据形态

- 条件仍是按类型取字段的对象。朝向条件 = `yaw1` / `pitch1` / `yaw2` / `pitch2` 四个角度字段。
- 组合器条件 = `list` 数组，元素 = `{ type, conditions }`；数量不设上限，嵌套含顶层不超过 8 层。
- 间隔 = 整数 tick：触发器级 `poll_interval`、脚本级 `meta.trigger_poll_interval`，取值 `1`~`600`；缺失与 JSON null 同义，解析期非法值告警后按未写处理。
- 生效间隔是注册项上的一个整数：轮询类至少 1，事件驱动恒 0（不入桶）。
- 朝向语义：水平从 `yaw1` 顺时针扫到 `yaw2`，跨 ±180° 自然环绕，起止相同 = 整圈；俯仰取两端 min/max，端点包含，端点限 ±90。

### 4.4 边界

- 组合子条件接受轮询类触发器、已注册前置条件、另一个组合器，以及带时间窗/时间戳的事件类条件；无时间窗的事件类条件仍拒绝——它们会退化成「很久以前发生过也算」。事件类的时间窗/时间戳由 H7/H8 事实层提供，落地前保持过渡拒绝。
- 嵌套深度上限 8（含最外层为第 1 层），校验与运行期共用同一常量；超深校验报错，运行期防御性按不满足。
- 前置条件是锁存语义：放进全部满足时其余子条件仍需此刻成立；放进任一满足时解锁后下一轮即命中。
- 朝向只读玩家实体自身的视线角度（服务端同步值），与相机、脚本相机、模组相机无关；注视条件继续负责「看向具体目标」。
- 离开缓冲只对带空间条件的触发器有意义；朝向与组合器上写会被提示无效。
- 触发器动作面只有控制脚本与回报等待点两类；命令与声音归脚本轨道。
- 间隔只改采样节奏，不改条件语义：间隔内的瞬时穿越可能采不到。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 每个条件各写一个触发器，用前置脚本串起来 | 「到某地」与「看向某处」各一个触发器，第一个先播一个中间脚本，第二个用前置依赖 | 作者要造并维护只用于判定的空脚本；中间脚本真的会播，产生副作用与延迟；组合数随条件数爆炸 | 组合器在同一触发器内表达，零额外脚本 |
| 二元与 / 或字段 | 每个触发器加两个字段，值为两个子条件 | 只能表达两层；三个以上条件要靠嵌套对象，写法与校验都膨胀 | 多元列表 + 统一内核，数量不设上限 |
| 事件类条件无时间窗直接进组合 | 允许任意已注册事件类型作为子条件 | 「最近发生过」与「此刻成立」混在一起，很久以前的事件会让组合恒真 | 事件类条件必须带时间窗/时间戳；无窗口的单槽不进组合 |
| 每个类型各配一个间隔字段 | 配置里为每个类型增加独立键 | 配置项数量与类型数同步增长；作者无法按单个触发器调 | 三级回落：触发器 → 脚本 → 类型默认 |
| 用射线 / 目标判定代替朝向区间 | 朝指定方向发射射线，命中才算 | 需要具体目标；开销随距离增长；空视野朝北无法表达 | 纯角度数学，零射线 |

**本方案的缺点**：

- 无时间窗的事件类条件仍不能进组合；「两件事在 N 秒内先后发生」要等 H7/H8 的时间窗/事实层落地，当前只能靠前置条件锁存或另一个脚本。
- 深层嵌套的 JSON 难读难改；校验只报结构，不报「这个组合永远不可能成立」这类语义问题。
- 轮询是采样：间隔越大越容易漏掉短暂穿越；间隔可配把成本决策交给作者，没有全局配额。
- 取反缺失，补集条件要枚举。
- 朝向条件与注视条件语义相邻，作者容易选错。
- 编辑器未跟进，组合与朝向只能手写。

## 六、实现结果

状态：实现中

三项需求（条件组合、朝向区间、检测频率）已落地；触发器动作面与触发器内部判定统一仍未落地。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 触发器内部的多字段判定纳入统一组合器，不再写死与逻辑 | 仍按类型各自判定：场景组字段逐个判真，多字段条件全部满足 | 内部判定改造未排期，先落地跨条件组合 |
| 动作面收敛为控制脚本（播放 / 停止 / 暂停）与回报等待点 | 只有「起播本脚本」一个动作；命令与声音由脚本轨道承担，无停止 / 暂停动作、无等待点回报 | 动作面改造未排期 |
| 组合器最小版本只做全部满足 | 全部满足与任一满足同构，支持多层嵌套（含顶层不超过 8 层） | 后续批次放开嵌套，两层共用同一内核 |
| 事件类条件进组合 | 当前仍拒绝事件类子条件（无时间窗/时间戳） | 目标口径允许带时间窗的事件类条件；时间窗归 H7/H8，落地前保持过渡拒绝 |
| 检测频率按触发器 / 按脚本可配 | 一致：三级回落，`1`~`600` tick，事件驱动恒 0 | — |
| 朝向按玩家原版视线参数判定 | 一致 | — |
| 求值按声明顺序、首个决定即短路 | 一致 | — |

## 附录：重写前原文

> 2026-10-30 口径更新：下文「事件类拒绝」「不做窗口语义」等是当时的实现记录，不是现行目标；现行口径见正文顶部注记与 §2–§5：事件类条件可带时间窗/时间戳进入组合，无时间窗仍拒绝。

### 0.3.6 触发器体系一次升级：条件组合 + 朝向范围 + 检测频率（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [触发器类型](../../../docs/TRIGGER_TYPES.md)
> - [脚本格式](../../../docs/SCRIPT_FORMAT.md)
> - [反馈：on_enter 状态机问题](../../complete/0.3.6/feedback-0.3.5/05-on-enter-not-repeatable.md)

---

#### 1. 定位（已确认）

**这是触发器体系的“一次升级”**：现在够用但偏保守，升级一次到位；更大的（事件树、可视化条件编排）不进计划。

三个需求：

1. **多重条件触发**：一个触发器可以要求**多个条件组合满足**才触发——组合器是 `any` / `all_of`（**多元**，不设上限）；例如“到某个位置 **且** 看向某处”；原格式一个触发器只有一类条件，最小版本已用 `all_of` 补上一层组合。
2. **朝向范围条件**：检查玩家视线方向是否落在 `(yaw1, pitch1) → (yaw2, pitch2)` 范围内。按**玩家原版视线参数**判定：`yaw ∈ [-180, 180]`（0 = 南，正 = 顺时针）、`pitch ∈ [-90, 90]`（正 = 向下）；不掺模组相机侧的基准语义（`yaw_base` 等）。
3. **检测频率可配**：轮询间隔从“按类型全局”升级为可按触发器 / 按脚本单独设置——现状是所有 location 触发器共用 `triggerPollInterval_location`，少量需要高频检测的触发器被迫跟随全局（见 `feedback-0.3.5/04-trigger-latency.md` 待改进 #2）。朝向判定很便宜，高频轮询对它尤其有意义。

**节奏**：最小版本（`facing` / `all_of`）已落地；其余按效果分批放开。

**最小版本**：JSON 手写可用 + 服务端生效 + 校验通过；只做 `all_of`（全部满足）、只做玩家；编辑器 UI（游戏内 + WebUI）后置。

**条件模型**：触发条件包含——**单一条件**、**组合器（`any` / `all_of`，多元、不设上限）**、**前置条件（锁存）**。**组合器是统一的组合机制**：`any` = 任一满足，`all_of` = 全部满足；它**替代 AND / OR 这类二元的旧说法**，并且**统一用于所有层级**——既用于跨条件的条件列表，**也用于触发器内部的判定**（不是写死的 AND）。条件的元素可以是任何现有触发器类型的条件，也可以是前置条件——**前置条件归类为触发条件的一种**，参与组合（`requires` 现有字段写法保持兼容）。取反（not）未定，列为待定。

---

#### 2. 现状与缺口

| 现状 | 说明 |
|---|---|
| 单个触发器 = 单一条件类型 | 27 种触发器各有一套条件字段；同一触发器内部的多字段判定目前写死为 AND（如 entity_kill 的场景条件）——方向：纳入统一组合器（`any` / `all_of`）；跨类型组合由 `all_of` 承担（一层） |
| 同一脚本多个触发器 = **任一** | 任意一个满足就触发该脚本（任一语义）；没有“都满足才触发” |
| `requires` = **全部满足**，但只支持脚本状态类前置 | 内置 `script_played` / `script_started` / `script_completed`；不能把两个触发器的条件组合起来 |
| `facing` / `all_of` / `any`（已实现） | `facing`：视线角度区间（纯角度判定，不需目标）；`all_of` / `any`：组合器（支持嵌套 ≤ 8 层，见 §9），子条件可为轮询类、前置条件或另一个组合器 |
| `observation` | 看向某个**具体目标**（实体 / 方块，带 reach）；与 `facing` 互补，两者都保留 |

**缺口（当前）**：① ~~组合器只支持一层~~ **已做**（多层嵌套，≤ 8 层，见 §9）；② ~~检测频率仍按类型全局，未按触发器 / 脚本可配~~ **已做**（触发器级 / 脚本级 / 类型级三级回落，见 §11）；③ 编辑器 UI 未跟进（游戏内编辑器类型清单不含 `facing` / `all_of` / `any`）。

---

#### 3. 方案方向

##### 3.1 组合层：条件列表（`all_of` / `any` / 前置）

- 引入“**条件列表**”的写法，对应条件模型（见 §1）：
  - 单一条件 = 现有写法（不动）；
  - `all_of`（全部满足）= 列表内**全部满足**才触发（已实现为一层）；
  - `any`（任一满足）= 列表内**任一满足**即触发（已实现，与 `all_of` 同构：同一份 list 结构、同一套子条件求值）；
  - 前置条件（requires 类：script_played / script_started / script_completed）= 作为条件类型参与列表（已实现：与触发器一致写在子条件的 `conditions` 里，锁存语义）。
- **组合器统一**：同一套 `any` / `all_of` 也用于**触发器内部的判定**——不再另造一套 AND / OR。
- 列表元素**复用现有条件类型**（location / observation / entity_kill / …）+ 新增“朝向”条件 + 前置条件；**数量不设上限**。
- 语义区分：轮询条件是瞬时语义（现在是否满足）；前置条件是**锁存语义**（发生过即真）——组合求值时两类分清，避免“先 A 后 B”的误判。
- 现有“单条件触发器”写法**保持不动**（旧脚本零迁移）；新写法是可选的；
- 最小版本已做一层 all；`any` 与前置条件入组合已落地；组合器嵌套已落地（多层，≤ 8 层，见 §9）。

##### 3.2 朝向条件：视线角度范围

- 条件参数 = 两对角：`(yaw1, pitch1)` 与 `(yaw2, pitch2)`；
- 判定对象 = **玩家视线**（原版参数），不需要目标、不需要射线命中——与 `observation`（看向具体目标）互补；
- 可以单独作为触发器使用（只判朝向），也可以放进条件列表与其他条件组合。

##### 3.3 实现落点（方向）

- 复用现有触发器引擎：类型注册、求值器、轮询、触发状态机（`on_enter` / `exit_buffer` / `repeatable`）都不新造；
- 组合器 = 一个“依次求值：`all_of` 全真才真 / `any` 任一即真”的条件求值器；朝向条件 = 一个新的求值器；
- 轮询频率沿用现有机制；单列可配（按触发器 / 按脚本）见 §4。

##### 3.4 动作面（方向）

触发器的动作收敛为两类：

- **控制脚本**：播放 / 停止 / 暂停某个脚本；
- **回报等待点**：给等待点（[等待点](../_archive/wait-point-track.md)）回报事件。

命令与声音**不属于动作面**——它们归脚本自己的轨道（命令 = EVENT 轨；声音 = AUDIO 轨）。现状代码里的 `ExecuteCommandAction` / `PlaySoundAction` 按此方向收敛。

---

#### 4. 边界与细节

1. **yaw 环绕**：已实现“从 yaw1 顺时针扫到 yaw2”——跨越 ±180° 自然环绕；起止相同 = 整圈 / 不限制。
2. **边界包含性**：端点包含；`pitch` 端点由校验限制在 ±90。
3. **与 `on_enter` / `exit_buffer` 的关系**：组合条件下的“进入触发一次”同样依赖触发状态机——状态机已修复（fb-05），组合条件直接沿用。
4. **轮询频率**：已定单列（按触发器 / 按脚本可配）；**已落地（2026-10-14，C3，见 §11）**：生效链 = 触发器级 `poll_interval` ?? 脚本级 `meta.trigger_poll_interval` ?? 类型级默认（tick，1~600）。
5. **求值顺序与短路**：已实现按列表顺序求值、任一不满足立即短路；按代价排序（先便宜后贵）未做。
6. **与 `observation` 的分工**：`observation` = “看向某个具体目标（实体 / 方块，带距离）”；朝向条件 = “视线落在某角度范围”；两者都保留，互不替代。
7. **校验与报错**：已实现结构校验（条件列表非空、子类型已知且为轮询类 / 已注册前置条件 / 另一个组合器、组合器嵌套 ≤ 8 层（含顶层，见 §9）、朝向端点合法、组合内前置条件的 `script` 字段与跨脚本引用）；旧脚本零回归。
8. **编辑器与文档**：`TRIGGER_TYPES.md` / `AI_SCRIPTING_GUIDE.md` 已同步；游戏内编辑器与 WebUI 的触发器编辑（组合条件增删、朝向范围编辑）未跟进。

---

#### 5. 已确认 / 待定

**已确认**

- 三个需求（条件组合、朝向范围、检测频率）都做。
- 条件模型：单一条件 + 组合器（`any` / `all_of`，多元、不设上限）+ 前置条件（锁存）；**组合器统一用于跨条件与触发器内部判定**；前置条件归类为触发条件的一种。
- 动作面：只有两类——**控制脚本**（播放 / 停止 / 暂停）与**回报等待点**；命令 / 声音归脚本轨道。
- 朝向范围按**玩家原版视线参数**（yaw ±180、pitch ±90）。
- 最小版本：只做 `all_of`、只做玩家、JSON 手写 + 服务端生效 + 校验通过；编辑器后置。
- 现有触发器格式不动，旧脚本零迁移。
- 轮询间隔单列：按触发器 / 按脚本可配（**已落地**，见 §11）。

**待定**

- 条件列表的字段形态与嵌套方式（any 纳入体系、最小版本后放开；嵌套执行时定 —— **已定**：多层嵌套，≤ 8 层含顶层，见 §9）。
- 前置条件 ↔ **脚本状态**：`script_played` / `script_started` / `script_completed` 的判定口径（"播完"是否含跳过 / 打断）、状态归属（谁记）、并行播放下的口径（按玩家 × 脚本 id，还是按实例）。→ **部分**：口径仍为「任何退出原因都算」（现状保持）；**状态归属 / 并行口径归 P1-impl**（`state-tracking.md` 事实层，未落地）。
- 取反（not）是否纳入。→ 仍未定（未纳入）。
- 编辑器支持的排期。→ Z 轮。

---

#### 6. 验收标准（草案）

- [ ] 条件列表：全部条件满足才触发；任一不满足不触发（含轮询下的连续判定）
- [ ] 朝向范围：区间判定正确（含跨 ±180° 区间、端点边界、pitch 上下限）
- [ ] 朝向条件可单独使用，也可与位置等条件组合
- [ ] 旧格式脚本行为无变化（零回归）
- [ ] `/icinematics validate` 能校验新写法并给出可读报错
- [ ] 最小版本全程 JSON 手写可用（不依赖编辑器）

---

#### 7. 最小版本落地情况

| # | 改动 | 状态 |
|---|---|---|
| 1 | `Evaluators` 新增 `evaluateFacing` + `evaluateAllOf` | 已落地 |
| 2 | `ImmersiveCinematics.registerTriggerTypes()` 注册 `facing`、`all_of` | 已落地（间隔 5 tick） |
| 3 | `ScriptValidator` 加结构校验 | 已落地 |
| 4 | 解析层不改（`conditions` 已是通用嵌套结构） | 已落地 |
| 5 | 文档 `TRIGGER_TYPES.md` 加两节 | 已落地（`AI_SCRIPTING_GUIDE.md` 同批） |
| 6 | 测试脚本 `cinematics/tests/trigger/test_trigger_facing.json`、`test_trigger_all_of.json` | 已落地 |
| 7 | 编辑器 UI、schema 控件；any、嵌套、前置入组合 | 部分落地：`any` + 前置入组合已落地（见 §8）；组合器嵌套已落地（见 §9）；编辑器 UI / schema 控件未做 |

**实现约定（已落地）**

- 条件字段：`facing` = `yaw1 / pitch1 / yaw2 / pitch2`；组合器（`all_of` / `any`）= `list`（数组，元素 = `{ type, conditions }`）；
- yaw 区间语义：**从 yaw1 顺时针扫到 yaw2**（跨越 ±180° 自然环绕；起止相同 = 整圈 / 不限制）；pitch 区间取 min / max；
- 组合器子条件**当前允许三类**：轮询类触发器类型（location / facing / observation / structure / biome / xp / inventory / gamestage / dimension，瞬时语义）、已注册前置条件（script_played / script_started / script_completed，锁存语义）与另一个组合器（嵌套，≤ 8 层含顶层，见 §9）；事件类（advancement / entity_kill / item_* 等）当前仍拒绝。2026-10-30 目标口径：带时间窗/时间戳的事件类条件可进入组合；无窗口仍拒绝，时间窗归 H7/H8。
- 前置条件（requires 类）是**锁存语义**（发生过即真），已可纳入组合（见 §8）；`requires` 现有字段不动；
- schema：`facing` 已登记字段（yaw1 / pitch1 / yaw2 / pitch2）；`all_of` / `any` 只登记类型，无专用控件（手写 JSON）。

**后续批次**：~~新增类型 `death`（玩家死亡）~~ **已落地（2026-10-14，C2）**：事件驱动，场景条件（dimension / biome / position+radius / corner1+corner2）按死亡时刻记录判定；求值器 `Evaluators.evaluateDeath` + 记录 `Evaluators.DeathTracker`，事件入口 `ServerEventHandler.onLivingDeath`（与击杀者分支独立，PvP 双触发）；无死因字段、无重生回调；见 `docs/TRIGGER_TYPES.md` §28。其余待做：编辑器 UI（游戏内 + WebUI）——~~组合器嵌套（多层）~~ 见 §9、~~检测频率可配（按触发器 / 按脚本）~~ 见 §11，均已落地。

**验收标准**：validate 通过；facing 可单独用；all_of 只在全真时触发（"先 A 后 B"不误判）；any 任一满足即触发；旧脚本零回归。

---

#### 8. any + 前置条件入组合（2026-10-07 落地）

对应本文档 §1 / §3.1 的剩余批次前两项（`death` 类型与检测频率可配不在本批）。

**条件模型**

- `any`：与 `all_of` **同构**——同一份 `list` 结构（元素 = `{ type, conditions }`）、同一套子条件求值（`Evaluators.evaluateCombinationChild`），只有判定从"全真"改为"任一真"，命中即短路；
- 前置条件作为**条件类型**进列表：与触发器一致写在子条件的 `conditions` 里（`{ "type": "script_played", "conditions": { "script": "id" } }`），`any` / `all_of` 均可引用；**锁存语义**（发生过即真）与轮询条件的瞬时语义在同一轮求值中分清：
  - 同一轮里所有轮询子条件都用**同一时刻**的玩家状态求值 → "先 A 后 B"不误判；
  - 前置条件解锁后持续为真：放进 `all_of` 时其余轮询项仍需此刻满足；放进 `any` 时该子条件独立为真、下一轮轮询即命中；
- **禁嵌套**（**§9 起已放开**：多层嵌套 ≤ 8 层，含顶层）：`any` / `all_of` 都不允许出现在对方（或自身）的 list 里——校验层拒绝，运行时同样拒绝（`evaluateCombinationChild` 直接判 false，防止绕过校验递归求值）。

**落地清单**

| # | 改动 | 位置 |
|---|---|---|
| 1 | `Evaluators.evaluateAny` + 共用子条件求值 `evaluateCombinationChild` / `combinationList`（`evaluateAllOf` 同步改为共用） | `trigger/server/evaluator/Evaluators.java` |
| 2 | 注册 `any`（POLLING，5 tick） | `ImmersiveCinematics.registerTriggerTypes()` |
| 3 | 校验：`validateCombinationConditions`（any/all_of 共用，含嵌套拒绝、前置条件子项校验）+ `validatePrerequisiteRef`（requires 与组合子条件共用） | `script/ScriptValidator.java` |
| 4 | schema：`typeList()` 与 `all()` 登记 `any`（与 `all_of` 共用空字段表） | `script/schema/TriggerSchemas.java` |
| 5 | 文档：`TRIGGER_TYPES.md` §25 更新 + 新增 §26 `any` / §27 组合器里的前置条件；`AI_SCRIPTING_GUIDE.md` 表格加 `any` 行 + 组合器子条件说明 | `docs/` |
| 6 | 测试脚本：`test_trigger_any.json`（any[location, facing]）、`test_trigger_prereq_combo.json`（any[script_played, facing] + all_of[script_started, location]） | `cinematics/tests/trigger/` |

**现行事实**：`registerTriggerTypes` 注册 **27** 个类型；组合器子条件可含前置条件（不限轮询类）；`ScriptValidator` 用 `validateCombinationConditions`。

**验收**：validate 通过；`any` 任一满足即触发；`all_of` 仍只在全真时触发（"先 A 后 B"不误判）；组合内前置条件锁存不误判；旧脚本零回归。

---

#### 9. 组合器嵌套（多层 all_of / any）（2026-10-14 落地，C1）

对应 §2 缺口①（§1 条件模型的剩余批次之一）：`all_of` / `any` 的**嵌套**放开为多层。

**语义（沿用现状，不引入窗口语义）**

- 组合器统一：`all_of` = 全真、`any` = 任一真，同一份 `list` 结构、同一套子条件求值——嵌套层与顶层走**同一个内核**（`Evaluators.evaluateCombination`，顶层 depth = 1）；
- 每层内部「同一时刻」求值 + 短路（`all_of` 首个不满足 / `any` 首个满足即停），嵌套子组合器同样短路；
- 子条件类别不变：轮询类触发器（瞬时语义）、已注册前置条件（锁存语义），以及**另一个组合器**；事件类仍拒绝（2026-10-30 更新：带时间窗/时间戳的事件类可进入组合，无窗口仍拒绝）；
- **深度上限 = 8**（含顶层组合器自身为第 1 层）：单源常量 `Evaluators.MAX_COMBINATION_DEPTH`，校验侧引用同一常量——超深 validator 报「嵌套超过 8 层」，运行时该子条件按不满足处理（防御，正常路径校验已拦）；
- **不做窗口语义**：`P3-impl §5.5` 的 all_of / any 窗口版是另一条线（`trigger-continuity.md`），本批只放开嵌套结构；窗口落地时在同一内核上统一考虑。2026-10-30 更新：事件类条件的时间窗/时间戳归 H7/H8；无窗口事件类仍拒绝。

**落地清单**

| # | 改动 | 位置 |
|---|---|---|
| 1 | `MAX_COMBINATION_DEPTH = 8` + 统一内核 `evaluateCombination`（`evaluateAllOf` / `evaluateAny` 变薄包装，depth = 1 起）；`evaluateCombinationChild` 对 `all_of` / `any` 递归求值其 `conditions` | `trigger/server/evaluator/Evaluators.java` |
| 2 | `validateCombinationConditions` 改为带 depth 的递归（每层沿用既有检查：list 非空 / 子 type 已知 / 事件类拒绝 / 前置条件引用校验；超深报错并停止下钻） | `script/ScriptValidator.java` |
| 3 | 测试脚本：`test_trigger_nested.json`（两层 `all_of[location, any[facing, script_played]]` + 三层 `any[all_of[location, facing], dimension]`） | `cinematics/tests/trigger/` |
| 4 | 文档：`TRIGGER_TYPES.md` §25 / §26（嵌套规则 + 嵌套示例）、`AI_SCRIPTING_GUIDE.md` 组合器说明 | `docs/` |

**验收**：validate 通过（新脚本 0 issue、旧脚本零回归）；两层 / 三层嵌套求值与手算一致；8 层通过、9 层报错且求值按 false；无时间窗的事件类子条件在嵌套层内仍被拒（带时间窗的待 H7/H8 落地后按窗口求值）。

---

#### 10. 注册面收敛（2026-10-14 用户口径，C-reg）

> 用户口径：「触发器那边也写的容易注册一些。毕竟触发器已经多了很多种类了，可以看见的未来是肯定不止这些触发器类型的」。
> 目标：**加一个新触发器类型 = 动最少的文件**——把「校验分支」这处从必改清单里去掉。

**手段**：`ScriptValidator.validateTriggerConditions` 的类型分发末尾加 schema 通用层（`validateConditionsBySchema`）：
按 `TriggerSchemas.all()` 里该类型的字段表逐字段校验 **必填缺失 / 类型不符 / enum 越值 / 数值非有限**
（`FieldDef` 的 type / required / enumValues 元数据），新普通类型只要在 schema 登记字段，校验自动生效。

**落地清单**

| # | 改动 | 位置 |
|---|---|---|
| 1 | 新增 `validateConditionsBySchema`（schema 驱动通用层）+ 辅助 `enumLabel` / `isString` / `isInt32`；类型分发 `else` 分支接入 | `script/ScriptValidator.java` |
| 2 | 通用层单源：`TriggerSchemas.all()` 改为构建一次后缓存（只读共享表），类 javadoc 写明「新增类型登记 typeList() + all() 即可」 | `script/schema/TriggerSchemas.java` |
| 3 | schema 修正：`inventory.items` 声明由 `map` 改为 `array`（求值器要求 `isJsonArray()`、文档写 `string[]`、既有脚本用的是数组——原声明是笔误） | `script/schema/TriggerSchemas.java` |
| 4 | 文档：`docs/modules/trigger.md` 新增「新增触发器类型的注册清单」（① 求值器 ② 注册行 ③ 事件入口 ④ schema ⑤ 文档；并写明普通类型不需要动 ScriptValidator） | `docs/modules/trigger.md` |

**仍走专用分支的类型（schema 表达不了，逐个注明原因）**

| 类型 | 保留的校验 | 为什么 schema 表达不了 |
|---|---|---|
| `facing` | yaw1/pitch1/yaw2/pitch2 为数字 + pitch 端点 ±90 / 有限 | 四字段是两对**配对端点**，且 pitch 要按原版范围钳制；报错文案把四字段作为一组说明 |
| `all_of` / `any` | `list` 结构、子项 `{ type, conditions }`、子类型（轮询类 / 前置条件 / 嵌套组合器）、嵌套深度 | `list` 元素是**异构结构**（触发器 / 前置条件 / 组合器三类语义），schema 只登记类型、无字段 |
| `death` | 场景组字段类型（dimension / biome / position / radius / corner1 / corner2） | 场景组字段**互相配合**（position+radius 与 corner1+corner2 二选一），且报错文案带「按死亡时刻记录判定」的场景语义，通用文案无法逐字节复现 |

**行为边界**：既有类型（含 facing / all_of / any / death）的校验结果逐字节不变；通用层只覆盖此前**没有任何校验**
的普通类型 conditions 字段（新增报错，不修改既有文案）——「未知字段静默」策略不变（不做未知字段拒绝）。

**验收**（无头 validator，仓库外脚手架 `E:/tmp/icv/Validate.java`）

- `sh gradlew compileJava`：BUILD SUCCESSFUL。
- 153 脚本（`cinematics/tests` + `cinematics/release`）改前 / 改后 stdout：`cmp` 逐字节一致（0 差异）。
- 负例语料 16 条（`E:/tmp/creg/negative/`，覆盖 facing 缺失 / pitch 超范围 / pitch 非有限 / death 场景组类型 /
  组合器 list 结构·子类型·嵌套深度·前置条件 / 未知类型 / conditions 形状 / exit_buffer·on_enter 提示 / requires）：
  改前 / 改后 `cmp` 逐字节一致。
- 新类型演练探针（`E:/tmp/creg/NewTypeProbe.java`）：探针内注册假类型 + 反射往 `TriggerSchemas.all()` 声明字段，
  必填 / 类型 / enum / 非有限数值错误全部由通用层自动报出（11 项断言全过，校验器源码无该类型名）。

---

#### 11. 检测频率可配（按触发器 / 按脚本）（2026-10-14 落地，C3）

对应 §2 缺口②（§1 需求 3）：轮询间隔从"类型级固化"升级为 **触发器级 ?? 脚本级 ?? 类型级默认**；EVENT_DRIVEN 不受影响。

**语义（已定）**

- 字段形态：触发器级 `triggers[].poll_interval`、脚本级 `meta.trigger_poll_interval`（int，单位 **tick**，范围 `1`~`600`，与 Forge `defineInRange(…, 1, 600)` 对齐）；
- 生效链 = 触发器级 `?? 脚本级 `?? 类型级（注册时读 Config 的默认值）；**只对 POLLING 类型生效**；EVENT_DRIVEN 触发器写该字段 = validator 提示无效（照 `exit_buffer` 无效提示写法）；
- 单位 = tick（与 Config 侧一致）；`delay` 用秒是既有事实，本批不统一它；
- 生效时机：JSON 字段经 `/icinematics reload` 生效（reload → registerAllTriggers → rebuildIndex 既有链路）；全局 Config 改动仍需重启（不在本批）；
- 防御：解析层非法值（越界 / 非整数）→ 告警 + 回落 null（照 `skip_vote_ratio` 范式）；引擎侧 `Math.max(1, interval)` 兜底防 `%0`（`TriggerType.resolvePollInterval`）。

**落地清单**

| # | 改动 | 位置 |
|---|---|---|
| 1 | `POLL_INTERVAL_MIN/MAX` + `resolvePollInterval`（生效链 + 钳 ≥ 1；事件驱动恒 0） | `trigger/server/TriggerType.java` |
| 2 | 触发器级 `pollInterval` 字段 + 新构造重载（旧重载缺省 = 类型级值） | `script/TriggerDefinition.java`、`trigger/server/TriggerRegistration.java` |
| 3 | 脚本级 `triggerPollInterval` 字段 + accessor | `script/ScriptMeta.java` |
| 4 | 解析 `poll_interval` / `trigger_poll_interval`（`optPollInterval`：非整数 / 越界 → 告警 + null） | `script/ScriptParser.java` |
| 5 | 注册时算生效间隔并传入注册项；分桶键改用 `getEffectivePollInterval()` | `script/ScriptManager.java`、`trigger/server/TriggerEngine.java` |
| 6 | 校验：`checkPollIntervalField`（1~600 整数，触发器级 + meta 级同口径）+ 事件驱动类型上的无效提示 | `script/ScriptValidator.java` |
| 7 | 测试脚本 `test_trigger_poll_interval.json`；负例与分桶探针放仓库外 `E:/tmp/c3/` | `cinematics/tests/trigger/` |
| 8 | 文档：`TRIGGER_TYPES.md`（通用字段表 +「检测频率可配」小节 + 11 处轮询散文）、`AI_SCRIPTING_GUIDE.md` §3.6、`docs/modules/trigger.md` | `docs/` |

**验收**：compileJava 通过；154 脚本语料 2 of 154 FAIL（基线不变，新 fixture `[OK]`）；分桶探针三触发器 → `pollBuckets` 键集合 `{2, 10, 20}`、EVENT_DRIVEN 只进 `eventIndex`、类型级默认 0 被钳成桶键 1；reload 重建后新间隔生效（`{2, 10}` → `{7, 30}`）。

**不做**（见任务书 §4）：dwell / window 语义原语（P3-impl 线）、全局 Config 热生效、schema / 编辑器暴露、`delay` 单位统一。

---

#### 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **多人服下触发器播放的结构 / 方块来源不可解析**：推送前的结构 / 方块替换只覆盖 `look_at_target_structure` 与 `position.relative_origin`；触发器路径（`StartPlaybackAction`）直接发 rawJson、零替换 → 触发器播放时结构 / 方块来源全部不可解析（单人服不受影响）。**✅ 已修复（2026-10-07）**：替换逻辑提取为共享 `util/ScriptStructureResolver.resolveTargets(json, level, origin)`，`StartPlaybackAction.execute` 在发送前按触发者位置应用同一套替换（`/icinematics play` 路径同步切换到该 helper，行为不变）。

---

#### 事实核查（2026-10-07）

> 依据：本仓库工作区源码（`common/src/main/java/...`、`forge/src/...`、`fabric/src/...`）、MC 1.20.1 源码（`build/mc-sources/net/minecraft/...` 与 loom `*-sources.jar`）。本次核查以工作区文件内容为准；未发现需按 `git log` 时间裁决的文档/代码冲突（文末另附相关文件最后提交时间备查）。

##### ① 核实为真的断言

| 断言 | 证据 |
|---|---|
| §7#1 `Evaluators` 新增 `evaluateFacing` + `evaluateAllOf` | `trigger/server/evaluator/Evaluators.java:518`（`evaluateFacing`）、`:546`（`evaluateAllOf`） |
| §7#2 `registerTriggerTypes` 注册 `facing`、`all_of`，间隔 5 tick | `ImmersiveCinematics.java:50` `new TriggerType("facing", POLLING, 5, Evaluators::evaluateFacing)`、`:51` `("all_of", POLLING, 5, Evaluators::evaluateAllOf)` |
| §7#3 `ScriptValidator` 加结构校验 | `script/ScriptValidator.java:436` `validateTriggerConditions` → `:474` `validateFacingConditions`（yaw1/pitch1/yaw2/pitch2 必填为数字；pitch 端点限 -90~90）、`:491` `validateAllOfConditions`（list 必须非空数组、元素为 `{type,conditions}`、子类型必须已注册且 `ListenStrategy.POLLING`、禁止嵌套 `all_of`）；`:466` 对 facing/all_of 上无效的 `exit_buffer` 给出告警 |
| §7#4 解析层不改（conditions 已是通用嵌套结构） | `script/ScriptParser.parseTriggerDefinition`（:554 起）对 conditions 走通用 `parseDataMap`，无类型分支 |
| §7#5 `TRIGGER_TYPES.md` 加两节 + `AI_SCRIPTING_GUIDE.md` 同批 | `docs/TRIGGER_TYPES.md:604`（第 24 节 `facing`）、`:632`（第 25 节 `all_of`）；`docs/AI_SCRIPTING_GUIDE.md:268-269` 表格含 `facing` / `all_of` |
| §7#6 测试脚本存在 | `cinematics/tests/trigger/test_trigger_facing.json`（facing 单条件）、`cinematics/tests/trigger/test_trigger_all_of.json`（all_of[location,facing]）均存在 |
| §7 实现约定：facing 字段 `yaw1/pitch1/yaw2/pitch2` | `Evaluators.java:519-530` |
| §7 实现约定：all_of = `list`，元素 `{type, conditions}` | `Evaluators.java:547-559` |
| §7 实现约定：all_of 子条件限轮询类 | 校验器 `ScriptValidator.java:523` 判 `strategy == POLLING`；允许集恰为 location/facing/observation/structure/biome/xp/inventory/gamestage/dimension（与注册表 POLLING 类型一致） |
| §7 实现约定：yaw 从 yaw1 顺时针扫到 yaw2；起止相同 = 整圈 | `Evaluators.java:531-536`（`span = wrapDegrees(yaw2-yaw1)`；`span==0 → true`；`span>0` 取 `0 ≤ delta ≤ span`） |
| §4.2 端点包含 + pitch 限 ±90 | 端点包含：`Evaluators.java:526`（pitch 用 `< min` / `> max` 排除）、`:534`（`delta >= 0 && delta <= span`）；pitch 限值由 `ScriptValidator.java:483-486` 校验 |
| §4.4 当前实现仍为固定 5 tick，单列可配未做 | facing/all_of 在 `ImmersiveCinematics.java:50-51` 硬编码 5；`Config` 无 facing/all_of 专用间隔字段 |
| §2 "25 种触发器" | `registerTriggerTypes` 共注册 25 个类型（`ImmersiveCinematics.java:24-51`） |
| §2 `requires` = 全部满足，内置 script_played/script_started/script_completed | `TriggerEngine.prerequisitesMet`（:185-189）逐条 `PrerequisiteRegistry.evaluate`，任一 false 即 false；`prereq/BuiltinPrerequisites.java:17/23/29` 注册三者 |
| §2 同一触发器内部多字段判定目前写死 AND（如 entity_kill 场景条件） | `Evaluators.matchesScene`（:141）逐字段 `return false`；`evaluateXp`（:256-259）level/total 全判 |
| §2 on_enter / exit_buffer / repeatable 状态机现状 | `TriggerEngine.checkEnterState`（:253-274）+ `enterStates`（:30）；字段定义见 `ScriptParser.java:558-561`、`TriggerRegistration.java:71-75`；fb-05 状态标 ✅ 已修复（`feedback-0.3.5/05-on-enter-not-repeatable.md` 头部） |
| 已知缺陷：触发器路径零替换 | `StartPlaybackAction.sendTo`（:64）`S2CPlayScriptPacket.send(p, script.getRawJson())`；rawJson 由 `ScriptManager.java:75` `script.setRawJson(content)` 原样存入，无替换 |

##### ② 已修正的断言

- 无。本次核查未发现与工作区源码不一致的事实性断言，未改动原文事实描述。

##### ③ 补全的信息

- **`triggerPollInterval_location` 的来源**：它是 **Forge 配置文件键**（`forge/.../ForgeConfig.java:102` `.defineInRange("triggerPollInterval_location", 20, 1, 600)`）；Fabric 侧键为 camelCase `triggerPollIntervalLocation`（`fabric/.../FabricConfig.java:57`、`:120`）；Java 静态字段为 `Config.triggerPollIntervalLocation`（`Config.java:45`，默认 20）。且该间隔**不止 location 使用**：`xp`、`dimension` 两类型也复用 `Config.triggerPollIntervalLocation`（`ImmersiveCinematics.java:42`、`:44`）。其余：`triggerPollIntervalBiome`（默认 40）、`...Inventory`/`...Structure`/`...Gamestage`（默认 20）；**无** xp/dimension/observation/facing/all_of 的专用间隔字段。
- **facing 判定的角度获取方式**：直接读**玩家实体自身** `player.getXRot()` / `player.getYRot()`（`Evaluators.java:523`、`:528`），**不是**用 `getViewVector()` 反算；yaw 经 `Mth.wrapDegrees` 归一。
- **MC 原版朝向语义**（`build/mc-sources/net/minecraft/world/entity/Entity.java`）：`getYRot()`（:3321）/`getXRot()`（:3337）返回字段原值；`getViewVector`（:1483）→ `calculateViewVector(xRot,yRot)`（:1495）返回 `(sin(-yaw)·cos(pitch), -sin(pitch), cos(-yaw)·cos(pitch))` ⇒ yaw 0 指向 +Z（南），yaw 增大转向 -X（西），俯视顺时针；pitch 正指向 -Y（向下）。yaw 存值经 `% 360`（`setRot` :385、`absMoveTo` :1356），可能超出 [-180,180]；pitch 经 `Mth.clamp(..., -90, 90)`（`absMoveTo` :1357、`turn` :411），服务端移动包经 `ServerGamePacketListenerImpl` → `player.absMoveTo(...)`（:849），故服务端 `getXRot()` ∈ [-90,90]。`Mth.wrapDegrees`（`util/Mth.java:198`）把角度归一到 [-180,180)。⇒ §1/§4 的 "yaw ∈ [-180,180]（0=南，正=顺时针）、pitch ∈ [-90,90]（正=向下）" 与原版一致（yaw 区间为求值器 wrap 后的有效区间）。
- **schema 侧**：`script/schema/TriggerSchemas.java` 的 `typeList()`（:25-47）已含 `facing`/`all_of`；`facing()`（:239）登记 yaw1/pitch1/yaw2/pitch2 四个 float 字段；`allOf()`（:249）返回空 map（只登记类型、无专用控件）——与 §7 "schema" 约定一致。
- **单人服不受影响的机制**：客户端兜底 `CameraTrackPlayer.resolveStructurePos`（:555）/`resolveBlockPos`（:595）通过 `mc.getSingleplayerServer()` 定位；多人（返回 null）无法解析。`/icinematics play` 路径的服务端替换在 `CinematicCommand.resolveStructureTargets`（:165），覆盖 `look_at_target_structure`（:183）与 `position.relative_origin`（:189，含 `block:` 写法 :241）。

##### ④ 无法核实的断言

- 无。本文所有事实性断言均已在工作区源码 / MC 源码中核实。

##### 附：相关文件最后提交时间（冲突裁决用；本次无冲突）

| 文件 | `git log -1 --format=%cI` |
|---|---|
| `plans/0.3.6/trigger-conditions.md` | 2026-10-06T21:16:28+08:00 |
| `.../trigger/server/evaluator/Evaluators.java` | 2026-09-29T14:12:03+08:00（注：facing/all_of 相关改动在工作区中已存在） |
| `docs/TRIGGER_TYPES.md` | 2026-10-05T17:42:24+08:00 |
