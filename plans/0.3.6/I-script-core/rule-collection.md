# 规则集合：定义、绑定、激活与多入口

> 状态：已实现（I4-2 / I4-3 关闭）
> 能力清单：顶层 `rules`（定义表）+ `rule_bindings`（绑定表）；绑定引用定义 / 目标片段 / 必选时间门；同一规则多条绑定 = 多入口，各自独立激活；与旧 `clip.rules` 同脚本互斥
> 范围：脚本级规则集合的归属、条目形态（定义 / 绑定 / 激活 / 事实 / 调度）、多条目与多入口、身份与引用
> 关联：`rule-system.md`（索引与跨篇口径）、`rule-time-gate.md`、`rule-suspension.md`、`rule-facts-scheduling.md`、`rule-exits.md`、`rule-implementation.md`、`object-model.md`、`schema-and-types.md`

## 一、摘要

规则是脚本级独立集合中的声明，与路径集合并列，不是片段元数据。集合分五层：不可变的**规则定义**、声明式的**规则绑定**、运行期的**规则激活**、本实例内的**事实记录**与**动作调度**。

绑定是唯一的定位入口：它引用一个定义、一个目标片段和一个必选时间门，把规则定位到目标片段的某次执行及其局部时间。一个定义可以被多条绑定复用；复用只共享不可变定义，不共享任何运行态。

**数量口径**：规则条目、同一规则的绑定都不设数量上限——「规则 A 做什么、规则 B 触发什么」这类编排要能容纳很多条，而不是一条规则塞满所有条件。

## 二、为什么做

- v1 把规则放在 `clip.rules`，按片段活跃窗建槽；规则生命周期被窗口截断，跨片段观察与实例级等待不可达。
- 定义、激活位置和运行态压在同一层：同一规则想做多个独立入口时，没有承载位置，只能复制整条规则或互相覆盖。
- 片段重排后，按下标定位的规则会静默指错，作者看不出绑定的是哪一段。

## 三、方案

### 3.1 归属

- 规则集合是**脚本级**的，与 `meta`、`timeline` 并列；不占用 `objects` 的路径键空间，也不是新轨道。
- 片段仍是执行与编排单元；集合条目只通过引用定位片段内容，不改变片段内部对象与关键帧的归属，不复制关键帧。
- 集合在解析期建成，播放期只读；脚本替换 = 重建集合，旧激活、事实、等待与待执行动作一并作废。

### 3.2 五层条目

- **规则定义（RuleDefinition）**：不可变的内层条件树、动作与策略；可被多个绑定或多个规则条目引用，不持有播放状态，也不绑定某个片段。
- **规则绑定（RuleBinding）**：集合条目，声明定义引用、目标片段引用、必选外层时间门与自身身份；可另声明判定阶段控制（实例级持有 / 非阻塞观察）与动作调度目标。绑定是静态声明，不持有计时、成功或待执行状态。
- **规则激活（RuleActivation）**：运行期记录，身份 =（播放实例、绑定、片段执行）。在绑定所属片段执行进入时建立，保存观察、判定、成功事实、等待与待执行动作状态；不是 schema 条目，不写入脚本。
- **事实记录（FactRecord）**：本实例内可被后续规则引用的判定／执行事实，定位到实例与相关绑定／执行，不使用定义上的共享布尔。
- **动作调度（ActionSchedule）**：判定成功后等待释放条件的待提交动作；与激活分开记账，不与原始条件重新判定混同。

### 3.3 多条目与多入口

- 同一片段可以挂多条规则；同一条规则可以有多个绑定；每条绑定是独立入口。
- 每条绑定各自的时间门、执行身份、计时、成功事实与待执行动作分别记账。不得把多个入口合并成一条槽，也不得让一个绑定的成立遮蔽其它绑定。
- 后一次执行的激活不重置、不取消前一次；实例结束、引擎重建、停播时全部作废。
- 同一脚本的多个实例各自一份运行态，不共享。

### 3.4 身份与引用（已定稿：I4-1 / I4-2）

- **片段**：可选 `id`，`^[A-Za-z0-9_]{1,64}$`，脚本内唯一（内联片段与脚本级独立片段共用同一命名空间，重复 → 加载期错误）。被规则绑定、路径条目、分支目标（`goto_clip`）、出口目标（`exit.target`）引用的片段必须写 `id`；缺 id → 加载期错误「片段被 <宿主> 引用但没有 id」。未被引用的片段可匿名。
- **定义表 `rules[]`**：`id` 必填且脚本内唯一；`condition` 必填对象（内层条件树，不得含时间门语义）；`actions` 必填非空数组；`policy` 可省，字段与 v1 同名同默认（`once=true`、`cooldown=0`、`mode=enter`、`dwell=0`、`timeout=-1`、`on_timeout=[]`）。
- **绑定表 `rule_bindings[]`**：`id` 必填唯一；`rule` 必填（解析到 `rules[].id`）；`clip` 必填（解析到片段 id）；`gate.at` 必填（执行局部秒，有限片段 `0 <= at <= duration`，无限片段 `>= 0`）；`control` = `observe`（缺省，非阻塞）/ `hold`（到门后未成立即持有实例）；`schedule` 可省（= 成功后立即提交）；`exit` 可省（= 用片段默认出口）。
- **动作**：v1 九种动作类型 + 新形态 `goto_clip`（`{"clip":"<id>","entry_time":<s>}`）。新集合收到旧形态 `{"track":...,"at":...}` → 加载期错误。
- 悬空引用、重复 id、非法 id 一律在解析／校验期报错，不静默改指；字段全量口径见 `docs/SCRIPT_FORMAT.md` §5。

## 四、技术细节

- 规则引擎每播放实例一份，编译定义与绑定；加载期按目标片段建 `片段 → 绑定[]` 索引，运行期不扫全集合。
- 运行态归实例：跨片段、跨其它时间入口继续；同一规则的多条激活各自独立记账。
- 判定源、条件树与时间门语义归 `rule-time-gate.md`；持有归 `rule-suspension.md`；事实与调度归 `rule-facts-scheduling.md`；出口归 `rule-exits.md`。本篇只定集合结构与归属。

## 五、边界

- 本篇不定义时间门何时满足、条件树如何求值、动作何时提交、分支去向如何决定。
- 规则集合与路径集合分开承载，不合并为万能对象；路径见 `path-collection.md`。
- 集合条目不成为新的总时长或完成来源。

## 六、结构评审项（已定稿：I4-1 / I4-2 / I4-3）

| 评审项 | 定稿口径 |
|---|---|
| 稳定片段引用与绑定身份 | 片段 `id` + 绑定 `id`；引用一律用 id 字符串，不用轨道下标 / `start_time`；悬空引用加载期错误。使用边索引见 `clip-identity.md` §七 |
| 五层条目进 schema 的范围 | 进 schema：`rules[]`（定义）、`rule_bindings[]`（绑定）、片段 `exit`、路径 `paths[]`；只在运行态：`RuleActivation`、`RuleFactStore`、`PendingAction`、`SuspensionLedger` |
| 字段名与序列化 | 见 `docs/SCRIPT_FORMAT.md` §5（`rules` / `rule_bindings` / `clips` / `paths` 四键与各条目字段） |
| 事实记录与动作调度的声明形态 | 绑定上的 `schedule` 五形态（`clip_start` / `clip_complete` / `local_time` / `delay` / `fact`）；`control` = `observe` / `hold`；事实由运行态 `RuleFactStore` 记录，不写进脚本 |

## 七、实现结果

状态：已实现（I4-2 / I4-3 关闭）。

- **数据模型**：`script/rule/RuleDefinition`（`id` / `condition` / `actions` / `onTimeout` / `once` / `cooldown` / `mode` / `dwell` / `timeout`）、`RuleBinding`（`id` / `definition` / `target` / `clipId` / `gateTime` / `control` / `schedule` / `exitOverride`）、`RuleCollection`（`EMPTY`、`definitions()`、`definition(id)`、`bindings()`、`binding(id)`、`bindingsFor(Clip)`、`isEmpty()`）、`RuleControl`（`OBSERVE` / `HOLD`）。
- **解析**：`RuleCollectionParser.parse(root, ClipResolver, basePath)`（另有带 `UsageIndex.Builder` 的重载登记使用边）；首个错误即抛 `ScriptParseException`（带路径）。动作类型 = v1 九种 + `goto_clip` 新形态；`goto_clip` 旧形态在解析期拒绝。
- **校验**：`ScriptValidator.checkScriptRuleCollection` 覆盖定义 / 绑定 / 条件树 / 动作四层，错误路径前缀 `rules[i]` / `rule_bindings[i]`；`checkHoldReachability` 给「hold + 无超时 + 等待目标片段之后的片段」警告级 issue（`[警告] ` 前缀，`errorsOf` 过滤后不阻止加载）。
- **互斥**：同一脚本出现 `clip.rules` 与顶层 `rules` → 校验期错误「rules 脚本级规则集合与片段级 clip.rules 不能同时出现」；解析期同一套判定（`ScriptParser.rejectLegacyClipRules`，先于其它字段解析）。旧引擎求值语义不变。
- **运行态**：`script/rule/runtime/RuleActivation` 以（播放实例、绑定、片段执行）为身份；对象在 `RuleRuntime.sync` 时按绑定预分配，`evaluate` 每帧比对目标片段当前执行的 `executionId`，变化即 `beginExecution`（按执行重置基本字段；`cooldown` 按播放时钟跨执行保留）。`bindingsFor(Clip)` 索引在集合内预建（`IdentityHashMap`），运行期不扫全集合。同一规则的多条绑定各自计时 / `once` / 边沿 / 等待 / 待执行，互不遮蔽。
- **脚本级独立片段**：顶层 `clips[]`（`track` + `duration` 必填，禁 `start_time`），与内联片段共用 id 命名空间，可被规则绑定 / 路径 / 分支 / 出口引用。
- **边界**：规则条目不成为总时长或完成来源；激活不跨实例共享，实例终止 / 脚本替换 / 停播回收全部激活、事实与待执行动作。
