# 事实接力与动作调度：判定成功不等于动作完成

> 状态：已实现（I4-5 关闭）
> 能力清单：`RuleFactStore` 四类事实（片段开始 / 片段完成 / 规则成功 / 动作完成）；`rule_fact` 条件引用；`schedule` 五形态（`clip_start` / `clip_complete` / `local_time` / `delay` / `fact`）；`PendingAction` 队列与 `ScriptPlayer.setCompletionDependency` 参与自然完成
> 范围：事实记录与接力、判定成功与动作提交分离、延迟动作、承诺动作参与自然完成
> 关联：`rule-system.md`、`rule-collection.md`、`rule-time-gate.md`、`rule-suspension.md`、`rule-exits.md`、`rule-implementation.md`、`runtime-evaluation.md`、`track-event.md`

## 一、摘要

判定成功是一个可引用的事实，与动作提交完成不同。成功事实可以接力为后续规则或片段的条件；动作提交可以延迟到指定片段开始、执行完成、指定局部时间，或明确的时间／事实／延迟条件。

事实与调度都归播放实例：按绑定／执行定位，不共享定义状态，不建立跨实例事实总线；与服务端触发器事实层（`H-other/state-tracking.md`）分开，不合并。

## 二、为什么做

- v1 判定成功即提交动作：无法先挂起实例等待，也无法把成功事实交给后续规则或片段。
- 规则结果不能作为条件，片段开始、片段完成、规则成功、规则动作完成没有被区分为不同事实。
- 动作提交依赖调用栈与当前片段窗口，跨片段编排只能靠堆片段或 EVENT。

## 三、方案

### 3.1 事实

- **可区分的事实**：片段开始、片段完成、规则判定成功、规则动作完成。四者不得统称“实现了”。
- **归属**：事实定位本实例以及相关绑定／执行，不能用脚本定义上的共享布尔串循环或多实例。
- **消费与有效期**：作用域固定为「最近一次执行 / 最近一次激活」（见本篇 §六）；不做无界历史、不跨实例，也不假设任意历史事件永久有效。
- **接力**：某片段的规则成立、某片段开始或执行完成等事实，可以作为后续规则的条件，触发下一片段的执行。
- **边界**：事实接力只在本实例内，不扩大到通用变量语言、跨脚本任意控制或外部注册总线；不建立无界历史。

### 3.2 判定成功与动作提交

- 判定成功与动作提交分开记账；动作按激活与调度身份提交，不按规则合并。
- 已成功事实不因原状态条件之后变假而被隐式抹掉；如需再次确认，应显式声明执行条件，而不是隐含行为。
- 延迟动作不是重新判定原始规则：它消费已记录的判定成功事实与声明的释放条件。

### 3.3 动作调度

- 释放条件：指定片段开始播放、执行完成、到达指定局部时间，或明确的时间条件／延迟。等待开始与等待完成是不同语义，分别提供。
- 动作目标片段可以与时间参照片段不同；目标尚未开始时，不得要求目标先推进自身时间才能满足“等待目标开始”的释放条件。
- 参数调整工件按动作自身目标、声明时效及所属内容或实例生命周期管理，不继承时间门所在片段的窗口。
- 动作执行失败不中断播放；失败静默或限频日志，由动作契约决定。

### 3.4 与自然完成的关系

- 判定成功后已经承诺但尚未释放的后续动作，不等于普通非阻塞观察：主线采样结束不得静默丢弃待执行任务。
- 它作为明确执行依赖参与自然完成，但不要求冻结内容推进（否则等待未来片段会互锁）。
- 普通非阻塞观察不计入自然完成；显式退出／停播按实例终止回收。

## 四、技术细节

- 事实记录与动作调度都是运行态，不序列化；身份按（播放实例、绑定、执行）表达。
- 同一绑定／执行的成功事实与动作完成分别记录；多个待执行动作各自持有释放条件。
- 动作族沿用既有能力：参数调整（相机参数、覆盖层、调色）、分支／跳转、播放控制（暂停、恢复、退出）、媒体（声音、命令）；跨脚本动作与规则自控不属于本集合。动作只作用触发它的实例。
- 动作走动作路径，不复用 EVENT 的 `EventKey`，不改变 EVENT 每实例一次语义与 EVENT 命令协议；规则命令请求的信任边界见 `rule-command-trust.md`。

## 五、边界

- 本篇不定义时间门、挂起持有与出口策略；分别见 `rule-time-gate.md`、`rule-suspension.md`、`rule-exits.md`。
- 事实接力不替代分支出口：正常完成去向与失败 fallback 由 `rule-exits.md` 定义。
- 不把调度写成新的时间轴字段或完成判据。

## 六、结构评审项（已定稿：I4-3 / I4-5）

| 评审项 | 定稿口径 |
|---|---|
| 事实的开始、有效期与跨执行匹配 | 作用域 = 最近一次执行：`clip_fact` 指该片段**当前执行**（活跃时）或**最近一次已完成执行**；`rule_fact` 指该绑定的**最近一次激活**（新激活建立时 `RuleFactStore.clearActivationFacts` 清该绑定的成功 / 动作完成事实）。不做无界历史、不跨实例 |
| 延迟动作的释放条件表达 | 绑定上的 `schedule` 五形态：`{"on":"clip_start","clip":"<id>"}` / `{"on":"clip_complete","clip":"<id>"}` / `{"on":"local_time","clip":"<id>","at":<s>}` / `{"on":"delay","seconds":<s>}` / `{"on":"fact","binding":"<id>","fact":"succeeded"\|"action_completed"}`；缺省 = 成功后立即提交 |
| 待执行动作的声明形态与回收 | 声明只在绑定 `schedule`；运行态是 `PendingAction` 队列（`release` + 调度时读数 `requestedHead` / `requestedReal` / `requestedSeq` / `requestedExecutionId`，用于把释放条件钉在本次承诺上），随实例终止 / 脚本替换 / 停播回收 |

## 七、实现结果

状态：已实现（I4-5 关闭）。

- **事实存储**：`script/rule/runtime/RuleFactStore` 记录 `clip_started` / `clip_completed`（按片段 + `executionId`）、`rule_succeeded` / `action_completed`（按绑定 + 激活序）；`reset()` 在实例终止 / 脚本替换时清空。查询接口 `clipStarted(Clip)` / `clipCompleted(Clip)` / `ruleSucceeded(RuleBinding)` / `actionCompleted(RuleBinding)` 由 `RuleRuntime` 的内部 `RuntimeFactSource` 适配给 `ClientConditions`。
- **成功与提交分离**：判定成立先记 `rule_succeeded`；`RuleRuntime.commit` 按绑定 `schedule` 决定立即执行还是入队 `PendingAction`；动作真正提交后才记 `action_completed`（`RuleActionRunner` 返回后回报）。已成功事实不因原状态条件变假而抹除。
- **调度**：`RuleSchedule.parse` 解析五形态；`RuleRuntime` 每帧检查 `PendingAction.release`：`clip_start` / `clip_complete` 按目标片段执行边界触发，`local_time` 按目标片段当前执行局部时间 `>=`，`delay` 按真实秒（挂起不冻结），`fact` 按另一绑定的最近一次激活事实。`on_timeout` 动作走同一队列，只以 `timedOut` 标记来源。
- **完成依赖**：`PlaybackInstance` 构造时 `player.setCompletionDependency(() -> rules.hasPendingActions())`；待执行动作存在即阻止自然完成，普通 `observe` 不阻止。`hasPendingActions()` / `pendingCount()` 暴露给探针与诊断。
- **边界**：`delay` 用真实秒而非内容局部时间；事实不跨实例、不做无界历史；动作失败不中断播放（限频日志），仍记 `action_completed` 以免完成依赖悬挂。
