# Schema 与类型（Schema and Types）

> 状态：未实现
> 范围：对象 schema 的声明形态、类型系统、插值方式、默认值/必填、parser/validator/editor 的消费契约
> 关联：`object-model.md`、`runtime-evaluation.md`、各 `track-*.md`；吸收 `G-script-model/script-model.md` 的 G1/G2

## 一、摘要

每个轨道类型有两段 schema：**clip 字段**（片段级、不可关键帧）和 **objects**（对象级、全部可关键帧）。对象声明包含路径、类型、默认值、插值方式、必填、枚举、编辑器分组等元数据；parser、validator、编辑器、模板都从这一份声明生成行为，不再各自维护硬编码字段表。G1 的“参数寻址到分量”由对象路径吸收；G2 的“keyframable 标记”由“objects 全部可关键帧、clip 字段全部不可关键帧”吸收，不再需要单独布尔位。

## 二、为什么做

### 2.1 问题

- 当前 `FieldDef` 只有 `type/defaultValue/required/enumValues/section`，没有插值方式、对象路径、可空、范围等元数据；插值方式散落在 `KeyframeInterpolator`、`ColorAdjustSampler`、`OverlayTrackPlayer` 等代码里，新增字段要同时改多处。
- `TrackTypeSchema` 只有 `clipFields` + `kfFields` 两张扁平表；`position`/`dest`/`source` 是 map，schema 无法表达“展开成叶子”，parser/validator 只能写特判。
- 编辑器 `KeyframePanel.vue` 直接把 schema 的关键帧字段铺成一排；没有对象分组、没有单对象关键帧编辑，也没有“这个字段是 step 还是线性”的信息。
- `keyframable` 如果做成字段标记，会留下“可关键帧但不在对象里”的中间态；对象模型已经用容器区分了层级，不需要再加一个布尔位。

### 2.2 目标

- 一份 schema 同时驱动：脚本解析、校验、运行时采样、编辑器表单、模板生成、文档表格。
- 对象声明能表达：路径、类型、默认值、插值、必填、枚举、可空、范围、编辑器分组、适用轨道/层类型。
- 新增一个可调项 = 在 objects 表加一行 + 在轨道播放器消费该路径；不改关键帧容器，不改 parser 主流程。
- 类型错误、未知路径、重复时间、非有限值在加载期报错；运行时不做类型检查。
- 插值方式由 schema 决定，不在各播放器里复制 `if (field.equals(...))`。

### 2.3 非目标

- 不在脚本 JSON 里写 schema；schema 是代码/资源，不随脚本分发。
- 不做运行时反射扫描；schema 在启动时编译成不可变表。
- 不做脚本格式版本号；schema 变更靠代码发布和一次性迁移。
- 不做用户自定义对象类型/插件 schema；本轮只覆盖内置轨道。

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
  clipFields: Map<String, FieldDef>       // 播放控制/结构：start_time/duration/loop/rules
  objects:    Map<String, ObjectDef>      // 其余字段：value-only / animated / event
```

`ObjectDef` 字段：

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `path` | string | 是 | 点分路径，track 内唯一；与 `objects` 的键一致 |
| `mode` | enum | 是 | `value`（只有 value，无 keyframes）/ `animated`（value + 可选 keyframes）/ `event`（离散记录） |
| `type` | enum | 是 | `float` / `int` / `bool` / `string` / `enum` / `ref` / `nullable_float` / `event_record` / `curve` / `control_points` |
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

clip 字段只有 `start_time`、`duration`、`loop`/`loop_count`/`loop_mode`、`rules` 这类播放控制/结构字段；`curve`、`orient`、`motion_*`、`transition*`、`mask_shape`、`layer_type`、`sound` 等作用于片段/段的字段都进 `objects`，按 `mode` 决定是否带 keyframes。这样不会多一层 clip meta，也不会把结构化字段强行做成关键帧组。

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
| `curve` | object | 无 | 轴集合 + 两个 3D 控制点；`mode=value` |
| `axis_set` | array | 无 | 轴名集合：`x`/`y`/`z`，至少 2 个、最多 3 个、去重、顺序无关；由 `curve.axes` 消费 |
| `control_points` | array | 无 | 两个 3D 控制点（每个含 x/y/z 分量，相对/绝对；与旧 `BezierCurve` 的 P1/P2 一致）；2D 模式只取对应两个分量，3D 模式取三个；由 `curve` 消费；`mode=value` |

- `float` 的 `interp` 可以是 `linear`、`angle`、`geometric`；`angle` 只用于角度，`geometric` 只用于 `zoom`。
- `nullable_float` 的 `interp` 默认 `step`；若声明 `angle`，两个非空值之间走最短路径，任一端为 `null` 时按 step（在非空值的时间点切换）。
- `event_record` 没有 `interp`；同一对象内 keyframes 是事件序列，按时间触发，不做值插值。
- `mode=value` 的字段（`curve`/`orient`）没有 `interp`；只读 `value`，不接受 keyframes。`curve` 内部的 `axes`/`control_points` 是它的结构，不单独作为字段。
- `curve` 是一个整体字段：`axes` 是轴集合（至少 2 个、去重、顺序无关，`["y","x"]` 等价 `["x","y"]`），`control_points` 是两个 3D 控制点。`curve` 是片段级一份，同一组控制点对每个“位置组”段复用；位置组 = `position.x/y/z` 同时间关键帧。有 `curve` 时三轴关键帧时间集合必须完全一致、每个时间点三轴都有显式值，缺值/不对齐 = 错误；没有 `curve` 时三轴可独立。轴集合 2 轴时做该平面内的 2D 弧长，只取控制点对应两个分量；3 轴时做 3D 弧长，取三个分量。
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
| `mode=value`（`curve`/`orient`/`control_points`） | 无 |
| `curve`/`control_points` | 无 |

显式 `interp` 覆盖推导；validator 拒绝不兼容组合（例如 `type=bool` + `interp=linear`、`type=float` + `interp=step` 可以但会警告“该对象不会平滑变化”）。

### 3.5 默认值与必填

- 对象缺省：采样时直接用 `default`；没有 `default` 且 `required=false` → 对象视为“未提供”，轨道播放器必须能处理缺省（通常等价于无效果/默认行为）。
- 对象缺省且 `required=true` → 解析错误，错误信息带路径。
- 对象存在但既无 `value` 也无 keyframes：等价于缺省；parser 可丢弃。
- 空 `keyframes: []`：等价于无 keyframes；用 `value`/`default`。
- `value` 与 keyframes 同时存在：keyframes 覆盖；第一帧之前用第一帧，不回落到 `value`。
- `default` 可以是 `null`（仅 nullable 类型）；`default` 为 `null` 且 `required=true` 视为“没有默认值”，需要显式值。
- `mode=value`：只读 `value`/`default`；有 keyframes 直接报错。`value` 可以是标量，也可以是 `curve`/`control_points` 结构。
- `mode=event`：必须有 keyframes，且每个 keyframe 值是 `event_record`；`value` 不参与求值。
- `requires` 不满足：字段视为未提供，不读取、不校验；`required=true` 也不触发错误（由前置条件决定它是否属于当前配置）。

### 3.6 parser 契约

- `SchemaLoader` 在启动时构建 `SchemaRegistry`；每个 track type 的 `objects` 表不可变。
- `ScriptParser` 读 clip 的 `objects`：
  1. 未知路径 → 错误“未知对象路径”；
  2. 值不是对象/字段多余 → 错误；
  3. 按 `ObjectDef.mode` 校验形态：`value` 不许有 keyframes；`animated` 允许；`event` 必须有 keyframes 且值为 `event_record`；
  4. 按 `ObjectDef.type` 校验 `value` 和每个 keyframe 的 `value`；`curve` 校验 `axes`（至少 2 个、最多 3 个、去重、顺序无关）和两个 3D 控制点；有 `curve` 时校验 `position.x/y/z` 的关键帧时间集合完全一致且每个时间点三轴都有值；
  5. 校验时间有限、严格递增、重复时间错误；
  6. 非有限值错误；
  7. `required` 对象没有可求值来源 → 错误（`requires` 不满足的字段除外）；
  8. 构建字段依赖图；`requires` 指向未知路径/环 → schema 构建期错误；
  9. 超界关键帧保留，不报错；
  10. 旧字段（`keyframes`、裸标量对象、`meta.version`、`timeline.total_duration`）→ 错误“已移除”。
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
  - `curve` 的轴控制点只作用于对应轴；控制点数量/相对/绝对合法；
  - `yaw_offset`/`pitch_offset` 只在 `orient=tangent` 时读取；其他模式写了 → 警告；
  - 位置点源/偏移一致性：`position_mode=absolute` 必须配 `position_space=world`、`position_origin` 为空；`position_space=base` 必须配 `position_mode=relative`；`absolute` + 非空 `position_origin`、`absolute` + `base` → 错误；`base` 的方向源由 `facing_source` 单值选择：`auto`（缺省）/ `world` / `entity` / `line`；`auto` 有 `facing_target` → line、有 `facing_entity` → entity、都没有按原点回落（实体选择器 / 隐式 `@p` / `player` → entity，坐标 / 方块 / 结构 → world）；`auto` 下两个载荷同时非空 → 错误；`entity` 且原点非实体又无 `facing_entity` → 错误；`line` 无 `facing_target` → 错误；`world` 带载荷 → 警告；`facing_entity` 只取 body yaw，视线俯仰属于 `yaw_base`/`pitch_base`；base 轴没有独立角度偏移字段；
  - `position_origin` 为空且 `position_mode=relative`：`position_space=world` → 缺省玩家激活位置；`base` → 缺省隐式 `@p`（默认不冻结）；`position_origin_freeze` 对显式实体选择器和空+base 的隐式 `@p` 生效，world 空/`player` 等静态来源写了 → 警告；`facing_target_freeze` 只在 `facing_source=line` 或 `auto→line` 时读取，其他情况写了 → 警告；`look_at_freeze` 只在 `look_at` 为实体目标时读取；`yaw_base_from_freeze`/`yaw_base_to_freeze` 只在对应端点为实体目标时读取；静态来源写了 → 警告；`facing_entity` 无冻结开关；`facing_source`/`facing_target`/`facing_entity` 只在 `position_space=base` 读取，`world` 写了 → 警告；
  - `position_mode`/`position_space`/`position_origin`/`position_origin_freeze`/`facing_source` 有多个值但切换点没有对应 `position.x/y/z` 关键帧 → 错误；`position_origin`/`position_origin_freeze` 变化，或 `position_space=base` 时 `facing_source` 变化，或当前生效的 `facing_target`/`facing_target_freeze`/`facing_entity`/`look_at_freeze`/`yaw_base_from_freeze`/`yaw_base_to_freeze` 变化，改变参考系但切换点没有位置关键帧 → 错误；base 轴没有独立角度偏移字段；有 `curve` 时切换点不在位置组上 → 错误；
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
- `curve` 显示轴集合多选（至少 2 个，顺序无关）和两个 3D 控制点；轴集合 2 轴时提示 2D 弧长、第三轴线性，3 轴时提示 3D 弧长。开启 `curve` 后，加/删/移动 `position` 关键帧必须三轴成组，常量轴补当前值；关闭 `curve` 后恢复三轴独立。
- 时间轴按字段渲染关键帧；同一时间多个字段的关键帧不合并、不去重。
- 保存时只写显式值：`value` 等于 schema 默认值且无 keyframes 的字段可以省略；不要为了“完整”给每个字段写默认值。
- 编辑器不生成 `meta.version`、`timeline.total_duration`；不强制边界关键帧；不把超界关键帧钳进 `[0,duration]`。
- 编辑器可以做“缓动烘焙”“字段分组”；`curve`/`orient`/offset 原样写回，不烘焙。写回脚本时缓动必须是显式对象 keyframes；运行时格式保留 `curve`/`orient`/offset 字段。

### 3.10 模板契约

- `ScriptTemplate` / `TemplateScriptAssembler` 从 schema 取字段默认值和路径，不再手写 `position`/`dest` 的 map 结构。
- 模板生成的对象 keyframes 必须满足同一对象时间严格递增；不同对象可以不同时间。
- `curve`/`orient` 作为 value-only 字段生成；有 `curve` 时模板生成的 `position.x/y/z` 关键帧必须三轴成组；`yaw_offset`/`pitch_offset` 只在 `orient=tangent` 时生成，可带 keyframes。
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
```

- `LinkedHashMap` 保持声明顺序，用于编辑器/文档稳定排序；运行时按 path 查表。
- `ObjectDef` 不可变；启动时校验路径唯一、默认值类型匹配、枚举非空、`interp` 与类型兼容。
- `SchemaLoader` 从代码声明构建；以后可以改为 JSON 资源，但本轮不引入外部 schema 文件。

### 4.2 契约

- **谁创建**：`SchemaRegistry` 在模组初始化时创建一次；所有 parser/validator/editor 请求共享只读实例。
- **谁销毁**：进程结束；不随脚本重载重建。
- **状态归谁**：schema 无运行时状态；采样器/播放器持有自己的缓存。
- **边界**：schema 不知道脚本实例；parser 不知道编辑器；编辑器只通过序列化 schema 工作。

### 4.3 数据形态

- 对象路径是字符串键；不允许空段、连续点、前后点、数组下标。
- `default` 的 JSON 形态必须与 `type` 一致；`nullable_float` 允许 `null`。
- `enumValues` 只对 `enum` 有效；其他类型写了会被 schema 构建期拒绝。
- `appliesTo` 为空表示所有层类型；OVERLAY 的 `source` 只适用于 `image`，`font_scale` 只适用于 `subtitle`，`color.r/g/b`/`mask_*`/`gradient_*` 只适用于 `fade`。
- `section` 是编辑器分组名，不参与运行时；允许同一 section 下多个对象。

### 4.4 边界

- schema 不定义轨道语义（怎么组装相机、怎么画覆盖层）；只定义“有什么对象、什么类型、怎么插值”。
- schema 不定义迁移；迁移器读取旧格式，输出符合 schema 的新对象。
- schema 不定义循环；`loop*` 是 clip 字段，由执行层消费。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留 `FieldDef` 扁平表，加 `keyframable` | 关键帧字段和 clip 字段仍在一张表，布尔位区分 | “可关键帧但不在对象里”的中间态；parser/编辑器要处理两种容器 | 对象模型已经用容器分层，布尔位多余 |
| 每个轨道一个 Java 类 | `CameraObjects`、`OverlayObjects` 等强类型类 | 新增字段要改类、schema、parser、validator、编辑器、模板；跨轨道不能统一 | 与“加对象不改容器”冲突 |
| schema 放脚本 JSON | 每个脚本带自己的字段表 | 脚本体积、校验、版本、编辑器都要处理自定义 schema；作者容易写错 | schema 是代码契约，不是脚本数据 |
| 运行时反射读 schema | 用注解/反射生成表单和采样器 | 启动慢、平台差异、混淆风险；零分配难保证 | 启动时编译成不可变表更稳 |
| 保留 `vec3`/`rect` 类型 | 对象值是一个复合类型，内部再分 | 不能单轴调；插值要特例；编辑器要复合控件 | 与叶子对象原则冲突 |

**本方案的缺点**：

- 对象表很长（CAMERA 160+ 项），schema 声明和维护成本高；需要文档/编辑器自动生成表格，避免手抄。
- `appliesTo` 让同一轨道的对象集合随层类型变化；validator/编辑器要处理条件字段，比“一张表”复杂。
- 类型系统多了 `nullable_float`/`event_record`，parser/validator 的分支增加。
- 不做运行时反射意味着 schema 变更要重新编译；热重载脚本可以，热重载 schema 不行。
- 编辑器依赖服务端 schema；离线 demo 需要一份同步的 schema 快照，存在漂移风险。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
