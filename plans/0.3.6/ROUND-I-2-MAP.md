# I-2 轮项目地图（主代理）

> 建立时间：2026-11-02。依据：本轮任务书 + `plans/0.3.6/I-script-core/{loop-execution,clip-identity,runtime-evaluation,rule-exits,acceptance,migration}.md` + 源码核对。
> 本文件是本轮派单与验收的唯一索引；行号仅供定位，以类名 / 方法签名为准。
> `docs/` 过时，只作线索；冲突时以设计文档与源码为准。

## 〇、用户裁决（本轮口径，优先级最高）

1. **允许改轨道播放器的「局部时间取值」**：统一从执行账本取 `executionLocalTime`，不改各轨装配语义（相机六参数、图层参数、调色栈一行不动）。
2. **分支出口只做运行期策略模型 + API + 探针**：不新增脚本字段、不接规则引擎；序列化留后续结构评审。
3. **不可达诊断只对显式依赖边**：例如「等待某片段完成」而该片段是无限执行；不把「无限片段后有片段」判错。
4. **旧格式 AUDIO 的 `loop` 仍是文件循环**：执行循环按格式区分——旧格式 AUDIO 不算执行循环，新格式（I-1 `playControl`）算执行循环。差异写入迁移对照。
5. **I-1 过渡策略继续有效**：旧格式可读可播到 I-5；本轮不切换格式、不清理旧字段。

## 一、轮次范围与基线

**目标**：把片段执行从「时间折叠」换成「执行次数 + 执行身份 + 局部采样 + 完成事实」，并落分支片段出口。

**不做**：轨道装配语义、规则集合/引擎、格式清理/迁移器、稳定片段 id 序列化、独立片段表、路径集合。

**基线（本轮改动前实测，2026-11-02）**：
- 工作树干净，HEAD `cf52816`；`:common:compileJava` PASS（11.5s）。
- 旧解析：`E:/tmp/i1/baseline-parse.txt` → 231 脚本，225 OK / 6 ERR（6 个既有失败）。
- 旧校验：`E:/tmp/i1/baseline-icv.txt` → 231 脚本，223 OK / 18 FAIL（既有语料问题）；基线中**没有**「无限循环后仍有片段」「duration 与周期不匹配」「loop_count 不允许为 0」三类 issue。
- 循环夹具：`cinematics/0.3.6/tests/camera/test_camera_loop_{repeat,pingpong}.json`、`cinematics/0.3.6/tests/rules/test_exit_{loop_count,infinite}.json`；四支的 `duration` 都等于关键帧跨度，旧/新窗口数值一致，可做画面回归。

## 二、任务清单（编号 + 目标 + 验收）

| 编号 | 目标（一句话） | 验收 |
|---|---|---|
| I2-1 | 执行模型核心：`Clip` 播放控制语义、`ClipExecution`/`ClipExecutionLedger`、执行身份、局部采样轴、次数/模式、完成事实；删除 `%` 折叠 / `getAnimPeriod` / `LaneMask.effectiveTime`，各轨改从账本取局部时间与活跃窗 | 循环探针 PASS：执行身份唯一、局部时间映射（repeat/pingpong/seek）、有限 N 次数耗尽才消费、无限永不完成、无有限自然末端；`:common:compileJava` PASS；旧解析基线逐行一致 |
| I2-2 | 循环校验迁移 + 无限编排诊断：删除「无限后仍有片段」禁播与「duration≠周期」错配；loop 规则七轨/新格式统一；显式依赖不可达诊断 | 校验探针 PASS：旧脚本基线不变（无新增/删除 issue）；新格式/构造样例命中指定文案；无限后片段不再报错；`WAIT_COMPLETION` 指向无限片段产诊断 |
| I2-3 | 分支片段出口：片段默认策略、入口显式覆盖优先、正常完成去向、失败 fallback 严格分离 | 分支探针 PASS：正常完成走默认/覆盖策略；失败只回主线、不执行正常后继；入口覆盖 > 片段默认 |
| I2-4 | 旧循环迁移对照 + 探针/回归收口：差异报告、基线复跑、文档状态回写 | 对照文档覆盖 8 项差异；全部探针复跑 PASS；parse diff 0；validator 差异可解释；编译 PASS |

## 三、真实文件 / 类 / 方法地图

### 3.1 执行模型（本轮新建）

| 文件 | 目标形态 |
|---|---|
| `script/execution/LoopMode.java` | `enum LoopMode { REPEAT, PINGPONG }`；`static LoopMode fromName(String, LoopMode)` |
| `script/execution/ExecutionDirection.java` | `enum ExecutionDirection { FORWARD, BACKWARD }`；`static of(LoopMode, int executionIndex)` |
| `script/execution/ClipExecution.java` | 单片段当前执行记录：`clip/flatIndex/executionId/executionIndex/executionStart/localTime/duration/loopMode/loopCount/executionsRemaining/infinite/direction/contentDone/done/branchEntry` + 只读访问器 |
| `script/execution/ClipExecutionLedger.java` | 播放实例级执行账本：`reset/advance/executionOf/at/size/isActive/localTime/windowStart/hasFiniteWindowEnd/windowEnd/hasFiniteContentEnd/contentEnd/allDone/remainingContent/lastHead/startBranch/endBranch/snapshot/restore` |
| `script/execution/ClipTimeSource.java` | 轨道求值只读接口：`isClipActive(Clip,double)`、`executionLocalTime(Clip,double)`、`finiteWindowEnd(Clip)`；`ScriptPlayer implements ClipTimeSource` |

**语义冻结**：
- `executionId`：实例内单调递增 long；seek/路径重导分配新身份，绝不复用。
- `executionIndex`：当前是第几次执行，0 起；无限可增长。
- `executionLocalTime`：始终 `[0, duration]`（`duration<0` 时只保证 `>=0`）；pingpong 反向由账本镜像，采样器只认升序。
- `executionsRemaining`：当前执行之后还剩几次；无限 = `-1`。
- `done`：`contentDone && executionsRemaining == 0`；无限恒 false。
- `windowEnd`：有限 `start + duration × (loop ? count : 1)`；无限无有限值。**调用方必须先判 `hasFiniteWindowEnd`**。
- `startBranch(clip, executionStart, entryLocalTime)`：把该片段的默认放置执行替换为分支入口执行（同一时刻每个片段只有一份当前执行）；`endBranch` 按当前 head 重导默认放置。
- `snapshot/restore`：深拷贝全部执行记录 + `lastHead`；失败 fallback 用，不重放内容。

### 3.2 `Clip`（本轮改）

| 现状 | 目标 |
|---|---|
| `getAnimPeriod()` 用末-首关键帧时间 | **删除** |
| `isLoop()` 直接读 `data.loop` | `isExecutionLoop()`：`loop=true && (playControlFormat \|\| trackType != AUDIO)`；`isLoop()` 保留为它 |
| 无文件循环访问器 | `isFileLoop()`：仅旧格式 AUDIO 的 `loop` |
| `getWindowEnd()` 无限返回 `Float.MAX_VALUE`；循环用 `period × count` | `hasFiniteWindowEnd()`；`getWindowEnd()` 只在有限时返回 `start + duration × (loop ? count : 1)`，无限调用抛 `IllegalStateException`（编程错误断言） |
| 新/旧格式无标记 | 构造器增 `boolean playControlFormat`；`parseObjectClip` 传 true；旧构造器 false |
| `getLoopMode()` 返回 String | 增 `LoopMode getLoopModeValue()`；旧 `getLoopMode()` 保留 |
| `isEffectivelyInfinite()` = duration<0 \|\| (loop && count<0) | 公式不变（`loop` 已按格式区分） |

### 3.3 `ScriptPlayer`（本轮改）

| 现状 | 目标 |
|---|---|
| `clipLoopCounts` / `clipStates` / `contentFactTime` / `remainingContent` 手写执行事实 | 全部由 `ClipExecutionLedger` 接管 |
| `recordContentFacts` / `rederiveContentFacts` / `clipConsumedAt` / `lapsBefore` | **删除** |
| `contentEnd()` 用 `getWindowEnd` + `NaN` 哨兵 | `executions.hasFiniteContentEnd()/contentEnd()` |
| `isFinished` / `allContentConsumed` / `getRemainingTime` / hold 驻留 | 读账本完成事实 |
| `contentPosition(float[])` 用 `start + head-start` | 读 `executions.windowStart/clip localTime` |
| `start` / `replaceScript` / `alignTime` 重导事实 | `executions.reset(head)` |
| 每帧 `recordContentFacts` | `executions.advance(elapsed)`（宏观循环折返时 `reset(local)`） |
| 无局部时间出口 | `implements ClipTimeSource`：`isClipActive` / `executionLocalTime` / `finiteWindowEnd`；`executions()` 只读访问器 |
| `collectModifiers(script, player::timelineTime)` | 传 `player` + `player::timelineTime`（Modifier 活跃窗按执行账本） |

### 3.4 轨道与求值接线（本轮改，只换取值来源）

| 文件 | 现状 | 目标 |
|---|---|---|
| `script/KeyframeInterpolator.java` | `computeInterpolation` 内做 `%`/镜像折叠 + 有限循环末端钳到末两帧 | 删除整个循环折叠块；输入即 `executionLocalTime`；`interpolateChannel` 不变 |
| `client/lane/LaneMask.java` | `effectiveTime` 复制折叠 | 删除 `effectiveTime`；`sample(clip, localTime)` 直接用执行局部时间 |
| `camera/source/CameraKeyframeEvaluator.java` | `renderMorph` 自算 `globalTime - start`；`findActiveClips` 遇无限即 `break`；`isActiveAt` 用 `getWindowEnd` | 构造器注入 `ClipTimeSource`；`renderMorph` 用 `timeSource.executionLocalTime`；`findActiveClips` 逐 clip 问 `timeSource.isClipActive`，**删除无限即停的 break**；过渡窗用 `finiteWindowEnd` |
| `script/CameraTrackPlayer.java` | `renderSingle(..., globalTime - start)`；`transitionPairAt` 用 `getWindowEnd` | 用 `scriptPlayer.executionLocalTime`；无限先判 |
| `camera/source/LaneSnapshotCollector.java` | 下层 lane 自算 `globalTime - start` | 注入 `ClipTimeSource`，用执行局部时间 |
| `script/OverlayTrackPlayer.java` | `findActiveClip` 只看 `[start,start+duration)`；`clipTime` 自算 | 用 `scriptPlayer.isClipActive` / `executionLocalTime` |
| `script/AdjustTrackPlayer.java` | 同上 | 同上 |
| `script/LetterboxTrackPlayer.java` | 同上 | 同上 |
| `script/AudioTrackPlayer.java` | `clip.isLoop()` 当文件循环；`findActiveClip` / `clipTime` 自算；fade-out 用 `start+duration` | `clip.isFileLoop()`；活跃/局部时间走账本；fade-out 用 `finiteWindowEnd`（无限不淡出） |
| `camera/ClipModifierGate.java` | 构造时缓存 `start/windowEnd`，`getWindowEnd` 无限会抛 | 注入 `ClipTimeSource`；活跃判定走 `isClipActive`，morph 延长用 `finiteWindowEnd` |
| `camera/rule/RuleActions.java` / `RuleEngine.java` | `getWindowEnd` 未先判无限 | 只加无限保护（规则语义不动） |

### 3.5 校验与诊断（I2-2）

| 文件 | 目标 |
|---|---|
| `script/ScriptValidator.java` | 删「无限循环后仍有片段」；删「duration 与循环周期不匹配」；删「loop 关键帧不足 2 个」；旧 CAMERA 循环块改为通用 `checkExecutionLoop`；`duration<0 + loop*` 报错；`loop_count < -1` 报错；`loop=false + loop_count` 告警 |
| `script/object/ObjectValidator.java` | 新格式已校验类型/pingpong 轨道；补：`loop_count < -1` 报错、`loop=false + loop_count` 告警、`pingpong + loop=false/loop_count=1` 告警、`duration<0 + loop*` 报错 |
| `script/execution/DependencyKind.java` | `enum DependencyKind { WAIT_START, WAIT_COMPLETION }` |
| `script/execution/ExecutionDependency.java` | `record ExecutionDependency(Clip dependent, Clip prerequisite, DependencyKind kind)` |
| `script/execution/ExecutionDiagnostics.java` | `static List<String> check(List<ExecutionDependency>, Function<Clip,String> label)`：空/自依赖报错；`WAIT_COMPLETION` 前置 `isEffectivelyInfinite()` → 「不可达依赖」；`WAIT_START` 无限可达 |

### 3.6 分支出口（I2-3）

| 文件 | 目标形态 |
|---|---|
| `script/execution/ClipExitKind.java` | `enum { CONTINUE, GOTO, RETURN, END_EXECUTION, END_SCRIPT }` |
| `script/execution/ClipExitPolicy.java` | `record ClipExitPolicy(ClipExitKind kind, Clip target, double entryLocalTime)`；`CONTINUE`/`endScript()`/`gotoClip(target, entryLocalTime)`；`GOTO` 目标空 → 构造期 `IllegalArgumentException` |
| `script/execution/ClipExitResolver.java` | 运行期默认表 `setDefault/clear/resolve(clip, entryOverride)`；优先级 **入口显式 > 片段默认 > CONTINUE** |
| `script/execution/ClipBranchHost.java` | `now()`、`seekTo(double)`、`requestScriptEnd()`、`onBranchStarted(Clip, ClipExecution)`、`onBranchFailed(Clip, String)` |
| `script/execution/ClipBranchController.java` | `launch(target, entryLocalTime, entryOverride)`（快照 + `ledger.startBranch`）；`poll()`（分支执行完成 → 解析策略 → 应用）；`fail(target, reason)`（`ledger.restore` + `host.seekTo`，**不走正常策略**）；`isBranching()` |

**策略语义冻结**：
- 正常完成：`CONTINUE`/`RETURN` = 结束分支、恢复默认放置；`GOTO` = 结束本分支并按入口局部时间启动目标；`END_EXECUTION` = 结束本执行、不启动后继；`END_SCRIPT` = 请求本实例自然结束。
- 失败 fallback：恒回原主线快照；**不得**执行正常后继；日志可区分正常/失败。
- 本轮不做嵌套分支栈；`GOTO` 链按顺序执行，不并行。

## 四、依赖顺序

```
I2-1（执行模型核心 + 轨道接线）
  ├─→ I2-2（校验 + 无限诊断）──┐
  └─→ I2-3（分支出口）──────────┤（2/3 文件不重叠，可并行）
                                └─→ I2-4（迁移对照 + 探针/回归）
```

- I2-2 与 I2-3 都只依赖 I2-1 的冻结接口，可并行；I2-4 必须在两者验收后。
- 每单收口只跑 `sh gradlew :common:compileJava --offline --no-daemon`；主代理独立复跑编译 + 探针。

## 五、现状与缺口

| 设计目标 | 现状 | 缺口 |
|---|---|---|
| 执行次数/执行身份/局部采样 | `KeyframeInterpolator` 取模、`LaneMask.effectiveTime` 复制、各轨自算 `globalTime-start`；`ScriptPlayer.lapsBefore` 用 `getAnimPeriod` | 执行账本、身份、方向、seek 重导 |
| 完成事实 | `clipConsumedAt` = 窗口末 + 圈数；无限 `return false`；`remainingContent` 全脚本片段数 | 次数耗尽 + 本次内容完成才 done；分支在跑/等待依赖（本轮仅账本完成 + 分支控制器） |
| 无限只无自然完成 | `Clip.getWindowEnd` 返回 `MAX_VALUE`；`contentEnd` 返回 `NaN`；`findActiveClips` 遇无限 `break`；validator 禁播 | 派生标志、删除 break、删除禁播 |
| 局部秒定位关键帧/过渡 | 关键帧由折叠后的周期时间采样；过渡窗用 `period × count` | 执行局部时间 + duration 窗口 |
| 分支出口 | 只有 `goto_clip`（轨道类型+时间寻址）；正常/失败未分层 | 策略模型 + 入口覆盖 + fallback |
| 不可达依赖诊断 | 无 | 显式依赖边诊断 API |
| 迁移对照 | `loop-execution.md` 只列设计差异 | 旧脚本逐项对照 + 基线结果 |

## 六、验证工具

| 工具 | 命令 | 用途 |
|---|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon` | 每单收口 |
| 旧解析 | `sh E:/tmp/i1/run-parse.sh cinematics` | 与 `baseline-parse.txt` diff |
| 旧校验 | `sh E:/tmp/icv/run.sh cinematics` | 与 `baseline-icv.txt` 对比（本轮允许“删除目标 issue”的差异，需逐条解释） |
| 循环探针（I2-1/4 建） | `sh E:/tmp/i2/run-loop.sh` | 身份/局部轴/次数/完成/无限/seek |
| 分支探针（I2-3/4 建） | `sh E:/tmp/i2/run-branch.sh` | 正常/失败/入口覆盖/策略链 |
| 校验探针（I2-2/4 建） | `sh E:/tmp/i2/run-validator-loop.sh` | 新规则正/负例；无限后片段；依赖诊断 |

探针放在仓库外（`E:/tmp/i2/`），编译口径复用 I-1：`common/build/classes/java/main` + Gradle 缓存依赖 + Minecraft merged jar。探针文件与临时日志不入库。

## 七、本轮明确不做 / 边界

- 不改各轨道装配语义：相机六参数合成、位置/朝向顺序、过渡混合公式、图层/调色/音频参数组装一行不动。
- 不接规则引擎：`clip.rules`、实例级挂起、事实接力、延迟动作留给规则轮。
- 不新增脚本字段、不做格式迁移/旧字段拒绝；`file_loop` 改名留 I-5。
- 不实现稳定片段 id / 脚本级独立片段表 / 使用边索引；执行身份用运行期 `Clip` 对象 + 扁平序。
- 不做多入口并行执行；同一片段同一时刻只有一份当前执行（分支入口替换默认放置），多放置留结构评审。
- 不改变宏观循环 `macro_loop_count/mode` 语义；编辑器预览仍按真实时间线。
- 不改变 EVENT 去重、音频文件循环实例复用、玩家移动复位点。

## 八、风险 / 待观察

1. **旧循环画面语义变化**：旧实现窗口 = 关键帧周期 × 次数，新实现 = duration × 次数。四支既有夹具 duration==period，数值一致；其他作者脚本若 duration≠period，窗口/速度会变，必须在迁移对照中单列。
2. **AUDIO 旧 `loop`**：按文件循环处理，避免把现有 `test_audio_loop` 变成无限执行；新格式执行循环本轮不进轨道装配，仅账本/校验层可用。
3. **分支快照恢复**：只恢复执行账本与播放头，不回滚已发生的音频/覆盖层副作用；探针按执行事实断言，文档标注该边界。
4. **Modifier 活跃窗**：改为执行账本后，分支入口执行的活跃窗与默认放置不同；本轮只覆盖默认放置，分支 + Modifier 组合留后续。
5. **零分配**：账本每帧只写既有字段/数组，不 new；探针需覆盖 1e5 帧无分配（若可行）。

## 十一、I-2 验收记录（2026-11-02）

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon` | `BUILD SUCCESSFUL`（`:common:compileJava` UP-TO-DATE），exit 0 |
| 循环探针 | `sh E:/tmp/i2/run-loop.sh` | `checks: 169` / `LOOP PROBE: PASS`，exit 0 |
| 分支探针 | `sh E:/tmp/i2/run-branch.sh` | `checks: 135` / `BRANCH PROBE: PASS`，exit 0 |
| 校验探针 | `sh E:/tmp/i2/run-validator-loop.sh` | `checks: 20` / `LOOP PROBE: PASS`，exit 0 |
| 旧解析 | `sh E:/tmp/i1/run-parse.sh "E:/Documents/GitHub/ImmersiveCinematics/cinematics"` | 与 `E:/tmp/i1/baseline-parse.txt` diff 0；`RESULT: parsed=225, failed=6, total=231`（脚本 exit 2 = 6 支故障夹具按设计失败，基线同口径） |
| 旧校验 | `sh E:/tmp/icv/run.sh "E:/Documents/GitHub/ImmersiveCinematics/cinematics"` | 与 `E:/tmp/i1/baseline-icv.txt` diff 0；`RESULT: 18 of 231 scripts have issues`，`SELFTEST: all validator-rule self-tests passed`，exit 0 |

- 探针与临时日志在仓库外（`E:/tmp/i2/`、`E:/tmp/i1/`、`E:/tmp/icv/`），不入库。
- 旧校验无新增/删除 issue：I2-2 删除的「无限后禁播」「duration≠周期」在 231 支既有脚本上未命中，基线因此保持逐行一致。

## 十二、遗留项与移交（I 轮结束前清零）

I-2 未完成、但不在本轮范围的事项已全部排入 [`I-BACKLOG.md`](I-BACKLOG.md)（I-3 / I-4 / I-5 与契约边界）。本节只保留索引；**I 轮（I-5 收口）结束时该表必须清零，不能悬空**。

| 来源 | 事项 | 归属 |
|---|---|---|
| §十二-1 | 旧循环夹具实机像素回归 | I-3 收口（旧格式）/ I-5 最终全量 |
| §十二-2 | 分支控制器接 `ScriptPlayer` / 规则引擎 | I-4 |
| §十二-3 | 稳定片段身份 / ClipRef / 使用边 + 多入口/多放置 | I-4 |
| §十二-4 | `END_EXECUTION` 与 `CONTINUE` 语义分离/定性 | I-4 |
| §十二-5 | 分支 + Modifier 活跃窗 | I-4 |
| §十二-6 | `docs/SCRIPT_FORMAT.md` 重写 | I-5 |
| §十二-7 | 宏观循环 L6（执行次数/完成事实）裁决 | I-5 |
| §十二-8 | `finiteWindowEnd` 无限抛异常 | 契约（不排期，调用方先判） |
| §十二-9 | 分支失败副作用边界 | I-4（文档定性） |
| 新发现 | 跨帧跳过多次执行的中间执行身份/规则激活 | I-4 |
| 新发现 | 分支 launch 时间不变量（`host.now() >= ledger.lastHead()`） | I-4 |
| 新发现 | 分支出口脚本声明/序列化（片段默认 + 入口覆盖） | I-4/I-5 |
| 新发现 | AUDIO `file_loop` 改名 + 旧 `loop` 双语义切换 | I-5 |
| 新发现 | 旧循环脚本迁移器/差异报告 + 旧字段拒绝 | I-5 |
