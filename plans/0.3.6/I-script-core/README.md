# I 轮：脚本底层重构与运行时媒体能力（索引 / 决策日志）

> 状态：方向已定，详细文档已写，未实现
> 范围：对象-关键帧组、schema/类型、运行时求值、各轨道装配、片段循环、删除 `meta.version` / `timeline.total_duration`、迁移、编辑器对象模型、验收；视频播放/传输属于运行时底层能力，不改脚本字段，见 `media-video.md`
> 关联：`G-script-model/script-model.md`（G1/G2 被本轮吸收，G3 延后）、`done/script-loop.md`（片段级折叠作废）、`F-playback/`、`docs/SCRIPT_FORMAT.md`

本页只做索引和决策记录，不重复各文档的正文。一个点一篇文档；接口、字段、公式、迁移步骤以各文档为准。

作者入口是编辑器；原始 JSON 是运行时/迁移/调试格式，手写脚本不是推荐路径。

## 一、文档地图

| 文档 | 负责什么 | 状态 |
|---|---|---|
| [object-model.md](object-model.md) | 字段形态（value/animated/event）、路径、取值/插值/边界、依赖前置条件 | 未实现 |
| [schema-and-types.md](schema-and-types.md) | schema 声明、字段模式/类型、插值推导、parser/validator/editor/模板契约 | 未实现 |
| [runtime-evaluation.md](runtime-evaluation.md) | 同一 executionLocalTime 采样、装配边界、缓存/零分配、morph/lane 求值 | 未实现 |
| [track-camera.md](track-camera.md) | CAMERA 字段表、curve 轴组合、orient/offset、位置/朝向装配、点源/选择器、morph/motion/breath/modifier | 未实现 |
| [track-overlay-letterbox.md](track-overlay-letterbox.md) | OVERLAY fade/image/subtitle 字段、颜色/遮罩/渐变、LETTERBOX | 未实现 |
| [track-audio-adjust.md](track-audio-adjust.md) | AUDIO 字段/value-only、ADJUST 59 通道、曲线/LUT/混合 | 未实现 |
| [track-event.md](track-event.md) | EVENT/MOD_EVENT 离散记录、边沿触发、事件身份 | 未实现 |
| [loop-execution.md](loop-execution.md) | 按次数执行、executionLocalTime、展开窗口、pingpong、执行边界状态、宏观循环 | 未实现 |
| [format-cleanup.md](format-cleanup.md) | 删除 version/total_duration、contentEnd/infinite、触点/旧字段拒绝 | 未实现 |
| [migration.md](migration.md) | 一次性转换器、稀疏保真、curve 原样迁移、orient/offset 保留、验收/回滚 | 未实现 |
| [editor-object-model.md](editor-object-model.md) | 编辑器字段模型、关键帧 UI、缓动烘焙、curve/orient/offset 编辑、保存/校验 | 未实现 |
| [media-video.md](media-video.md) | 运行时视频解码/播放、预览编码流、媒体分片传输；不改脚本字段 | 未实现 |
| [acceptance.md](acceptance.md) | 数据/相机/覆盖层/音频/事件/循环/迁移/编辑器/真实机器矩阵 | 未实现 |

## 二、已定决策

以下决策由用户口径 + 现有计划推导，写在对应文档里；如果与你的想法不同，直接改文档，不要改实现去猜。

1. **字段是叶子，路径扁平；`objects` 是字段容器。** `objects` 是 `{path: {value, keyframes[]}}`；路径是点分字符串。已有标量字段名保留；只有 JSON map 展开（`position.x`、`dest.w`、`source.h`、`color.r`、`offset.x`）。嵌套 JSON 只作为编辑器呈现，不是运行时格式。
2. **同一片段所有字段在同一 `executionLocalTime` 采样，采样后再装配。** 不再有共享 `from/to` 快照；不再有 per-frame `frame` 隐藏字段；轨道装配顺序显式。
3. **`curve`/`orient`/`yaw_offset`/`pitch_offset` 是运行时字段，不烘焙。** `curve` 是一个整体字段，不拆成 `curve.x/y/z`：它带一个**轴集合**（至少两个轴，顺序无关，允许 `["y","x"]` 这类写法）和两个 3D 控制点；轴集合选 2 轴时做该平面内的 2D 弧长，选 3 轴时做 3D 弧长。`curve` 是**片段级一份**，同一组控制点对每个“位置组”段复用；位置组 = `position.x/y/z` 同时间关键帧；**有 `curve` 时三轴关键帧时间集合必须完全一致，每个时间点三轴都要有显式值**，缺值/不对齐 = validator 错误；没有 `curve` 时三轴仍可独立。`orient` 是 value-only 模式；`yaw_offset`/`pitch_offset` 是 animated 字段，前置条件 `orient=tangent`，可逐关键帧微调。运行时保留 `BezierPathStrategy`/`TangentOrientation`，按轴集合求值/导数。`curve` 在段的空间内求值（`world` 用世界坐标，`base` 用 fwd/up/right）；动态参考系不改变曲线语义。
4. **位置 = 点源 + 偏移；绝对位置 = 无点源的世界坐标。** 点源 = 来源 + 偏移 + 表达空间 + 冻结开关，来源分静态/动态：静态 = 玩家激活位置/固定坐标/结构/方块/实体冻结，动态 = 实体实时。字段统一为 `position_mode`（absolute/relative）、`position_space`（world/base）、`position_origin`（玩家激活/坐标/实体选择器/方块/结构）、`position_origin_freeze`（bool，true = 固定首次解析到的坐标，含偏移展开）；`follow`/`follow_selector` 不再是新格式字段，“跟随实体”= `position_origin=<实体选择器>` + `position_origin_freeze=false`。`absolute` 时原点必须为空；`absolute`+`base`、`absolute`+非空原点错误；`relative` 空原点：`world` 缺省玩家激活位置（静态），`base` 缺省隐式 `@p`（不冻结）；`base` 的方向源由 `facing_source` 单值选择：`auto`（缺省）/ `world` / `entity` / `line`；`auto` 有 `facing_target` → line、有 `facing_entity` → entity、都没有按原点回落（实体选择器 / 隐式 `@p` / `player` → entity；坐标 / 方块 / 结构 → world fwd=+Z、right=-X、up=+Y）；`auto` 下两个载荷同时非空 = 错误；显式 `world`/`entity`/`line` 只读各自载荷，未选中载荷警告。`facing_entity` 只取 body yaw 且无冻结开关；base 轴没有独立角度偏移字段。切换点必须和位置关键帧同一时间，否则 validator 报错。
5. **`look_at=none` 保持现行语义。** 仍用 100 格远伪目标供两端目标点插值复用；是否改成纯手动朝向另开设计，I 轮不偷偷改。
6. **矩形/颜色/遮罩/渐变拆叶子；遮罩/渐变保留现有标量名。** `dest`/`source` → `.x/.y/.w/.h`；`color` → `.r/.g/.b`；`mask_x`/`gradient_angle` 等已是标量，不改名。
7. **插值只有 linear / angle / geometric / step。** 数值默认 linear；yaw/roll/lock 角度；`zoom` 几何；枚举/字符串/布尔/引用/可空数值 step。不引入缓动；编辑器烘焙。
8. **循环 = 片段级执行次数，不是时间折叠/重新计时。** 声明 `loop` 的片段按 `loop_count` 执行 N 次；每次执行有自己的 `executionLocalTime`。只有循环片段重复，其他片段仍按自己的 `start_time`/`duration` 执行一次。`windowEnd = start + duration × N` 是展开结果，不是驱动时钟。`pingpong` 是每次执行内的遍历模式，只允许视觉轨道；AUDIO/EVENT/MOD_EVENT 拒绝。
9. **执行边界状态按现有循环设计。** 只重置 `PlayerMoveController`；motion/breath/selector 继续；音频实例跨边界仍活跃则不重启；服务端 EVENT 不重复；overlay/letterbox 每次执行按局部时间求值，不额外重置。详见 `loop-execution.md` §3.5。
10. **AUDIO `loop` 改名 `file_loop`。** 片段执行循环用公共 `loop`/`loop_count`；文件循环与执行循环分开。
11. **EVENT/MOD_EVENT 是离散记录字段。** 一个 `event`/`mod_event` 字段的 keyframes 值是事件记录（`command`/`position`/`event_type`/`data`），不拆成可插值叶子；边沿触发；事件身份 = 实例 + clip + 字段路径 + 事件索引。
12. **删除 `meta.version` 与 `timeline.total_duration`。** `contentEnd = max(clip.getWindowEnd())`；`infinite = any(clip.isEffectivelyInfinite())`；旧字段出现即“已移除”错误；不做兼容。
13. **字段有三种形态：value-only / animated / event。** `objects` 是字段容器，不是“只能放关键帧对象”：`curve`/`orient` 是 value-only，`position.x`/`yaw_offset` 是 animated，`event` 是 event。字段可声明 `requires` 前置条件（如 offset 要求 `orient=tangent`），可递归但禁止环。clip 外层只留播放控制/结构字段（`start_time`/`duration`/`loop`/`rules`）。G1/G2 由字段路径/形态吸收。
14. **迁移是一次性 Java CLI，保真优先。** 旧全局关键帧 + 密集网格对照；稀疏字段插修正帧；旧 3D `curve` 原样映射为 `axes:["x","y","z"]`，控制点不拆；`orient`/offset 原样保留；旧 `facing_origin`/`facing_origin_time` 归一为 `position_origin`/`position_origin_freeze`（旧 `snapshot` → `true`，`live`/缺省 → `false`）；旧 `selector_*_follow`/`selector_*_facing_origin` 归一为 `selector_*_position_origin`；旧 `follow=entity` 归一到 `position_origin` + `position_origin_freeze=false`，`follow=none` 不生成原点；旧 `base` 段无 `facing_target` 时写 `facing_source=entity` + `facing_entity`（原点已是该实体/玩家时可省）保留旧方向源；`absolute+follow` 归一为 `relative+position_origin+freeze=false`，`base+follow` 的旧原点差异报告人工复核；旧 follow 段的 curve 按旧线性行为不写/标记；备份/回滚/报告；新格式幂等跳过。
15. **`meta` 关键帧化（G3）本轮不做。** 对象模型预留 script 级扩展点；不在 clip 对象模型里混。
16. **视频播放/传输是运行时底层能力。** 不改变对象模型、schema 或脚本字段；不单列一轮，单列 `media-video.md`，随 I 轮实现推进。
17. **方向源与位置原点解耦。** `base` 轴方向由 `facing_source` 单值选择：`auto`（缺省）/ `world` / `entity` / `line`；`auto` 有 `facing_target` → line、有 `facing_entity` → entity、都没有按原点回落（实体选择器 / 隐式 `@p` / `player` → entity；坐标 / 方块 / 结构 → world）。显式 `world`/`entity`/`line` 只读各自载荷，未选中载荷警告；`auto` 下两个载荷同时非空 = 错误。`facing_entity` 只取 body yaw 且无冻结开关；base 轴没有独立角度偏移字段；`fwd`/`up`/`right` 是位置偏移，`yaw`/`pitch` 是相机朝向相对 `yaw_base`/`pitch_base` 的偏移。冻结按对象各自 `_freeze`，固定解析结果（点，含偏移展开），不是停时间；`facing_entity` 不冻结，固定方向用 line + `facing_target_freeze=true`。

## 三、吸收 / 取代 / 更新

| 现有计划 | 关系 |
|---|---|
| `G-script-model/script-model.md` | G1（参数寻址到分量）/G2（keyframable）被本轮吸收；§4.1/§4.3/§5 的“复合值只能整体插值”作废；G3 延后，G4 只引用 |
| `G-script-model/clock-abstraction.md` | 只引用；关键帧时间仍 float 秒，不改精度 |
| `done/script-loop.md` | 现行循环设计（按次数执行/宏观循环/状态策略）是本文基准；`loop-execution.md` 只补对象模型落点，不另立一套 |
| `F-playback/camera-composition.md` | 字段语义/默认值/叠放顺序保留；`dest`/`source` 的“复合整体插值”理由更新为叶子；lane 采样改为一个 `SampledClip` |
| `F-playback/multi-camera-rendering.md` | 只引用；FBO/lane 上屏不变 |
| `F-playback/iris-oculus-compat.md` | 只引用；每视角隔离不变 |
| `F-playback/cross-dimension-camera.md` | 只引用；`dimension` 仍是 value-only 字段 |
| `F-playback/parallel-playback.md` | 只引用；实例/lane/队列不变；clip 窗口口径更新 |
| `D-transition/overlay-color-mask.md` | 颜色/遮罩/渐变语义保留；`ColorMaskSpec` 的 keyframe color cache 被 `color.r/g/b` 对象取代 |
| `D-transition/variable-frame.md` | 字段语义保留；关键帧字段改对象；`z_index` 等 value-only 字段定义不变 |
| `complete/0.3.6/A-color/screen-color-adjust.md` | 调色数学/顺序不变；59 通道对象化；曲线/LUT 仍是 value-only 字段 |
| `complete/0.3.6/B-camera/*` | 相机数学/cover chain/transition/selector/motion 语义不变；本轮的 `track-camera.md` 只定义对象怎么喂给它们 |
| `C-trigger/*` | 点源/选择器语义不变；新格式用 `position_origin_freeze` 等 `_freeze` 布尔替代 `_time` 枚举（`snapshot` → `true`，`live`/缺省 → `false`）；选择器字段对象化，冻结键按对象路径 |
| `E-template/templates.md` | 模板不再写 total_duration/version；字段生成；curve/orient/offset 原样保留 |
| `F-playback/f5-script-packet-limit.md` | 加对象格式体积说明；4 MiB 解压/1 MiB 压缩仍适用 |
| `docs/SCRIPT_FORMAT.md` / `AI_SCRIPTING_GUIDE.md` / `docs/modules/*` | 需要按新格式重写；本轮只写计划，未改文档 |

## 四、待定 / 开放项

- **AUDIO `attenuation=inverse`**：现行实际按 linear 处理；实现时要么补真正反距离衰减，要么从枚举删除并迁移为 linear。见 `track-audio-adjust.md` §4.4。
- **2D `curve` + 第三轴线性**：轴集合选 2 轴时只在该平面内做 2D 弧长；第三轴仍按自己的 position 关键帧线性走，不参与弧长，所以 3D 速度不一定匀。要整条 3D 路径匀速就选 3 轴。见 `track-camera.md` §3.2。
- **`curve` 开启后三轴成组**：有 `curve` 的 clip 失去三轴独立打点；常量轴也要在组时间写关键帧/值。这是为了让 A→B 段定义唯一，不开 `curve` 时三轴仍独立。见 `track-camera.md` §3.2。
- **`orient=tangent` 的切线抖动**：轴集合段边界/控制点差异大时，导数组合的切线可能抖动；需要编辑器/验收覆盖。见 `track-camera.md` §3.3。
- **动态相机迁移**：position_origin/选择器/动态 look_at/base 方向源无法完全预计算；旧 base 段补 `facing_entity` 保留旧方向，转换器保留语义 + 修正帧，报告人工复核。见 `migration.md` §3.5/§3.10。
- **负 `duration` 哨兵**：保留为无限单次片段，但与 `[0,duration]` 轴冲突；后续可单独清理为只用 `loop_count=-1`。见 `loop-execution.md` §3.3。
- **EVENT 不随片段循环重复**：服务端 EVENT 只触发一次；作者需要重复命令时用规则动作/显式分段。见 `track-event.md` §3.2 / `loop-execution.md` §3.5。
- **执行边界状态**：I 轮只重置玩家移动；motion/breath/selector/audio 按现有设计继续。若要做“每次执行全状态一致”，另开设计。见 `loop-execution.md` §3.5。
- **主索引/进度表**：`plans/0.3.6/README.md` 还没有 I 行；`implementation-progress.md` 还没有 I 轮记录。等本轮文档评审后再登记，避免先写状态后改。

## 五、非目标（本轮）

- 编辑器 UI/对象分组/曲线编辑的具体实现（`editor-object-model.md` 只定数据/交互契约）。
- `meta` 静态字段关键帧化（G3）。
- 时间精度 float → double / 有理数。
- 旧格式兼容层、双语法、解析期分桶。
- 多版本代码适配工程（脚本格式稳定 + 命名空间 ID 是边界，不是本轮实现）。

## 六、建议实现顺序（不是任务书）

1. schema/对象模型 + parser/validator + 采样器（核心）。
2. 各轨道装配（先 CAMERA，再 OVERLAY/LETTERBOX/AUDIO/ADJUST/EVENT）。
3. 循环执行 + 格式清理（version/total_duration）。
4. 迁移器 + 旧脚本转换 + 保真验收。
5. 编辑器字段模型/缓动烘焙。
6. 运行时视频播放/传输（`media-video.md`）。
7. 真实机器/性能/文档收尾。

## 七、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
