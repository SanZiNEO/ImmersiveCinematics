# I-4 轮项目地图与共享契约（主代理）

> 建立时间：2026-11-03。依据：本轮任务书 + `plans/0.3.6/I-script-core/{rule-system,rule-collection,rule-time-gate,rule-suspension,rule-facts-scheduling,rule-exits,rule-command-trust,rule-implementation,clip-identity,path-collection,schema-and-types,track-event,loop-execution,acceptance}.md` + 源码核对 + 三份只读测绘（规则引擎 / 命令链路 / 路径与执行）。
> 行号仅供定位，以类名 / 方法签名为准。`docs/` 已过时，只作线索；冲突以设计文档与源码为准。
> 本文件是本轮派单与验收的唯一索引；I-1/I-2/I-3 地图只作前置。

---

## 〇、口径裁决（优先级最高）

### 0.1 用户裁决（2026-11-03）

| 项 | 裁决 |
|---|---|
| 规则集合拓扑 | **定义表 + 绑定表**：两个顶层键 `rules`（定义）与 `rule_bindings`（绑定） |
| 片段身份 | **可选 `id`**；被规则 / 路径 / 分支 / 出口引用的片段必须写 `id`，否则加载期错误；未被引用的片段可省略（231 支既有夹具零改动） |
| 出口声明 | **片段 `exit` 为默认 + 绑定 `exit` 为入口覆盖**；优先级 = 入口覆盖 > 片段默认 > `CONTINUE` |

### 0.2 本轮契约裁决（实现口径，已按设计文档推导，非用户待确认项）

1. **新旧并存 = 双读，不同脚本**：`clip.rules`（v1 引擎）与脚本级 `rules`（新引擎）**同一脚本同时出现 → 加载期错误**；两种脚本各自独立可读可播。旧引擎代码路径一行不改。
2. **脚本级集合四个新顶层键**：`clips`（脚本级独立片段表）、`rules`（规则定义表）、`rule_bindings`（绑定表）、`paths`（路径集合）。均为可选；缺省 = 空集合。
3. **事实作用域 = 最近一次执行**：`clip_fact` 指该片段**当前执行**（活跃时）或**最近一次已完成执行**；`rule_fact` 指该绑定的**最近一次激活**。不做无界历史、不跨实例。跨执行历史匹配列为后续结构评审（本轮回写文档）。
4. **门前采集 = 激活在片段执行进入时建立**：从执行进入即记录本实例的片段事实与规则事实（`clip_fact` / `rule_fact` 一旦为该执行成立即保持）；状态谓词按实时语义在门后逐帧判定，不锁存。**不为条件树新增“事件源”类型**（条件树现有类型全为状态谓词）。
5. **跳过执行不建立激活/事实**：一帧跨多次执行时账本只保留最终执行身份，被跳过的执行不产生激活、不产生事实（I2-B6 裁决）。
6. **分支 launch 时间不变量**：`ClipBranchController.launch` 取 `launch = max(host.now(), ledger.lastHead())`；`host.now() < lastHead` 时限频告警（I2-B7）。
7. **`goto_clip` 新形态 = 分支启动**：新规则动作 `{"type":"goto_clip","clip":"<id>","entry_time":<s>}` → `ClipBranchController.launch`；旧形态 `{"track":..., "at":...}` 只在旧 `clip.rules` 引擎里继续有效（新引擎收到旧形态 → 校验期错误）。
8. **挂起 = 来源记账**：新增 `SuspensionLedger`（来源：规则判定持有 / `pause` 动作 / 旧 `until`）。`ScriptPlayer.beginSuspend/endSuspend` 仍是唯一内容冻结机制，由账本在“首个来源获取 / 最后一个来源释放”时调用。
9. **路径端点用**关键帧时间**引用**（不是下标）：插入关键帧不改变端点；删除端点 → 加载期错误（显式重映射）。相邻性只在目标片段 `position.x/y/z` 的节点时间序列内判断。
10. **`geometry` 复用 `PathCurveRegistry`**：`line` 为 `linear` 的别名，其余类型按注册表原样透传（首批 `line`/`bezier`，可扩展）。
11. **`emit_command` 新协议**：客户端只发动作引用；服务端从脚本解析命令原文；`PlaybackGrant` 由服务端发送播放包时创建；规则命令源权限默认 2（`Config` 可调）；限频 + 审计；旧原文包不再注册。
12. **探针在仓库外**（`E:/tmp/i4/`），临时日志不入库；实机夹具放 `cinematics/0.3.6/tests/objects/` 与 `cinematics/0.3.6/tests/rules/`。
13. **不做的**：格式清理 / 迁移器 / 旧字段拒绝（I-5）；编辑器 UI（Z 轮）；`meta.version`、`total_duration` 删除（I-5）。

---

## 一、JSON 契约（本轮定稿，写入专篇）

### 1.1 片段身份与独立片段表

```json
{
  "clips": [
    { "id": "intro", "track": "CAMERA", "duration": 5.0, "objects": { ... }, "exit": { ... } }
  ],
  "timeline": {
    "tracks": [
      { "type": "CAMERA", "clips": [
          { "id": "c1", "start_time": 0.0, "duration": 4.0, "objects": { ... }, "exit": { ... } },
          { "start_time": 4.0, "duration": 3.0, "objects": { ... } }
      ]}
    ]
  }
}
```

- `clips[]` 条目 = 独立片段：**无 `start_time`**（没有默认放置），`track` 必填（决定 `objects` schema），`duration` 必填（负数 = 无限单次），`objects` / `exit` 与内联片段同 schema。
- `id` 规则：`^[A-Za-z0-9_]{1,64}$`；脚本内唯一（内联 + 独立共用一个命名空间）；重复 → 错误。
- 被引用却无 `id` → 错误「片段被 <宿主> 引用但没有 id」。
- `goto` 目标、规则绑定目标、路径目标、出口目标都用 `id` 字符串引用。

### 1.2 规则定义表 `rules`

```json
"rules": [
  {
    "id": "r_door",
    "condition": { "type": "all_of", "conditions": [ ... ] },
    "actions": [ { "type": "camera_set", ... } ],
    "policy": {
      "once": true, "cooldown": 0.0, "mode": "enter", "dwell": 0.0,
      "timeout": -1.0, "on_timeout": [ ... ]
    }
  }
]
```

- `id` 必填、脚本内唯一；`condition` 必填对象（内层条件树，**不得**包含时间门语义）；`actions` 必填非空数组。
- `policy` 可省；字段与 v1 同名同默认（`once=true`、`cooldown=0`、`mode=enter`、`dwell=0`、`timeout=-1`、`on_timeout=[]`）。
- 动作类型 = v1 九种 + 新形态 `goto_clip`；`emit_command` 的 `command` 仍写原文（只存在于脚本/服务端）。

### 1.3 规则绑定表 `rule_bindings`

```json
"rule_bindings": [
  {
    "id": "b_door_c1",
    "rule": "r_door",
    "clip": "c1",
    "gate": { "at": 1.5 },
    "control": "hold",
    "schedule": { "on": "clip_start", "clip": "c2" },
    "exit": { "on_complete": "goto", "target": "c3", "entry_time": 0.0 }
  }
]
```

- `id` 必填唯一；`rule` 必填（解析到 `rules[].id`）；`clip` 必填（解析到片段 id）；`gate.at` **必填**（执行局部秒；有限片段 `0 <= at <= duration`，无限片段 `>= 0`）。
- `control`：`observe`（缺省，非阻塞）/ `hold`（到门后未成立即持有实例）。
- `schedule`：可省（= 成功后立即提交）。
- `exit`：可省（= 用片段默认出口）。
- 同一 `rule` 多条绑定 = 多入口，各自独立运行态。

### 1.4 动作调度 `schedule`

| 形态 | 语义 |
|---|---|
| 缺省 | 判定成功后立即提交 |
| `{"on":"clip_start","clip":"<id>"}` | 目标片段下一次执行开始时提交 |
| `{"on":"clip_complete","clip":"<id>"}` | 目标片段执行完成时提交 |
| `{"on":"local_time","clip":"<id>","at":<s>}` | 目标片段执行到达局部时间时提交 |
| `{"on":"delay","seconds":<s>}` | 判定成功后经过真实秒数提交（挂起不冻结） |
| `{"on":"fact","binding":"<id>","fact":"succeeded"\|"action_completed"}` | 另一绑定的最近一次激活事实成立时提交 |

### 1.5 出口 `exit`

```json
"exit": { "on_complete": "continue" | "goto" | "return" | "end_execution" | "end_script",
          "target": "<clipId>", "entry_time": 0.0 }
```

- `goto` 必须有 `target`，`entry_time` 有限且 `>= 0`；其余类型不得带 `target` / `entry_time`。
- 片段 `exit` = 默认策略；绑定 `exit` = 入口覆盖。校验期比对优先级。
- 主线上轨道放置的片段完成时不走出口策略（时间轴继续）；出口策略只在**分支入口**启动的执行完成时解析。

### 1.6 路径集合 `paths`

```json
"paths": [
  {
    "id": "p1",
    "clip": "c1",
    "from": 0.0,
    "to": 2.0,
    "axes": ["x", "y", "z"],
    "geometry": { "type": "line" },
    "timing": { "type": "keyframes", "points": [ {"t": 0.0, "s": 0.0}, {"t": 1.0, "s": 1.0} ] }
  }
]
```

- `id` 必填唯一；`clip` 必填；`from`/`to` 必填（目标片段 `position.x/y/z` 节点时间序列中**相邻**的两个时间，`from < to`）。
- `axes` 可省（缺省三轴）；取值 `x`/`y`/`z`，去重、顺序无关、至少一项。
- `geometry` 必填：`{"type":"line"}` 或 `{"type":"bezier","control_points":[...]}`（其余 `PathCurveRegistry` 类型同样接受）。
- `timing` 可省（缺省线性）；`keyframes` 形态要求 `t` 端点 0/1、`s` 端点 0/1、两序列单调不减、至少 2 点。
- 同一片段多条路径合法；端点对完全相同 → 错误；区间部分重叠 → 警告（解析顺序 = `startTime` 最大者优先，与 `PathBindingResolver` 一致）。

### 1.7 条件树新增类型（事实）

```json
{"type": "clip_fact", "clip": "<id>", "fact": "started" | "completed"}
{"type": "rule_fact", "binding": "<id>", "fact": "succeeded" | "action_completed"}
```

- 两者可与 `all_of` / `any` 嵌套，参与同一深度上限。
- 引用必须可解析（片段 id / 绑定 id 存在），否则加载期错误。
- 求值侧：`ClientConditions` 增重载 `evaluate(JsonObject, Context, double localTime, FactSource facts)`；旧 3 参重载委托 `FactSource.NONE`（事实类型恒 false）。`FactSource` 是接口：`boolean clipFact(String clipId, boolean started)` / `boolean ruleFact(String bindingId, boolean actionCompleted)`。
- 条件树保持原文 JSON（不在解析期改写），运行期按 id 查 `RuleCollection` 的 map（零分配）。

---

## 二、Java 契约（新建 / 改动，派单按此写）

### 2.1 片段身份 `script/identity/`

| 类型 | 形态 |
|---|---|
| `ClipIdentity` | `record ClipIdentity(String id)`；`static ClipIdentity of(String)`；校验 `^[A-Za-z0-9_]{1,64}$` |
| `ClipRef` | `record ClipRef(Clip clip, String id, double entryLocalTime)`；`entryLocalTime` 缺省 0 |
| `UsageKind` | `enum { TRACK_PLACEMENT, RULE_BINDING, PATH_ENTRY, BRANCH_TARGET, EXIT_TARGET }` |
| `UsageEdge` | `record UsageEdge(UsageKind kind, String hostPath, ClipRef ref)` |
| `UsageIndex` | `final class`：`static UsageIndex build(CinematicScript)`；`List<UsageEdge> edgesOf(Clip)`；`List<UsageEdge> all()`；`Optional<Clip> resolve(String id)`；`Clip require(String id, String hostPath)` |
| `ExecutionIdentity` | `record ExecutionIdentity(int instanceToken, String clipId, long executionId)` |

- `Clip` 增 `String getClipId()`（无 id → `null`）与 `ClipIdentity identity()`（无 id → `null`）。
- `CinematicScript` 增 `List<Clip> getStandaloneClips()`、`Clip findClip(String id)`、`UsageIndex getUsageIndex()`。
- 片段 id 在解析期分配并写入 `Clip`；`UsageIndex` 在解析末尾编译。

### 2.2 规则集合 `script/rule/`

| 类型 | 形态 |
|---|---|
| `RuleDefinition` | `final class`：`id()`、`condition()`(JsonObject)、`actions()`(List<JsonObject>)、`onTimeout()`、`once()`、`cooldown()`、`mode()`、`dwell()`、`timeout()` |
| `RuleControl` | `enum { OBSERVE, HOLD }` |
| `RuleReleaseKind` | `enum { CLIP_START, CLIP_COMPLETE, LOCAL_TIME, DELAY, FACT }` |
| `RuleRelease` | `record RuleRelease(RuleReleaseKind kind, Clip clip, double at, RuleBinding source, String fact)` |
| `RuleSchedule` | `record RuleSchedule(RuleRelease release)`；`static RuleSchedule parse(JsonObject, Resolver)` |
| `RuleBinding` | `final class`：`id()`、`definition()`、`target()`(Clip)、`gateTime()`、`control()`、`schedule()`、`exitOverride()`(ClipExitPolicy) |
| `RuleCollection` | `final class`：`static RuleCollection EMPTY`；`definitions()`、`definition(String)`、`bindings()`、`binding(String)`、`bindingsFor(Clip)`、`isEmpty()` |
| `RuleCollectionParser` | `static RuleCollection parse(JsonObject root, ClipResolver, ErrorSink)`；`ErrorSink` = `java.util.function.BiConsumer<String,String>`（路径, 文案），解析期首个错误即抛 `ScriptParseException` |
| `ClipExitPolicyParser` | `static ClipExitPolicy parse(JsonObject exit, ClipResolver, String path)`；`continue`/`goto`/`return`/`end_execution`/`end_script` → `ClipExitKind` |
| `RuleFactKind` | `enum { CLIP_STARTED, CLIP_COMPLETED, RULE_SUCCEEDED, ACTION_COMPLETED }` |
| `FactSource` | `interface { boolean clipFact(String clipId, boolean started); boolean ruleFact(String bindingId, boolean actionCompleted); FactSource NONE; }`（`client/rule/` 包，供 `ClientConditions` 使用） |

### 2.3 规则运行态 `script/rule/runtime/`

| 类型 | 形态 |
|---|---|
| `SuspensionLedger` | `acquire(String source)` / `release(String source)` / `boolean held()` / `int holdCount()` / `void clear()`；同 source 幂等 |
| `RuleActivation` | 运行态记录：`binding`、`executionId`、`executionIndex`、`succeeded`、`timedOut`、`edgeTrue`、`dwellAccum`、`armed`、`holdAcquired`、`submitted`、`pending`、`pendingRelease` |
| `RuleFactStore` | `recordClipStarted(Clip, long executionId)`、`recordClipCompleted(...)`、`recordRuleSucceeded(RuleBinding, long)`、`recordActionCompleted(RuleBinding, long)`、`boolean clipStarted(Clip)`、`boolean clipCompleted(Clip)`、`boolean ruleSucceeded(RuleBinding)`、`boolean actionCompleted(RuleBinding)`、`void reset()` |
| `PendingAction` | `record PendingAction(RuleBinding binding, List<JsonObject> actions, RuleRelease release, double deadline, boolean timedOut)` |
| `RuleRuntime` | 每实例一份：`sync(CinematicScript, ScriptPlayer)`、`evaluate(ScriptPlayer)`、`reset(ScriptPlayer)`、`facts()`、`holds()`、`boolean hasPendingActions()`、`void onExecutionEntered(Clip, long)`、`void onExecutionCompleted(Clip, long)` |

### 2.4 出口与分支（改）

- `ClipExitKind` / `ClipExitPolicy` / `ClipExitResolver` 保留；新增 `ClipExitPolicyParser`（`parse(JsonObject, ClipResolver, String path)`）。
- `PlaybackInstance implements ClipBranchHost`：`now()` → `player.timelineTime()`；`seekTo(t)` → `player.seekTo(t)`；`requestScriptEnd()` → 现有声明式退出入口；`onBranchStarted` / `onBranchFailed` → 日志 + `RuleRuntime` 执行边界通知。
- `ClipBranchController.launch` 增加 launch 时间不变量（§0.2-6）。
- `ClipModifierGate` 活跃窗起点改从 `ClipTimeSource` 取（新增 `double windowStart(Clip)`）。

### 2.5 命令信任 `trigger/`

| 类型 | 形态 |
|---|---|
| `C2SRuleCommandPacket` | 字段改为 `scriptId` / `instanceId` / `bindingId`（新规则，空串 = 旧形态）/ `clipIndex` / `ruleIndex` / `actionIndex` |
| `PlaybackGrant` | `record PlaybackGrant(String scriptId, String instanceId, Set<UUID> targets, long createdTick)`；存于 `ScriptEventManager` |
| `RuleCommandSource` | `extends CommandSourceStack`，权限 = `Config.ruleCommandPermissionLevel()`（缺省 2） |
| `RuleCommandAction` | `record RuleCommandAction(String scriptId, String bindingId, int clipIndex, int ruleIndex, int actionIndex, String command, String sourcePath)` |
| `RuleCommandTable` | 脚本加载期编译：`static RuleCommandTable compile(CinematicScript)`；`RuleCommandAction resolve(String bindingId, int clipIndex, int ruleIndex, int actionIndex)` |
| `RuleCommandRateLimiter` | 按 `(playerUuid, instanceId)` 令牌桶；`boolean tryAcquire(UUID, String)` |

### 2.6 路径集合 `script/path/`

| 类型 | 形态 |
|---|---|
| `PathEntry` | `record PathEntry(String id, Clip target, float from, float to, AxisConstraint axes, PathSpec geometry, PathTiming timing)` |
| `PathCollection` | `final class`：`static PathCollection EMPTY`；`entries()`、`entriesFor(Clip)`、`isEmpty()` |
| `PathCollectionParser` | `static PathCollection parse(JsonArray, ClipResolver, PositionSequence, Errors)` |
| `PathCollectionSource` | `implements PathBindingSource`：`bindingsFor(Clip)` → `List<PathBinding>`（首次访问时按片段缓存） |

---

## 三、任务清单（编号 / 目标 / 验收 / 依赖）

| 编号 | 目标（一句话） | 验收 | 依赖 | 状态 |
|---|---|---|---|---|
| **I4-1** | 片段身份 + 脚本级独立片段表 + 使用边索引：`Clip.id`、`clips[]` 解析、`UsageIndex` 编译、悬空/重复/缺 id 校验 | 探针：id 唯一、重复报错、被引用无 id 报错、独立片段可解析、使用边可反查；旧 231 支解析/校验基线 diff 0 | — | 完成 |
| **I4-2** | 规则集合数据模型 + 解析 + 校验：`rules` / `rule_bindings`、定义/绑定身份、时间门必选与越界、定义与绑定引用、动作类型、`clip.rules` 与新集合互斥 | 探针：缺 gate / gate 越界 / 未知 rule / 未知 clip / 重复 id / 同时出现旧 `clip.rules` 均报错；合法脚本解析出正确绑定表；`SchemaExporter` 输出结构 schema | I4-1 | 完成 |
| **I4-3** | 规则运行态核心：激活建立（执行进入）、时间门 `>=`、门前事实采集、门后条件判定、`observe` / `hold`、`once` / `cooldown` / `mode` / `dwell` / `timeout` | 探针：多入口独立计时、迟到成立、门前事实不丢、`any` 不能绕过门、动作不在门前执行、跳过执行不建激活 | I4-2 | 完成 |
| **I4-4** | 实例级挂起 + 多来源持有：`SuspensionLedger`、`pause` / `resume` 动作、释放可达性诊断 | 探针：多来源持有、最后一个释放才恢复、`resume` 释放全部 `pause` 持有、不可达诊断文案、`hold` 与 `observe` 差异 | I4-3 | 完成 |
| **I4-5** | 事实接力 + 延迟动作 + 完成依赖：`RuleFactStore`、`schedule` 五种释放条件、待执行动作参与自然完成 | 探针：成功≠动作完成、A 成功启动 B、延迟到片段开始/完成/局部时间/延迟/事实、待执行动作阻止自然结束、非阻塞观察不阻止 | I4-3 | 完成 |
| **I4-6** | 出口三族 + 分支接线：`exit` 解析、`ClipExitResolver` 默认表、`PlaybackInstance` 实现 `ClipBranchHost`、`goto_clip` 新形态、launch 不变量、Modifier 活跃窗按分支入口 | 探针：正常完成走片段默认 / 入口覆盖优先、失败只回主线不执行后继、`END_EXECUTION` 与 `CONTINUE` 等价定性、launch 时间不变量、分支 + Modifier 活跃窗 | I4-3, I4-5 | 完成 |
| **I4-7** | 命令信任边界：动作引用协议、`PlaybackGrant`、服务端动作表、受限命令源（默认 2）、限频、审计、旧原文包丢弃 | 探针：伪造回执不增观看者、伪造动作引用不执行、合法请求只执行脚本命令、权限 3/4 被拒、EVENT 仍 4、旧包丢弃 | I4-2 | 完成 |
| **I4-8** | 脚本级 `paths[]` 集合（I3-B1）：解析 / 校验 / 索引 + 接 `ObjectCameraEvaluator#setPathBindingSource` | 探针：line/bezier、2/3 轴、显式 timing、多条目、端点相邻性、越界不生效、无条目回落采样属性 | I4-1 | 完成 |
| **I4-9** | 服务端 EVENT seek 协议（I3-B2）+ 循环/多入口重放定性（I3-B3） | 探针：前跳不补触发、后跳不重复、暂停恢复；文档写明不重放 + 作者替代路径 | — | 完成 |
| **I4-10** | 卫生项：删除 `ColorAdjustSampler#sampleCameraLaneObjects` 的 `ClipValueView` 参数并同步调用点 | 编译通过；`grep` 无旧签名 | — | 完成 |
| **I4-11** | 探针 + 实机 + 文档回写 + 新旧差异报告 | 全部探针 PASS；旧解析/校验基线 diff 0；至少一条含挂起/事实接力的实机规则脚本跑通；8 篇规则专篇状态回写；`I-BACKLOG.md` 对应条目关闭 | 全部 | 待收口（文档已回写，探针全 PASS；实机待补） |

### 依赖图

```
I4-1 ──┬─→ I4-2 ──┬─→ I4-3 ──┬─→ I4-4
       │          │          ├─→ I4-5 ──→ I4-6
       │          └─→ I4-7
       └─→ I4-8

I4-9  ─ 独立
I4-10 ─ 独立
I4-11 ─ 全部之后
```

---

## 四、现状与缺口（源码核对，2026-11-03）

| 设计目标 | 现状 | 缺口归属 |
|---|---|---|
| 片段稳定身份 / ClipRef / 使用边 | `Clip` 无 id；片段是 `track.clips[]` 匿名元素；无脚本级 `clips` 表；`goto_clip` 按轨道 + 时间 | **本轮 I4-1** |
| 脚本级规则集合（定义/绑定/激活） | `clip.rules` 每规则一槽，槽 = 片段活跃窗 + 规则，多入口会互相覆盖；无定义/绑定分层 | **本轮 I4-2 / I4-3** |
| 必选时间门 + 门前采集 + 门后提交 | 规则在片段活跃窗起点激活，窗口外不判定；无时间门 | **本轮 I4-3** |
| 实例级挂起 + 多来源持有 | `until` 与 `pause` 共用实例级停住状态但分开记账；同片段无释放者自动解除；无来源计数 | **本轮 I4-4** |
| 事实接力 + 成功≠动作完成 + 延迟动作 | 条件成立即提交动作；无事实层、无调度 | **本轮 I4-5** |
| 正常完成去向 + 失败 fallback + 退出三族 | `ClipExitKind/Policy/Resolver/Controller` 已实现但**零生产接线**；`goto_clip` 是 seek 不是分支 | **本轮 I4-6** |
| 命令信任边界 | 客户端发命令原文；服务端只查观看者；观看者可由客户端回执自登记；权限硬编码 4；无 grant / 限频 / 审计 | **本轮 I4-7** |
| 脚本级路径集合 | `PathBinding` / `PathBindingSource` / `PathBindingResolver` / `PathTiming` 已实现（消费侧）；`ObjectCameraEvaluator#setPathBindingSource` 存在但**零调用**；无生产者 | **本轮 I4-8** |
| EVENT seek 协议 | 服务端 `scannedThroughSeconds` 单调推进；无 seek 包；`EventClipRuntime#reset` 无协议调用点 | **本轮 I4-9** |
| `ColorAdjustSampler#sampleCameraLaneObjects` 的 `ClipValueView` 参数 | 已无使用 | **本轮 I4-10** |
| 格式清理 / 迁移器 / 旧字段拒绝 | 旧字段仍可读 | 移交 **I-5** |
| `docs/SCRIPT_FORMAT.md` 重写 | 过时 | 移交 **I-5**（I2-B10） |
| 宏观循环 L6 执行次数口径 | 未决 | 移交 **I-5**（I2-B11） |
| AUDIO `file_loop` 改名 | 未做 | 移交 **I-5**（I2-B12） |
| 编辑器规则/路径可视化编辑 | 未做 | 移交 **Z 轮** |

### 契约边界（本轮写入专篇，不实现）

- 分支失败只恢复执行事实（账本 + 播放头），**不回滚音频 / 覆盖层副作用**（I2-B8）。
- `END_EXECUTION` 与 `CONTINUE` 在无调用栈前提下**行为等价**；`RETURN` 按显式目标表达（I2-B4）。
- 无限内容不自然完成；等待无限片段完成 = 不可达依赖，给诊断不自动解除。
- 事实不跨实例、不做无界历史；跨执行历史匹配列为后续结构评审。
- 规则命令的服务端不证明“规则真的成立”，只保证命令来自脚本定义。

---

## 五、验证工具

| 工具 | 命令 |
|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon` |
| 旧解析基线 | `sh E:/tmp/i1/run-parse.sh "E:/Documents/GitHub/ImmersiveCinematics/cinematics"` → 与 `E:/tmp/i1/baseline-parse.txt` diff |
| 旧校验基线 | `sh E:/tmp/icv/run.sh "E:/Documents/GitHub/ImmersiveCinematics/cinematics"` → 与 `E:/tmp/i1/baseline-icv.txt` 对比 |
| I-2 探针回归 | `sh E:/tmp/i2/run-loop.sh`、`run-branch.sh`、`run-validator-loop.sh` |
| I-3 探针回归 | `sh E:/tmp/i3/run-object.sh` … `run-fixtures.sh`（12 个） |
| 本轮探针 | `E:/tmp/i4/`：`run-identity.sh`、`run-rule-parse.sh`、`run-rule-gate.sh`、`run-rule-hold.sh`、`run-rule-facts.sh`、`run-exit-branch.sh`、`run-command-trust.sh`、`run-path-collection.sh`、`run-event-seek.sh` |
| 实机 | `tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py client "<世界>"`、`ic play`、`ic tail` |

---

## 七、验收记录（2026-11-03）

### 7.1 本轮探针（`E:/tmp/i4/`）

| 命令 | 结果 | checks |
|---|---|---|
| `sh E:/tmp/i4/run-identity.sh` | PASS | 45 |
| `sh E:/tmp/i4/run-rule-parse.sh` | PASS | 142 |
| `sh E:/tmp/i4/run-rule-gate.sh` | PASS | 80 |
| `sh E:/tmp/i4/run-rule-hold.sh` | PASS | 56 |
| `sh E:/tmp/i4/run-rule-facts.sh` | PASS | 65 |
| `sh E:/tmp/i4/run-exit-branch.sh` | PASS | 110 |
| `sh E:/tmp/i4/run-path-collection.sh` | PASS | 59 |
| `sh E:/tmp/i4/run-event-seek.sh` | PASS | 55 |
| `sh E:/tmp/i4/run-command-trust.sh` | PASS | 55 |

合计 667 checks，全部 PASS。

### 7.2 旧基线回归

| 命令 | 结果 |
|---|---|
| `sh E:/tmp/i1/run-parse.sh "<repo>/cinematics"` → 与 `E:/tmp/i1/baseline-parse.txt` 按文件路径逐行对比 | 旧 231 支 **diff 0**。当前总数 parsed=235 / failed=6 / total=241（基线 225 / 6 / 231），多出的 10 支是 I-3/I-4 新增新格式夹具 |
| `sh E:/tmp/icv/run.sh "<repo>/cinematics"` → 与 `E:/tmp/i1/baseline-icv.txt` 按脚本名逐行对比 | 旧 231 支 **diff 0**。当前 18 of 241 have issues（基线 18 of 231）；本轮新增夹具 `cinematics/0.3.6/tests/rules/test_rules_gate_suspend.json` 已改为 v1 动作参数形态（`adjust_set` 平铺通道、`overlay_set` 去 `targets`）并通过校验 |

### 7.3 前置轮次回归（本轮重跑）

| 命令 | 结果 | checks |
|---|---|---|
| `sh E:/tmp/i2/run-loop.sh` | PASS | 169 |
| `sh E:/tmp/i2/run-branch.sh` | PASS | 135 |
| `sh E:/tmp/i2/run-validator-loop.sh` | PASS | 20 |
| `sh E:/tmp/i3/run-object.sh` | PASS | 40 |
| `sh E:/tmp/i3/run-camera.sh` | PASS | 57 |
| `sh E:/tmp/i3/run-overlay.sh` | PASS | 76 |
| `sh E:/tmp/i3/run-audio.sh` | PASS | 139 |
| `sh E:/tmp/i3/run-event.sh` | PASS | 33 |
| `sh E:/tmp/i3/run-path.sh` | PASS | 22 |
| `sh E:/tmp/i3/run-morph.sh` | PASS | 59 |
| `sh E:/tmp/i3/run-schema.sh` | PASS | 42 |
| `sh E:/tmp/i3/run-clip.sh` | PASS | 18 |
| `sh E:/tmp/i3/run-struct-norm.sh` | PASS | 13 |
| `sh E:/tmp/i3/run-validator-object.sh` | PASS | 22 |
| `sh E:/tmp/i3/run-fixtures.sh` | PASS | 38（clips 19） |

### 7.4 实机（2026-11-03，Fabric 1.20.1 dev 客户端 + `quickPlaySingleplayer "新的世界"`）

夹具：`cinematics/0.3.6/tests/rules/test_rules_gate_suspend.json`（时间门 1.0 / `control=hold` + `timeout=1.0` / `rule_fact` 接力 / `schedule=delay 0.5` / `goto_clip` 分支 + 片段默认出口）。

| 观测点 | 证据 |
|---|---|
| 播放与自然结束 | `Script 'test_rules_gate_suspend' fully complete — all viewers finished` |
| **挂起生效** | 相机 x 在 `2.00` 连续保持约 1 秒（`13:34:56`→`13:34:57` 多条 `POS x=2.00`），随后 `2.03 → 2.29` 继续推进；对应 `control=hold` 冻结 + `timeout=1.0` 真实时钟释放 |
| **分支跳转** | `x=5.79 → 20.03`（`branch_a` 起点 20，y 64→66），并推进到 `21.99`；证明 `goto_clip` 经 `ClipBranchController.launch` 生效 |
| **延迟动作** | `[Rules] 规则 r_delay（绑定 b_delay）的待执行动作已提交：delay` |
| 错误 | 无本轮相关 ERROR（仅既有夹具的触发器 `requires` 提示） |

**实机发现的缺口（已登记 `I-BACKLOG.md` I4-R6）**：脚本级独立片段（`clips[]`）能被 `launch` 记账、但不被任何轨道播放器遍历，因此分支启动的独立片段**没有画面**。本夹具因此把分支目标放在 CAMERA 轨内（`start_time: 10`），分支启动把窗口改到 4.0；独立片段的渲染归属后续轮次。

### 7.5 未覆盖

- **像素级回归**：本轮未做改动前后截图对比（无基线截图）；旧格式解析/校验基线 diff 0 + 探针覆盖是当前证据边界。
- **独立片段渲染**：见 §7.4 与 `I-BACKLOG.md` I4-R6（归属 I-5）。
- **Forge 实机**：本轮只跑 Fabric；Forge 侧仅编译验证。

---

## 八、本轮明确不做

- 不改旧 `clip.rules` 引擎的任何求值语义（`RuleEngine` / `RuleActions` 的旧入口保持逐位行为）。
- 不做格式清理、迁移器、旧字段拒绝、`file_loop` 改名、`total_duration` / `meta.version` 删除（I-5）。
- 不做编辑器 UI / 规则可视化（Z 轮）；只保证 `SchemaExporter` 输出结构 schema。
- 不新增调用栈 / 嵌套分支 / 并行流程 / 全局片段库。
- 不改 EVENT 命令协议、去重身份、权限 4 口径（只加 seek）。
- 不做通用变量语言、任意表达式求值、外部注册判定源/动作/事实总线。
