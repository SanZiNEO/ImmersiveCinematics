# 规则与路径：现行实现与新旧差异

> 状态：记录文档（不定义语义）
> 范围：v1 实现基线、现行两套引擎速查、现行实现表、新旧规则系统差异报告、结构评审项状态
> 关联：`rule-system.md`、`rule-collection.md`、`rule-time-gate.md`、`rule-suspension.md`、`rule-facts-scheduling.md`、`rule-exits.md`、`rule-command-trust.md`、`path-collection.md`、`clip-identity.md`、`../C-trigger/condition-system.md`

## 一、v1 实现基线（旧引擎，现行仍可读旧格式脚本）

- 规则是 `clip.rules[{if|until, do}]` 片段元数据，按片段活跃窗建槽；`once`／`until` 由 timeline 回退重置。
- 规则生命周期截在 `[startTime, windowEnd)`；`until` 超时与 `if` 驻留／冷却使用不同时钟；`pause` 出口按同片段判定。
- 动作工件按锚点窗口回收；内容消费仍含 `getWindowEnd()`；`total_duration` 仍为必填统计位。
- EVENT 去重与命令协议保持一致。

旧引擎的求值语义不变（槽位窗口、判定、动作提交时机逐位保持）；为命令信任只补了槽位的「平铺片段下标 + 规则下标 + 动作下标」记账，动作执行抽出 `RuleActions.executeShared` 供两套引擎共用。

## 二、当前实现速查（两套引擎，互斥）

| | 旧引擎（v1） | 新引擎（I-4） |
|---|---|---|
| 载体 | 片段级 `clip.rules[]`（`TrackSchemas.putCommonClipFields`） | 脚本顶层 `rules`（定义表）+ `rule_bindings`（绑定表） |
| 目标定位 | 片段自身（活跃窗内） | 绑定引用片段 `id` + 必选时间门 `gate.at` |
| 生命周期 | 片段活跃窗 `[startTime, windowEnd)` | 片段每次执行内建立激活；时间门 `>=` 后持续可判定 |
| 多入口 | 每片段每规则一槽，多入口合并／覆盖 | 每条绑定独立激活，各自计时 / `once` / 等待 / 待执行 |
| 动作 | 条件成立即提交（`RuleActions.execute`） | 判定成功记事实；`schedule` 决定立即或延迟提交（`RuleActions.executeShared` 共享实现） |
| 挂起 | `until` + `pause` 共用实例停住状态、分开记账 | `SuspensionLedger` 多来源持有（`rule:<bindingId>` / `action:pause`），0→1 / 1→0 驱动 `ScriptPlayer` |
| 出口 | `goto_clip` 按轨道 + 时间 seek | 片段 `exit` 默认 + 绑定 `exit` 覆盖；`goto_clip` 新形态启动分支 |
| 路径 | `Clip.curve` 一份控制点隐式套所有段 | 顶层 `paths[]` 逐段条目（端点按关键帧时间 + 相邻性） |
| 命令 | 客户端发命令原文，观看者自登记，权限 4 | 客户端只发动作引用；`PlaybackGrant` + `RuleCommandTable` + 权限 `Config.ruleCommandPermissionLevel`（缺省 2） |
| 互斥 | 同一脚本同时出现两套 → 加载期错误 | 同左 |

现行落点（类 / 方法）：

| 能力 | 落点 |
|---|---|
| 片段身份 / 使用边 | `script/identity/`：`ClipIdentity`、`ClipRef`、`UsageKind`、`UsageEdge`、`UsageIndex`、`ClipResolver`；`Clip.scriptClipId()`；`CinematicScript.findClip` / `getStandaloneClips` / `getUsageIndex` |
| 规则集合 | `script/rule/`：`RuleDefinition`、`RuleBinding`、`RuleCollection`、`RuleCollectionParser`、`RuleControl`、`RuleSchedule`、`RuleRelease`、`RuleReleaseKind`、`ClipExitPolicyParser` |
| 运行态 | `script/rule/runtime/`：`RuleRuntime`（`sync` / `evaluate` / `reset` / `hasPendingActions` / `pendingCount` / `facts` / `holds`）、`RuleActivation`、`RuleFactStore`、`SuspensionLedger`、`PendingAction`、`RuleActionRunner` |
| 条件事实 | `ClientConditions.evaluate(..., double localTime, FactSource)`；`FactSource` 由 `RuleRuntime.RuntimeFactSource` 适配 |
| 出口 / 分支 | `ClipExitPolicy` / `ClipExitResolver` / `ClipExitPolicyParser`；`PlaybackInstance implements ClipBranchHost`；`ClipBranchController.launch` / `poll` / `fail`；`ClipTimeSource.windowStart(Clip)` + `ClipModifierGate` |
| 命令 | `trigger/network/C2SRuleCommandPacket`；`trigger/server/PlaybackGrant` / `RuleCommandTable` / `RuleCommandRateLimiter` / `RuleCommandAudit`；`util/RuleCommandSource`；`Config.ruleCommandPermissionLevel` |
| 路径 | `script/path/`：`PathEntry`、`PathCollection`、`PathCollectionParser`、`PathCollectionSource`；`CameraTrackPlayer` → `CameraKeyframeEvaluator#setPathBindingSource` |
| EVENT seek | `C2SScriptSeekPacket` / `S2CScriptSeekAckPacket`、`ScriptEventManager.handleSeek`、`EventSeekPlan`、`PlaybackInstance.seekTo` |
| 结构 schema | `SchemaExporter.scriptStructure()` |

## 三、现行实现表

| 目标 | 实现 | 边界 |
|---|---|---|
| 片段稳定身份、跨结构引用、使用关系索引 | 片段可选 `id`（`^[A-Za-z0-9_]{1,64}$`，内联 / 独立共命名空间）；`UsageIndex` 编译五类使用边；悬空 / 重复 / 缺 id 加载期错误 | 编辑器的重排 / 删除重映射操作归 Z 轮；迁移器归 I-5 |
| 脚本级独立片段表 | 顶层 `clips[]`：`track` + `duration` 必填、禁 `start_time`、`objects` 非空；可作规则 / 路径 / 分支 / 出口目标 | 无默认放置；未启动时恒不活跃、不计入完成度 |
| 脚本级规则集合：定义 / 绑定 / 激活分层 | `rules[]` + `rule_bindings[]`；`RuleCollection.bindingsFor(Clip)` 预建索引；每条绑定独立激活 | 动作参数校验仍按 v1 动作 schema（`adjust_set` 通道为平铺键） |
| 必选时间门、门前采集、门后提交 | `gate.at` 必选并按片段时长校验；`localTime >= gateTime` 持续满足；`RuleFactStore` 从执行进入即记录 | 条件树无「事件源」类型；不做无界历史 |
| 多入口独立运行态 | 每绑定 × 每次执行一份 `RuleActivation`；`once` / `cooldown` / `mode` / `dwell` / `timeout` 各自记账 | 同一绑定不并发多激活；后一次执行不重置前一次 |
| 实例级挂起 + 多来源持有 | `SuspensionLedger`（`rule:<bindingId>` / `action:pause`）；0→1 `beginSuspend`、1→0 `endSuspend`；`resume` 只释放 `action:pause` | 可达性只告警不自动解除；`pause` 无释放者才自动释放（防卡死） |
| 事实接力与判定成功分离 | `RuleFactStore` 四类事实；`rule_fact` / `clip_fact` 条件叶子；`action_completed` 在动作执行后记录 | 事实作用域 = 最近一次执行 / 最近一次激活；不跨实例 |
| 动作调度 | `schedule` 五形态（`clip_start` / `clip_complete` / `local_time` / `delay` / `fact`）；`PendingAction` 队列 | `delay` 按真实秒（挂起不冻结）；失败动作不中断播放 |
| 待执行动作参与自然完成 | `player.setCompletionDependency(() -> rules.hasPendingActions())` | 非阻塞 `observe` 不阻止完成 |
| 正常完成去向 + 失败 fallback | 片段 `exit` 默认 + 绑定 `exit` 覆盖；`ClipExitResolver` 实例级默认表；`ClipBranchController` 收口 | `END_EXECUTION` ≡ `CONTINUE`（无调用栈）；失败不回滚音频 / 覆盖层副作用 |
| 分支接线与活跃窗 | `PlaybackInstance implements ClipBranchHost`；`FrameDriver` 在规则求值后 `branch.poll()`；launch 时间 = `max(now, lastHead)`；`ClipModifierGate` 按 `windowStart(Clip)` 判活 | seek 期间不 abort 分支；无限分支永不自然完成 |
| 规则命令服务端权威 | 六字段请求包、`PlaybackGrant`、`RuleCommandTable`、`RuleCommandSource`（缺省权限 2）、限频、审计 | 服务端不证明规则条件成立；权限上限只由服务端配置决定 |
| 脚本级路径集合 | `paths[]` → `PathEntry`；端点按 `position` 节点时间 + 相邻性；`PathCollectionSource` 接入相机 | 迁移器归 I-5；编辑器呈现归 Z 轮 |
| EVENT seek | `C2SScriptSeekPacket` → `ScriptEventManager.handleSeek` → `EventSeekPlan` / `PlaybackInstance.seekTo` | 前跳不补、后跳不重复；不重放 EVENT |
| 卫生 | `ColorAdjustSampler#sampleCameraLaneObjects` 去掉 `ClipValueView` 参数并同步调用点 | — |
| 格式清理 / 迁移 / 旧字段拒绝 | 未实现 | 归 I-5（`format-cleanup.md` / `migration.md`）；`docs/SCRIPT_FORMAT.md` 重写同轮 |

## 四、新旧规则系统差异报告

| # | 旧行为（`clip.rules` / 旧链路） | 新行为（脚本级集合） | 影响 |
|---|---|---|---|
| 1 | 规则载体是片段元数据 `clip.rules[]`，随片段走 | 脚本顶层 `rules`（定义）+ `rule_bindings`（绑定）；片段只被引用 | 一条规则可被多片段复用；脚本顶层多出两个键；同脚本混用两套 → 加载期错误 |
| 2 | 规则生命周期截在片段活跃窗 `[startTime, windowEnd)` | 激活绑定到片段**某次执行**，跨片段、跨其它时间入口持续；时间门只限制判定资格 | 可以「A 片段门后等待、B 片段开始时动作」；不再有窗口外静默失效 |
| 3 | 同一片段多条规则各占一槽，多入口会合并 / 互相覆盖 | 每条绑定是独立入口，独立激活、独立计时 / 成功 / 待执行 | 同规则做多个时间入口不再复制整条规则；入口互不遮蔽 |
| 4 | 无时间门；规则在活跃窗起点即判定；时间条件只能是片段本地时钟比较 | `gate.at` 必选，`localTime >= at` 后持续满足；门前采集事实、门后提交动作 | 作者必须写门（含 `at: 0`）；迟到成立仍执行；`any` 不能绕过门 |
| 5 | `until` / `pause` 共用实例停住状态，释放者必须写在同一片段；同片段无释放者时自动解除 | `SuspensionLedger` 多来源持有（规则判定 / `pause` 动作），最后一个释放才恢复；不可达依赖给告警 | 跨片段等待可达；不会再被「另一个来源」提前解除；`pause` 无释放者仍自动释放 |
| 6 | 条件成立即提交动作；成功与执行完成是同一件事 | 判定成功记事实、动作按 `schedule` 延迟；`action_completed` 单独记录并参与自然完成 | 支持「A 成功后等 B 开始再执行」；待执行动作会阻止实例提前结束 |
| 7 | `goto_clip` 按轨道 + 时间 seek，回到主线继续 | `goto_clip` 新形态 `{clip, entry_time}` 启动分支；片段 `exit` 默认 + 绑定 `exit` 覆盖；失败回原主线快照 | 目标按片段 id 定位，重排不改指；完成去向可声明；失败不回滚副作用 |
| 8 | 客户端发命令原文；观看者可由播放回执自登记；命令源权限 4 | 客户端只发动作引用；`PlaybackGrant` 由服务端签发；服务端动作表取命令原文；权限缺省 2；限频 + 审计 | 任意命令注入面关闭；依赖 op 命令的旧规则脚本需改用 EVENT / 触发器或调高配置 |
| 9 | 路径 = `Clip.curve` 一份控制点，隐式套到每个 from→to 段 | 顶层 `paths[]` 逐段条目：端点按关键帧时间 + 相邻性，`geometry` + `timing` 分离，可多条 | 同片段不同段可有不同形状 / 速度；删端点会报错而不是静默改绑 |
| 10 | 只有 `clip.rules` 一种规则格式 | 新旧互斥：同脚本同时出现 `clip.rules` 与顶层 `rules` → 加载期错误；两种脚本各自独立可读可播 | 迁移必须整脚本切换，不能逐片段混用；旧脚本零改动仍可播 |

## 五、结构评审项

已定稿（字段与运行态口径见 `docs/SCRIPT_FORMAT.md` §5）：

- 稳定片段引用与绑定身份（片段 `id` / 绑定 `id`，id 字符串寻址）。
- 规则集合进 schema 的范围（`rules` / `rule_bindings` / `clips` / `paths` 四键；运行态不进脚本）。
- 事实作用域（最近一次执行 / 最近一次激活）。
- 持有来源与可达性诊断形态（来源键 + 限频日志 + 校验器警告）。
- 延迟动作释放条件（`schedule` 五形态）。
- 路径集合字段、端点引用与 `timing` 序列化。

仍开放（不属于 I-4 实现范围）：

- 编辑器的重排 / 插入 / 删除显式重映射操作与错误 / 警告边界（Z 轮）。
- 事实跨执行历史匹配（当前只到「最近一次执行」）。
- 旧格式迁移器与旧字段拒绝（I-5）。
- 多条路径与同一段并存的编辑器呈现（Z 轮）。
