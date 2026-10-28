# 片段循环：按次数执行

> 状态：未实现
> 范围：片段循环的执行次数、每次执行的局部时间、展开窗口、pingpong、状态策略、宏观循环边界
> 关联：`done/script-loop.md`（现行循环设计，本文与其对齐并补 I 轮对象模型的落点）、`object-model.md`、`runtime-evaluation.md`、`track-camera.md`、`track-overlay-letterbox.md`、`track-audio-adjust.md`、`track-event.md`

## 一、摘要

片段循环不是“重新计时”，也不是把全局时间取模折回。它是**片段级的执行次数**：声明了 `loop` 的片段，按 `loop_count` 执行 N 次；每次执行都有自己的 `executionLocalTime`（0 → duration）。非循环片段不受影响，仍按自己的 `start_time`/`duration` 执行一次。播放控制层负责“这个片段再执行一次”，关键帧只看到当前这次执行的局部时间；`windowEnd = start + duration × N` 是展开后的活跃跨度，是执行次数的结果，不是驱动循环的时钟。

## 二、为什么做

### 2.1 问题

- 当前 `KeyframeInterpolator.computeInterpolation` 仍用 `%`/镜像折叠片段局部时间；这是实现层的旧口径，不是“按次数执行”的语义。
- 周期 = 末关键帧时间 − 首关键帧时间（`getAnimPeriod`），不是 `duration`；有限窗口 = `start + animPeriod × loop_count`。如果关键帧没有铺满 `duration`，窗口和视觉都对不上。
- `ScriptPlayer` 的 `lapsBefore`/`clipConsumedAt` 也基于 `animPeriod`；执行事实和窗口可能不一致。
- `Validator` 强制 `duration` 与关键帧周期匹配，掩盖了周期/时长混淆。
- 我上一版文档把循环写成 `floor(elapsed/duration)` 的时间驱动 lap，并给了一套“每 lap 重置”的表；这既不符合现有 `done/script-loop.md` 的“按次数执行”，也没有代码依据。本文按现有循环设计重写。

### 2.2 目标

- `loop_count` 是执行次数，不是时间周期；播放控制按次数调度。
- 每次执行只暴露 `executionIndex` + `executionLocalTime`；对象采样器只认这次执行的局部时间。
- 只有声明了 `loop` 的片段重复；非循环片段仍按自己的定义执行一次，不被别的片段循环改变。
- 删除 `getAnimPeriod()`、`%` 折叠、`LaneMask.effectiveTime`、有限循环末端钳到末两帧的逻辑。
- 状态策略与现有循环设计一致：只重置 `PlayerMoveController`；音频实例跨边界仍活跃则不重启；相机/覆盖层每帧重查；服务端 EVENT 不重复。
- 宏观循环保持现有语义：`macro_loop_count` 是圈数，`[a,b]` 是区间；宏观循环只作用于客户端表现层。

### 2.3 非目标

- 不改变宏观循环的 `macro_loop_count`/`macro_loop_mode` 语义；`pingpong` 宏观循环仍未实现（按 repeat 播放，validator 告警）。
- 不改变暂停/挂起/退出模型；`pause` 仍保持内容位置，EVENT 随实例冻结。
- 不改变 `hold_at_end` 的驻留点定义；仍用 `contentEnd`。
- 不做编辑器循环展开/折叠；预览按真实时间线播放。
- 不新增“每 lap 全状态重置”的通用机制；状态策略见 §3.5，改动需单独设计。

## 三、方案

### 3.1 字段与执行次数

| 字段 | 位置 | 类型 | 默认 | 语义 |
|---|---|---|---|---|
| `loop` | clip | bool | false | 是否按次数重复执行这个片段 |
| `loop_count` | clip | int | -1 | 执行次数：`-1` = 无限次；正整数 N = 执行 N 次；`0` 非法 |
| `loop_mode` | clip | enum | repeat | 每次执行内的遍历方式：`repeat` = 正向 0→duration；`pingpong` = 正向/反向交替 |

- `loop=false`：只执行 1 次；`loop_count` 写了也忽略（validator 可警告“写了但无效”）。
- `loop=true`、`loop_count=N>0`：执行 N 次；第 N 次结束后片段执行完。
- `loop=true`、`loop_count=-1`：无限次；片段永不执行完，宏观末端不存在。
- `loop_count=0`：非法；validator 报错（运行时按 1 处理只作为防御）。
- `loop`/`loop_count`/`loop_mode` 从「仅 CAMERA 声明」提升为**所有轨道共用的 clip 字段**；AUDIO 的文件循环改名 `file_loop`，不再与执行 `loop` 冲突。这是 schema/格式变更，旧脚本由迁移器处理。

### 3.2 执行状态

播放控制层对每个循环片段维护：

```text
executionIndex       // 当前是第几次执行，0 起
executionLocalTime   // 当前这次执行的局部时间，0..duration
executionsRemaining  // 剩余执行次数；无限 = 无上限
executionDone        // 当前片段是否已执行完
```

- 每次执行开始时，`executionLocalTime=0`；播放控制按帧推进到 `duration`；到 `duration` 后：
  - 还有剩余次数 → `executionIndex++`，`executionLocalTime=0`，开始下一次执行；
  - 没有剩余次数 → `executionDone=true`，片段不再活跃。
- `windowEnd = start + duration × N`（N 有限）是展开后的活跃跨度；它是**结果**，不是驱动循环的时钟。无限次 → `windowEnd=∞`。
- 非循环片段只有 `executionIndex=0` 一次执行；它的 `start_time`/`duration`/`loop` 定义不被其他片段的循环改变。
- 时间轴上的片段顺序/重叠仍按 lane 模型处理；循环只扩展该片段自己的执行跨度，不是把整个时间轴取模。
- 纯函数轨道（相机/覆盖层/调色/黑边）可以拿 `executionLocalTime` 直接求值；实现上允许用除法/取模从时钟推 `executionIndex`，但**语义和控制状态是次数**，不是“重新计时”。

### 3.3 每次执行的局部时间

- `executionLocalTime` 始终在 `[0, duration]`；对象关键帧在这条轴上采样。
- `repeat`：每次执行 0→duration。
- `pingpong`：第 0 次正向 0→duration，第 1 次反向 duration→0，第 2 次正向……；`executionLocalTime` 仍在该次执行的 `[0,duration]` 内，方向由执行序号决定。
- `loop_count=N` 是总执行次数（正向/反向各算一次）；`N=1` 时 pingpong 没有反向，validator 警告。
- `pingpong` 只允许视觉轨道（CAMERA/OVERLAY/ADJUST/LETTERBOX）；AUDIO/EVENT/MOD_EVENT 拒绝（音频/事件没有反向语义）。
- `duration=0`：瞬间片段；只允许 EVENT/瞬时动作；视觉/音频片段 validator 警告或拒绝。
- `duration<0`：无限时长哨兵，不是循环；`windowEnd=∞`，片段只执行一次且时间持续增长；`loop`/`loop_count` 与负 duration 同时出现时 validator 报错。该哨兵没有 fixture 使用，后续可单独清理。

### 3.4 展开窗口与内容末端

```text
windowEnd =
  duration < 0                 -> +∞
  !loop                        -> start + duration
  loop && loop_count < 0       -> +∞
  loop && loop_count > 0       -> start + duration * loop_count
```

- `Clip.getWindowEnd()` 用这个公式；不再用 `getAnimPeriod()`。
- `Clip.isEffectivelyInfinite()` = `duration < 0 || (loop && loop_count < 0)`。
- `contentEnd = max(clip.getWindowEnd())`（无限片段 -> ∞）；`Timeline` 不再存 `totalDuration`。
- `isActiveAt(t)` 用展开窗口；窗口结束那一帧由 transition/hold 处理，不由活跃片段重复采样。
- 有限循环的最后一圈在 `windowEnd` 结束；不会“停在末两帧”或“回到对象值”。
- 非循环片段的窗口仍是 `start + duration`；循环片段只改变自己的窗口。

### 3.5 状态策略（与现有循环设计一致）

循环边界（一次执行结束、下一次执行开始）**不自动重置所有状态**。现有 `done/script-loop.md` 的口径：

| 状态 | 执行边界行为 | 说明 |
|---|---|---|
| 关键帧值/对象采样 | 每次执行用新的 `executionLocalTime` 重新求值 | 纯函数，天然重复 |
| 相机 motion（`motion_*`） | **继续**，不重置 | 状态按时间推进；只在 seek/片段定义变化时归位 |
| 相机 breath（`cam_breath_*`） | **继续**，不重置 | 它是 `globalTime` 的确定性函数，没有“执行圈”概念 |
| 选择器锁/点源冻结 | **继续**；seek 会清点源冻结 | 没有统一的执行边界重置路径 |
| 相机 transition | 执行边界不触发 transition | transition 是片段间转场，不是执行间转场 |
| OVERLAY / LETTERBOX | 每次执行按局部时间重新求值；图层生命周期由活跃窗口/播放控制决定 | 不额外重置淡出/图层 |
| AUDIO 实例 | 跨执行边界**仍然活跃的实例不重启**；不再活跃的，下次执行重新起实例 | 与现有 `done/script-loop.md` §10 一致 |
| EVENT | 服务端不参与客户端循环；事件按自己的时间线触发一次，不按执行次数重复 | 与现有设计一致 |
| 玩家移动目标索引 | 执行边界**重置** | 现有 `PlayerMoveController.onScriptLoop`；这是唯一明确需要重置的状态 |
| 宏观循环 | 整个实例重新执行；只重置玩家移动，服务端 EVENT 不重复 | 宏观循环是另一层，见 §3.6 |

- 如果作者希望音频/事件每次执行都重启，应在内容层用分段/多条轨道表达，或另开“每执行重置”设计；I 轮不偷偷改。
- 如果作者希望 motion/breath 每次执行重置，当前不支持；它们会连续。需要确定性的每执行一致运动时，用多个片段/显式关键帧。

### 3.6 宏观循环

- `meta.macro_loop`、`macro_loop_count`、`macro_loop_mode`、`timeline.loop_start`/`loop_end` 保持现有语义；宏观循环是脚本级“整段/子区间重复执行 N 次”。
- 宏观循环只作用于客户端表现层；服务端 EVENT 不重复。
- 圈边界（局部时间回绕）是折返点；玩家移动目标索引按圈复位（现有口径）。
- `macro_loop_mode=pingpong` 仍未实现，按 repeat 播放并告警。
- 编辑器预览不做循环展开；按真实时间线播放。

### 3.7 校验

- `loop_count >= -1`；`0` 报错。
- `loop=true` 时 `loop_count` 缺省 = -1；`loop=false` 时 `loop_count` 写了告警。
- `loop_mode=pingpong`：仅视觉轨道；`loop=false` 或 `loop_count=1` 告警“无效果”。
- 删除“duration 与关键帧周期不匹配”的校验；关键帧超出 duration 按对象模型忽略。
- 片段重叠仍允许（lane 模型）；不因循环改变重叠规则。
- 非循环片段不因其他片段循环改变自己的 `start_time`/`duration`；validator 不要求所有片段一起展开。

## 四、技术细节

### 4.1 核心模型

```text
ClipExecution
  start
  duration
  loop
  loopCount
  loopMode
  executionIndex
  executionLocalTime
  executionsRemaining
  done
```

- `ClipExecution` 是播放控制状态，不是对象采样器状态；每个播放实例每个片段一份。
- `advance(dt)`：推进 `executionLocalTime`；到 duration 时按 `executionsRemaining` 决定“下一次执行”或“done”。
- `sampleTime()`：返回当前 `executionLocalTime`；`direction` 由 `loopMode` + `executionIndex` 决定。
- `windowEnd` 在加载时预计算；`executionDone` 由执行状态给出，不用浮点窗口比较驱动。
- `ScriptPlayer` 用 `ClipExecution` 做活跃/消费/内容事实；不再用 `getAnimPeriod`。

### 4.2 契约

- **创建**：clip 加载时计算窗口/无限标志；实例创建时初始化 `executionIndex=0`、`executionsRemaining=loopCount`。
- **销毁**：随实例/clip 销毁；无跨实例共享执行状态。
- **状态归谁**：执行次数/局部时间归播放控制；每帧派生状态归各轨道（见 §3.5）；宏观循环归 `ScriptPlayer`。
- **边界**：`ClipExecution` 不知道对象/轨道语义；只给“第几次执行 + 局部时间 + 方向”。对象采样器不知道 `loop_mode`；只认 `executionLocalTime`。
- **实现自由**：纯函数轨道可以用 `floor(elapsed/duration)`/取模从时钟推执行状态，但这是实现细节；状态机语义以次数为准，不能反过来把时间当驱动。

### 4.3 数据形态

- `executionIndex` int；无限执行可增长到 int 上限（实际播放不会到）。
- `executionLocalTime` float；`[0,duration]`；pingpong 反向也在这个范围。
- `direction` enum `forward`/`backward`；非 pingpong 恒 forward。
- `windowEnd` float 或 `+∞` 哨兵；`infinite` bool 避免浮点比较。
- `loopCount` int；`-1` 无限；`0` 非法；`N>0` 次数。

### 4.4 边界

- 不改变 transition 的混合公式；只改变窗口/局部时间来源。
- 不改变 motion/breath 的数学模型；只定义执行边界不重置。
- 不改变选择器/点源解析；只定义执行边界不重置。
- 不改变宏观循环的服务端 EVENT 排除规则。
- 不改变 `hold_at_end` 的驻留点定义。
- 不改变非循环片段的 `start_time`/`duration` 定义；循环只扩展自己的执行跨度。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 时间驱动 lap（`floor(elapsed/duration)`） | 把循环当成全局时钟的模 | 把“执行次数”变成“重新计时”；其他片段被时间轴绑死；与现有循环设计冲突 | 按次数执行 |
| 保留 `%`/镜像折叠 | 采样前把时间折回周期 | 关键帧轴不是执行轴；窗口用周期不是 duration；LaneMask 重复实现 | 执行次数 + 每次局部时间 |
| 所有片段一起循环 | 时间轴整体取模 | 非循环片段也被重复；不符合“只影响有 loop 的片段” | 只循环声明 loop 的片段 |
| 执行边界全状态重置 | motion/breath/selector/audio/event 全部归零 | 手持/噪声跳变；音频尾巴被切；事件重复；与现有设计冲突 | 只重置玩家移动；其他状态按现有口径 |
| 删除 pingpong | 只支持 repeat | 少一个已测试 fixture/作者能力；反向需求无法表达 | 保留为每次执行内的遍历模式，限制轨道 |
| 负 duration 继续作为无限 | 保留旧哨兵 | 对象轴 `[0,duration]` 失效；每个轨道特判 | 保留但隔离，建议用 loop_count=-1 |

**本方案的缺点**：

- AUDIO/EVENT 不按执行次数重复；作者可能期望“循环就重播”，需要文档/编辑器提示。
- 只有玩家移动在执行边界重置；motion/breath/selector 连续，循环不是“每一遍完全一样”。
- 循环片段的展开跨度会改变后续片段的实际播放位置；作者需要理解 `start_time`/`duration`/`loop_count` 的组合。
- `pingpong` 只允许视觉轨道；音频/事件不能反向。
- 负 `duration` 哨兵保留，对象轴有例外；后续清理会再动一次格式。
- 实现上仍可能需要除法/取模来从时钟推执行状态；如果写成“时间驱动”，很容易又滑回旧语义。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
