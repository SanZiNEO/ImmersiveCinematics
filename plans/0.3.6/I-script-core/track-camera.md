# CAMERA 轨道对象与装配

> 状态：未实现
> 范围：CAMERA 对象清单、位置/朝向装配顺序、点源/选择器对象、morph/motion/breath/modifier、lane 合成参数
> 关联：`object-model.md`、`runtime-evaluation.md`、`track-overlay-letterbox.md`；语义参考 `complete/0.3.6/B-camera/*`、`F-playback/camera-composition.md`、`F-playback/multi-camera-rendering.md`、`F-playback/iris-oculus-compat.md`

## 一、摘要

CAMERA 的字段是叶子：`position.x/y/z`、`yaw`、`pitch`、`look_at`、`opacity`、`dest.x`……每个字段独立采样。运行时在同一 `executionLocalTime` 采样全部字段，然后按固定顺序组装世界位置、朝向、六参数 `CameraState`，再应用 value-only motion/breath/transition/modifier，最后把 lane 合成参数（opacity/dest/source/mask/调色）与相机状态一起交给渲染。`curve`/`orient`/`yaw_offset`/`pitch_offset` 留在运行时格式里：`curve` 是一个带轴集合的曲线字段，`orient` 是 value-only 模式，offset 是 animated 字段；不做曲线烘焙。

## 二、为什么做

### 2.1 问题

- `position` 是一个 map，只能整体插值；想只动 x 轴做不到。
- 相机求值按“两个端点”算世界位置/朝向，再在端点之间插值；对象模型要求每个叶子独立采样，端点快照不再成立。
- `frame` 是 per-frame 可变字段，base-space 偏移读到哪个 frame 取决于调用顺序（`from`/`to`/tangent 重算）；这是隐藏状态。
- `look_at=none` 用 100 格远的伪目标，和手动朝向的 `base + lock + offset` 口径不一致。
- 旧 `curve` 是 3D 共享参数，三轴不能独立调；新模型把三轴拆开，无 `curve` 时独立，有 `curve` 时再成组。`orient`/offset 又和位置字段分散在两层，前置条件不显式。
- lane 合成参数、mask、lane 调色各自重复采样，循环时可能不同步。

### 2.2 目标

- 每个可独立调的量是一个字段；位置三轴、矩形四边、遮罩几何、调色通道都能单独加关键帧。
- 所有字段在同一 `t` 采样；位置/朝向装配只读采样值，不依赖调用顺序。
- 相机静态片段（无关键帧）也能工作；超界关键帧不影响 clip 可用性。
- 一个 `SampledClip` 同时供相机状态、lane 合成参数、lane 调色、mask 使用；同一帧同一 `t`。
- 运行时保留 Bezier/切线组合，按轴集合求值/导数；曲线不烘焙成位置关键帧。
- 保持 B-camera 已定的 cover chain、transition、selector、motion 语义；本文件只定义“对象怎么喂给它们”。

### 2.3 非目标

- 不重新定义相机数学（坐标框架、cover chain、transition 曲线、selector 策略、motion 模型）；这些以 `complete/0.3.6/B-camera/*` 为准。
- 不把 `motion_*`/`cam_breath_*`/`transition_*` 做成 animated/event；它们是 value-only 字段。
- 不把 `dimension` 做成 animated/event；跨维度是 value-only 字段。
- 不做编辑器曲线 UI；只定义运行时字段格式。
- 不改变渲染/合成管线；lane 仍走 `multi-camera-rendering.md`。

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

- 位置三轴是三个字段；`position_mode`/`position_space`/`position_origin`/`position_origin_freeze` 是 step 字段，但语义是点源/参考系切换，必须和位置关键帧对齐；不是独立旋钮。
- `dest`/`source` 展开成 `dest.x/y/w/h`、`source.x/y/w/h`；遮罩几何保留现有标量名（`mask_x`……）；调色通道保留现有名字（`exposure`、`mix_rg`……）。
- `curve` 是一个 value-only 字段，不拆成 `curve.x/y/z`。`axes` 是轴集合：至少两个轴、去重、顺序无关（`["y","x"]` 与 `["x","y"]` 等价）；`control_points` 是两个 3D 控制点（与旧 `BezierCurve` 的 P1/P2 一致）。`curve` 是片段级一份；有 `curve` 时三轴关键帧成组，没有 `curve` 时三轴独立。轴集合选 2 轴时做该平面内的 2D 弧长，选 3 轴时做 3D 弧长。
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
- 有 `curve` 时，位置组由三轴关键帧定义；点源/参考系切换也必须落在位置组时间上，否则曲线段跨两个参考系，validator 报错。
- 旧脚本的 `{dx,dy,dz}` 迁移为 `position_mode=relative`、`position_space=world`、`position.x/y/z`。
- 旧脚本的 `{fwd,up,right}` 迁移为 `position_mode=relative`、`position_space=base`、`position.x/y/z`（x=fwd、y=up、z=right）。
- 旧脚本的 `{x,y,z}` 迁移为 `position_mode=absolute`、`position_space=world`、`position.x/y/z`。
- 旧 `base` 段：旧 `facing_target` 非空 → `facing_source=line`；否则 `facing_source=entity`，旧方向源选择器（原点实体 / 隐式 `@p` / follow 实体 / 玩家）写进 `facing_entity`（原点本身就是该实体/玩家时可省略）；`facing_entity` 无冻结开关，`facing_target_freeze` 仅 line 保留。
- `curve` 是片段级 value-only，一份，同一组控制点对每个“位置组”段复用；位置组 = `position.x/y/z` 同一时间的关键帧。**有 `curve` 时三轴关键帧时间集合必须完全一致，每个时间点三轴都要有显式值**；缺值/不对齐 = validator 错误；没有 `curve` 时三轴仍可独立。控制点在段的空间内解析：`world` 段用世界坐标，`base` 段用 fwd/up/right 坐标；相对/绝对按段起点。`axes` 决定哪些轴参与曲线形状，未参与的轴按自己的 `position` 关键帧线性求值（关键帧时间与位置组一致）。
- 轴集合选 2 轴时，在该平面内做 2D 弧长；第三轴仍按自己的 `position` 关键帧线性走，不参与弧长，所以 3D 速度不一定匀。轴集合选 3 轴时，做完整 3D 弧长，整条路径匀速。
- 控制点统一按 3D 存；2D 模式只取 `axes` 对应的两个分量，3D 模式取三个分量。
- `curve` 在段的空间内是绝对的：`base` 段即使参考系动态，曲线仍按 base 内的 A→B 控制点求值，再随参考系整体变换到世界；不因动态参考系退化为线性。冻结只把所属点源的解析结果固定住（含偏移展开的世界坐标），不是停时间，也不改变曲线语义。

### 3.3 朝向模型

朝向优先级（从高到低）：

1. `look_at != none`：解析注视目标点，`yaw/pitch` 由当前世界位置到目标点的方向计算；`roll` 仍取 `roll` 对象；`yaw`/`pitch` 偏移不参与（与现行口径一致）。
2. `look_at == none`：手动朝向。
   - `yaw_base`/`pitch_base` 解析基准：`world` = 0；`entity` = `yaw_base_selector` 的身体朝向/视线俯仰；`line` = `yaw_base_from`/`yaw_base_to` 连线方向。
   - `yaw_lock`/`pitch_lock` 非 `null` 时替换该轴基准；`yaw`/`pitch` 偏移仍叠加。
   - `finalYaw = baseYaw + yaw`；`finalPitch = basePitch + pitch`；`roll` 直接。
3. `look_at=none` **保持现行语义**：运行时仍用该关键帧 `yaw`/`pitch` 的 100 格远伪目标，供两端目标点插值复用。是否改成“纯手动朝向”是另一个设计决策，I 轮不偷偷改；迁移/验收按现行行为。

- `yaw`/`pitch` 是**朝向偏移**，相对 `yaw_base`/`pitch_base`，不是 base 轴的 `fwd`/`right` 位置偏移；base 轴没有独立的角度偏移字段。`fwd`/`up`/`right` 只出现在位置偏移里。
- `look_at_freeze`：布尔，缺省 `false`；`true` = 首次解析成功后固定注视目标解析结果（含偏移展开的世界坐标），之后不再重取。只对实体目标生效；`look_at` 为玩家/方块/坐标等静态目标时写了 → 警告。`yaw_base_from_freeze`/`yaw_base_to_freeze` 同理，分别固定 `yaw_base` 连线的两端；只对实体端点生效。
- `yaw`/`pitch`/`roll`/`yaw_lock`/`pitch_lock` 的插值：角度最短路径；`yaw_lock`/`pitch_lock` 为 `nullable_float`，`null` 表示不锁，`null` 与数值之间按 step。
- `fov` 线性；`zoom` 几何（见 `schema-and-types.md` §3.8）。
- `orient=tangent` 且本段没有 `look_at`：`yaw/pitch` 由路径切线决定；切线分量 `dx/dy/dz` 来自曲线路径导数（参与轴由 `curve`，未参与轴按 `position` 线性）；关键帧 `yaw`/`pitch`/`yaw_lock`/`pitch_lock` 不参与。`yaw_offset`/`pitch_offset` 是叠加在切线 yaw/pitch 上的角度，**只有 `orient=tangent` 时读取**，可以带 keyframes 微调。
- `look_at != none` 优先于 `orient=tangent`；`look_at` 目标不可用时回退切线，再回退手动角度。`yaw_offset`/`pitch_offset` 在 `look_at` 模式下不参与。
- `orient=manual` 时，`yaw_offset`/`pitch_offset` 不读取；手动朝向的偏移是每帧 `yaw`/`pitch` 自己的值。validator 对“manual 下写了 yaw_offset/pitch_offset”给警告。
- `curve`/`orient`/`yaw_offset`/`pitch_offset` 都留在运行时格式里，不烘焙。`BezierPathStrategy` 按轴集合求值/弧长；`TangentOrientation` 的切线由完整 3D 路径导数组合，2 轴组合的第三轴线性分量也参与切线，但不参与 2D 弧长。旧 3D `curve` 原样迁移为 `axes:["x","y","z"]`，不拆控制点。

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

- **morph**：A（上一段）/B（下一段）各自用自己的 `executionLocalTime` 采样、组装六参数；`CameraTransitionLayer` 按上一段的 `transition_*` 混合。A/B 的采样缓冲预分配，不复制。
- **motion**：`motion_position/yaw/pitch/roll/fov/zoom` 是 value-only 字段（片段级行为，无 keyframes），在 A/B 混合后应用；当前口径是“motion 用下一段（lane owner）”，保持。
- **breath**：`cam_breath_*` 是 value-only 字段，在 motion 后应用；当前口径是“breath 用上一段”，保持。该不对称是现行行为，不在本轮修正；若以后要统一，单独改。
- **transition**：`transition`/`transition_duration`/`transition_curve`/`transition_params`/`transition_interrupt` 是 value-only 字段，语义见 `B-camera/transition.md`；本轮只把这些字段放进 `objects`，不改混合公式。
- **modifier**：`modifier`/`suppress_modifiers` 是 value-only 字段；只作用于全局相机状态，不作用于 lane；top lane 替换逻辑保持（`ScriptLaneDriver.applyModifiers`）。
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
- `isClipUsable` 只扫描范围内对象；一个 `time > duration` 的关键帧即使点源解析失败，也不让 clip 不可用。
- 片段窗口仍由 `getWindowEnd` 决定；`duration` 为负 = 无限（见 `loop-execution.md`/`format-cleanup.md`）。

## 四、技术细节

### 4.1 核心模型

```text
CameraClipRuntime
  SampledClip sample
  CameraAssembly assembly
  PointSourceResolver pointSources
  EntityTargetResolver selectors
  LaneMotionStepper motion
  BreathDisturbance breath
  CameraTransitionLayer transition
```

- `SampledClip` 的相机槽位包括位置/位置模式/位置空间/位置原点/点源/朝向/`curve`/`orient`/offset/合成/调色；槽位布局由 `SchemaRegistry` 编译。
- `CameraAssembly` 是纯函数：`(SampledClip, clipData, resolver results) -> CameraState + LaneComposite`。
- `PointSourceResolver`/`EntityTargetResolver` 读采样值，不读关键帧列表；解析结果显式传给 `CameraAssembly`。
- `LaneMotionStepper`/`BreathDisturbance` 仍按 clip/global time 持有状态；不进入采样器。

### 4.2 契约

- **创建**：clip 加载时构建 `CameraClipRuntime`；采样器编译；resolver 绑定实例/世界。
- **销毁**：随 clip/实例销毁；释放采样缓冲/解析缓存。
- **状态归谁**：采样器纯函数；选择器锁/冻结归 resolver；motion/breath 归各自 stepper；transition 归 `CameraTransitionLayer`。
- **时间**：`executionLocalTime` 由播放控制给；morph A/B 各自时间；`globalTime` 只给 motion/breath/transition。
- **边界**：`CameraAssembly` 不写全局相机；写全局由 `CameraTrackPlayer`/cover chain 做；lane 合成参数由 `ScriptLaneDriver` 读。

### 4.3 数据形态

- 相机对象槽位：float 数组 + 少量 string/ref/enum 数组；`yaw_lock`/`pitch_lock` 用 `float[]` + `presence`/NaN 禁止；用 `nullable` 标记 + `float[]` + `boolean[]`。
- `position_mode`/`position_space`/`position_origin`/`position_origin_freeze`/`facing_source`/`facing_target`/`facing_entity`/`facing_target_freeze`/`look_at`/`look_at_freeze`/`yaw_base`/`yaw_base_from`/`yaw_base_to`/`yaw_base_from_freeze`/`yaw_base_to_freeze`/`pitch_base`/`selector_*` 是 step 槽位；step 槽位采样时只取当前值。
- 57 个调色通道顺序 = `ColorAdjustParams` 着色器栈顺序；schema/params/fsh 三处逐项对应，增删必须同步。
- 点源对象是“kind + payload + freeze”的组合；kind/freeze step，payload 数值 linear/step；payload 的 space 是 step。
- `event` 不属于相机；相机没有离散事件对象。

### 4.4 边界

- 不碰 `multi-camera-rendering` 的 FBO/lane 上屏；只提供 lane 的 `CameraState` 和合成参数。
- 不碰 `iris-oculus-compat` 的光影隔离；lane 维度/状态由渲染层处理。
- 不碰 cover chain 的优先级/修饰符公式；只把 value-only `modifier`/`suppress_modifiers` 字段传进去。
- 不碰选择器数学；只把采样值传给 `EntityTargetResolver`。
- 不碰迁移；`curve` 原样迁移、`orient`/offset 保留的规则在 `migration.md`。

### 4.5 点源/方向源冻结键（行为修正）

旧实现 `PointSourceSnapshots` 以 clip + slot 为键；如果位置点源/`look_at` 在 clip 内从实体 A 切到实体 B，第一次捕获的点会一直用到 clip 结束。新模型（`PointSourceFreezes`）：

- 键 = clip 实例 + 对象路径 + 来源形态（kind/selector/structure/block/coordinate）+ `freeze` 状态；
- 点源/方向源对象值变化时，旧桶失效，重新捕获；`facing_target_freeze` 独立，`facing_entity` 无冻结；
- `freeze=true` 在 seek 时重取；`loop` 折返时是否延续按 `loop-execution.md` 的状态策略；
- morph A/B 各自持有桶，不共享。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| `position` 保持复合对象 | `position` 一个对象，内部 x/y/z 再分 | 不能单轴调；与“叶子对象”冲突；schema/编辑器要写复合特例 | 用户已明确位置是 x/y/z 三个对象 |
| 端点快照 + 叶子采样并存 | 位置/朝向仍按 from/to 端点求值，其他对象按叶子 | 两套求值；隐藏顺序；稀疏字段/循环更复杂 | 无法统一“同一 t 采样” |
| 拆成三条独立 1D 曲线 | `curve.x/y/z` 各存标量控制点 | 1D 没有平面/空间曲线，无法定义 2D/3D 弧长；三轴时间不对齐时也不是一条明确的几何曲线 | 一个 `curve` 字段 + 轴集合 |
| `position_mode`/`position_space`/`position_origin` 三个独立开关任意组合 | 作者自由组合 | `absolute`+非空原点、`absolute+base` 自相矛盾；同一位置出现无点源/两个点源 | 按点源模型投影，冲突组合校验拒绝 |
| 保留 `_time` 枚举（`live`/`snapshot`） | 字符串枚举表达时态 | 字符串拼写/未知值回落；字段名带 `_time` 容易被读成时间；新增第三态要改 schema/parser/文档 | 改 `_freeze` 布尔：`true` 冻结 / `false` 实时，缺省 `false`；语义唯一 |
| 保留 `follow` 作为独立字段 | `follow` + `follow_selector` 与 `position_origin` 并存 | 同一件事两种写法；动态实体点源被拆成两套字段/选择器调用点 | 删除 `follow`，统一为 `position_origin` + `position_origin_freeze` |
| 方向源只按原点类型写死 | 实体默认实体朝向、非实体默认世界轴，不能显式覆盖 | 作者不能在非实体原点用实体水平朝向，也不能在实体原点用连线/世界轴 | 方向源单选：`facing_source` 显式选 world/entity/line；`auto` 按载荷/原点回落 |
| 轴集合写成固定枚举 `xy/xz/yz/xyz` | 只允许四种组合 | 不能表达 `["y","x"]` 这类等价写法，也不能表达未来新增轴组合 | 轴集合：至少两个、去重、顺序无关 |
| 只要求 `axes` 选中轴成组 | 第三轴可独立打点 | 2D 模式的段定义要按轴集合分别算，编辑器和校验更绕 | 有 `curve` 时三轴统一成组，段定义唯一 |
| `curve` 退出运行时、烘焙成位置关键帧 | 迁移/编辑器按采样率烘焙 | 脚本变大、曲线不可逆、旧路径无法逐点保真 | 保留 value-only `curve` 字段 |
| `orient`/offset 进 clip meta | 单独 clip 字段，不进字段容器 | 脚本多一层；前置条件/关键帧关系散落 | `orient` value-only + offset animated + `requires` |
| `look_at=none` 改纯手动朝向 | 删除伪目标，改 base + lock + offset | 改变现行端点/混合语义；需要单独设计 | 本轮保持现行伪目标，另开设计 |
| lane 参数各自重采样 | 相机、合成、mask、调色各调一次 | 循环/seek 时可能不同步；重复计算 | 一个 `SampledClip` 多消费者 |

**本方案的缺点**：

- 相机字段数量大（160+），原始 JSON 冗长；作者入口是编辑器，手写脚本不是推荐路径。编辑器必须用折叠/搜索/虚拟滚动承接字段密度。
- 位置点源/参考系切换会改变三轴含义；validator 拒绝冲突组合，并要求切换点有位置关键帧，不能自动修复。
- 方向源 `auto` 仍按原点回落：实体原点默认实体水平朝向，非实体原点默认世界轴；旧 `base` 段若原点不是方向来源（例如非实体原点却用玩家朝向），迁移必须显式写 `facing_source=entity` + `facing_entity`，否则画面会变。显式方向源让作者多写字段，但避免了“按原点猜”的耦合。
- `_freeze` 只有两态；未来若需要第三种（延迟捕获/暂停/分段冻结）要加字段或改回枚举。当前没有这种需求，先用布尔保持简单。
- `facing_entity` 没有冻结开关；要固定方向只能用 `facing_source=line` + `facing_target_freeze=true`，不能冻结实体 body yaw。这是为了不新增第六个冻结字段。
- 有 `curve` 的 clip 失去三轴独立打点：三轴关键帧必须成组，常量轴也要补值；不开 `curve` 时三轴仍独立。
- 2 轴 `curve` 只在该平面内做 2D 弧长；第三轴线性变化时，3D 速度不一定匀。要整条 3D 路径匀速就选 3 轴。
- `orient=tangent` 的切线由完整 3D 路径导数组合；2 轴组合的第三轴线性分量也参与切线，但不参与 2D 弧长；轴集合段边界/控制点差异大时切线可能抖动，需要编辑器/验收覆盖。
- `yaw_offset`/`pitch_offset` 是 animated 字段，前置条件 `orient=tangent`；编辑器必须正确置灰/隐藏，否则作者会写“无效字段”。
- morph 的 motion/breath 来源不对称（next/prev）保留，读起来不直观。
- 点源冻结键变化会改变“clip 内换目标”的行为；需要实测确认没有意外重置。
- `look_at=none` 的 100 格伪目标保留；混合段仍是“伪目标端点”，与手动朝向口径不一致。这是现行行为，I 轮不改，后续单独评估。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
