# Schema 与类型（Schema and Types）

> 状态：未实现
> 范围：对象 schema 的声明形态、类型系统、插值方式、默认值/必填、脚本级曲线/规则集合的声明职责、parser/validator/editor 的消费契约
> 关联：`object-model.md`、`runtime-evaluation.md`、`I-script-core/rule-system.md`（规则新方向专篇，未完成轮次；旧 `C-trigger/condition-system.md` 仅作既有实现参照）、各 `track-*.md`；吸收 `G-script-model/script-model.md` 的 G1/G2

## 一、摘要

轨道类型声明 **clip 字段**（片段级、不可关键帧）与 **objects**（SampledProperty 采样属性，按 `mode` 分为 value-only / animated / event）。曲线与规则是**脚本级独立集合**的声明，不属于任何轨道类型：曲线集合的元素引用目标片段与目标位置节点序列中的两个相邻关键帧，持有段几何/所选轴/进度；规则集合的元素由不可变 RuleDefinition 与 RuleBinding 组成，绑定引用目标片段与执行局部时间门，并可声明判定阶段控制（实例级持有/非阻塞观察）与动作延迟目标（片段开始/完成、局部时间、延迟、事实）。判定成功与动作完成是不同事实，规则事实可被后续绑定引用。它们独立于 SampledProperty，不是 value-only，也不是新轨道；RuleActivation 是运行期实例，不属于 schema。SampledProperty 声明包含路径、类型、默认值、插值方式、必填、枚举、编辑器分组等元数据；脚本级结构声明负责目标片段引用、端点/时间门、几何/进度或规则定义/绑定。parser、validator、编辑器、模板都从同一份声明生成行为，不再各自维护硬编码字段表。G1 的“参数寻址到分量”由属性路径吸收；G2 的“keyframable 标记”由 `mode` 吸收（`value` 不可关键帧、`animated` 可关键帧、`event` 离散），不再需要单独布尔位。统一原则：组合优先、通道级覆盖、定义与运行态分离、创作策略与执行机制分离、脚本级结构按片段引用定位。

## 二、为什么做

### 2.1 问题

- 当前 `FieldDef` 只有 `type/defaultValue/required/enumValues/section`，没有插值方式、对象路径、可空、范围等元数据；插值方式散落在 `KeyframeInterpolator`、`ColorAdjustSampler`、`OverlayTrackPlayer` 等代码里，新增字段要同时改多处。
- `TrackTypeSchema` 只有 `clipFields` + `kfFields` 两张扁平表；`position`/`dest`/`source` 是 map，schema 无法表达“展开成叶子”，parser/validator 只能写特判。
- 编辑器 `KeyframePanel.vue` 直接把 schema 的关键帧字段铺成一排；没有对象分组、没有单对象关键帧编辑，也没有“这个字段是 step 还是线性”的信息。
- `keyframable` 如果做成字段标记，会留下“可关键帧但不在对象里”的中间态；对象模型已经用容器区分了层级，不需要再加一个布尔位。
- 曲线与规则没有独立的声明入口：旧 `Clip.curve` 是一份控制点，`clip.rules` 是片段级数组；schema 无法声明“曲线引用哪个片段、哪两个位置端点”“规则绑定引用哪个片段、哪个执行局部时间门”，也无法表达同片段多条曲线/同一规则多条绑定。

### 2.2 目标

- 一份 schema 同时驱动：脚本解析、校验、运行时采样、编辑器表单、模板生成、文档表格。
- 对象声明能表达：路径、类型、默认值、插值、必填、枚举、可空、范围、编辑器分组、适用轨道/层类型。
- 新增一个可调项 = 在 objects 表加一行 + 在轨道播放器消费该路径；不改关键帧容器，不改 parser 主流程。
- 类型错误、未知路径、重复时间、非有限值在加载期报错；运行时不做类型检查。
- 插值方式由 schema 决定，不在各播放器里复制 `if (field.equals(...))`。
- 脚本级曲线/规则集合有独立声明：曲线声明目标片段、目标位置序列、相邻端点、几何/轴/进度；规则声明定义（内层组合条件树/动作/策略）与绑定（片段引用 + 执行局部时间门）。
- 普通片段与分支片段使用同一轨道 schema 与对象声明；分支用途不产生第二套字段表。

### 2.3 非目标

- 不在脚本 JSON 里写 schema；schema 是代码/资源，不随脚本分发。
- 不做运行时反射扫描；schema 在启动时编译成不可变表。
- 不做脚本格式版本号；schema 变更靠代码发布和一次性迁移。
- 不做用户自定义对象类型/插件 schema；本轮只覆盖内置轨道。
- 不新增强制全局片段库、段落/sequence 库、独立分支内容 schema 或独立分支引擎。

## 三、方案

### 3.1 作者看到的形态

作者只看到 `objects` 的路径和值；看不到 schema 的 Java 结构。编辑器根据 schema 生成：

- 左侧对象列表按 `section` 分组（位置 / 朝向 / 注视 / 合成 / 调色……）；
- 选中对象后，右侧只显示该对象的 `value` 和 keyframes；
- 时间轴上每个对象一行（或按分组折叠），关键帧菱形只属于该对象；
- step 对象的关键帧显示为台阶，不显示缓动曲线；
- 必填对象缺失时表单标红；未知路径不会出现在表单里。

### 3.2 Schema 结构

```text
TrackTypeSchema
  type
  clipFields: Map<String, FieldDef>       // 播放控制字段（不固定清单）
  objects:    Map<String, ObjectDef>      // 其余字段：value-only / animated / event
```

`objects` 只声明 SampledProperty。曲线与规则另有**脚本级结构声明**，不挂在 `TrackTypeSchema` 下：

```text
ScriptStructureSchema              // 声明形态；具体条目属于脚本数据
  curves: CurveDef
  rules:  RuleDef
  // 绑定可嵌在 RuleDef 内，或独立为 RuleBinding；待结构评审
```

- `CurveDef` 声明目标片段引用、目标位置节点序列（由所选位置对象/位置组构成）、两个相邻端点、几何（控制点/轴集合/弧长）与段进度。它不是 `ObjectDef`、不是 `mode=value`，也不与 SampledProperty 共用关键帧容器；端点相邻性只在目标位置序列内判断，不并入其它对象帧。
- `RuleDef`（RuleDefinition，职责标签）声明内层组合条件树、动作与行为策略；`RuleBinding` 声明目标片段引用、必选执行局部时间门（关键帧可作为时间点来源）与定义引用。RuleActivation 是运行期实例，身份为（播放实例，绑定身份，执行身份），不属于 schema。
- 普通片段与分支片段共用同一 `TrackTypeSchema`；分支用途不产生第二套 `ObjectDef`。
- 过渡不是 schema 类型：TransitionState 由轨道文档定义，窗口以交界为中心（3 秒窗口 = 前后各 1.5 秒），每侧从各自执行位置求实际状态后再混合；某侧无显式关键帧时按所在段/value 求值，不取末/首帧；schema 不声明共享 `from/to` 或混合缓冲。概念职责已定，概念名是职责标签，不是 JSON 字段名；具体序列化/引用字段待评审。

`ObjectDef` 字段：

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `path` | string | 是 | 点分路径，track 内唯一；与 `objects` 的键一致 |
| `mode` | enum | 是 | `value`（只有 value，无 keyframes）/ `animated`（value + 可选 keyframes）/ `event`（离散记录） |
| `type` | enum | 是 | `float` / `int` / `bool` / `string` / `enum` / `ref` / `nullable_float` / `event_record`；CurveDef/RuleDef/RuleBinding 不是 SampledProperty 类型，不在本表 |
| `default` | JSON value | 否 | 缺省对象/空组/缺值时的回落值；没有默认值且 `required` 为真 → 解析错误 |
| `interp` | enum | 否 | `linear` / `angle` / `geometric` / `step` / `none`；`mode=value`/`event` 时为 `none` |
| `requires` | string | 否 | 前置条件，如 `orient=tangent`、`look_at!=none`；不满足时字段不读取/不校验 |
| `required` | bool | 否 | 默认 false；true 时字段必须能求出一个值（value/keyframes/default 至少一个） |
| `enumValues` | string[] | 否 | `type=enum` 时必需 |
| `nullable` | bool | 否 | `type=nullable_float` 时隐含 true；`null` 是合法值 |
| `min` / `max` | number | 否 | 数值范围；validator 校验，运行时不钳制 |
| `section` | string | 否 | 编辑器分组/文档分组 |
| `appliesTo` | string[] | 否 | 限定层类型/轨道子类型，例如 OVERLAY 的 `fade`/`image`/`subtitle`；空 = 全部 |
| `labelKey` | string | 否 | 编辑器 i18n 键；缺省用 path |
| `unit` | string | 否 | 编辑器显示单位（度、秒、比例、块）；不影响运行时 |

`FieldDef`（clip 字段）只保留播放控制/结构字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | enum | `float` / `int` / `bool` / `string` / `enum` / `map` |
| `default` | JSON value | 缺省值 |
| `required` | bool | 片段级必填 |
| `enumValues` | string[] | 枚举 |
| `section` | string | 编辑器分组 |

clip 字段只保留播放控制字段（`start_time`、`duration`、`loop`/`loop_count`/`loop_mode` 等）；不把 `rules` 固定为 clip 外层目标字段，规则定义/绑定归脚本级规则集合，RuleActivation 归播放实例。`orient`、`motion_*`、`transition*`、`mask_shape`、`layer_type`、`sound` 等作用于片段/段的普通数据都进 `objects`，按 `mode` 决定是否带 keyframes。CurveDef/RuleDef/RuleBinding 属于脚本级结构声明，不进 `objects`，也不做成 value-only；它们由独立的结构声明/解析/校验入口处理。这样不会多一层 clip meta，也不会把结构化字段强行做成关键帧组。

**G2 的 `keyframable` 不采用布尔位**：字段形态由 `mode` 表达。`mode=value` 就是不可关键帧；`mode=animated` 可关键帧；`mode=event` 是离散事件。新增字段只改 `ObjectDef` 一行。

### 3.3 类型系统

| 类型 | JSON 值 | 默认插值 | 说明 |
|---|---|---|---|
| `float` | number | `linear` | 有限数；不接受字符串数字 |
| `int` | integer | `step` | 有限整数；用于离散计数/索引；对象中很少用 |
| `bool` | boolean | `step` | 开关 |
| `string` | string | `step` | 自由字符串；路径、命令、选择器之外的文本 |
| `enum` | string | `step` | 必须在 `enumValues` 中 |
| `ref` | string | `step` | 选择器/实体引用（`@p`、`@e[...]`）；语义由轨道定义 |
| `nullable_float` | number 或 null | `step` | `null` = 不锁/不启用；两个非空值之间按 `angle` 或 `linear` 由 `interp` 指定 |
| `event_record` | object | 无 | 只用于 EVENT/MOD_EVENT；关键帧值是离散记录，不插值 |

- `float` 的 `interp` 可以是 `linear`、`angle`、`geometric`；`angle` 只用于角度，`geometric` 只用于 `zoom`。
- `nullable_float` 的 `interp` 默认 `step`；若声明 `angle`，两个非空值之间走最短路径，任一端为 `null` 时按 step（在非空值的时间点切换）。
- `event_record` 没有 `interp`；同一对象内 keyframes 是事件序列，按时间触发，不做值插值。
- `mode=value` 的字段（`orient` 等）没有 `interp`；只读 `value`，不接受 keyframes。CurveDef/RuleDef/RuleBinding 不属于 `mode=value`。
- 脚本级曲线集合（CurveDef）：每条条目先声明目标片段引用，再声明该片段目标位置节点序列中的两个相邻关键帧作为端点，并持有该段几何（三次贝塞尔两个内部控制点，XYZ 可分别编辑；控制点参考空间显式，保留相对段起点/绝对世界轴语义；轴集合/2D/3D 弧长）与段进度映射；几何与进度分离，不覆盖 `fov`/`roll`/`yaw` 等 SampledProperty，不建立共享快照。目标位置节点序列只由该曲线所选的位置对象/位置组构成；端点相邻性只在这个序列内判断，不把其它对象帧拼成全片段总序列。一个片段可有多条曲线条目，各自选择端点对，不隐式复用控制点；曲线只负责所选轴，未选轴继续按自身 SampledProperty 的局部时间/关键帧求值；段外关键帧仍独立，不要求整片段三轴全局成组；2 轴只保证该平面内 2D 弧长，不承诺 3D 匀速。插入/重排位置关键帧时，绑定必须显式重映射或由作者显式重建；不得静默改指另一对端点，也不得用“插位置点后复用旧控制点”冒充保形。
- 脚本级规则集合（RuleDef / RuleBinding）：RuleDef 是不可变行为声明（内层条件组合树、动作、观察/等待/once/边沿/驻留/超时等策略）；RuleBinding 引用 RuleDef、目标片段与**必选的执行局部时间门**。时间门是有效判定的一部分，不是一次性到达事件；关键帧只提供一个已经确定的时间点，作为时间门的选取/引用方式，规则不依赖关键帧、不要求创建空关键帧。有效判定 = 时间门 AND 内层条件树；内层 `any`/`or` 不得绕过时间门。同一 RuleDef 可有多条绑定，每条绑定有独立运行身份与 once/边沿/计时/等待状态；不把多个“时间 >= t”OR 成最早入口。RuleActivation 以（播放实例，绑定身份，执行身份）持有运行态，不是 schema 条目；共用定义不共用运行态。默认时间门 = 本次执行片段局部时间 >= 指定值；门后开始/继续判定其它条件，后面成立可以执行，不是精确时刻一次判定或 cross 事件。门前有效事件/事实可采集，动作不得提前提交。绑定还可声明判定阶段控制（实例级持有等待/非阻塞观察）与动作延迟目标（片段开始/完成、局部时间、延迟、事实）；判定成功、动作提交、动作完成分属不同事实，成功后按声明调度而非重判。时间参照片段与动作目标片段可不同；规则事实可被后续绑定引用，但只定位本实例与绑定/执行，不做脚本级共享布尔或无界历史。判定成功后已承诺未释放的动作是明确执行依赖，参与自然完成但不冻结内容推进；等待片段开始与等待片段完成是不同语义。
- 普通片段与分支片段使用同一 `TrackTypeSchema`、同一对象声明与同一采样规则；分支只改变执行来源/编排用途，不产生第二套内容 schema。结构声明由独立 schema 入口表达；当前固定职责与验收，序列化/引用字段待评审。`objects.curve` 与 `clip.rules` 只是前稿/现行实现参照，不是目标载体；当前代码是 `clip.getCurve` 单份曲线与片段级 `clip.rules`。
- `requires` 是字段级前置条件：例如 `yaw_offset` 的 `requires=orient=tangent`，`look_at_offset` 的 `requires=look_at!=none`。条件不满足时字段不读取、不校验；条件可以递归，但 schema 构建期拒绝环。
- 颜色不设 `color` 类型：OVERLAY 的 `color.r/g/b` 是三个 `float`，各自线性；这样颜色也能单通道调。
- 不设 `vec3`/`rect` 类型：复合值展开成叶子；`map` 只用于播放控制/结构字段，不用于字段值。

### 3.4 插值推导

| 条件 | 缺省 `interp` |
|---|---|
| `type=float` 且 path 是 `yaw`/`roll`/`yaw_lock`/`pitch_lock` 或声明 `unit=degree` | `angle` |
| `type=float` 且 path 是 `zoom` | `geometric` |
| 其他 `float` | `linear` |
| `int` / `bool` / `string` / `enum` / `ref` | `step` |
| `nullable_float` | `step`（除非显式 `angle`） |
| `event_record` / `mode=event` | 无 |

显式 `interp` 覆盖推导；validator 拒绝不兼容组合（例如 `type=bool` + `interp=linear`、`type=float` + `interp=step` 可以但会警告“该对象不会平滑变化”）。

### 3.5 默认值与必填

- 对象缺省：采样时直接用 `default`；没有 `default` 且 `required=false` → 对象视为“未提供”，轨道播放器必须能处理缺省（通常等价于无效果/默认行为）。
- 对象缺省且 `required=true` → 解析错误，错误信息带路径。
- 对象存在但既无 `value` 也无 keyframes：等价于缺省；parser 可丢弃。
- 空 `keyframes: []`：等价于无 keyframes；用 `value`/`default`。
- `value` 与 keyframes 同时存在：keyframes 覆盖；第一帧之前用第一帧，不回落到 `value`。
- `default` 可以是 `null`（仅 nullable 类型）；`default` 为 `null` 且 `required=true` 视为“没有默认值”，需要显式值。
- `mode=value`：只读 `value`/`default`；有 keyframes 直接报错。`value` 可以是标量或类型定稿后的结构化值；CurveDef/RuleDef/RuleBinding 不在 `mode=value` 中承载。
- `mode=event`：必须有 keyframes，且每个 keyframe 值是 `event_record`；`value` 不参与求值。
- `requires` 不满足：字段视为未提供，不读取、不校验；`required=true` 也不触发错误（由前置条件决定它是否属于当前配置）。

### 3.6 parser 契约

- `SchemaLoader` 在启动时构建 `SchemaRegistry`；每个 track type 的 `objects` 表不可变。
- `ScriptParser` 读 clip 的 `objects`：
  1. 未知路径 → 错误“未知对象路径”；
  2. 值不是对象/字段多余 → 错误；
  3. 按 `ObjectDef.mode` 校验形态：`value` 不许有 keyframes；`animated` 允许；`event` 必须有 keyframes 且值为 `event_record`；
  4. 按 `ObjectDef.type` 校验 `value` 和每个 keyframe 的 `value`；CurveDef/RuleDef/RuleBinding 由脚本级结构解析器单独校验目标片段引用、端点/时间门、定义引用与几何，不走 `ObjectDef` 类型校验；RuleActivation 只在运行期创建；
  5. 校验时间有限、严格递增、重复时间错误；
  6. 非有限值错误；
  7. `required` 对象没有可求值来源 → 错误（`requires` 不满足的字段除外）；
  8. 构建字段依赖图；`requires` 指向未知路径/环 → schema 构建期错误；
  9. 超界关键帧保留，不报错；
  10. 无限执行是执行状态语义（当前格式负 `duration`）：对象轴无有限上界，只忽略 `time < 0`；不得用 ∞/NaN/MAX 作完成判据或有限末端估计，有限部分显示范围单独派生；
  11. 旧字段（`keyframes`、裸标量对象、`meta.version`、`timeline.total_duration`、片段级 `curve`、片段级 `rules`）→ 错误“已移除”；旧曲线/规则由迁移器转换为脚本级集合。
- 脚本级结构解析：
  1. 曲线集合：校验 `curveId` 唯一、目标片段引用可解析、目标位置对象/组存在于该片段、两个端点在该目标位置序列中相邻、几何/轴集合/进度合法；同一片段多条曲线合法；端点对重叠合法；
  2. 规则集合：校验 `ruleId`/`bindingId` 唯一、RuleDef 条件树与动作完整、RuleBinding 引用已存在定义与目标片段、时间门必选且位于该片段的执行局部轴、关键帧时间来源（若声明）可解析；同一规则多绑定合法；无时间门的绑定报错；判定阶段控制/动作延迟目标/动作目标片段（若声明）可解析；事实引用定位到本实例的绑定/执行，不解析为脚本级共享标记；
  3. 规则时间门与内层条件树的结构分离：内层组合器不得包含“绕过时间门”的顶层替代路径；validator 检查外层时间门始终参与有效判定；
  4. 普通/分支片段共用同一对象 schema 校验，不为分支建立第二套字段表。
- parser 不排序 keyframes；作者/编辑器负责顺序，错误直接暴露。
- parser 不做类型转换（字符串数字不转 number）；不做别名/模糊匹配。
- parser 构建 `Clip` 时把对象 keyframes 编译成数组；路径 -> 槽位的映射由 `ClipSampler` 持有。

### 3.7 validator 契约

- 复用 parser 的结构校验；额外做语义校验：
  - `enum` 值在枚举内；
  - `min`/`max` 范围；
  - `required` 对象存在；
  - 对象路径适用于当前轨道/层类型（`appliesTo`）；
  - `requires` 前置条件：条件不满足时字段存在 → 警告“写了但不会读取”；条件满足但字段缺失且 `required` → 错误；
  - 脚本级曲线集合：CurveDef 校验目标片段引用、目标位置序列、两个端点相邻、重排不静默改绑、段内位置采样与 geometry 一致；同一片段多条曲线合法，端点对重叠合法；端点对/几何引用不完整报错；
  - 脚本级规则集合：RuleDef 校验内层条件树/动作/策略完整；RuleBinding 校验定义引用、目标片段引用、时间门必选且位于该片段的执行局部轴、关键帧时间来源可解析；同一规则多绑定合法，每条绑定独立；外层时间门必须参与有效判定，内层 any/or 不得绕过；判定阶段控制/动作延迟目标/动作目标片段可解析；实例级持有检查依赖可达性（等未来内容时不能同时冻结该进度），不可达给诊断；RuleActivation 不参与 schema 校验，运行期身份为（播放实例，绑定身份，执行身份）；曲线/规则不与 SampledProperty 共用 `ObjectDef` 校验；序列化/引用字段待评审，但不得把结构条目当 value-only；
  - 普通/分支片段：同一 schema、同一对象路径、同一插值规则；不为分支声明第二套字段表或强制全局片段库；
  - `yaw_offset`/`pitch_offset` 只在 `orient=tangent` 时读取；其他模式写了 → 警告；
  - 位置点源/偏移一致性：`position_mode=absolute` 必须配 `position_space=world`、`position_origin` 为空；`position_space=base` 必须配 `position_mode=relative`；`absolute` + 非空 `position_origin`、`absolute` + `base` → 错误；`base` 的方向源由 `facing_source` 单值选择：`auto`（缺省）/ `world` / `entity` / `line`；`auto` 有 `facing_target` → line、有 `facing_entity` → entity、都没有按原点回落（实体选择器 / 隐式 `@p` / `player` → entity，坐标 / 方块 / 结构 → world）；`auto` 下两个载荷同时非空 → 错误；`entity` 且原点非实体又无 `facing_entity` → 错误；`line` 无 `facing_target` → 错误；`world` 带载荷 → 警告；`facing_entity` 只取 body yaw，视线俯仰属于 `yaw_base`/`pitch_base`；base 轴没有独立角度偏移字段；
  - `position_origin` 为空且 `position_mode=relative`：`position_space=world` → 缺省玩家激活位置；`base` → 缺省隐式 `@p`（默认不冻结）；`position_origin_freeze` 对显式实体选择器和空+base 的隐式 `@p` 生效，world 空/`player` 等静态来源写了 → 警告；`facing_target_freeze` 只在 `facing_source=line` 或 `auto→line` 时读取，其他情况写了 → 警告；`look_at_freeze` 只在 `look_at` 为实体目标时读取；`yaw_base_from_freeze`/`yaw_base_to_freeze` 只在对应端点为实体目标时读取；静态来源写了 → 警告；`facing_entity` 无冻结开关；`facing_source`/`facing_target`/`facing_entity` 只在 `position_space=base` 读取，`world` 写了 → 警告；
  - `position_mode`/`position_space`/`position_origin`/`position_origin_freeze`/`facing_source` 有多个值但切换点没有对应 `position.x/y/z` 关键帧 → 错误；`position_origin`/`position_origin_freeze` 变化，或 `position_space=base` 时 `facing_source` 变化，或当前生效的 `facing_target`/`facing_target_freeze`/`facing_entity`/`look_at_freeze`/`yaw_base_from_freeze`/`yaw_base_to_freeze` 变化，改变参考系但切换点没有位置关键帧 → 错误；base 轴没有独立角度偏移字段；
  - `layer_type=image` 的 `path` 允许 `.png` / `.gif` / `.mp4` / `.webm` / `.mkv`；扩展名只决定运行时实现（静态/GIF/视频），解析期不要求 ffmpeg 在场；其他扩展名 → 错误；
  - `loop`/`loop_count` 按次数语义校验（见 `loop-execution.md`）；
  - `zoom` 的 `geometric` 只在两端 > 0 时有效，否则报错。
- validator 不修改脚本；不自动补关键帧、不排序、不钳制。
- 错误/警告带脚本路径、轨道、clip 索引、对象路径、keyframe 索引；支持 `--json` 输出给编辑器。

### 3.8 特例：`zoom`

`zoom` 是唯一的 `geometric` 数值对象，保留当前视觉手感：

- 两端都 > 0：`exp(log(a) + (log(b) - log(a)) * s)`；
- 任一端 ≤ 0：validator 报错（缩放必须为正）；运行时不静默改成线性。
- 迁移时旧 `zoom` 保持几何插值；不因对象模型改成线性。

### 3.9 编辑器契约

- 编辑器从 WebUI 获取 `TrackTypeSchema` 的 JSON 序列化（clipFields + objects）；不再内置字段表。
- 对象表单按 `section` 分组；`labelKey` 走 i18n；`unit` 显示单位；`min/max` 做输入约束；`enum` 用下拉；`ref` 用选择器输入；`nullable_float` 有“启用/不启用”开关。
- `mode=value` 的字段只显示值编辑器，不显示关键帧行；`mode=animated` 显示关键帧；`mode=event` 显示事件列表。
- `requires` 不满足的字段隐藏/置灰；条件满足时再显示。例如 `yaw_offset`/`pitch_offset` 只在 `orient=tangent` 时显示。
- `position_mode`/`position_space`/`position_origin`/`position_origin_freeze`/`facing_source` 按点源模型联动：`absolute` 禁用 `base`/原点；`position_origin=实体 + position_origin_freeze=false` 是动态点源（旧的“跟随实体”）；空原点在 `world` = 玩家激活位置，在 `base` = 隐式 `@p`（不冻结）；`player` = 玩家激活位置；`base` 的方向源用 `facing_source` 单值选择：`auto` / `world` / `entity` / `line`；`auto` 有 `facing_target` → line、有 `facing_entity` → entity、都没有按原点回落；`entity` 显示 `facing_entity`（可选，缺省原点实体/玩家），`line` 显示 `facing_target`，`world` 隐藏两个载荷；`facing_entity` 只取 body yaw 且无冻结开关；base 轴没有独立角度偏移字段；改动时在切换点插三轴位置关键帧，不能只改 step 值。
- `_freeze` 开关只对实体来源显示：`position_origin_freeze` 只随实体选择器原点显示，`facing_target_freeze` 只随 `line` 目标显示，`look_at_freeze`/`yaw_base_from_freeze`/`yaw_base_to_freeze` 只随对应实体端点显示；静态来源隐藏/置灰。勾选后提示“首次解析到的位置将固定，后续实体移动/偏移变化不再影响该点”。
- `layer_type=image` 的 `path` 选择器允许 `.png` / `.gif` / `.mp4` / `.webm` / `.mkv`；视频只探测宽高/时长/帧率，不在编辑器内解码；播放预览走 `media-video.md` 的预览通道。
- 脚本级曲线集合：由独立的曲线列表/段编辑器呈现，不混进 SampledProperty 表单；编辑时先选目标片段，再在目标位置节点序列中选择两个相邻关键帧；可显示 1/2…序号供选择，但底层绑定是目标片段 + 端点引用，重排/插入/删除后必须重映射或报错。同一片段可有多条曲线，编辑器必须显示每条的目标片段、端点对、控制点参考空间与几何。
- 脚本级规则集合：RuleDef 由独立的行为定义编辑器呈现（内层条件树/动作/策略）；RuleBinding 由绑定编辑器呈现，显示定义引用、目标片段、执行局部时间门、关键帧时间来源、阶段控制与动作调度目标；运行态预览显示 RuleActivation 的（播放实例，绑定身份，执行身份）身份，不把激活状态写回定义。同一规则多绑定各自显示，不能合并成一个最早入口。
- 结构条目的序列化/引用字段待评审，编辑器不预设字段表；普通/分支片段共用同一对象表单，不提供“分支专用内容编辑器”。
- 时间轴按字段渲染关键帧；同一时间多个字段的关键帧不合并、不去重。
- 保存时只写显式值：`value` 等于 schema 默认值且无 keyframes 的字段可以省略；不要为了“完整”给每个字段写默认值。
- 编辑器不生成 `meta.version`、`timeline.total_duration`；不强制边界关键帧；不把超界关键帧钳进 `[0,duration]`。
- 编辑器可以做“缓动烘焙”“字段分组”；`orient`/offset 原样写回，不烘焙。曲线由脚本级曲线集合写回，不写 `objects.curve` 或片段级 `curve`。写回脚本时缓动必须是显式对象 keyframes；运行时格式保留 `orient`/offset 字段。

### 3.10 模板契约（编辑器侧产物）

- 模板属于编辑器侧：模板引擎从 schema 取字段默认值和路径，产物是标准脚本，不再手写 `position`/`dest` 的 map 结构。运行时 `script/template/`、`ScriptTemplate`、`/icinematics template` 随 Z 轮删除，运行时零模板语义。
- 模板生成的对象 keyframes 必须满足同一对象时间严格递增；不同对象可以不同时间。
- 脚本级结构集合：模板按需生成曲线集合与规则集合条目，不把它们写成 SampledProperty，也不写片段级 `curve`/`rules`。曲线条目生成目标片段引用 + 目标位置序列相邻端点 + 几何/进度；规则生成 RuleDef（内层条件树/动作/策略）与 RuleBinding（定义引用 + 目标片段 + 执行局部时间门，可声明阶段控制/动作调度目标）；RuleActivation 只在运行期创建。同一片段可生成多条曲线/多条绑定；结构条目的序列化/引用字段待评审；`yaw_offset`/`pitch_offset` 只在 `orient=tangent` 时生成，可带 keyframes。
- 普通/分支片段：模板不为分支生成第二套内容结构；分支只影响执行来源/编排去向，不强制全局片段库或 sequence 库。
- 模板生成位置时遵守点源模型：`absolute` 不生成 `base`/`position_origin`；动态实体点源生成 `position_origin=<selector>` + `position_origin_freeze=false`；`base` 不强制生成 `facing_source`（缺省 auto 按原点回落）；需要显式世界/实体/连线时生成 `facing_source=world|entity|line`，entity 再生成 `facing_entity`，line 再生成 `facing_target`。base 轴没有独立角度偏移字段。
- 模板不再写 `timeline.total_duration`；需要片段窗口时由 `getWindowEnd` 推导。
- 模板不再写 `meta.version`。

## 四、技术细节

### 4.1 核心模型

```text
SchemaRegistry
  byType: Map<TrackType, TrackTypeSchema>

TrackTypeSchema
  clipFields: LinkedHashMap<String, FieldDef>
  objects:    LinkedHashMap<String, ObjectDef>

ObjectDef
  path, type, default, interp, required,
  enumValues, nullable, min, max,
  section, appliesTo, labelKey, unit

ScriptStructureSchema
  curves: LinkedHashMap<String, CurveDef>
  rules:  LinkedHashMap<String, RuleDef>
  bindings: LinkedHashMap<String, RuleBinding>   // 或嵌在 RuleDef 内；待评审

CurveDef
  targetClipRef, positionSequence, endpointA/B,
  axes, geometry, progress

RuleDef
  conditionTree, actions, policy

RuleBinding
  ruleRef, targetClipRef, timeGate, timeSource?   // timeSource 可为关键帧引用
  control?          // 判定阶段：instanceHold / observe
  actionSchedule?   // clipStart / clipComplete / localTime / delay / fact
  actionTarget?     // 动作目标片段，可与时间参照片段不同
```

- `LinkedHashMap` 保持声明顺序，用于编辑器/文档稳定排序；运行时按 path/集合键查表。
- `ObjectDef`/`CurveDef`/`RuleDef`/`RuleBinding` 不可变；启动时校验键唯一、默认值类型匹配、枚举非空、`interp` 与类型兼容、引用可解析。
- `SchemaLoader` 从代码声明构建；以后可以改为 JSON 资源，但本轮不引入外部 schema 文件。
- 脚本级结构声明不挂在 `TrackTypeSchema` 下：曲线/规则可跨轨道引用片段，但目标片段必须存在且其对象 schema 支持所需的位置路径。

### 4.2 契约

- **谁创建**：`SchemaRegistry` 与 `ScriptStructureSchema` 在模组初始化时创建一次；所有 parser/validator/editor 请求共享只读实例。
- **谁销毁**：进程结束；不随脚本重载重建。
- **状态归谁**：schema 无运行时状态；采样器/播放器/规则引擎持有自己的缓存与激活。
- **边界**：schema 不知道脚本实例；parser 不知道编辑器；编辑器只通过序列化 schema 工作。脚本级结构声明只定义可引用位置与字段职责，不决定具体片段的端点/时间门值。

### 4.3 数据形态

- 对象路径是字符串键；不允许空段、连续点、前后点、数组下标。
- `default` 的 JSON 形态必须与 `type` 一致；`nullable_float` 允许 `null`。
- `enumValues` 只对 `enum` 有效；其他类型写了会被 schema 构建期拒绝。
- `appliesTo` 为空表示所有层类型；OVERLAY 的 `source` 只适用于 `image`，`font_scale` 只适用于 `subtitle`，`color.r/g/b`/`mask_*`/`gradient_*` 只适用于 `fade`。
- `section` 是编辑器分组名，不参与运行时；允许同一 section 下多个对象。
- 曲线集合键/规则集合键/绑定键是脚本内稳定标识；具体命名与引用语法待评审。同一片段可被多条曲线/多条绑定引用，集合不按片段唯一化。
- 规则绑定的时间门是必选数据；没有时间门的绑定在加载期报错，不能以“无条件规则”形式存在。关键帧引用只保存时间点来源，不复制关键帧值，也不要求目标对象有额外空帧。

### 4.4 边界

- schema 不定义轨道语义（怎么组装相机、怎么画覆盖层）；只定义“有什么对象、什么类型、怎么插值”。
- schema 不定义迁移；迁移器读取旧格式，输出符合 schema 的新对象/脚本级集合。
- schema 不定义循环；`loop*` 是 clip 字段，由执行层消费。
- schema 固定“时间门必选且参与有效判定”；默认时间门语义（本次执行局部时间 >= 指定值，门后持续判定）已确认，schema 不另造精确时刻或统一窗口变体。
- schema 不定义普通/分支片段的编排去向（归 `rule-system.md`）；只固定二者共用同一内容 schema。
- schema 不定义规则控制阶段的运行算法（何时持有、成功事实如何匹配、动作延迟如何调度）；这些归 `rule-system.md`。schema 只声明绑定可表达的阶段/目标，并校验引用可解析与依赖可达性诊断。
- 结构评审项（非用户待确认）：事实记录起点/有效期/消费/跨执行匹配、稳定片段引用、持有/调度 schema 表达、集合序列化/迁移/编辑器呈现。schema 不预设这些字段的最终形态。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留 `FieldDef` 扁平表，加 `keyframable` | 关键帧字段和 clip 字段仍在一张表，布尔位区分 | “可关键帧但不在对象里”的中间态；parser/编辑器要处理两种容器 | 对象模型已经用容器分层，布尔位多余 |
| 每个轨道一个 Java 类 | `CameraObjects`、`OverlayObjects` 等强类型类 | 新增字段要改类、schema、parser、validator、编辑器、模板；跨轨道不能统一 | 与“加对象不改容器”冲突 |
| schema 放脚本 JSON | 每个脚本带自己的字段表 | 脚本体积、校验、版本、编辑器都要处理自定义 schema；作者容易写错 | schema 是代码契约，不是脚本数据 |
| 运行时反射读 schema | 用注解/反射生成表单和采样器 | 启动慢、平台差异、混淆风险；零分配难保证 | 启动时编译成不可变表更稳 |
| 保留 `vec3`/`rect` 类型 | 对象值是一个复合类型，内部再分 | 不能单轴调；插值要特例；编辑器要复合控件 | 与叶子对象原则冲突 |
| clip 级一份控制点套所有段 | `clip.curve`/`objects.curve` 只有一组 P1/P2，隐式作用到每个位置段 | 不同段不能有不同曲线；同片段不能有多条候选路径；插入关键帧会改变其余段 | 脚本级曲线集合，每条引用目标片段与目标位置序列的相邻端点 |
| 曲线只按全片段关键帧总编号 | 把 FOV/调色等所有对象的帧拼成一条总序列，曲线按总序号取相邻帧 | 其它对象增删帧会改变位置曲线端点；段归属不可见 | 端点相邻性只在目标位置序列内判断 |
| 把 CurveDef/RuleDef/RuleBinding 做成 value-only SampledProperty | 复用普通字段容器和关键帧容器 | 曲线需要目标片段/端点/几何/进度，规则需要定义/绑定/时间门/激活；单值容器表达不了 | 脚本级结构声明，与 SampledProperty 分离 |
| 规则继续放 `clip.rules` | 定义、激活位置与运行态都在片段元数据 | 规则只能随片段活跃窗存在；同规则多入口无法独立；复用定义会串运行态 | 脚本级规则集合：定义 + 绑定 + 运行激活分离 |
| 多个时间入口 OR 成最早时间 | 同一规则写多个 `time >= t`，取最早满足的一个 | 后续入口被最早入口吞掉；once/边沿/计时无法按入口独立 | 每条绑定独立时间门与运行身份 |
| 判定成功即动作完成 | 不区分成功事实与动作提交/完成 | 延迟动作、规则接力、完成诊断无法表达 | 绑定声明控制阶段与动作延迟目标；成功/完成分开 |
| 动作延迟重新判定原规则 | 到目标片段/时间再判一次 | 原状态条件可能已变假，成功被隐式抹掉 | 成功后按声明调度，不重判 |
| 时间参照片段与动作目标必须同片段 | 只允许同片段内等时间/完成 | A 的时间/成功事实无法启动 B | 两者可不同，不要求目标片段先推进时间 |
| 分支片段使用独立内容 schema / 全局片段库 / 新 sequence 库 | 分支内容另存一份格式，或把全部片段迁到全局库 | 两套对象模型、两套迁移/编辑器；普通片段也被迫改承载 | 普通/分支同一内容模型，分支只改执行来源/编排用途 |

**本方案的缺点**：

- 对象表很长（CAMERA 160+ 项），schema 声明和维护成本高；需要文档/编辑器自动生成表格，避免手抄。
- `appliesTo` 让同一轨道的对象集合随层类型变化；validator/编辑器要处理条件字段，比“一张表”复杂。
- 类型系统多了 `nullable_float`/`event_record`，parser/validator 的分支增加。
- 不做运行时反射意味着 schema 变更要重新编译；热重载脚本可以，热重载 schema 不行。
- 编辑器依赖服务端 schema；离线 demo 需要一份同步的 schema 快照，存在漂移风险。
- CurveDef/RuleDef/RuleBinding 的职责已定，但序列化/引用/身份字段仍待评审；结构声明不能复用 SampledProperty 的表单/关键帧容器，parser/validator/编辑器都要多一层入口。RuleActivation 不在 schema 中，运行期身份由（播放实例，绑定身份，执行身份）决定。
- 曲线条目的几何保真要求保留，但不锁死编辑算法：插入关键帧后如何表达/维护端点关系由结构契约决定；不能用复用旧控制点冒充保形。
- 同一片段多条曲线/同一规则多绑定会增加校验与编辑器呈现复杂度；需要明确的端点对/绑定列表，不能退化成“取最早一条”。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 曲线为脚本级集合，每条引用目标片段与目标位置序列相邻端点；规则为脚本级定义 + 绑定 + 运行激活 | 旧 `Clip.curve` 是 clip 级一份 3D 控制点，对每个 from→to 段隐式复用；`objects.curve` 从未实现；规则是片段级 `clip.rules`，没有定义/绑定/时间门分层 | 旧格式只有一份 `curve` 与片段级规则数组；脚本级结构是新增设计，序列化/引用待评审 |
| 普通/分支片段共用同一 schema；分支不产生第二套内容声明 | 当前没有分支内容模型，普通片段仍是 clip 对象容器 | 分支编排未实现 |
| 规则阶段：绑定可声明实例级持有等待判定与动作延迟目标；判定成功事实、动作完成与跨片段事实接力由运行态记录 | 旧 `clip.rules` 只有 if/until + 动作，没有阶段/成功事实/延迟/接力声明 | 规则阶段声明与运行态均未实现；新方向见 `rule-system.md` |
