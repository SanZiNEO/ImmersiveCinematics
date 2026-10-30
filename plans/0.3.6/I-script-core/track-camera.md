# CAMERA 轨道对象与装配

> 状态：未实现
> 范围：CAMERA 对象清单、位置/朝向装配顺序、点源/选择器对象、morph/motion/breath/modifier、lane 合成参数、脚本级曲线条目在相机上的消费
> 关联：`object-model.md`、`runtime-evaluation.md`、`schema-and-types.md`、`I-script-core/rule-system.md`（规则新方向专篇，未完成轮次）、`track-overlay-letterbox.md`；语义参考 `complete/0.3.6/B-camera/*`、`F-playback/camera-composition.md`、`F-playback/multi-camera-rendering.md`、`F-playback/iris-oculus-compat.md`

## 一、摘要

CAMERA 的普通数据是 **SampledProperty（采样属性）**：`position.x/y/z`、`yaw`、`pitch`、`look_at`、`opacity`、`dest.x`……每个属性独立采样。相机是模组自己的模型（六参数 `CameraState` + lane 合成参数），原版 Camera 只是渲染适配目标，不定义作者能力上限。运行时在同一 `executionLocalTime` 采样全部 SampledProperty（范围内关键帧覆盖 `value`；`value` 只是没有范围内关键帧时的回落），然后按固定顺序组装世界位置、朝向、六参数 `CameraState`；motion/breath 是各自片段的效果，在每侧求值内完成，transition 只混合两侧的完整状态。位置路径由**脚本级曲线集合**的条目覆盖：每条条目先引用目标片段，再引用该片段目标位置节点序列中的两个相邻关键帧，持有自己的路径几何与段进度，几何与进度分离；它不是 SampledProperty，也不是新轨道，不把 `fov`/`roll` 等其它属性拉进共享快照。同一片段可被多条曲线条目引用，互不隐式复用控制点。RuleDefinition/RuleBinding 属于脚本级规则集合，不属于相机属性，也不并入曲线集合；RuleActivation 是播放实例的运行态。`orient` 是 value-only SampledProperty，`yaw_offset`/`pitch_offset` 是 animated SampledProperty；不做曲线烘焙。普通片段与分支片段使用同一 CAMERA 对象 schema。统一原则：组合优先、通道级覆盖、定义与运行态分离、创作策略与执行机制分离、曲线按片段引用定位。

## 二、为什么做

### 2.1 问题

- `position` 是一个 map，只能整体插值；想只动 x 轴做不到。
- 相机求值按“两个端点”算世界位置/朝向，再在端点之间插值；对象模型要求每个叶子独立采样，端点快照不再成立。
- `frame` 是 per-frame 可变字段，base-space 偏移读到哪个 frame 取决于调用顺序（`from`/`to`/tangent 重算）；这是隐藏状态。
- `look_at=none` 的普通求值已按角度插值；但至少一端 `look_at` 启用的混合段当前用 100 格远伪目标做端点求值，和手动朝向的 `base + lock + offset` 口径不一致。伪目标只影响混合段，不应写成 `none` 的通用实现；模型层不把它当成前提。
- 旧 `curve` 是 clip 级一份 3D 控制点，隐式套到每个位置段：不同段不能有不同曲线，同一片段也不能并列多条候选路径；插入一个位置关键帧会同时改变其余段的形状/速度。曲线没有“目标片段 + 端点对”的独立声明，`orient`/offset 又和位置字段分散在两层，前置条件不显式。
- 过渡混合后用下一段的 motion、上一段的 breath 补齐效果；两侧没有各自按执行位置求出完整效果状态，混合后的效果归属错位。不能据此推断每侧已经独立推进。
- lane 合成参数、mask、lane 调色各自重复采样，循环时可能不同步。

### 2.2 目标

- 每个可独立调的量是一个字段；位置三轴、矩形四边、遮罩几何、调色通道都能单独加关键帧。
- 所有字段在同一 `t` 采样；位置/朝向装配只读采样值，不依赖调用顺序。位置和朝向各自持有明确参考系，互不自动继承。
- 相机静态片段（无关键帧）也能工作；超界关键帧不影响 clip 可用性。
- 一个 `SampledClip` 同时供相机状态、lane 合成参数、lane 调色、mask 使用；同一帧同一 `t`。
- 运行时保留三次 Bezier/切线组合；脚本级曲线集合的每条条目引用目标片段与目标位置序列中的 A→B 相邻端点，持有自己的 geometry，按条目求值/导数，不烘焙成位置关键帧；段进度独立于几何。同一片段可有多条曲线条目。
- 普通片段与分支片段共用同一 CAMERA 对象 schema；分支用途不改变相机字段或采样规则。
- 过渡两侧各自从自己的执行位置求出完整状态（含本片段的 motion/breath），再按 transition 声明混合；每侧每帧只推进一次，过渡层不推进效果。
- 保留 B-camera 的 cover chain、transition、selector、motion 数学；状态归属与装配按本文件新目标（每侧完整状态、过渡只混合快照），不改公式但改输入/归属。

### 2.3 非目标

- 不重新定义相机数学（坐标框架、cover chain、transition 曲线、selector 策略、motion 模型）；这些以 `complete/0.3.6/B-camera/*` 为准。
- 不把 `motion_*`/`cam_breath_*`/`transition_*` 做成 animated/event；它们是 value-only 字段。
- 不把 `dimension` 做成 animated/event；跨维度是 value-only 字段。
- 不新增任意阶曲线或曲线 sidecar；曲线条目是脚本级集合元素，不是普通 SampledProperty，也不是新轨道。
- 不把过渡层变成效果推进层；过渡只混合两侧已求出的状态。
- 不定义编辑器 UI；曲线条目的序列化/引用表达待评审。
- 不改变渲染/合成管线；lane 仍走 `multi-camera-rendering.md`。
- 不强制把相机片段迁入全局片段库或新 sequence 库；分支相机片段与主线相机片段使用同一 schema。

## 三、方案

### 3.1 作者看到的形态

一个相机片段：

```json
{
  "start_time": 0,
  "duration": 5,
  "objects": {
    "position.x": {"value": 0, "keyframes": [{"time": 0, "value": 0}, {"time": 5, "value": 10}]},
    "position.y": {"value": 2},
    "position.z": {"value": 0},
    "position_mode": {"value": "relative"},
    "position_space": {"value": "world"},
    "yaw": {"keyframes": [{"time": 0, "value": 0}, {"time": 5, "value": 45}]},
    "pitch": {"value": 0},
    "roll": {"value": 0},
    "fov": {"value": 70},
    "zoom": {"value": 1},
    "opacity": {"value": 1}
  }
}
```

- 位置三轴是三个 SampledProperty；`position_mode`/`position_space`/`position_origin`/`position_origin_freeze` 是 step 属性，但语义是点源/参考系切换，必须和位置关键帧对齐；不是独立旋钮。
- SampledProperty 采样：`[0,duration]` 内的关键帧覆盖 `value`；`value` 只在没有范围内关键帧时作为回落，不是高于关键帧的固定值。
- `dest`/`source` 展开成 `dest.x/y/w/h`、`source.x/y/w/h`；遮罩几何保留现有标量名（`mask_x`……）；调色通道保留现有名字（`exposure`、`mix_rg`……）。
- 位置路径可由**脚本级曲线集合**的条目覆盖：每条条目先引用目标片段，再引用该片段目标位置节点序列中的两个相邻关键帧作为 A→B 端点，持有该段几何与段进度；一个片段可被多条条目引用，各自选择端点对，端点必须明确；不同条目不共享控制点。关键帧有明确序号/顺序，编辑器可按 1/2…序号选段，但底层绑定是“目标片段 + 端点引用”，不得因重排静默指错。控制点 XYZ 分量可分别编辑；控制点参考空间显式（相对段起点/绝对世界轴），保留三次贝塞尔（两个内部控制点）与 2D/3D 弧长数学。曲线条目只负责 axes 中选中的坐标轴；未选轴继续按自身 SampledProperty 的局部时间/关键帧求值。插入位置关键帧时作者可显式重建/重绑定产生新几何，不得静默复用旧控制点改变路径。曲线条目不放进 `objects`，不是 value-only，也不是新轨道；序列化/引用表达待评审。详见 §3.2。
- `orient` 是 value-only 模式；`yaw_offset`/`pitch_offset` 是 animated 字段，前置条件 `orient=tangent`，可以逐关键帧微调。它们不是手动朝向的通用偏移。

### 3.2 位置模型

`position.x/y/z` 是三个数值叶子；它们不是自解释坐标，必须和点源/偏移一起读。

**模型：位置 = 点源 + 偏移；绝对位置 = 无点源的世界坐标。** 点源 = 来源 + 偏移 + 表达空间 + 冻结开关；来源分静态/动态：

- **静态点源**：玩家激活位置、固定坐标、结构、方块、实体冻结；
- **动态点源**：实体实时。

方向源只有一个，由 `facing_source` 单值选择：`auto`（缺省）/ `world` / `entity` / `line`。`auto` 的判定顺序：写了 `facing_target` → `line`；写了 `facing_entity` → `entity`；都没写 → 原点来源是实体选择器 / 隐式 `@p` / `player` 时 `entity`，坐标 / 方块 / 结构时 `world`。`auto` 下 `facing_target` 与 `facing_entity` 同时非空 = 冲突（必须显式选一个）；显式 `world` 忽略两个载荷，显式 `entity` 只用 `facing_entity`（缺省用原点实体/玩家），显式 `line` 只用 `facing_target`。世界轴 = 原版坐标轴：fwd=+Z、right=-X、up=+Y（yaw=0 南、90 西）；base 的上轴固定世界竖直，前轴/右轴取水平面内分量，前轴俯仰不参与。`facing_entity` 只取实体身体水平朝向（yaw），不取视线俯仰；视线俯仰属于相机 `yaw_base`/`pitch_base`，不是 base 轴。`facing_entity` 始终实时，不配冻结开关；要固定方向用 `facing_source=line` + `facing_target_freeze=true`。base 轴没有独立的角度偏移字段；`fwd`/`up`/`right` 是位置偏移，`yaw`/`pitch` 是相机朝向相对 `yaw_base`/`pitch_base` 的偏移。方向源静/动由来源决定：world 静态；entity 随实体实时；line 随原点/目标动态；参考系静/动由点源或方向源任一动态决定。

**位置字段统一为：**

- `position_mode`：`absolute` = 无点源，直接世界坐标；`relative` = 点源 + 偏移。
- `position_space`：偏移表达空间，`world` = 世界轴，`base` = 基准系（原点 + 方向源）。
- `position_origin`：位置点源；五形态 = 玩家激活位置 / 坐标 / 实体选择器 / 方块 / 结构。空 = 不显式指定：`relative` + `world` 缺省玩家激活位置（静态）；`relative` + `base` 缺省玩家实体 `@p`（隐式选择器，默认不冻结）；`absolute` 必须为空。
- `position_origin_x/y/z`：`position_origin=coordinate` 时的坐标。
- `position_origin_freeze`：布尔，缺省 `false`；`true` = 首次解析成功后固定点源解析结果（含偏移展开的世界坐标），之后不再重取。只对实体选择器（含空+`base` 的隐式 `@p`）生效；固定的是坐标值，不是时间。
- `facing_source`：方向源单值选择，`auto` / `world` / `entity` / `line`；缺省 `auto`。`auto` 按载荷与原点来源回落，见下表。
- `facing_target`：`line` 的载荷（点源）；`auto` 下写了即选 `line`；显式 `entity`/`world` 时忽略并警告。
- `facing_entity`：`entity` 的载荷（选择器；只取 body yaw）；`auto` 下写了即选 `entity`；显式 `line`/`world` 时忽略并警告。方向源实体始终实时，不配冻结开关。
- `facing_target_freeze`：布尔，缺省 `false`；`true` = 首次解析成功后固定目标点解析结果（含偏移展开的世界坐标），之后不再重取。只对 `line` 生效；固定的是坐标值，不是时间。

`follow` / `follow_selector` 不再是新格式字段；“跟随实体”= `position_origin=<实体选择器>` + `position_origin_freeze=false`（或不写，默认不冻结）。

| 点源状态 | `position_mode` | `position_space` | `position_origin` / `freeze` | 解释 |
|---|---|---|---|---|
| 无点源 | `absolute` | `world` | 空 / — | `(x,y,z)` 就是世界坐标 |
| 静态点源 | `relative` | `world` | 静态来源 / — | 点源 + 世界轴偏移；静态来源 = 玩家激活/坐标/结构/方块/实体冻结 |
| 静态点源 | `relative` | `base` | 静态来源 / — | 点源 + 基准系偏移：x=fwd、y=up、z=right；方向源可选（见下表） |
| 动态点源 | `relative` | `world` | 实体选择器 / `false` | 实体脚底 + 世界轴偏移 |
| 隐式玩家实体 | `relative` | `base` | 空（隐式 `@p`）/ `false` 或 `true` | 玩家脚底 + 基准系偏移；方向源可选（见下表） |
| 动态点源 | `relative` | `base` | 实体选择器 / `false` | 实体脚底 + 基准系偏移；方向源可选（见下表） |

**方向源选择（`base` 空间）：**

| `facing_source` | 载荷 | 说明 |
|---|---|---|
| `auto`（缺省） | 无 / `facing_target` / `facing_entity` | 有 `facing_target` → line；有 `facing_entity` → entity；都没有 → 原点实体/玩家 → entity，坐标/方块/结构 → world；两个载荷同时非空 = 冲突 |
| `world` | 无 | 原版坐标轴：fwd=+Z、right=-X、up=+Y；静态；载荷写了 = 警告并忽略 |
| `entity` | `facing_entity` 或原点实体/玩家 | 只取 body yaw；始终实时；显式选择器优先，缺省用原点实体/玩家；无实体来源 = 错误 |
| `line` | `facing_target` | 原点 → 目标点（点源）；`facing_target_freeze` 控制是否冻结目标点；缺目标 = 错误 |

方向源只有一个；`facing_source` 决定哪一个载荷生效，未选中的载荷不参与。方向源不要求原点是实体；`entity` 在原点非实体时必须给 `facing_entity`。

- **冲突组合**：`absolute` + 非空 `position_origin`（无点源 vs 点源）；`absolute` + `base`（绝对坐标没有基准系偏移）。validator 报错。
- **`position_origin` 为空时**：`relative` + `world` 用玩家激活位置（静态）；`relative` + `base` 用隐式 `@p`（实体选择器，默认不冻结）；`absolute` 没有点源。`position_origin_freeze` 只对显式实体选择器和空+base 的隐式 `@p` 生效；world 空/`player` 等静态来源写了警告。
- **`position_space=base` 的方向源由 `facing_source` 单值选择**：缺省 `auto`；`auto` 有 `facing_target` → line，有 `facing_entity` → entity，都没有 → 原点实体/玩家 → entity、坐标/方块/结构 → world。显式 `world` / `entity` / `line` 只读各自载荷；未选中的载荷写了 → 警告并忽略。
- **冲突/缺来源**：`auto` 下 `facing_target` 与 `facing_entity` 同时非空 → validator 错误；`entity` 且原点非实体、又没写 `facing_entity` → 错误；`line` 且没写 `facing_target` → 错误；`world` 写了任一载荷 → 警告。`facing_target_freeze` 只在 `line`/`auto→line` 读取，其他情况写了 → 警告；`facing_entity` 始终实时，没有冻结开关。
- **`facing_source`/`facing_target`/`facing_entity` 只在 `position_space=base` 读取**；`world` 写了 → 警告。显式方向源不可用时按片段可用性门处理，不静默回退到 `auto` 或另一个载荷。
- **`position_origin` 是实体选择器时**：`position_origin_freeze=false` = 动态点源（旧的 `follow=entity`），`true` = 静态点源（首次取到后固定坐标，含偏移展开）。`follow` 不再单独存在。
- **通道边界**：`position_origin` 只作用于位置；`look_at` 只作用于朝向；`facing_source` 及其载荷是位置基准的方向源，不是 `look_at`。位置原点不会自动成为注视目标，注视目标也不会自动成为位置原点。
- `position_mode`/`position_space`/`position_origin`/`position_origin_freeze`/`facing_source` 的字段形态仍是 step，但语义是点源/参考系切换：切换点必须和 `position.x/y/z` 关键帧同一时间；否则 validator 报错。编辑器在切换点插入三轴关键帧。
- `position_origin`（含冻结开关）变化，或 `position_space=base` 时 `facing_source` 变化，或当前生效的 `facing_target`/`facing_target_freeze`/`facing_entity` 变化，改变参考系；切换点必须落在位置关键帧/位置组上，否则 validator 报错。base 轴没有独立的角度偏移字段。
- `facing_entity` 只取 body yaw 且始终实时；要固定方向，用 `facing_source=line` + `facing_target_freeze=true`，不要给 `facing_entity` 造冻结字段。
- 曲线条目的端点必须能明确对应到有序位置节点/位置组；点源/参考系切换不能落在端点区间内造成一段跨两个参考系。段外关键帧仍独立，没有曲线条目覆盖的段不受此限制。结构条目的序列化/引用表达待评审，但验收按此契约执行。
- 旧脚本的 `{dx,dy,dz}` 迁移为 `position_mode=relative`、`position_space=world`、`position.x/y/z`。
- 旧脚本的 `{fwd,up,right}` 迁移为 `position_mode=relative`、`position_space=base`、`position.x/y/z`（x=fwd、y=up、z=right）。
- 旧脚本的 `{x,y,z}` 迁移为 `position_mode=absolute`、`position_space=world`、`position.x/y/z`。
- 旧 `base` 段：旧 `facing_target` 非空 → `facing_source=line`；否则 `facing_source=entity`，旧方向源选择器（原点实体 / 隐式 `@p` / follow 实体 / 玩家）写进 `facing_entity`（原点本身就是该实体/玩家时可省略）；`facing_entity` 无冻结开关，`facing_target_freeze` 仅 line 保留。
- 脚本级曲线条目：每条先引用目标片段，再引用该片段目标位置节点序列中的两个相邻关键帧作为 A→B 端点；端点必须明确，绑定不得因重排静默指错。一个片段可被多条条目引用，各自选择端点对；不同条目不共享控制点。位置节点只是路径寻址域，不把 `fov`/`roll` 等普通属性拉回共享 `from/to`；节点的具体引用/存储待评审。每条条目持有自己的几何（三次贝塞尔两个内部控制点，XYZ 可分别编辑；轴集合/2D/3D 弧长）与段进度；几何与进度分离。曲线只负责 axes 中选中的坐标轴；未选轴继续按自身 SampledProperty 的局部时间/关键帧求值。插入位置关键帧时，作者可以显式重建/重绑定以产生新几何；约束是不得静默复用旧控制点改变路径，不强制某种算法，也不拒绝合法的重建创作。段外关键帧仍按 SampledProperty 插值，不要求全片段三轴成组；没有曲线条目覆盖的段正常按 SampledProperty 插值。
- 曲线条目不是 SampledProperty，也不是新轨道；它不覆盖 `fov`/`roll`/`yaw` 等其它属性，不建立共享快照。序列化/引用表达待评审；parser/validator/编辑器/迁移必须按同一结构契约消费。`objects.curve` 与片段级 `curve` 只是前稿/现行实现参照，不是目标载体；当前代码是 `clip.getCurve` 单份曲线。
- RuleDefinition/RuleBinding/RuleActivation 不属于 CAMERA 轨道语义，也不与曲线集合合并：RuleDefinition 是不可变行为；RuleBinding 是脚本级关联，引用定义、目标片段、执行局部时间门、阶段控制与动作延迟声明；RuleActivation 是播放实例运行态，以（播放实例，绑定身份，执行身份）持有判定、成功事实、动作调度/完成、等待/观察/事实记录。非阻塞观察不阻止片段自然完成；规则不并入曲线集合，也不改变曲线条目的几何/进度。规则建立本实例级内容持有时，相机内容位置不推进、状态保持；持有解除后按原执行位置继续，不由相机层自动解除或跳转。

### 3.3 朝向模型

相机是模组自己的模型；原版 Camera 只是最终 `CameraState` 的渲染适配目标，不限制作者能表达的位置/朝向。位置和朝向各自持有参考系：位置的参考系 = 点源 + `position_space`（`base` 时由 `facing_source` 给方向）；朝向的参考系 = `yaw_base`/`pitch_base`（world/entity/line）或 `look_at` 目标（世界点）。两者不自动共享；`position_origin` 不会自动成为注视目标，`look_at` 也不会自动成为位置原点。

朝向优先级（从高到低）：

1. `look_at != none`：解析注视目标点，`yaw/pitch` 由当前世界位置到目标点的方向计算；`roll` 仍取 `roll` 对象；`yaw`/`pitch` 偏移不参与。模型语义是 **`look_at` 覆盖对应朝向结果**。
2. `look_at == none`：手动朝向。
   - `yaw_base`/`pitch_base` 解析基准：`world` = 0；`entity` = `yaw_base_selector` 的身体朝向/视线俯仰；`line` = `yaw_base_from`/`yaw_base_to` 连线方向。
   - `yaw_lock`/`pitch_lock` 非 `null` 时替换该轴基准；`yaw`/`pitch` 偏移仍叠加。
   - `finalYaw = baseYaw + yaw`；`finalPitch = basePitch + pitch`；`roll` 直接。

- `look_at` 的模型语义是覆盖对应朝向结果；`look_at=none` 按手动朝向直接求值，不以远点/伪目标作为模型中间表示。当前实现仅在**至少一端 `look_at` 启用**的混合段使用 100 格远伪目标做端点求值；两端 `none` 已按角度插值。伪目标只属于混合段实现技巧，不是 `none` 的模型前提；过渡/混合按每侧实际求出的朝向状态处理（见 §3.5）。
- `yaw`/`pitch` 是**朝向偏移**，相对 `yaw_base`/`pitch_base`，不是 base 轴的 `fwd`/`right` 位置偏移；base 轴没有独立的角度偏移字段。`fwd`/`up`/`right` 只出现在位置偏移里。
- `look_at_freeze`：布尔，缺省 `false`；`true` = 首次解析成功后固定注视目标解析结果（含偏移展开的世界坐标），之后不再重取。只对实体目标生效；`look_at` 为玩家/方块/坐标等静态目标时写了 → 警告。`yaw_base_from_freeze`/`yaw_base_to_freeze` 同理，分别固定 `yaw_base` 连线的两端；只对实体端点生效。
- `yaw`/`pitch`/`roll`/`yaw_lock`/`pitch_lock` 的插值：角度最短路径；`yaw_lock`/`pitch_lock` 为 `nullable_float`，`null` 表示不锁，`null` 与数值之间按 step。
- `fov` 线性；`zoom` 几何（见 `schema-and-types.md` §3.8）。
- `orient=tangent` 且本段没有 `look_at`：`yaw/pitch` 由路径切线决定；切线分量 `dx/dy/dz` = 当前曲线条目所选轴的路径导数 + 未选轴自身 SampledProperty 的导数；没有曲线条目覆盖时全部用 SampledProperty 插值导数。关键帧 `yaw`/`pitch`/`yaw_lock`/`pitch_lock` 不参与。`yaw_offset`/`pitch_offset` 是叠加在切线 yaw/pitch 上的角度，**只有 `orient=tangent` 时读取**，可以带 keyframes 微调。
- `look_at != none` 优先于 `orient=tangent`；`look_at` 目标不可用时回退切线，再回退手动角度。`yaw_offset`/`pitch_offset` 在 `look_at` 模式下不参与。
- `orient=manual` 时，`yaw_offset`/`pitch_offset` 不读取；手动朝向的偏移是每帧 `yaw`/`pitch` 自己的值。validator 对“manual 下写了 yaw_offset/pitch_offset”给警告。
- `orient`/`yaw_offset`/`pitch_offset` 留在运行时格式里，不烘焙。曲线条目按端点区间求值/弧长；`TangentOrientation` 的切线由当前条目路径导数与未选轴 SampledProperty 导数合成。2 轴条目只保证该平面内 2D 弧长，不承诺 3D 匀速；未选轴按自身局部时间/关键帧求值，仍可独立打帧。旧 3D 曲线迁移时按旧段迁移为脚本级曲线条目，保留 A→B geometry/控制点/弧长；序列化/引用待评审，不把一份控制点套到所有段。

### 3.4 点源与选择器

- 点源字段仍是对象：
  - `position_origin`（string/ref）、`position_origin_x/y/z`、`position_origin_freeze`；
  - `facing_source`（enum：auto/world/entity/line）；
  - `facing_target`、`facing_target_x/y/z`、`facing_target_offset.x/y/z` + `facing_target_offset.space`、`facing_target_freeze`；
  - `facing_entity`（选择器；无冻结开关）；
  - `yaw_base_from`/`yaw_base_to` + `_x/_y/_z` + `_offset.x/y/z` + `_offset.space` + `yaw_base_from_freeze`/`yaw_base_to_freeze`；
  - `look_at`、`look_at_selector`、`look_at_part.x/y/z`、`look_at_target_x/y/z`、`look_at_target_structure`、`look_at_target_block`、`look_at_offset.x/y/z` + `look_at_offset.space`、`look_at_freeze`。
- 所有 `_freeze` 缺省 `false`；`true` 固定首次解析结果（点/端点，含偏移展开的世界坐标），seek 时重取；固定的是坐标值，不是时间。
- 偏移 map 统一展开为 `.x/.y/.z` + `.space`（`world`/`base`）；旧 `{dx,dy,dz}` 迁移为 space=world，旧 `{fwd,up,right}` 迁移为 space=base。
- 选择器策略字段仍是对象：
  - 通用：`selector_refresh`、`selector_switch_while_alive`、`selector_switch_interval`、`selector_switch_smooth`、`selector_anchor`、`selector_pick`；
  - 调用点专属：`<字段>_<调用点>`，调用点 = `position_origin` / `look_at` / `yaw_base` / `yaw_base_from` / `yaw_base_to` / `facing_target` / `facing_entity`；共 7×6=42 个可选对象。
- 选择器字段的采样：所有对象在同一 `t` 采样；解析器用采样值作为策略。选择器锁/平滑/冻结状态由 `EntityTargetResolver`/`PointSourceFreezes` 持有，不进入采样器。
- 点源冻结桶：旧键 = clip + slot；新键 = clip + 对象路径 + 点源形态/选择器值 + `freeze` 状态。点源对象在 clip 内变化时，桶按新值重置；避免“第一次捕获后整个 clip 冻结”的歧义（见 `track-camera.md` §4.5）。
- `isClipUsable` 只检查 `[0,duration]` 内的点源对象；静态对象直接可用；超界关键帧不参与。

### 3.5 morph / motion / breath / transition

- **每侧完整状态**：morph 的 A（上一段）/B（下一段）各自从自己的执行位置（`executionLocalTime`）求值：字段采样（无关键帧用 `value`/默认；有关键帧就求所在段，不拿末帧/首帧）→ 位置装配 → 朝向装配 → 该片段自己的 motion → 该片段自己的 breath，得到一份完整六参数快照。每侧状态由该侧执行持有，每帧只推进一次。
- **边界无显式关键帧不是特例**：A/B 在交界处没有恰好落在边界的关键帧时，各按自己的 `executionLocalTime` 求所在段；只有该片段完全没有关键帧时才用 `value`/默认。不得用 A 的末帧/B 的首帧代替。
- **transition**：`CameraTransitionLayer` 只读 A/B 两份完整快照，按上一段的 `transition_*` 声明混合；它不推进 motion/breath，不重复推进相机状态，不替两侧选效果。窗口以交界为中心、半宽 = 该过渡声明的最大通道时长 / 2（缺省 = `transition_duration`）；例如总宽 3 秒 = 交界前后各 1.5 秒。每侧在自己的窗口位置采样。
- **motion**：`motion_position/yaw/pitch/roll/fov/zoom` 是 value-only 字段（片段级行为，无 keyframes），属于各自片段的求值；A/B 各自推进自己的 motion stepper，混合后不再叠加 motion。
- **breath**：`cam_breath_*` 是 value-only 字段，属于各自片段的求值；A/B 各自持有自己的 breath 相位，混合后不再叠加 breath。不再有“motion 用下一段 / breath 用上一段”的不对称。
- **transition 字段**：`transition`/`transition_duration`/`transition_curve`/`transition_params`/`transition_interrupt` 是 value-only 字段，语义见 `B-camera/transition.md`；混合公式不变，只把输入改为两侧完整状态。
- **modifier**：`modifier`/`suppress_modifiers` 是 value-only 字段；只作用于全局相机状态，不作用于 lane；top lane 替换逻辑保持（`ScriptLaneDriver.applyModifiers`）。全局 cover/modifier 仍在各自独立层，不改变过渡混合公式。
- **capture=false**：更新 `lastWorldPos`，不产生 capture lane；保持。
- **dimension**：value-only 字段；lane 的维度源；跨维度语义见 `cross-dimension-camera.md`。

### 3.6 lane 合成参数

同一个 `SampledClip` 提供：

- `opacity`（float，linear）；
- `dest.x/y/w/h`（float，linear；窗口归一化）；
- `source.x/y/w/h`（float，linear；素材归一化；仅 image 消费）；
- `mask_x`/`mask_y`/`mask_width`/`mask_height`/`mask_radius`（float，linear；形状是 value-only `mask_shape` 字段）；
- 57 个调色通道（47 标量 + 10 强度；float，linear；lane 级调色）。

`ScriptLaneDriver` 不再调用 `computeInterpolation`/`LaneMask.effectiveTime`/`ColorAdjustSampler` 重新采样；它读同一个 `SampledClip`。`LaneMask` 只读 `mask_*` 采样值；`ColorAdjustSampler` 只读调色槽位。这样相机状态、合成参数、mask、lane 调色在同一 `t`，循环时不会错位。

### 3.7 静态相机与超界关键帧

- 片段可以没有 keyframes：所有对象用 `value`/默认值；`position` 三轴默认 0/0/0，`fov` 70，`zoom` 1，`opacity` 1；`position_mode` 默认 relative、space world。`renderSingle` 不再因 `computeInterpolation` 返回 null 而跳过。
- `isClipUsable` 只扫描范围内对象；有限片段 `time > duration` 的关键帧、无限执行（当前格式负 `duration`）`time < 0` 的关键帧不参与；一个超界关键帧即使点源解析失败，也不让 clip 不可用。
- 片段窗口仍由 `getWindowEnd` 决定；无限执行是状态语义（当前格式以负 `duration` 表示，属于格式表示，不是完成判据；不用 ∞/NaN/MAX 数值哨兵）；有限部分显示范围单独派生，见 `loop-execution.md`/`format-cleanup.md`。

## 四、技术细节

### 4.1 核心模型

```text
CameraClipRuntime
  SampledClip sample
  ClipStructure structure
  CameraAssembly assembly
  PointSourceResolver pointSources
  EntityTargetResolver selectors
  LaneMotionStepper motion
  BreathDisturbance breath
  CameraTransitionLayer transition
```

- `SampledClip` 的相机槽位包括位置/位置模式/位置空间/位置原点/点源/朝向/`orient`/offset/合成/调色；曲线条目由脚本级索引按片段挂到 `CameraClipRuntime`，不进 SampledProperty 槽位；槽位布局由 `SchemaRegistry` 编译。
- `CameraAssembly` 是纯函数：`(SampledClip, clipData, resolver results) -> CameraState + LaneComposite`。
- `PointSourceResolver`/`EntityTargetResolver` 读采样值，不读关键帧列表；解析结果显式传给 `CameraAssembly`。
- `LaneMotionStepper`/`BreathDisturbance` 仍按 clip/global time 持有状态；不进入采样器。

### 4.2 契约

- **创建**：clip 加载时构建 `CameraClipRuntime`；采样器编译；resolver 绑定实例/世界。
- **销毁**：随 clip/实例销毁；释放采样缓冲/解析缓存。
- **状态归谁**：采样器纯函数；选择器锁/冻结归 resolver；motion/breath 归各自执行侧（morph A/B 各一份 stepper/相位）；曲线条目几何只读，段进度按执行位置计算；transition 归 `CameraTransitionLayer`，只持混合进度。
- **时间**：`executionLocalTime` 由播放控制给；morph A/B 各自时间；motion/breath 用各自侧时间/相位，transition 用 `globalTime` 算窗口与进度。
- **边界**：`CameraAssembly` 不写全局相机；写全局由 `CameraTrackPlayer`/cover chain 做；lane 合成参数由 `ScriptLaneDriver` 读。

### 4.3 数据形态

- 相机 SampledProperty 槽位：float 数组 + 少量 string/ref/enum 数组；`yaw_lock`/`pitch_lock` 用 `nullable` 标记 + `float[]` + `boolean[]`，禁止用 NaN 当哨兵。
- `position_mode`/`position_space`/`position_origin`/`position_origin_freeze`/`facing_source`/`facing_target`/`facing_entity`/`facing_target_freeze`/`look_at`/`look_at_freeze`/`yaw_base`/`yaw_base_from`/`yaw_base_to`/`yaw_base_from_freeze`/`yaw_base_to_freeze`/`pitch_base`/`selector_*` 是 step 槽位；step 槽位采样时只取当前值。
- 57 个调色通道按**具名映射**对应 `ColorAdjustParams` 的通道；字段排列顺序不决定实际运算顺序（运算顺序由着色器栈/参数定义决定）。schema/params/fsh 增删必须按名称同步，不能靠数组下标猜对应关系。
- 点源对象是“kind + payload + freeze”的组合；kind/freeze step，payload 数值 linear/step；payload 的 space 是 step。
- `event` 不属于相机；相机没有离散事件对象。

### 4.4 边界

- 不碰 `multi-camera-rendering` 的 FBO/lane 上屏；只提供 lane 的 `CameraState` 和合成参数。
- 不碰 `iris-oculus-compat` 的光影隔离；lane 维度/状态由渲染层处理。
- 不碰 cover chain 的优先级/修饰符公式；只把 value-only `modifier`/`suppress_modifiers` 字段传进去。
- 不碰选择器数学；只把采样值传给 `EntityTargetResolver`。
- 不碰迁移；旧曲线按旧段迁移为脚本级曲线条目、`orient`/offset 保留的规则在 `migration.md`。
- 不碰规则阶段/持有/事实接力；只响应执行层给出的内容位置。实例级持有期间相机不推进内容位置；规则何时持有、成功、延迟执行由 `rule-system.md` 定义。

### 4.5 点源/方向源冻结键（行为修正）

旧实现 `PointSourceSnapshots` 以 clip + slot 为键；如果位置点源/`look_at` 在 clip 内从实体 A 切到实体 B，第一次捕获的点会一直用到 clip 结束。新模型（`PointSourceFreezes`）：

- 键 = clip 实例 + 对象路径 + 来源形态（kind/selector/structure/block/coordinate）+ `freeze` 状态；
- 点源/方向源对象值变化时，旧桶失效，重新捕获；`facing_target_freeze` 独立，`facing_entity` 无冻结；
- `freeze=true` 在 seek 时重取；`loop` 折返时是否延续按 `loop-execution.md` 的状态策略；
- morph A/B 各自持有桶，不共享。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| `position` 保持复合对象 | `position` 一个对象，内部 x/y/z 再分 | 不能单轴调；与“叶子属性”冲突；schema/编辑器要写复合特例 | 位置是 `position.x/y/z` 三个 SampledProperty |
| 端点快照 + 叶子采样并存 | 位置/朝向仍按 from/to 端点求值，其他属性按叶子 | 两套求值；隐藏顺序；稀疏字段/循环更复杂 | 无法统一“同一 t 采样” |
| 拆成三条独立 1D 曲线 | `curve.x/y/z` 各存标量控制点 | 1D 没有平面/空间曲线，无法定义 2D/3D 弧长；三轴时间不对齐时也不是一条明确的几何曲线 | 每条曲线条目引用目标片段的 A→B 相邻端点并持有自己的 geometry |
| `position_mode`/`position_space`/`position_origin` 三个独立开关任意组合 | 作者自由组合 | `absolute`+非空原点、`absolute+base` 自相矛盾；同一位置出现无点源/两个点源 | 按点源模型投影，冲突组合校验拒绝 |
| 保留 `_time` 枚举（`live`/`snapshot`） | 字符串枚举表达时态 | 字符串拼写/未知值回落；字段名带 `_time` 容易被读成时间；新增第三态要改 schema/parser/文档 | 改 `_freeze` 布尔：`true` 冻结 / `false` 实时，缺省 `false`；语义唯一 |
| 保留 `follow` 作为独立字段 | `follow` + `follow_selector` 与 `position_origin` 并存 | 同一件事两种写法；动态实体点源被拆成两套字段/选择器调用点 | 删除 `follow`，统一为 `position_origin` + `position_origin_freeze` |
| 方向源只按原点类型写死 | 实体默认实体朝向、非实体默认世界轴，不能显式覆盖 | 作者不能在非实体原点用实体水平朝向，也不能在实体原点用连线/世界轴 | 方向源单选：`facing_source` 显式选 world/entity/line；`auto` 按载荷/原点回落 |
| 轴集合写成固定枚举 `xy/xz/yz/xyz` | 只允许四种组合 | 不能表达 `["y","x"]` 这类等价写法，也不能表达未来新增轴组合 | 轴集合：至少两个、去重、顺序无关 |
| clip 级一份控制点套所有段 | 一个字段只有一组 P1/P2，隐式作用到每个位置段 | 不同段不能有不同曲线；同片段不能有多条候选路径；插入关键帧会改变其余段 | 脚本级曲线集合，每条引用目标片段与端点对 |
| 曲线只按全片段关键帧总编号 | 把 FOV/调色等所有对象的帧拼成总序列，曲线按总序号取相邻帧 | 其它对象增删帧会改变位置曲线端点；段归属不可见 | 端点相邻性只在目标位置序列内判断 |
| 曲线退出运行时、烘焙成位置关键帧 | 迁移/编辑器按采样率烘焙 | 脚本变大、曲线不可逆、旧路径无法逐点保真 | 保留脚本级曲线条目作为编排结构，不烘焙 |
| 曲线作为片段内 value-only 字段 | `objects.curve` 只放一组值 | 无法表达目标片段/端点对/多条目；与 SampledProperty 容器混淆 | 脚本级曲线集合，与 SampledProperty 分离 |
| `orient`/offset 进 clip meta | 单独 clip 字段，不进字段容器 | 脚本多一层；前置条件/关键帧关系散落 | `orient` value-only + offset animated + `requires` |
| 把 100 格伪目标写进模型前提 | 模型规定混合段必须用伪目标端点插值 | 伪目标变成作者可见语义；与“`look_at` 覆盖朝向结果”冲突；混合/迁移被实现技巧绑死 | 模型只规定 `look_at` 覆盖对应朝向结果；伪目标只留在至少一端启用的混合段实现层，见 §六 |
| 过渡混合后再套 next motion / prev breath | 先混合六参数，再用下一段 motion、上一段 breath 补齐 | 两侧效果归属错位；每侧状态不完整；同一效果可能被推进多次或漏掉 | 每侧先求完整状态（含本段 motion/breath），过渡只混合快照 |
| lane 参数各自重采样 | 相机、合成、mask、调色各调一次 | 循环/seek 时可能不同步；重复计算 | 一个 `SampledClip` 多消费者 |

**本方案的缺点**：

- 相机字段数量大（160+），原始 JSON 冗长；作者入口是编辑器，手写脚本不是推荐路径。编辑器必须用折叠/搜索/虚拟滚动承接字段密度。
- 位置点源/参考系切换会改变三轴含义；validator 拒绝冲突组合，并要求切换点有位置关键帧，不能自动修复。
- 方向源 `auto` 仍按原点回落：实体原点默认实体水平朝向，非实体原点默认世界轴；旧 `base` 段若原点不是方向来源（例如非实体原点却用玩家朝向），迁移必须显式写 `facing_source=entity` + `facing_entity`，否则画面会变。显式方向源让作者多写字段，但避免了“按原点猜”的耦合。
- `_freeze` 只有两态；未来若需要第三种（延迟捕获/暂停/分段冻结）要加字段或改回枚举。当前没有这种需求，先用布尔保持简单。
- `facing_entity` 没有冻结开关；要固定方向只能用 `facing_source=line` + `facing_target_freeze=true`，不能冻结实体 body yaw。这是为了不新增第六个冻结字段。
- 曲线条目/RuleDefinition/RuleBinding 的职责已定，但序列化/引用/身份字段仍待评审；schema/编辑器/迁移必须按同一结构契约实现，不能把结构条目塞回 SampledProperty。RuleActivation 以（播放实例，绑定身份，执行身份）隔离运行态；非阻塞观察不阻止片段自然完成。
- 曲线条目的几何保真要求保留，但不锁死编辑算法：插入关键帧后如何表达/维护端点关系由结构契约决定；不能用“复用旧控制点”冒充保形。
- 2 轴曲线条目只保证该平面内 2D 弧长，不承诺 3D 匀速；未选轴继续按自身 SampledProperty 的局部时间/关键帧求值，仍可独立打帧。
- `orient=tangent` 的切线由当前曲线条目路径导数与未选轴 SampledProperty 导数合成；2D 弧长不承诺 3D 匀速。端点区间/控制点差异大时切线可能抖动，需要编辑器/验收覆盖。
- 同一片段多条曲线条目需要编辑器显式显示目标片段/端点对；否则作者难以判断当前时间由哪条条目覆盖。
- `yaw_offset`/`pitch_offset` 是 animated 字段，前置条件 `orient=tangent`；编辑器必须正确置灰/隐藏，否则作者会写“无效字段”。
- morph 每侧都要持一份完整 motion/breath 状态和快照；状态/缓冲比“混合后套一份效果”多，但这是两侧效果归属正确的前提。
- 点源冻结键变化会改变“clip 内换目标”的行为；需要实测确认没有意外重置。
- 至少一端 `look_at` 启用的混合段伪目标仍留在当前实现里；模型不把它当前提，新实现按每侧实际朝向状态求值；迁移/对拍仍要按旧行为处理，见 §六。

## 六、实现结果

状态：未实现

本节只列当前代码与设计的差距；未列出的项按“未实现”处理。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 每侧完整状态（含本段 motion/breath）→ 过渡混合 | `CameraKeyframeEvaluator.renderMorph` 先混合六参数，再用 `nextClip` 的 motion、`prevClip` 的 breath 补齐；两侧没有各自的完整状态 | 现行实现按“混合后套效果”接线；新设计要求每侧先求完整状态，过渡只混合快照 |
| 某侧无关键帧时按 `value`/所在段求值 | morph 某侧 `computeInterpolation` 返回 null 时用 `lastWorldPos`/0 兜底 | 旧端点求值路径没有静态片段语义；新设计按字段采样 |
| 每侧 motion/breath 每帧只推进一次 | 混合后只对 `nextClip`/`prevClip` 各跑一次 | 现行实现缺少两侧各自完整效果状态，不能据此推断每侧已独立推进 |
| 曲线为脚本级集合，每条引用目标片段与目标位置序列相邻端点；RuleDefinition 不可变、RuleBinding 引用目标片段与时间门、RuleActivation 以（实例，绑定，执行）隔离 | 旧 `Clip.curve` 是 clip 级一份 3D 控制点，`interpolateWorldPosition` 对每个 from→to 段隐式复用；`objects.curve` 从未实现；规则是片段级 `clip.rules`，没有定义/绑定/时间门分层 | 旧实现只有一份 `curve` 与片段级规则数组；脚本级结构是新增设计，序列化/引用待评审 |
| `look_at` 覆盖对应朝向结果；`look_at=none` 直接按手动朝向求值 | 至少一端 `look_at` 启用的混合段当前用 100 格远伪目标做端点求值；两端 `none` 已按角度插值 | 伪目标只影响混合段端点求值，不是 `none` 的通用实现；新实现按每侧实际朝向状态求值 |
| 过渡窗口以交界为中心（3 秒 = 前后各 1.5 秒） | `TransitionSpec` 窗口 = 交界 ± maxDuration/2；morph 走同一口径 | 与设计一致 |
