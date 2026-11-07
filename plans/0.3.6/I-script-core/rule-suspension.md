# 实例级挂起：判定等待与持有记账

> 状态：已实现（I4-4 关闭）
> 范围：到门后挂起整个播放实例、持有来源记账、释放条件可达性诊断、与动作延后的区分
> 关联：`rule-system.md`、`rule-collection.md`、`rule-time-gate.md`、`rule-facts-scheduling.md`、`rule-exits.md`、`rule-implementation.md`、`runtime-evaluation.md`、`loop-execution.md`

## 一、摘要

到时间门后，规则可以按声明挂起整个本次播放实例，等待后续判定；普通非阻塞观察仍然可用，不强制所有规则暂停。

挂起是实例级状态：本实例的全部内容推进停住，不是只停一个片段，也不影响其它独立脚本实例。判定等待与动作延后是两个不同阶段——前者可以形成持有，后者只是待执行调度，默认不冻结内容推进。多个持有来源分别记账，最后一个来源解除时内容位置才恢复推进。

## 二、为什么做

- v1 只有 `until` 阻塞等待与 `pause` 动作，二者都要求释放者写在同一个片段里；跨片段等待不可达。
- 没有持有来源记账时，规则暂停与 `pause` 动作互相覆盖，谁先解除不清楚。
- 冻结内容位置后，“等未来某个片段开始”这类条件永远等不到，需要显式可达性诊断。

## 三、方案

### 3.1 持有模型

- **持有**：实例内容位置不推进的状态；按来源记账（判定等待、`pause` 等），最后一个来源解除时恢复推进。
- 判定等待可按声明形成持有；动作延后是待执行调度，不默认形成内容持有（见 `rule-facts-scheduling.md`）。
- 挂起范围是本实例的全部内容推进；其它实例、服务端账本、EVENT 去重不受影响。
- 暂停表示保持内容位置，不是冻结时钟：挂起期间内容局部位置不推进，实时计时（超时、外部延迟）不因此冻结。

### 3.2 释放条件

- 释放条件可以是：条件成立、超时、显式 `resume`、外部事实、本实例未来片段的开始／完成。
- 等待实时时长或外部事实，与等待内容局部位置要区别；计时基准清楚，不允许等待超时本身被同一挂起冻结。
- 等待解除条件必须在挂起下仍可达：校验期按可达性检查声明的恢复、超时、外部事实或未来片段条件，区分“当前持有期间可激活”与“仅脚本后面某处存在”。
- 暂停整个实例后，若条件成功需要转为等待本实例未来片段或未来内容位置，必须在依赖该进度之前释放相应判定持有，或由声明的恢复／启动控制保证目标可达；不能假定冻结的内容位置仍会推进。
- 对明确不可达的持有依赖给诊断，不自动解除、不吞掉规则、不强跳主线以掩盖模型问题。可选外部解除路径与 `all_of`／`any` 必须按真实可达性区分，不把所有等待都判错。

### 3.3 回收

- 实例终止（强退／跳过／停播／重建）是最终出口，回收全部持有。
- 无限内容不自然完成；等待其完成不得由推算时间假冒成功。
- 预览实例同样按本模型挂起，但不参与服务端会话与账本。

## 四、技术细节

- 持有按来源分开记账，解除顺序 = 条件成立、超时、恢复动作、挂起出口、引擎重建、停播。
- 挂起状态变化那一帧发暂停握手（服务端冻结 / 解冻事件时间线）；位置随握手包上报，服务端只在收到非空位置时重定基准。
- 帧驱动逐实例读取持有状态；不跨实例共享。

## 五、边界

- 本篇不定义时间门何时满足（见 `rule-time-gate.md`）、动作如何调度（见 `rule-facts-scheduling.md`）、分支去向（见 `rule-exits.md`）。
- 挂起不等于游戏暂停：`pause_when_game_paused` 是另一套开关。
- 非阻塞观察不阻止自然完成；需要等条件才结束，作者应声明阻塞等待或明确执行依赖。

## 六、结构评审项（已实现）

- 持有来源与释放条件在绑定上的 schema 表达：`rule_bindings[].control = observe | hold` 决定判定是否形成持有；
  `rule_bindings[].schedule` 表达动作延后（待执行调度，不形成内容持有，见 `rule-facts-scheduling.md`）。
  运行态来源键 = `rule:<bindingId>`（判定持有）+ `action:pause`（`pause` 动作）。
- 可达性诊断的判定范围与报告形态：**限频日志 + 校验器警告**。
  - 运行期（`RuleRuntime`）：冻结期间持有等待「尚未开始的其它片段 `clip_fact`」或「未来局部时间
    `time.at` / `time.from`」→ `Diagnostics.warn`（缺省 `ErrorLog.logRateLimited`，5 秒窗口，
    key = `hold:unreachable:<bindingId>`）；每个「绑定 × 执行」最多一次，只告警不自动释放。
  - 校验期（`ScriptValidator`）：`control=hold` + 无超时（`policy.timeout` 缺省 / `-1`）+ 条件等待
    起点晚于目标片段结束的片段 → 警告级 issue（`[警告] ` 前缀，`errorsOf` 过滤后不阻止加载）。
  - `pause` 在本执行内无仍可触发的 `resume`（静态无释放者，或释放绑定已 `finished`（含超时终结）、
    `once` 已成功；带非立即 `schedule` 的绑定仍算活释放者）→ 运行期告警并自动释放
    `action:pause`（防卡死，与旧 `clip.rules` 同口径）。
- 挂起期间提前事实、时间门与路径 timing 的保留策略：事实按「最近一次激活」作用域（`RuleFactStore`
  的成功事实在新激活建立时清除）；时间门与路径 timing 只读播放器当前读数，挂起期间内容位置不推进，
  因此不重建、不补算。

## 七、实现结果

状态：已实现，**I4-4 关闭**。

- 持有账本：`script/rule/runtime/SuspensionLedger` 按来源字符串记账（`Set` 语义、同来源幂等）。
  来源键 = `rule:<bindingId>`（`control=hold` 判定持有）与 `action:pause`（`pause` 动作）。
  最后一个来源释放才恢复内容推进；`releaseAll(prefix)` 供 `resume` 只释放 `action:pause`。
- 边沿驱动：`RuleRuntime` 帧末比较账本状态，只在「0 → 1」调 `ScriptPlayer.beginSuspend()`、
  「1 → 0」调 `endSuspend()`；`frozenByRules` 只解除本引擎造成的挂起，旧 `until` / `pause` 的
  挂起不会被误解除。`sync` / `reset` / 停播回收全部来源。
- 动作接线：`RuleActionRunner` 是实例方法（构造器接 `RuleRuntime`），`pause` / `resume` 走
  `RuleRuntime.onActionPause()` / `onActionResume()`，不再转调旧 `RuleEngine.suspendByAction` /
  `resumeByAction`；旧 `clip.rules` 路径（`RuleActions.execute`）保持不变。
- 诊断三类：
  1. 判定持有等待目标片段以外、播放头尚未到达其窗口起点的片段（`clip_fact`）；
  2. 判定持有等待大于当前执行局部时间的 `time.at` / `time.from`；
  3. `action:pause` 在本执行内无仍可触发的 `resume`（无 `resume` 动作且无 `schedule` 绑定；或释放
     绑定已 `finished`（含超时终结）、`once` 已成功）→ 告警并自动释放。
  前两类只告警、不自动释放，每个「绑定 × 执行」最多一次；缺省出口为
  `ErrorLog.logRateLimited("Rules", key, 5000, ...)`，探针可注入 `Diagnostics` 计数。
- 校验器：`rule_bindings` 的 `control=hold` + 无超时 + 条件等待目标片段结束后的片段 → 警告级 issue
  （`[警告] ` 前缀；`ScriptValidator.errorsOf` 过滤后不影响加载）；旧 `clip.rules` 的
  `checkPauseReleaser` 未改。
