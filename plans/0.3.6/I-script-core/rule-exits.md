# 出口与边界：正常完成、失败回退、循环与 EVENT

> 状态：已实现（I4-6 关闭）
> 能力清单：片段 `exit` 默认 + 绑定 `exit` 入口覆盖（`ClipExitPolicy` / `ClipExitResolver` / `ClipExitPolicyParser`）；`PlaybackInstance implements ClipBranchHost`；`goto_clip` 新形态经 `ClipBranchController.launch` 启动分支；失败只恢复执行事实；独立片段可作分支 / 出口目标（I4-6b）
> 范围：正常完成去向、失败 fallback、退出三族、与循环 / EVENT / 播放关系的边界
> 关联：`rule-system.md`、`rule-collection.md`、`rule-time-gate.md`、`rule-facts-scheduling.md`、`rule-implementation.md`、`loop-execution.md`、`track-event.md`、`format-cleanup.md`

## 一、摘要

分支片段与主线片段只有执行来源／编排用途不同：内容模型、对象 schema、关键帧、`duration` 与 `loop` 字段相同。不新增另一种分支内容格式、独立渲染器或独立分支引擎。

正常完成去向由片段定义默认、启动／编排入口显式覆盖；失败 fallback 恒为原主线，两者严格分开。

## 二、正常完成

- 去向：继续、指定目标、返回、结束本执行或本脚本。
- 指定目标 = 片段引用 + 可选入口局部时间；执行起点由跳转/入口给出，不读目标片段的默认放置锚点。目标可以是内联片段或脚本级独立片段，不要求它在任何轨道里（见 `clip-identity.md`）。
- 默认由片段定义给出；启动／编排入口可以显式覆盖；优先级为入口显式策略 > 片段默认策略。
- 正常完成默认与入口覆盖只使用已讨论的继续／指定目标／返回／结束能力，不为此强制全局片段库、sequence 或调用栈改造。
- 不以嵌套调用栈、并行流程、全局 sequence 或独立分支引擎作为正常控制流的前置要求；“返回”按显式目标表达，不以隐式调用栈为必要条件。不能从“分支失败无栈回退”推出“正常控制流必须全部无栈”。
- **`END_EXECUTION` 与 `CONTINUE` 在无调用栈前提下行为等价**：现行实现没有隐式序列 / 调用栈，二者都结束当前分支并回主线（`ClipBranchController` 对两者走同一 `ledger.endBranch(current)` 分支）；差异留给后续序列轮，届时才可能区分“结束本执行”与“继续编排”。`RETURN` 同样按显式目标表达，不以调用栈为必要条件（**I2-B4 关闭**）。

## 三、失败 fallback

- 目标缺失、参数非法、中途被打断等失败，直接回到原主线当前状态继续；fallback 目标恒为原主线。
- 分支无栈、不做嵌套恢复。该保守策略保留，不因需要“正常返回”而删除或贬低。
- 正常完成与失败 fallback 严格分开：失败不得静默执行正常完成去向；正常完成不得被当作失败 fallback。二者在声明、校验与运行日志中可区分。
- **失败只恢复执行事实**：分支失败恢复 launch 时的账本快照（执行身份 / 局部时间 / 完成事实 / 播放头），不重放内容、不回滚音频 / 覆盖层副作用；各层按自身生命周期收口（音频实例、覆盖层、Modifier 工件各自按播放实例 / 片段窗口管理）。规则运行态在失败后作废并按当前脚本重建空运行态，从执行事实重新建立激活（**I2-B8 关闭**）。

## 四、退出三族

- **人为干预**：强退／跳过／投票跳过，按元信息开关。
- **内容播完**：按实际执行事实自然结束；末尾驻留停在最后一帧。
- **声明式退出**：规则动作 `exit`，跳过末尾驻留。

## 五、与循环、EVENT、播放关系的边界

- **循环**：按执行次数与执行状态；`loop_count` 是次数，不是时间周期；每次执行有独立身份与局部时间；规则绑定在每个执行中独立判定时间门与运行态。规则不定义循环次数，循环不定义规则状态。
- **完成判据**：由实际执行事实给出；窗口乘积或全局时长不代替完成；非阻塞观察不阻止自然完成；无限只表示没有自然完成点。自然完成由实际执行路径的剩余执行、明确等待、在跑分支及已承诺未释放的动作调度给出；不由窗口或末端数值比较决定。
- **EVENT**：去重身份仍是（实例、clip 索引、对象路径、事件索引）；规则动作走动作路径，不复用 `EventKey`，不改变 EVENT 每实例一次语义，也不改变 EVENT 命令协议。规则按执行判定不等于 EVENT 重放；规则命令请求的信任边界见 `rule-command-trust.md`。
- **多入口 / 循环不重放**：片段级 `loop`、宏观循环、同一片段的多个入口（多放置 / 规则启动 / `goto_clip` 把播放送回该片段）都**不**重放服务端 EVENT——每条事件每播放实例最多触发一次；客户端 seek（`script_seek`）只重定位事件游标（前跳不补、后跳不重复），不构成重放。作者若需要每圈都执行命令，用脚本级规则集合的时间入口（每次片段执行独立判定）或显式分段，不要依赖 EVENT 循环；与 `track-event.md` §3.2 同口径（**I3-B2 / I3-B3 关闭**）。
- **播放关系**：规则可以暂停或退出本实例，但不起播、不排队、不停止其它脚本；多实例各自判定，不共享运行态。
- **预览实例**：同样跑规则与出口策略，但不参与服务端会话与账本。

## 六、边界

- 本篇不定义时间门、挂起与动作调度；分别见 `rule-time-gate.md`、`rule-suspension.md`、`rule-facts-scheduling.md`。
- `goto_clip` 只换定位范围并保留可达／覆盖约束，不因删除总时长而放行任意空档；目标是稳定片段引用 + 可选入口局部时间，不按轨道下标定位，也不读目标片段的 `start_time` 作为执行起点（见 `clip-identity.md`）。
- 退出不改变渲染、媒体或 EVENT 命令协议；规则命令请求见 `rule-command-trust.md`。

## 七、实现结果

状态：已实现（I4-6 关闭）。

- **声明与解析**：片段 `exit`（内联 / 独立同 schema）与绑定 `exit` 都由 `ClipExitPolicyParser.parse` 解析（`continue` / `goto` / `return` / `end_execution` / `end_script`；`goto` 必须带 `target` 与可选 `entry_time`，其余类型禁止二者；未知键报错）。优先级 = 绑定覆盖 > 片段默认 > `CONTINUE`。
- **解析表**：`ClipExitResolver` 在播放实例内建「片段 → 默认策略」表（`setDefault` / `defaultOf` / `resolve(clip, entryOverride)` / `clear`）；主线上轨道放置的片段完成时不走出口策略（时间轴继续），只有分支入口启动的执行完成时才解析。
- **分支接线**：`PlaybackInstance implements ClipBranchHost`（`now()` → `player.timelineTime()`，`seekTo(t)` → `player.seekTo(t)`，`requestScriptEnd()` → 声明式退出入口，`onBranchStarted` → 诊断日志，`onBranchFailed` → 限频日志 + `rules.reset` / `rules.sync` 重建空运行态）；`FrameDriver` 在规则求值后推进 `branch().poll()`。
- **`goto_clip` 新形态**：`{"clip":"<id>","entry_time":<s>}` → `RuleActionRunner.launchBranch` → `ClipBranchController.launch`；入口局部时间缺省 0。launch 时间不变量 = `max(host.now(), ledger.lastHead())`，`host.now() < lastHead` 时限频告警。旧形态 `{"track":...,"at":...}` 只在新引擎解析期被拒绝，旧 `clip.rules` 引擎仍按旧语义 seek。
- **活跃窗**：`ClipTimeSource.windowStart(Clip)` 提供分支入口活跃窗起点，`ClipModifierGate` 按它判活；分支入口的 `windowStart = executionStart = launch`。
- **独立片段作目标（I4-6b）**：顶层 `clips[]` 的片段可作 `goto_clip` / `exit.goto` / 规则绑定目标。账本为其追加 `markStandalone` 记录（`windowStart = +∞`），未启动时恒不活跃、不计入完成度；被分支启动后按普通执行记账。
- **失败 fallback**：`ClipBranchController.fail` 恢复 launch 时的账本快照（执行身份 / 局部时间 / 完成事实 / 播放头），不重放内容、不回滚音频与覆盖层副作用；规则运行态按当前脚本重建空运行态，从执行事实重新建立激活。
- **退出三族**：人为干预按元信息开关；内容播完按实际执行事实自然结束（末尾驻留停在最后一帧）；声明式 `exit` 动作跳过末尾驻留。
- **未实现（I-5）**：嵌套调用栈 / 并行分支 / 全局片段库仍为非目标，`END_EXECUTION` 与 `CONTINUE` 因此仍行为等价；`total_duration` 仍是必填统计位，删除归 I-5（`format-cleanup.md`）。
- **契约边界**：分支失败不回滚副作用（I2-B8）；独立片段分支 + 无限循环 = 永不自然完成，与 timeline 无限片段同口径；seek 期间不 abort 分支（既有设计）。
