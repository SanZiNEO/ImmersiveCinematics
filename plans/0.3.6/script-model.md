# 0.3.6 脚本模型：meta、轨道与关键帧（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [并行播放](./parallel-playback.md)
> - [可变画面](./variable-frame.md)
> - [脚本循环](./script-loop.md)
> - [相机状态与覆盖链](./camera-state-plan.md)

---

## 1. 定位（已确认）

一份脚本 = **meta（脚本自己的属性）+ 轨道（时间轴上的内容）**。

- **meta**：这份脚本自己的属性——听者模式、HUD 隐藏、循环、可跳过、可打断、优先级、末尾行为……（现状：除“循环”外这些已在 `ScriptMeta` / `schema/MetaSchemas.java` 里；“循环”当前是 clip 级字段而不是 meta，见 §2。）
- **轨道**：时间轴上的内容——相机 / 音频 / 事件 / 覆盖层 / 黑边 / 模组事件……

**判定规则（已确认）**：脚本里的某个东西，**只要它“可以被调整、允许被调整、或者希望被调整”，它就应该进关键帧，通过关键帧来调整**——而不是做成 meta 里的一段静态值。

这条规则是本文的核心：它把“哪些东西该做成关键帧”从口味问题变成了一个可判定的标准。

### 1.1 时间轴：片段允许时间重叠（已确认·2026-10-07）

- 一份脚本的时间轴上，**片段允许在时间上重叠**（lane 模型后的自然结果——以前不允许是因为只有一个相机，同一时刻只能有一个相机状态；现在每个活跃 clip 渲染自己的 lane）。
- **重叠区的解析规则**：后面的 clip 覆盖在前面 clip 的上层；整体层级 = 先按**轨道层级**分层，再按**轨道内 clip 顺序**分层（详见[画面合成](./camera-composition.md) §1）。
- **用途**：叠化（上一个 clip 的**末尾帧复制延长** + 下一个 clip 的**首帧复制延长**——hold 等值关键帧，交叉窗口内 opacity 关键帧；延长区由工具自动补充，作者不写内容）、无空隙全屏覆盖（不露原版画面）等——见[画面转场](./scene-transition.md) §3.2。
- **hold 延伸的模型语义（已确认·2026-10-07）**：clip 的窗口可以被"复制延长"——延长区（窗口内、原关键帧范围外）取**末关键帧值**（末尾延长）/ **首关键帧值**（开头延长），画面冻结。实现 = **插等值关键帧 + 窗口调整**，**不新增字段**、不新增机制；它是"关键帧数据"的一种生成规则，模板 / 叠化工具按此生成（见[模板](./templates.md)）。
- **对模型的影响**：重叠窗口内的活跃片段不止一个 → “活跃片段”查询返回**集合**（见[脚本循环](./script-loop.md) §4 的 `findActiveClip` 改动）；宏观末端 = 各片段展开结束时刻的最大值，不受重叠影响。

---

## 2. 现状（方向）

- 关键帧**已经是通用容器**（`Keyframe { time, trackType, data: Map }`，`script/Keyframe.java`）——加字段不用改类，底子是好的。
- 但离“所有东西都能关键帧”还差：
  - **插值是片段级的**，不是关键帧级：`interpolation` 是 clip 字段（`schema/TrackSchemas.java` 里 CAMERA 与 OVERLAY 的 `clips.put("interpolation", …)`，取值 `linear|smooth`），`Keyframe` 没有插值字段。**相机段根本不读这个字段**（`script/CameraTrackPlayer.java` 全文没有 `"interpolation"`，它用 clip 级 `curve` + `KeyframeInterpolator.computeInterpolation()`），真正消费它的是 `OverlayTrackPlayer`（`"smooth".equals(clip.getString("interpolation", "linear"))`）。
    > 原文此处引 `camera-state-plan.md` 的“已知缺陷”——该节并没有这一条，见文末核查。
  - **复合值整体插值**（位置是一个整体）：`Keyframe.getPosition()` 返回单个 `PositionData`（x/y/z 三分量在同一对象内），`KeyframeInterpolator.interpolatePosition()` 对整条 `Vec3` 插值——做不到按分量打关键帧，做不到“只让 X 动、Y 不动”。
  - **meta 里的东西完全不可关键帧**：听者 `meta.listener`、HUD `hide_hud`、可跳过 `skippable`、可打断 `interruptible`、优先级 `priority`、末尾行为 `hold_at_end` 等都是 `ScriptMeta.RuntimeBehavior` 里整段一个值，没有任何关键帧通道。
  - **“循环”当前不在 meta 里**：`loop` / `loop_count` / `loop_mode` 是 **clip 级**字段（`schema/TrackSchemas.java`），由 `Clip.isLoop() / getLoopCount() / getLoopMode()` 读取（`script/Clip.java`）；meta schema 里没有循环开关。（§1 把“循环”列进 meta 是方向，不是现状。）
  - 时间是 float 秒：`Keyframe.time` 是 `float`；`ScriptParser` 用 `requireFloat` 读 `time` / `start_time` / `duration` / `total_duration`，并强制同一 clip 内关键帧时间**严格单调递增**（`keyframes.get(i).getTime() <= keyframes.get(i-1).getTime()` → 解析错误）。
- [并行播放](./parallel-playback.md)要求**每个脚本实例持有自己的实例状态**，所以模型必须能支撑“**一份脚本定义 × N 份运行时状态**”。

---

## 3. 参考：剪辑软件的数据模型（方向）

参考 `example/editor/olive`（GPL 开源非线性剪辑软件）的做法：

- **参数声明**：每个参数在节点里存成一条记录 `Node::Input { type, flags, default_value, properties, human_name, array_size }`（`app/node/node.h:1147-1154` 私有结构体；由 `AddInput(id, NodeValue::Type, default, InputFlags)` → `InsertInput()` 建立，`app/node/node.cpp:1672`）。注意 **`NodeInput`（`app/node/param.h`）不是这条声明**，而是“指向某个参数的引用”三元组 `{ node, input, element }`。**“能不能打关键帧”是参数自己的一个 flag**（`kInputFlagNotKeyframable`，`app/node/param.h` 的 `enum InputFlag`），不是特例机制；还有 `kInputFlagNotConnectable`、`kInputFlagArray`、`kInputFlagHidden`、`kInputFlagIgnoreInvalidations` 等（默认 `kInputFlagNormal` = 可关键帧 + 可连接 + 非数组）。
- **关键帧**：`NodeKeyframe { time, value, type, bezier_control_in/out, input, track, element }`（`app/node/keyframe.h`）——**插值方式与贝塞尔手柄挂在每个关键帧上**（`enum Type { kLinear, kHold, kBezier }`，`static const Type kDefaultType = kLinear`；手柄为 `QPointF bezier_control_in_/bezier_control_out_`，配 `enum BezierType { kInHandle, kOutHandle }`），不是整段一个。
- **向量按分量分轨**：`kVec2` 的 track 0 = X、track 1 = Y（`NodeKeyframe::track()` 注释即此；`NodeValue::split_normal_value_into_track_values()` 与 `get_number_of_keyframe_tracks(kVec2) = 2`，`app/node/value.cpp`），**X 和 Y 各有独立的关键帧曲线**（`NodeInputImmediate::keyframe_tracks_`，每个 track 一条 `NodeKeyframeTrack = QVector<NodeKeyframe*>`）。
- **同一时刻允许多个关键帧**（不同 track / element）：`NodeInputImmediate::get_keyframe_at_time()` 遍历所有 track 收集（`app/node/inputimmediate.cpp`），`Node::GetKeyframesAtTime()` 返回 `QVector<NodeKeyframe*>`；同一条 track 上同一时刻只允许一个（`get_keyframe_at_time_on_track()` 只返回首个匹配），`NodeKeyframe::has_sibling_at_time()` 即“同 input/track/element 是否已有同刻帧”。
- 类型系统通用（`NodeValue::Type`：Float / Vec2 / Vec3 / Vec4 / Color / Combo / Rational / Text / Boolean / Texture / Samples / Bezier / …），参数用 properties 声明 min / max / 显示方式（`SetInputProperty(id, "min"|"max"|"view", …)`，如 `app/node/audio/volume/volume.cpp` 声明 `min`、`app/node/block/clip/clip.cpp` 用 `FloatSlider::kPercentage` 做“按百分比显示”）。
- 时间用**有理数**，不是浮点秒：`NodeKeyframe` 的时间是 `rational`（构造器 `NodeKeyframe(const rational& time, const QVariant& value, Type type, int track, int element, const QString& input, …)`），类型系统里也有 `NodeValue::kRational`。

---

## 4. 升级方向

1. **缓动 = 编辑器烘焙，运行时保持线性（已确认·2026-10-07，方案 E）**：编辑时用剪辑软件标准的**贝塞尔手柄**（第一版先做缓动预设：linear / ease_in / ease_out / ease_in_out，手柄曲线编辑器后置）；**落盘时把曲线采样烘焙成显式关键帧**（每段变速 ≈8–20 个采样关键帧），脚本 = 纯线性 + 显式关键帧；**运行时零新增**（只做线性插值），实际运动与脚本完全一致（所见即所得，无隐式数学）。**时长守恒**：烘焙不改变区间总时长，只把速度分布"显示化"为更多关键帧。clip 级 `interpolation` 字段删除（旧 smooth=Catmull-Rom 退役，不留双轨）。编辑器侧曲线 UI + 烘焙属编辑器线任务。
   > **落地记录·2026-10-07（编辑器侧，第一版预设曲线）**：`editor/src/operations.ts` 新增「缓动烘焙」段——`EASING_PRESETS`（linear / ease_in / ease_out / ease_in_out，公式沿用旧 `InterpolationType`：`t` / `t²` / `1−(1−t)²` / 两段二次）、`easingProgress(easing, u)`、`bakeSampleCount(duration) = clamp(round(duration×4)+2, 8, 20)`（含首尾的帧数）、`easingSupport(trackType, clip)`、`bakeClipEasing` / `bakeDocEasing`；UI 在关键帧面板（`editor/src/components/KeyframePanel.vue`，作用于「本帧 → 下一帧」区段）。**标记 `easing` 是编辑器私有字段**（挂在区段起点关键帧上），保存（`store.saveScript`）/ 预览推送（`store.pushScript`）/ 校验前在深拷贝上烘焙后删除，脚本 JSON 里只有显式关键帧。采样：时间等距（首尾时间与值不变），值按缓动进度逐通道采样，口径与运行时逐通道取值一致（yaw/roll 最短路径环绕、pitch/fov/位置分量/其余标量线性、zoom 对数、`source`/`dest` 矩形逐分量），区段起点的离散字段（`position_mode` / `follow` / `look_at` / `yaw_base` / `fit` …）原样复制进补帧（运行时这些字段只读区段起点）。**不参与烘焙**：LETTERBOX（运行时自带 smoothstep）、EVENT / MOD_EVENT（离散事件，补帧会重复触发）、带贝塞尔 `curve` 的 CAMERA 片段（路径由运行时按弧长求值）。
   > **结案标注（2026-10-08 用户裁决「任务2改判」，见 `implementation-progress.md` 用户裁决新增表）**：本节按**方案 E 结案**——缓动 = 编辑器烘焙（`editor/src/operations.ts`），贝塞尔**手柄归编辑器阶段**（运行时只读只执行、不含手柄逻辑；产物数据形态随编辑器阶段定稿）。
2. **参数寻址到分量**：关键帧的归属从“轨道类型”细化为“**哪个实例的哪条轨道、哪个参数、哪个分量**”——这是“所有东西进关键帧”的前提（否则没法给“第 2 条 lane 的目标矩形 x”打关键帧）。
3. **参数声明加 keyframable**：能不能打关键帧变成声明，编辑器据此决定是否显示关键帧按钮。
4. **meta 纳入关键帧**：`listener` / `hide_hud` / 循环 / 可跳过……凡满足 §1 判定规则的一律进关键帧；做不到的要单独说明为什么。
5. **时间精度**：是否改为有理数 / 高精度（长脚本 + 密集关键帧）。
6. **合成参数（opacity 等）挂在 clip 上（方向·倾向）**：叠化需要 opacity 关键帧直接落在 CAMERA clip 上（A 末尾 1→0、B 开头 0→1）——这支持[画面合成](./camera-composition.md) §2 的候选 A（自声明）；候选 B（合成导演）与本条不冲突，执行时随 camera-composition 一起定。
7. **hold 延伸与叠化生成规则（已确认·2026-10-07）**：见 §1.1——复制延长 = 插等值关键帧 + 窗口调整，不新增字段；叠化工具 / 模板按此规则生成两组 hold 关键帧 + 两组 opacity 关键帧。

---

## 5. 实例与状态（方向）

- **一份脚本定义 × N 份运行时状态**：定义（meta + 轨道 + 关键帧）只读；实例状态（时钟、当前值、播放头、lane 绑定）各自独立。（现状：`CinematicScript` 确为只读定义——字段 `final`，类注释即“脚本只包含编辑时确定的静态数据，不包含运行时动态状态。运行时状态由 `ScriptPlayer` 管理”；但 `ScriptPlayer` 目前是单实例（`CameraManager.getScriptPlayer()`），N 份实例状态尚未实现。）
- 实例之间**不共享可变状态**（与[并行播放](./parallel-playback.md)的实例模型对齐）。
- 预览（编辑器）与正式播放的关系——预览是“特殊实例”还是“独立通道”，见并行播放的落地步骤 5。
- 规范要点：**实例身份**（谁是谁）、**状态归属**（哪些是定义、哪些是实例）、**跨实例引用**（能不能引用别的实例的 lane，见[可变画面](./variable-frame.md)的摆放参数）。

---

## 6. 可能的问题

- 参数寻址的粒度：到“轨道 / 参数 / 分量”之后，编辑器怎么呈现（每个分量一条曲线还是合并显示）。
- 关键帧级插值之后，片段级插值字段的去留与迁移。
- meta 关键帧化的边界：哪些 meta 天然不适合关键帧（例如“优先级”这种影响调度时机的）。
- 时间精度的迁移代价（现有脚本的 time 都是 float 秒）。
- 参数声明与现有 `FieldDef`（`script/schema/FieldDef.java` 的 record：`type / defaultValue / required / enumValues / section`）的合并方式。
- 关键帧数据的规模：全部东西都进关键帧之后，脚本体积与解析开销。

---

## 7. 待定

- 关键帧的字段形态（含贝塞尔手柄的表示）。
- 参数寻址的具体写法。
- keyframable 的声明位置（schema 还是运行时）。
- meta 关键帧化的清单与例外。
- 时间精度方案。
- 实例身份与跨实例引用的规则。

---

## 8. 落地顺序（方向）

1. 定下“可调即关键帧”的判定规则与例外清单
2. 关键帧结构升级（自带插值 / 手柄）——**按方案 E 结案**：缓动 = 编辑器烘焙（`editor/src/operations.ts`），手柄归编辑器阶段（2026-10-08 用户裁决「任务2改判」，见 `implementation-progress.md` 用户裁决新增表）
3. 时间重叠 + hold 延伸落地：活跃片段返回集合、复制延长（插等值关键帧 + 窗口调整）——与[脚本循环](./script-loop.md) §4、[画面转场](./scene-transition.md) §3.2 一起
4. 合成参数落点定稿（opacity 等挂在 clip 上 or 独立轨道，随[画面合成](./camera-composition.md)候选 A/B）
5. 参数寻址到分量
6. 参数声明加 keyframable
7. meta 关键帧化
8. 实例身份与状态归属的规范落地（与并行播放一起）
9. 编辑器跟进（曲线编辑 / 参数面板）

---

## 9. 相关文档

- [并行播放](./parallel-playback.md)
- [可变画面](./variable-frame.md)
- [脚本循环](./script-loop.md)
- [相机状态与覆盖链](./camera-state-plan.md)
- [时间插值](./temporal-interpolation.md)

---

## 事实核查（2026-10-07）

> 依据：参考实现 `example/editor/olive/app/`，本仓 `common/src/main/java/com/immersivecinematics/immersive_cinematics/`。每条附文件/符号；读不到的标“未验证”。

### ① 核实为真（olive 侧，§3）

| 断言 | 证据 |
|---|---|
| 参考 `example/editor/olive`，GPL 开源非线性剪辑软件 | `app/node/keyframe.h`、`app/node/node.h` 文件头均为 GNU GPL v3 声明 |
| “能不能打关键帧”是参数自己的 flag，不是特例机制 | `app/node/param.h` `enum InputFlag { kInputFlagNormal = 0x0, kInputFlagArray = 0x1, kInputFlagNotKeyframable = 0x2, kInputFlagNotConnectable = 0x4, kInputFlagHidden = 0x8, kInputFlagIgnoreInvalidations = 0x10, kInputFlagStatic = … }`，注释“By default, inputs are keyframable, connectable, and NOT arrays”；`Node::SetInputFlag()`（`app/node/node.cpp:908`）、`Node::IsInputKeyframable()`（`app/node/node.h:402`） |
| 还有 NotConnectable / Array 等 flag | 同上；用例：`app/node/block/clip/clip.cpp`（`kSpeedInput` = NotConnectable\|NotKeyframable）、`app/node/audio/pan/pan.cpp`（`kSamplesInput` = NotKeyframable） |
| 插值方式与贝塞尔手柄挂在每个关键帧上（Linear/Hold/Bezier） | `app/node/keyframe.h`：`enum Type { kInvalid = -1, kLinear, kHold, kBezier }`、`static const Type kDefaultType`（`app/node/keyframe.cpp:27` = `kLinear`）、`bezier_control_in()/out()`、`enum BezierType { kInHandle, kOutHandle }`、`valid_bezier_control_in/out()` |
| 关键帧还带 input / track / element | `app/node/keyframe.h`：`QString input_; int track_; int element_;`；构造器 `NodeKeyframe(const rational& time, const QVariant& value, Type type, int track, int element, const QString& input, QObject*)` |
| kVec2 的 track 0 = X、track 1 = Y，X/Y 各有独立曲线 | `keyframe.h` 的 `track()` 注释原文（“…such as kVec2, this will be 0 for X keyframes and 1 for Y keyframes”）；`app/node/value.cpp`：`split_normal_value_into_track_values()` kVec2 → `vals.replace(0, vec.x()); vals.replace(1, vec.y());`，`get_number_of_keyframe_tracks(kVec2) = 2`；`app/node/inputimmediate.h` `QVector<NodeKeyframeTrack> keyframe_tracks_`（`NodeKeyframeTrack = QVector<NodeKeyframe*>`） |
| 同一时刻允许多个关键帧（不同 track/element） | `app/node/inputimmediate.cpp:47` `get_keyframe_at_time()` 遍历全部 track 收集、返回 `QVector<NodeKeyframe*>`；`app/node/node.cpp:601` `Node::GetKeyframesAtTime()`；同 track 同刻只一个（`get_keyframe_at_time_on_track()` 返回首个匹配）；`NodeKeyframe::has_sibling_at_time()`（`app/node/keyframe.cpp:207`） |
| 类型系统通用（Float / Vec2 / Color / Combo / …） | `app/node/value.h` `enum Type`（kFloat/kInt/kVec2/kVec3/kVec4/kColor/kCombo/kRational/kText/kBoolean/kTexture/kSamples/kBezier…）、`type_is_vector()` / `type_is_numeric()` |
| 参数用 properties 声明 min / 显示方式（如按百分比） | `Node::SetInputProperty()`（`app/node/node.cpp:399`）/`NodeInput::GetProperty()`；`SetInputProperty(kRadiusInput, "min", 0)`（`app/node/filter/blur/blur.cpp`）、`"view" = FloatSlider::kPercentage`（`app/node/block/clip/clip.cpp`）、`"view" = RationalSlider::kTime`（`app/node/block/block.cpp`） |
| 时间用有理数，不是浮点秒 | `NodeKeyframe` 的 `rational time_`（`keyframe.h`）；`NodeValue::kRational`（`app/node/value.h:74`）；`app/node/block/block.cpp` 用 `NodeValue::kRational` + `RationalSlider::kTime` |

### ② 核实为真（本仓，§2）

- **`Keyframe` 是通用容器**：`script/Keyframe.java` 字段恰为 `private final float time; private final TrackType trackType; private final Map<String,Object> data;`，另有 `getString/getFloat/getInt/getBool/getObject` 泛型取值 → “加字段不用改类”成立（新字段进 `data` map）。
- **插值是片段级**：`schema/TrackSchemas.java` 把 `interpolation` 声明在 **clips** 上（CAMERA 第 32 行、OVERLAY 第 149 行，`enum linear|smooth`）；`Keyframe.java` 无插值字段；`script/InterpolationType.java` 只有 `LINEAR` 且**全仓无引用**（注释自述“仅用于 JSON 校验白名单”）；`ScriptValidator` 对 clip 做 `interpolation ∈ {linear, smooth}` 校验。
- **相机段不消费 `interpolation`**：`script/CameraTrackPlayer.java` 全文无 `"interpolation"`；它走 `KeyframeInterpolator.computeInterpolation()`（`KeyframeInterpolator.java:40`，只返回 from/to + `adjustedT`，无插值类型分支）+ clip 级 `curve`（`KeyframeInterpolator.java:123/144`、`CameraTrackPlayer.java:754/799`）。真正消费它的是 `OverlayTrackPlayer`（`:84`、`:180`）；黑边 `LetterboxTrackPlayer` 同样不消费。
- **复合值整体插值**：`Keyframe.getPosition()` 返回单个 `PositionData`（内部 `float x/y/z` + 基准字段）；`KeyframeInterpolator.interpolatePosition()` 取 `from.getPosition().toVec3()` 与 `to.getPosition().toVec3()` 后整条插值；相机关键帧 schema 里 `position` 是**一个** `FieldDef("position", …)` → 位置确实不能按分量打关键帧。
- **meta 不可关键帧**：`script/ScriptMeta.java` 只有 `id/name/author/version/description/behavior/priority/dimension/triggers/skipVoteRatio` + `record RuntimeBehavior(…)`，无关键帧通道；meta 字段表见 `schema/MetaSchemas.java`（含 `listener`、`hide_hud`、`skippable`、`interruptible`、`priority`、`hold_at_end`）。`listener` 属运行期字段，只在 `rawJson` 里读（`AudioListenerController.listenerMode()`；`ScriptParser` 注释“meta.listener 等运行期扩展字段只在 rawJson 里读取”）。
- **时间是 float 秒**：`Keyframe.time` 为 `float`；`ScriptParser` 用 `requireFloat` 读 `time`（`parseKeyframe`）、`start_time`/`duration`（`parseClip`）、`total_duration`（`parseTimeline`）。

### ③ 已修正

1. **§3 参数声明结构体名错**：旧“每个参数是一条 `NodeInput { type, flags, default, properties, human_name }`” → 新：声明记录是 **`Node::Input { type, flags, default_value, properties, human_name, array_size }`**（`app/node/node.h:1147-1154`，私有结构体），`NodeInput`（`app/node/param.h`）是**引用**三元组 `{node, input, element}`；字段名是 `default_value` 而非 `default`。证据：`Node::InsertInput()`（`app/node/node.cpp:1672`）写入 `i.type / i.default_value = NodeValue::split_normal_value_into_track_values(type, default_value) / i.flags / i.array_size`。另：olive 仓库中不存在 `app/node/input.h` 与 `params.cpp`；相关实现分布在 `param.h` / `param.cpp` / `node.cpp`。
2. **§3 flag 名**：旧“还有 `NotConnectable`、`Array` 等 flag” → 新：确切枚举名 `kInputFlagNotConnectable`(0x4)、`kInputFlagArray`(0x1)，另有 `kInputFlagHidden`、`kInputFlagIgnoreInvalidations`、`kInputFlagStatic`。
3. **§2 “meta 里的东西…（听者 / HUD / 循环 / 可跳过…）”**：旧把“循环”算作 meta 整段值 → 新：听者/HUD/可跳过/可打断/优先级/末尾行为确在 meta，但**“循环”当前是 clip 级字段** `loop` / `loop_count` / `loop_mode`（`schema/TrackSchemas.java` 第 35–37 行相机、第 102 行音频；`Clip.isLoop()/getLoopCount()/getLoopMode()`），meta schema 无循环开关。证据：`MetaSchemas.java` 全表无 loop；`KeyframeInterpolator.java:47` 的循环处理读 `clip.isLoop()`。
4. **§2 交叉引用错**：旧“见 `camera-state-plan.md` 的‘已知缺陷’” → 新：该文件（182 行）的“已知缺陷（2026-10-06 代码复查）”一节**只有 1 条**（“退出过场首帧视角跳变”），不含“相机段不消费 interpolation”。改为直接引代码证据（`CameraTrackPlayer.java` 无该字段读取）。跨文档不一致记录在案，未改他人文档。

### 冲突裁决（`git log -1 --format=%cI -- <路径>`，仓库根执行）

| 冲突 | 文档侧时间 | 代码/文档侧时间 | 裁决 |
|---|---|---|---|
| §2 “循环在 meta” vs 代码（clip 级） | `plans/0.3.6/script-model.md` = 2026-10-06T21:31:25+08:00 | `script/schema/TrackSchemas.java` = 2026-09-17T13:38:01+08:00；`script/Clip.java` = 2026-08-10T19:30:55+08:00 | 文档较新，但该句是**现状描述**、实现对现状有唯一解释权，且 §1 把“循环”列为 meta 是方向稿目标态 → 保留 §1 方向表述，修正 §2 现状句 |
| §2 引用 `camera-state-plan.md`“已知缺陷” vs 该文档实际内容 | `script-model.md` = 2026-10-06T21:31:25+08:00 | `plans/0.3.6/camera-state-plan.md` = 2026-10-06T21:16:28+08:00 | 文档较新但被引内容不存在 → 改为引代码证据，冲突记录在案 |

### ④ 补全的信息

- **olive 侧符号/路径**：`Node::Input`（`node.h:1147`）、`AddInput`/`PrependInput`/`InsertInput`/`SetInputProperty`/`SetInputFlag`/`IsInputKeyframable`（`node.h`/`node.cpp`）、`InputFlag` 枚举全值（`param.h`）、`NodeKeyframe` 全成员 + `kDefaultType`（`keyframe.h`/`keyframe.cpp`）、`NodeInputImmediate::keyframe_tracks_` 与 `get_keyframe_at_time*`（`inputimmediate.h/.cpp`）、`NodeValue::split_normal_value_into_track_values` / `combine_track_values_into_normal_value` / `get_number_of_keyframe_tracks`（`value.cpp`）、`NodeKeyframeTrackReference`（`param.h:318`）。
- **本仓符号/路径**：`script/Keyframe.java`、`script/Clip.java`（`isLoop/getLoopCount/getLoopMode/getCurve`）、`script/PositionData.java`、`script/KeyframeInterpolator.java`（`computeInterpolation` / `interpolatePosition` / `InterpolationResult`）、`script/CameraTrackPlayer.java`、`script/OverlayTrackPlayer.java`、`script/ScriptMeta.java`、`script/schema/TrackSchemas.java`、`script/schema/MetaSchemas.java`、`script/schema/FieldDef.java`（record 字段名 `defaultValue` / `enumValues`）、`script/ScriptParser.java`。
- **新增事实（对 §4 有约束）**：本仓同一 clip 内关键帧时间**必须严格单调递增**（`ScriptParser`：`keyframes.get(i).getTime() <= keyframes.get(i-1).getTime()` → 抛 `ScriptParseException`）；而 olive 允许同一时刻多帧（不同 track/element）。按分量分轨落地时必须一并处理这条约束。
- **§6 `FieldDef`**：实际 record 为 `FieldDef(String type, Object defaultValue, boolean required, List<String> enumValues, String section)`，`section` 默认 `"info"`；javadoc 现为「`section` 为 WebUI 自动分组预留；Java 侧只做元数据声明，不消费该字段」（0.3.6 前写的是「游戏内编辑器使用 `FieldGroup`，不直接消费 section」，该编辑器与 `FieldGroup` 已随退役删除）。

### ⑤ 未验证

- **§5“一份脚本定义 × N 份运行时状态”**：`CinematicScript` 只读定义已核实（字段 `final` + 类注释“脚本只包含编辑时确定的静态数据，不包含运行时动态状态。运行时状态由 `ScriptPlayer` 管理”）；但“N 份实例状态”当前不存在——`CameraManager` 只有 `private final ScriptPlayer scriptPlayer = new ScriptPlayer();`（单实例，`getScriptPlayer()` 返回它）。N 实例为方向，未实现（未验证）。
- **§3“参数声明 = `NodeInput`”**：olive 中不存在任何名为 `NodeInput` 的**声明型**结构体（只有引用型 `NodeInput`）；最接近的 `NodeInputImmediate`（`app/node/inputimmediate.h`）是运行期值容器（`standard_value_` + `keyframe_tracks_`）。已按实际结构体名（`Node::Input`）修正。
- **§7 待定项**（关键帧字段形态、参数寻址写法、keyframable 声明位置、时间精度方案、实例身份与跨实例引用规则）：均为设计待定，无源码可核。
