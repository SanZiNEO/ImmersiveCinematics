# 脚本对象模型（Object Model）

> 状态：未实现
> 范围：脚本数据形态、对象路径、关键帧组、取值/插值/边界、schema 对象声明
> 关联：`I-script-core/README.md`、`schema-and-types.md`、`runtime-evaluation.md`、`loop-execution.md`、`migration.md`；吸收 `G-script-model/script-model.md` 的 G1/G2，循环口径对齐 `done/script-loop.md` 的按次数执行

## 一、摘要

脚本从「轨道 → 片段 → 共享关键帧快照」改为「轨道 → 片段 → 字段容器」：每个字段有独立的 `value` 和 `keyframes[]`，关键帧只属于一个字段，不再有一帧携带全字段的快照。字段是叶子：`position.x`、`yaw`、`opacity` 各是一个字段；能独立调的量就是一个字段。运行时按 schema 逐字段求值，轨道播放器只读自己需要的路径并组装语义值；编辑器负责分组、缓动烘焙和呈现。

## 二、为什么做

### 2.1 问题

- **加一个字段要动所有关键帧。** 当前一个 `Keyframe` 是所有字段的稀疏快照；新增一个可调项，旧关键帧都要补默认值或依赖运行时逐字段回落，作者和工具都容易被“这一帧到底有没有写这个字段”绊住。
- **复合值只能整体调。** `position`、`dest`、`source` 是 map，只能整体插值；想只动 x 轴、只改矩形宽度，做不到。`script-model.md` 已把这条列为 G1 的未实现项。
- **稀疏字段的回落口径分裂。** 运行时对缺失字段取字段默认值（`KeyframeInterpolator`），编辑器只插值两端都存在的字段（`operations.ts`），迁移时同一份脚本会出现两套运动。
- **循环在折叠时间。** 片段循环用 `%`/镜像把 clip 内时间折回去，`LaneMask` 又复制了一份；关键帧轴不是真实执行轴。
- **两个字段没有信息量。** `meta.version` 只有一个值 3；`timeline.total_duration` 可以从片段窗口推导，却被 parser/validator/编辑器/模板反复写入和校验。

### 2.2 目标

- 新增/删除一个可调项，只动该对象的 schema 和消费代码，不改关键帧容器，也不改其他对象的 keyframes。
- 每个叶子对象可以独立加、删、移动关键帧；同一时间在不同对象上出现关键帧是合法的。
- 运行时只认「路径 + 值 + 关键帧」，不认编辑器分组；轨道播放器只读自己需要的路径。
- 同一片段内所有对象在同一 `t` 求值，然后组装；没有“先算 A 再算 B、B 读到 A 的临时状态”的隐藏顺序。
- 片段循环是执行层重复：关键帧永远在 `[0, duration]` 内采样，不做时间折叠。
- schema 是 parser、validator、编辑器、模板的唯一字段来源；不再维护硬编码通道表。

### 2.3 非目标

- 编辑器 UI、对象分组、曲线编辑、组合呈现：编辑器阶段。
- `meta` 静态字段关键帧化（G3）：本轮不做；对象模型只预留扩展点。
- 时间精度（float → double / 有理数）：本轮不动，关键帧时间仍是 float 秒。
- 旧格式兼容层、双语法、解析期分桶：不做。

## 三、方案

### 3.1 作者看到的形态

一个片段有 `objects`，键是稳定路径，值是对象：

```json
{
  "start_time": 0,
  "duration": 5,
  "objects": {
    "position.x": {
      "value": 0,
      "keyframes": [
        {"time": 0, "value": 0},
        {"time": 5, "value": 10}
      ]
    },
    "position.y": {
      "value": 2
    },
    "yaw": {
      "keyframes": [
        {"time": 0, "value": 0},
        {"time": 5, "value": 90}
      ]
    },
    "opacity": {
      "value": 1
    }
  }
}
```

- `objects` 是 JSON object，键是点分路径；键顺序无意义。
- 对象只放叶子；`position`、`dest`、`source`、`color`、`look_at_part`、`look_at_offset` 这类 JSON map 会展开成叶子路径（`position.x`、`dest.w`、`color.r`……）。
- 已有的标量字段名保持不变（`yaw`、`mask_x`、`mix_rr`、`lut_strength`……）；只有 JSON object/map 才展开。这样迁移是机械的，编辑器可以按 `section` 分组显示。
- `value` 是对象静态值；`keyframes` 非空时覆盖 `value`；`keyframes` 为空/省略时用 `value`；对象缺省时用 schema 默认值。
- 对象没有 `value` 也没有 `keyframes` 等价于缺省；parser 可以丢弃，不报错。
- 关键帧 `time` 相对片段起点，轴是 `[0, duration]`；`time` 落在轴外的关键帧不报错、不参与求值，范围内最后一帧就是末帧，hold 到片段结束。
- 同一对象内 `time` 严格递增；重复时间报错。不同对象之间同一时间合法。
- 非有限值（NaN/Infinity）解析期拒绝；字符串、枚举、布尔、引用按 step；数值按 schema 声明的插值方式。

### 3.2 对象路径

- 路径是 track-scoped 的：`CAMERA` 的 `opacity` 和 `OVERLAY` 的 `opacity` 由各自 track schema 解析，互不影响。
- 路径段用 `.` 分隔；段名与现有字段名一致，使用 `[a-z][a-z0-9_]*`。不引入数组下标；事件记录是离散对象值，不靠路径下标区分。
- 复合 map 的展开规则：
  - `position` → `position.x` / `position.y` / `position.z`；
  - `dest` / `source` → `.x` / `.y` / `.w` / `.h`；
  - `color`（OVERLAY 关键帧颜色）→ `color.r` / `color.g` / `color.b`；
  - `look_at_part` → `look_at_part.x` / `.y` / `.z`；
  - `look_at_offset`、`facing_target_offset`、`yaw_base_from_offset`、`yaw_base_to_offset` → `.x` / `.y` / `.z` + 显式的 `*.space` step 对象（`world` / `base`）；
  - 其余已是标量的字段不展开。
- 路径是稳定标识：schema、脚本、迁移器、编辑器选择状态都用同一字符串；运行时编译成整数槽位，不在每帧做字符串查表。
- 路径拼写错误是解析/校验错误，不做模糊匹配、不做别名。

### 3.3 字段形态

`objects` 是字段容器，不是“只能放关键帧对象”的容器。一个字段有三种形态：

- **value-only**：只有 `value`，没有 keyframes。用于作用于片段/段、但不需要逐帧变化的定义：`curve`（轴集合 + 控制点）、`orient`、`dimension`、`mask_shape`、`layer_type`、`sound`……它们不需要单独的 clip meta，直接放在字段容器里，脚本不会多一层。
- **animated**：`value` + 可选 `keyframes`。用于可独立调的量：`position.x`、`yaw`、`opacity`、`yaw_offset`、`pitch_offset`……
- **event**：`keyframes` 的值是离散记录，不插值：`event`/`mod_event`。

字段可以声明**前置条件**（`requires`）：例如 `yaw_offset`/`pitch_offset` 要求 `orient=tangent`；`look_at` 的目标/偏移要求 `look_at != none`；`curve` 的轴控制点要求对应轴存在。前置条件不满足时字段不读取/不校验（编辑器置灰/隐藏），满足时才参与求值。前置条件可以递归（A 开才读 B，B 开才读 C），但 schema 构建期必须拒绝环。

**只有播放控制/结构字段留在 clip 外层**：`start_time`、`duration`、`loop`/`loop_count`/`loop_mode`、`rules`。其余“作用于这个片段/这一段”的字段都进 `objects`；是否带 keyframes 由字段形态决定，而不是由“是不是 clip 级”决定。这样 `curve`/`orient` 不需要进 meta，也不会把脚本撑成两层结构。

`curve` 是一个 value-only 字段，不拆成 `curve.x/y/z`：`axes` 是轴集合（至少两个轴、去重、顺序无关，`["y","x"]` 与 `["x","y"]` 等价），`control_points` 是两个 3D 控制点。`curve` 是片段级一份，同一组控制点对每个“位置组”段复用；位置组 = `position.x/y/z` 同时间关键帧。有 `curve` 时三轴关键帧时间集合必须完全一致、每个时间点三轴都有显式值，缺值/不对齐 = validator 错误；没有 `curve` 时三轴仍可独立。轴集合选 2 轴时做该平面内的 2D 弧长，选 3 轴时做 3D 弧长。`orient` 是 value-only 模式；`yaw_offset`/`pitch_offset` 是 animated 字段，前置条件 `orient=tangent`，可以逐关键帧微调。详见 `track-camera.md`。

### 3.4 取值

对字段 `O` 和片段局部时间 `t`：

- `O.mode=value`：直接返回 `O.value`/schema 默认值，不读 keyframes。
- `O.mode=event`：不走本函数；由事件 runtime 按时间触发。
- `O.mode=animated`：按下面步骤采样。

1. 取 `O` 在 `[0, duration]` 内的关键帧，按 `time` 升序。
2. 没有范围内关键帧 → `O.value`；`O.value` 也没有 → schema 默认值；schema 没有默认值且对象 `required` → 解析期错误。
3. 只有一个范围内关键帧 → 该值，整段恒值。
4. `t` 小于第一个范围内关键帧 → 第一个值；`t` 大于最后一个范围内关键帧 → 最后一个值。
5. 否则找相邻两帧 `a`、`b`，按 `O.interp` 插值：
   - `linear`：`a + (b - a) * s`；
   - `angle`：最短路径角度插值；
   - `geometric`：`exp(log(a) + (log(b) - log(a)) * s)`（仅 `zoom`，见 `schema-and-types.md`）；
   - `step`：`s < 1 ? a : b`；枚举/字符串/布尔/引用/可空数值默认 step。
6. `s = (t - a.time) / (b.time - a.time)`；两帧时间相同是非法数据，parser/validator 拒绝。

不引入缓动曲线；需要缓动由编辑器在写入前烘焙成显式关键帧。运行时只做上述插值。

### 3.5 边界与防护

- 超界关键帧（`time < 0` 或 `time > duration`）忽略；这是正常语义，不是错误，迁移不清理、不报错。
- 范围内最后一帧就是末帧，hold 到片段结束；不会因为“最后一帧不在 duration 上”而回落到对象值。
- 空组回落到对象值；单帧恒值；非有限值解析期拒绝。
- 同一对象重复时间报错；不同对象同一时间合法。
- 未知路径报错；已知路径但类型不匹配报错；`required` 对象没有值/关键帧/默认值报错。
- `mode=value` 的字段不允许 keyframes；`mode=animated` 的字段允许空/非空 keyframes；`mode=event` 的字段只允许 event_record 值。
- `requires` 不满足时，字段存在也不读取；validator 可以警告“写了但前置条件不满足”。前置条件环在 schema 构建期拒绝。
- 可空数值（`yaw_lock`/`pitch_lock`）用 `null` 表示“不锁”；`null` 与数值之间按 step，两个数值之间按声明的角度插值。
- 片段没有任何关键帧也可以有静态相机/静态覆盖层：所有对象用 `value`/默认值；parser 不再要求 CAMERA 至少一个关键帧。
- 无限片段（`duration < 0`）是唯一例外：对象轴视为 `[0, ∞)`，只忽略 `time < 0`，`time >= 0` 全部在范围内；最后一帧之后 hold。该哨兵没有 fixture 使用，后续可单独清理为只用 `loop_count = -1`。
- 运行时求值假设数据已通过 parser/validator；不在每帧做类型检查、不抛异常、不分配临时 map。

### 3.6 与循环的关系

- 对象关键帧永远在 `[0, duration]` 内；循环按次数执行，不改变对象轴。
- 循环片段的第 n 次执行由播放控制给出 `executionIndex` 和 `executionLocalTime`；对象采样只用 `executionLocalTime`。`pingpong` 的反向是执行内的遍历方向，不是全局时间取模。
- 循环只作用于声明了 `loop` 的片段；非循环片段仍按自己的 `start_time`/`duration` 执行一次，不因为别的片段循环而改变定义。
- 不再有 `getAnimPeriod()`、`%` 折叠、`LaneMask.effectiveTime` 的重复实现。详见 `loop-execution.md`。

### 3.7 与迁移的关系

- 旧脚本的共享关键帧数组由一次性转换器拆成字段；转换器负责复合 map 展开、稀疏字段补齐、`curve` 原样迁移（旧 3D -> `axes:["x","y","z"]`）、`orient`/offset 原样保留、`meta.version`/`timeline.total_duration` 删除。
- 迁移以“旧运行时在同样输入下的可观察结果”为准；能保真的字段用修正关键帧，`curve` 保留原 3D 控制点与 3D 弧长，不做按轴拆分。
- 不做运行时兼容：新 parser 只认 `objects`；出现旧字段按“已移除”处理。

## 四、技术细节

### 4.1 核心模型

```text
CinematicScript
  meta
  timeline
    tracks[]
      type
      clips[]
        startTime
        duration
        clipData        // 播放控制字段（start/duration/loop/rules）
        objects         // Map<path, AnimObject>
          value         // 可选静态值
          keyframes[]   // {time, value}
```

- `AnimObject` 是统一容器；类型由 schema 的 `ObjectDef.type` 决定，不在对象里重复写类型。
- 对象值类型：`float`、`int`、`bool`、`string`、`enum`、`ref`、`nullable_float`、`event_record`。`event_record` 只用于 EVENT/MOD_EVENT。
- `Clip` 不再持有 `List<Keyframe>`；`Keyframe` 类被 `ObjectKeyframe`（`time` + `value`）取代。
- `TimelineTrack` 仍只持有 `type` + `clips[]`；对象路径由 track schema 解释。
- `Timeline` 不再持有 `totalDuration`；`contentEnd` 由片段窗口推导（见 `format-cleanup.md`）。

### 4.2 契约

- **创建**：parser 读取 clip 的 `objects`，按 schema 路径建对象；未知路径拒绝；缺省对象不创建，采样时回落 schema 默认值。
- **销毁**：随 clip 销毁；没有跨 clip 共享的对象或关键帧数组。
- **所有权**：对象只属于一个 clip；clip 只属于一个 track；轨道播放器只读，不写对象。
- **求值**：每个 clip 有一个 `ClipSampler`，对路径提供 `sample(path, t)`；采样是纯函数，不修改对象状态。状态（选择器锁、快照、运动模型）由轨道播放器/解析器单独持有，不藏在采样器里。
- **时间**：`t` 是当前执行的片段局部时间，范围 `[0, duration]`；执行层负责第几次执行/反向，采样器只接受已归一化的 `t`。
- **缓存**：`ClipSampler` 在加载时把每个对象的 keyframes 排序、编译成数组；每帧二分/游标查找；不按路径字符串查 map。
- **错误**：加载期错误带脚本路径、轨道、clip 索引、对象路径、关键帧索引；运行期不因数据错误崩溃（数据已校验）。

### 4.3 数据形态

- `objects` 的 JSON 键是路径；值必须是对象，不能是裸标量。裸标量是旧写法，按“已移除”拒绝。
- `keyframes` 必须是数组；每项必须是 `{time, value}`；多余字段拒绝。
- `time` 是有限数值；`value` 按 schema 类型校验；`null` 只允许 `nullable_float` 和 `event_record`。
- 同一对象的时间严格递增；parser 不做排序“帮忙”，排序错误直接报错，避免静默改变作者意图。
- 对象 keyframes 不要求覆盖 `0` 或 `duration`；边界值由采样规则补齐。
- `value` 和 `keyframes` 同时存在时，keyframes 覆盖静态值；第一帧之前仍用第一帧，不回落到 `value`（与“范围内第一帧就是起点”一致）。
- 空 `keyframes: []` 等价于没有 keyframes；用 `value`。

### 4.4 边界

- 对象模型不碰 `meta`；`meta` 仍是静态字段。G3 以后若要关键帧化，按同一 `AnimObject` 容器加在 script 级，不改 clip 对象模型。
- 对象模型不碰轨道语义：CAMERA 怎么把叶子组装成六参数、OVERLAY 怎么画、ADJUST 怎么走着色器栈，分别由轨道文档定义。
- 对象模型不碰循环执行：`executionIndex`/反向由播放控制给出；采样器不知道 `loop_mode`。
- 对象模型不碰编辑器呈现：编辑器可以把 `position.x/y/z` 显示成一组，也可以把 `dest` 显示成一个矩形控件；运行时只认路径。
- 对象模型不碰迁移实现：转换器可以用旧 evaluator 做对照，但运行时没有旧 evaluator。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留共享快照，只加 `keyframable` 标记 | 每个关键帧仍是全字段 map，schema 标记哪些字段可关键帧 | 加字段仍要动所有关键帧；稀疏回落仍分裂；复合值仍不能单轴调 | 没有解决 G1 的核心问题 |
| 每个轨道一个强类型 Keyframe 类 | CAMERA/OVERLAY/ADJUST 各自一个类，字段直接挂 | 新增字段要改类、parser、validator、编辑器、模板五处；跨轨道不能统一处理 | 违背“加对象不改容器”的目标 |
| 复合值保持整体对象 | `position`、`dest` 仍是一个对象，内部再分 | 不能只动一个轴/一个边；对象不是叶子，schema/编辑器要写特例 | 与“每个可独立调的量就是一个对象”冲突 |
| 嵌套 JSON 作为唯一写法 | `objects.position.x` 写成 `objects.position: {x: {...}}` | 路径解析要区分“命名空间”和“对象”；map 与对象歧义；迁移/校验更复杂 | 扁平路径更稳定，嵌套只是编辑器呈现 |
| 所有作用于片段的字段都进 clip meta | `curve`/`orient`/`motion_*` 等单独一层 clip 字段 | 脚本多一层；字段与关键帧的依赖关系散落；新增字段要改 meta/schema/编辑器 | 字段容器统一，value-only/animated 由字段形态决定 |
| 所有字段都必须有关键帧组 | 连 `curve`/`orient` 也写成 keyframes | 结构化字段没有时间语义；脚本变复杂；编辑器要伪造关键帧 | value-only 字段只带值 |
| `curve` 退出运行时、烘焙成位置关键帧 | 迁移/编辑器按采样率烘焙 | 脚本变大、曲线不可逆、旧 3D 弧长路径无法逐点保真 | 保留 `curve` 字段，轴集合 + 3D 控制点 |
| 运行时保留旧格式兼容 | parser 同时认 `keyframes` 和 `objects` | 两套语义、两套校验、两套编辑器路径；旧口径永远清不掉 | 违反版本原则“不维护自己的过时东西” |

**本方案的缺点**：

- CAMERA 一个片段可能有 160+ 个对象，原始 JSON 比快照冗长；作者入口是编辑器，手写脚本不是推荐路径。
- 扁平路径可读性不如嵌套；编辑器必须做分组和标签，否则 `mix_rg`、`hue_magenta` 这类名字很难扫。
- 稀疏字段迁移是高风险点：旧运行时的“缺失=默认”必须用修正关键帧或明确的语义变更来处理，不能靠机械拆 map。
- 路径拼写错误只能在解析/校验期发现；运行时没有模糊匹配兜底。
- 有 `curve` 的 clip 失去三轴独立打点：三轴关键帧必须成组，常量轴也要补值；不开 `curve` 时三轴仍独立。
- 2 轴 `curve` 只在该平面内做 2D 弧长；第三轴线性变化时，3D 速度不一定匀。要整条 3D 路径匀速就选 3 轴。`orient=tangent` 的切线仍由完整 3D 路径导数组合。
- value-only 结构化字段（`curve`/`orient`）需要 schema 支持 `mode=value` 和 `requires`，parser/validator/编辑器比“全是标量对象”多一层分支。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
