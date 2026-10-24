# 画面转场：底层工具的调用层

> 状态：已实现（2026-10-17）
> 范围：画面之间的切换——硬切、叠化、黑场/白场、划像——用现有 CAMERA / OVERLAY 片段与关键帧组合表达；不含相机六参数状态过渡。
> 关联：[可变画面](variable-frame.md) · [Overlay 颜色遮罩](overlay-color-mask.md) · [画面合成](../F-playback/camera-composition.md) · [多相机渲染](../F-playback/multi-camera-rendering.md) · [过渡](../../complete/0.3.6/B-camera/transition.md)

## 一、摘要

画面转场是应用层的调用组合，不新造底层：叠化用两段相机画面在时间上重叠、上层不透明度从 0 升到 1；黑场/白场用 OVERLAY 轨上的纯色覆盖片段；划像用相机片段的遮罩形状与关键帧几何；硬切就是不做过渡。所有效果都落成轨道、片段与关键帧数据，运行时只读只执行，不新增转场轨道、转场字段或生成器。硬切、叠化、黑场/白场、矩形与圆形划像均已可用，编辑器转场面板尚未提供。

## 二、为什么做

### 2.1 问题

- 两段画面直接衔接时切换生硬，观众能看出跳变。
- 需要压场或亮起时，缺少能同时盖住画面与字幕的统一图层。
- 只想让画面的一部分逐步显露时，没有可动画的几何手段。
- 若每种转场都写成一次性代码，作者在时间轴上既看不见也改不了，无法与其他动画对齐。

### 2.2 目标

- 两段相机画面可以在时间上重叠，重叠区内按不透明度线性交叉淡化，任何时刻底部都有一幅不透明画面。
- 可以用颜色片段做出黑场与白场，压场与亮起的时长、曲线由关键帧决定。
- 可以按矩形或圆形区域逐步显露画面，区域中心与尺寸可动画。
- 上述效果都能在脚本里读到、在时间轴上编辑，不需要专门的转场语法。
- 画面切换与相机状态变化互不牵连：画面可以硬切而相机平滑过渡，反之亦然。

### 2.3 非目标

- 不新增转场轨道、片段级转场字段或运行时转场生成器。
- 不提供转场时长/曲线的专用字段；时长与曲线就是关键帧本身。
- 不做斜线、星形等自定义形状的划像，也不做羽化边缘。
- 不在运行时做转场配对、互补 alpha 归一或自动延长片段。
- 不提供编辑器里的转场面板与预览。

## 三、方案

转场由已有工具组合而成：相机画面的不透明度、lane 合成参数、相机片段的遮罩、OVERLAY 轨的纯色片段。所有透明度都用同一条不透明度关键帧通道表达，转场不另造 alpha 语义。

硬切：两段画面首尾相接，不做不透明度过渡。相机状态是否同时硬切由相机片段自己的过渡设置决定，两者独立。

叠化：把两段相机画面在时间上重叠。下面那段全程保持不透明，上面那段在重叠窗口内把不透明度从 0 连续升到 1。观众看到的是前者逐渐被后者覆盖；底层始终不透明，所以不会露出第三层原版基画面。重叠区由作者写下的显式关键帧给出：下面那段写保持不透明的关键帧，上面那段写 0 到 1 的关键帧；运行时不自动生成延长区，也不新增转场字段。上面那段从自己片段的开头就在渲染，重叠窗口内画面已经有效，不需要额外的提前渲染调度。

黑场/白场：在覆盖层轨道放一段纯色片段，颜色取黑或白，不透明度先由 0 升到 1 压住画面，切段后再由 1 降到 0 亮起。黑场与白场没有实现差异，同一个工具换颜色即可。它盖住哪些层由片段显式写下的层级值决定，不是自动最高层，也不改原版 HUD。

划像：相机片段声明整幅、矩形或圆形遮罩，再用关键帧移动遮罩中心、改变宽高或半径，画面就按该区域逐步显露；也可以让新旧画面的取材区域与目标矩形滑动，完成推拉式划像。两种做法都是纯数据，遮罩只作用于画面本身，不裁字幕或图片。

层级：同一时刻要显示多个覆盖层，必须分别放在不同轨道；同一条覆盖层轨道同一时刻只消费第一个活跃片段。层级只在模组自己的覆盖层之间排序。要把黑场压在某条字幕之上，就给纯色片段写更大的层级值。

## 四、技术细节

### 4.1 核心模型

- 画面 lane：CAMERA 片段产出，合成参数 `opacity` / `source` / `dest` 与遮罩 `mask_shape` / `mask_*` 都挂在片段上。
- 覆盖层：OVERLAY 片段（`layer_type=fade|image|subtitle`）与 LETTERBOX 片段；fade 由 `FadeLayer` 承担，`OverlayTrackPlayer` 把活跃片段变成层并逐帧送参数。
- 调度：`OverlayManager` 是层注册表，按 `z_index` 升序渲染；`CinematicOverlay` 是 HUD 渲染入口。
- 遮罩与合成：`LaneMask` 按片段本地时间采样 clip 级形状与关键帧几何；`LaneCompositor` 在一次绘制内完成取材、目标矩形、不透明度与遮罩。
- 相机状态过渡归[过渡](../../complete/0.3.6/B-camera/transition.md)：按通道窗口混合 A/B 两侧的六参数，不产生画面层。

### 4.2 契约

- lane 由相机轨逐帧注册，合成即时发生；后注册的 lane 盖在先注册的上面。叠化只要求两条 lane 同时活跃，没有配对或归一逻辑。
- OVERLAY 层由播放侧在片段切换时创建，旧层移除；实例停止时清理本实例的层。同一条轨只取首个活跃片段。
- 画幅层每条 LETTERBOX 轨一层，随轨道创建与停止。
- 层归属 token = 播放实例；渐出触发、动画判定、清理都按归属过滤，一个实例退场不影响其他实例。
- `z_index` 在层创建时读取，`OverlayManager` 按升序稳定排序；渲染只画可见层。

### 4.3 数据形态

- 叠化：两条相机画面时间重叠；A 的 `opacity` 保持 1，B 在重叠区间从 0 到 1。没有额外字段。
- 黑/白场：OVERLAY 片段 `layer_type=fade`、`color` 为 `#000000` 或 `#FFFFFF`、关键帧 `opacity` 走 0→1→0。`z_index` 是 clip 级显式字段：普通 fade 缺省 10，转场 fade 显式 1000，转场示例字幕 100，高对比层级演示的字幕 1000。
- 划像：CAMERA 片段 clip 级 `mask_shape=full|rect|circle`；关键帧 `mask_x` / `mask_y`（窗口比例，缺省 0.5，可越界）、`mask_width` / `mask_height`（窗口比例，缺省 1，非负）、`mask_radius`（窗口短边比例，缺省 0.5，非负）。零尺寸不绘制，边界为硬边，窗口物理像素下圆形为真圆。
- 也可用 CAMERA 关键帧的 `source` / `dest` 矩形滑动完成推拉式划像；两者都是归一化矩形，原点在左上角。
- 转场没有独立的时间线区间：时长与曲线就是关键帧数据本身。

### 4.4 边界

- 相机状态过渡（`transition` / `transition_duration` / `transition_params` / `transition_curve` / `transition_interrupt`）只改相机六参数，不产生画面层；画面转场只改像素合成，不改相机状态。
- 调色（ADJUST 轨）只改 RGB / HSL，不做遮罩；fade 的 `blend_mode` 只作用于颜色层。
- 字幕与图片不参与 CAMERA 遮罩；CAMERA 不接受 fade 的 `gradient` / `gradient_*` / `blend_mode`。
- 画幅层不参与几何链，黑边由窗口与目标画幅比决定。
- 转场不在合成数学里加特例：没有配对、没有互补 alpha 归一、没有自动生成的重叠区。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 片段级进入/退出转场字段 | 在片段上声明转场，运行时生成过渡数据 | 生成的数据在编辑器里不可见，运行时多一套转场状态机 | 效果无法作为关键帧被追踪和编辑 |
| 独立转场轨道 | 新增一种轨道承载转场 | 要扩轨道类型、schema 与播放器，转场与片段数据分离 | 现有片段重叠与关键帧已能表达本版全部转场 |
| 互补 alpha | 下层 1→0、上层 0→1 同时进行 | 第三层基画面的权重不为零，会漏出原版画面，结果非线性 | 视觉不可靠，还要额外归一机制 |
| 硬编码转场效果 | 每种转场写专用渲染代码 | 作者不可调、编辑器不可见、无法与时间轴对齐 | 违反“效果即可追踪数据” |
| 专用形状着色器 | 为斜线、星形等单独实现遮罩 | 形状组合爆炸，校验与编辑器都要跟着扩 | 本版只保留矩形与圆形 |

**本方案的缺点**：

- 叠化要求作者手动制造片段重叠并写两端不透明度关键帧，写错一端就会露出基画面。
- 没有转场语义，编辑器无法提供“一键转场”；作者必须理解关键帧。
- 同轨只消费首个活跃片段，多层同时显示要分轨，轨道数量随层数增长。
- 划像只有硬边矩形与圆形，斜线、星形、羽化边缘都做不了。
- 跳过、打断、循环回卷期间的转场行为没有专门定义，仍需按通用播放规则处理。

## 六、实现结果

状态：已实现（2026-10-17）

转场不新增数据结构；硬切、叠化、黑场/白场、划像均由现有片段与关键帧表达，矩形/圆形遮罩由 `ic_lane_blit` 完成，黑/白场走 fade 快路径。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 纯显式 clip / 关键帧（C 方案），无转场轨、转场字段或运行时生成器 | 与设计一致 | — |
| 叠化 = 底层保持不透明 + 上层 0→1，无配对 / 归一 | 与设计一致 | — |
| 划像 = `source` / `dest` 滑动或 `mask_shape` + `mask_*` 几何 | 与设计一致：`mask_shape` clip 级，`mask_*` 关键帧，硬边 | — |

无偏离。验证记录：真实解析 / 校验 / 几何探针 337 项通过，`ic_lane_blit` GL 探针 80 项通过；Fabric 实机五支 fixture 自然结束，黑 / 白 hold 的中心与字幕矩形为 0/255。Forge 仅编译，Iris / Oculus 未实机；编辑器转场面板未实现。详见[执行记录](execution-round.md)与[颜色遮罩执行记录](color-mask-execution.md)。

## 附录：重写前原文

### 0.3.6 画面转场与透明度控制：底层工具的调用层

> D1–D3 已于 2026-10-17 实现验收并定稿；本文记录当前合同与应用方式。
> §6 保留应用层开放讨论，不改变已落地的格式与 D 轮关单状态。
>
> 相关文档：
> - [画面合成](../F-playback/camera-composition.md)（lane 合成参数：不透明度 / 叠化 / 预热）
> - [可变画面](variable-frame.md)（覆盖层统一参数：不透明度 0~1）
> - [Overlay 颜色遮罩](overlay-color-mask.md)（fade 遮罩层）
> - [画面颜色调整](../../complete/0.3.6/A-color/screen-color-adjust.md)（只动 RGB / HSL，不碰 alpha）
> - [过渡](../../complete/0.3.6/B-camera/transition.md)（相机六参数 A→B 状态过渡）
> - [多相机渲染](../F-playback/multi-camera-rendering.md)（叠化预热）
>
> **D1–D3 已收口（2026-10-17）**：纯显式 clip / 关键帧（C 方案）为最终合同；不新增 `transition_in` / `transition_out`、`TRANSITION` 轨或运行时生成器。实现与验收记录见 §9；历史执行轮见 [execution-round.md](execution-round.md)。
>
> **当前状态（2026-10-18 回写）**：D 轮已关单——D1–D8 / D10 于 2026-10-17 完成；本文合同冻结为纯显式 clip / 关键帧（C 方案）。§6 列出的是**应用层仍开放**的问题，不是未完成的 D 任务；§8 仅剩编辑器面板（归编辑器阶段）。

---

#### 1. 定位（已确认）

**本文是应用层文档**：转场 = 底层工具的组合调用，本文不新造底层。

底层工具已在各篇定义：

| 底层工具 | 定义处 | 本文用它做什么 |
|---|---|---|
| 覆盖层统一参数「不透明度 0~1」（关键帧驱动） | `variable-frame.md` §3 | 任何图层（含画面 lane）的淡入淡出 |
| lane 合成参数「不透明度关键帧」+ 叠化 + 叠化预热 | `camera-composition.md` §1/§4 | 两个画面交叉淡化的底层 |
| CAMERA clip 遮罩 `mask_shape`（full / rect / circle）+ 关键帧几何 | 本文 §3.5（D2 实现）；lane 合成链 | wipe 的显露区域；只作用于 lane RGB，不裁字幕 / 图片 |
| **纯色覆盖层**（solid color overlay：颜色 + 不透明度关键帧——一个工具） | `overlay-color-mask.md`（FadeLayer） | 黑场 / 白场 = **OVERLAY 轨上已调好参数的 clip**（`layer_type=fade` + color/opacity 关键帧，归 overlay 系统、由轨道追踪），见 §3.3 |

**用户原则（已确认·2026-10-07）**：先定义工具，再考虑怎么调用、整合工具——先底层后应用。本文就是"调用层"；将来发现转场缺底层能力（如形状 wipe 的遮罩形状），先回 `overlay-color-mask.md` / `variable-frame.md` 定义工具，再回本文加调用方式。

**效果 = 可追踪的数据，不是死代码（已确认·2026-10-07）**：大部分效果不要写成一次性硬编码实现（系统追踪不了、编辑器看不见），要写成**系统能追踪的东西**——轨道 → clip → 关键帧参数。转场尤其如此：叠化 = 底 A 保持不透明 + 上 B 的 opacity 关键帧 0→1（§3.2），黑场 / 白场 = 预配置 clip（§3.3）——都是关键帧数据。**参考项目实证**（`example/` 下拉的源码）：
- **ReplayMod**（`example/ReplayMod-stable`，`com.replaymod.pathing`）：相机路径 = `Timeline` → `Path`（位置路径 / 时间路径）→ `Keyframe`（时间 + 值 + 属性）→ `PathSegment`（关键帧间，插值器是段属性：LINEAR / CATMULL_ROM / CUBIC，经 `SetInterpolator` 数据变更切换）；编辑 = `AddKeyframe` / `RemoveKeyframe` / `UpdateKeyframeProperties` 数据操作（带撤销），GUI 时间轴直接拖关键帧——相机运动 100% 数据化，无硬编码动作。
- **Olive**（`example/editor/olive`）：效果 = 节点图，每个参数都是可关键帧的 NodeInput（`kInputFlagNotKeyframable` 显式排除例外）——与 script-model.md §3 已核实的模型一致。

---

#### 2. 透明度（alpha）的总账

RGBA 的 A 通道**不属于调色**（[画面颜色调整](../../complete/0.3.6/A-color/screen-color-adjust.md)只动 RGB / HSL），alpha 属于**覆盖层合成**维度，现状分布：

| 对象 | alpha 现状（代码） | 归属文档 |
|---|---|---|
| 画面 lane（相机画面） | 合成参数 `opacity`（0~1，关键帧）已落地；合成完成后控制整幅 lane 的透明度 | camera-composition |
| 图片层 / 字幕层 | `setOpacity`（关键帧驱动，已实现） | variable-frame / overlay-color-mask |
| fade 遮罩层（= 纯色覆盖层） | `setOpacity`（关键帧驱动，已实现）；`setColor`（任意色——白场就是 `#FFFFFF`） | overlay-color-mask / scene-transition |
| 黑边层（letterbox） | LETTERBOX 关键帧 `opacity`（0~1，缺省1）已接线（D10，2026-10-17）；与 `aspect_ratio` 均匀速线性播放 | variable-frame / execution-round |
| ~~pip 层~~ | 已删除（0.3.6，被 lane 取代）；画中画 = lane 合成参数 `dest`/`source`/`opacity` | camera-composition |

**统一方向（已确认）**：alpha 在覆盖层统一参数里是同一个「不透明度 0~1」关键帧通道（`variable-frame.md` §3 已定义）；本文只负责回答"怎么用 alpha 做转场"，不重复定义 alpha 本身。

---

#### 3. 转场清单（应用层：每类 = 一套工具调用）

##### 3.1 Cut（硬切）

- **调用**：上/下两个画面无 alpha 过渡直接切换——不透明度不参与；等价于叠化中过渡时长为 0 的特例。
- 相机状态是否同步硬切由[过渡](../../complete/0.3.6/B-camera/transition.md)（cut / blend / 分参数）决定，两者正交：画面 cut + 相机 blend 可以同时发生。

##### 3.2 Dissolve（叠化 / 交叉淡化）——两个相机 clip 的 opacity 关键帧（D1 最终 C 方案）

- **本质（2026-10-17 校正）**：叠化 = **底层 A 的整幅画面保持不透明（opacity=1）**，**上层 B 的 opacity 在重叠窗口内 0→1**。source-over 结果 = `B.rgb×a + A.rgb×(1-a)`，是线性交叉；任何时刻都有一幅不透明画面在底，不会露出第三层原版基画面。
- **旧“互补 alpha”写法错误，已删除**：A 末尾 1→0 且 B 开头 0→1 **不是**等价写法——第三层基画面的权重是 `(1-a)(1-b)`，a、b 同时变化时不为 0，且合成结果非线性；它会泄露原版画面。不得再用“A+B≈1”证明无泄露。**没有**运行时配对 / 归一机制，也不在合成数学里加特例。
- **重叠区由显式关键帧给出**：作者（或编辑器 / 模板工具）写入 A 的 hold 等值关键帧与 B 的 0→1 关键帧；运行时只执行，不自动生成延长区、不新增转场字段。两 clip 时间重叠，后一个 clip 在轨道内顺序在上。
- **预热自然解决**：重叠窗口内 B 从自己 clip 的开头（opacity 0）就在渲染（hold 区渲染的就是被复制的首帧），无需独立预热调度（`camera-composition.md` §4）。
- **追踪方式只有一种**：调这两个 clip 的关键帧参数（编辑器里能看见、能拖、能打点）。**没有**独立转场轨 / 转场字段 / 转场代码——否则就是系统追踪不了的东西。
- **唯一机制性支持**：多相机渲染本身（重叠窗口内两个 lane 同时渲染，见 `multi-camera-rendering.md`）。这是**渲染层**的支持，不是转场机制。
- **数据与实机验证（2026-10-17）**：`cinematics/tests/transition/test_dissolve.json` 为 A 全程 opacity=1、B 在 [4,6) 0→1、之后 hold=1；真实 `ScriptParser` / `ScriptValidator` / `Clip` / `LaneMask` 探针 337/0，Fabric 实机 10.01s 自然结束；A / 中间 / B 三个时点的四个稳定天空 / 地面像素与端点均值误差 ≤1。旧 2026-10-07 的“A+B≈1 互补”记录是错误口径，已被本节取代。

##### 3.3 黑场 / 白场（Fade to black / white）——OVERLAY 轨的预配置 clip（已确认·2026-10-07）

- **归属**：黑场 / 白场**算在 overlay 系统里**——它们是 OVERLAY 轨上的内容，由轨道追踪（时间轴上的 clip），不是转场机制自己实现的东西。
- **应用形态**：在衔接处插入一段**已调好参数的 clip**——`layer_type=fade`，黑场 = `color=#000000`、白场 = `color=#FFFFFF`，opacity 关键帧 0→1（压场）→ 切下一段 → 1→0（亮起）。参数已定，作者只需决定"放哪、多长"。
- **与模板的关系**：这类 clip 是[模板](../E-template/templates.md)片段级模板的天然素材（"黑场 1s" = 一个片段模板，一键插入）。
- **底层已具备**：纯色覆盖层（FadeLayer）+ opacity 关键帧（0.3.5）；黑场与白场之间没有任何实现差异（同一个工具换 `color`）。
- 与 `hold_at_end` / 末尾保持的组合（压场停在末尾等触发）属脚本编排，不属本文。
- **已验证可用（手写 JSON，2026-10-07）**：`cinematics/tests/transition/test_fade_black.json` / `test_fade_white.json`（CAMERA 轨两段 + OVERLAY 轨两个 fade clip）经 `ScriptValidator`（与 `/icinematics validate` 同源）校验通过。链路逐环节核实：`OverlayTrackPlayer.createLayer` 读 `layer_type` / `color` → `FadeLayer.setColor`（解析 `#RRGGBB`，白场 `#FFFFFF` 成立）→ 关键帧 `opacity` 线性插值 → 两 clip 先后衔接时按 `findActiveClip` 切换层，衔接点两侧 opacity 均为 1（压场末尾 1 → 亮起开头 1）故黑场/白场不闪。**2026-10-07 时游戏内视觉未验证；2026-10-17 Fabric 实机已通过（黑 / 白 hold 中心与字幕矩形严格 0/255），见 §9。**
- **z 序（D3 定稿·显式）**：`z_index` 是 clip 级显式字段，运行时与 schema 统一缺省 **10**；转场 fade 显式 `z_index=1000`，转场示例字幕显式 100（D7 高对比演示的字幕为 1000），普通 fade 不写则取 10。`OverlayManager` 按 z 升序绘制，大者在上。**不是**自动最高特权层，也不改原版 HUD。需要同时显示的 OVERLAY 层必须**各自独立轨道**：同一 OVERLAY 轨只消费首个活跃 clip，重叠的后续 clip 不可达（`findActiveClip` 语义）。黑 / 白 hold 已实测中心与字幕矩形严格 0/255；层序案的定点截图略早于 3s 全黑边界（RGB 1–4），不作为严格 0 证据；全黑 hold 由 black fixture 证明。

##### 3.5 Wipe / 划像（D2 已实现）

- **调用**：CAMERA lane 的 `source` / `dest` 矩形关键帧滑动（新画面推进、旧画面退出），或 full dest + clip 级 `mask_shape` + `mask_*` 几何关键帧逐步显露。两者都是纯数据。
- **CAMERA 遮罩合同（D2）**：clip 级 `mask_shape = full`（缺省）/ `rect` / `circle`；关键帧 `mask_x` / `mask_y` 缺省 0.5（窗口比例中心，有限、可越界），`mask_width` / `mask_height` 缺省 1（相对窗口，有限非负、不设人为上限，0 不绘制），`mask_radius` 缺省 0.5（相对窗口短边，有限非负，0 不绘制）。窗口物理像素定义真圆；source / dest 独立；边界硬边；full 保持既有逐像素输出。解析 / 校验拒绝非法枚举、非有限值与负尺寸，错误带字段路径。仅 CAMERA / OVERLAY fade 支持；image / subtitle 不裁切；CAMERA 不因此接受 fade 专用 `gradient` / `blend_mode`。
- **演示**：`cinematics/tests/transition/test_wipe.json` 三阶段——rect 水平、rect 垂直、circle（短边真圆）；B 与 A 同 dest/source 全屏避免缩放冒充 wipe；初始 0 → 中间 → 完全覆盖，序列中始终有 A 或 B 整幅画面在底，无原版基画面空隙。Fabric 15.01s 自然结束；2560×1392 截图中圆半径 792.50–793.14px（跨度 0.64px），不是椭圆。
- **边界**：斜线 / 星形边缘不在本版；编辑器转场面板归编辑器阶段。

---

#### 4. 转场的数据落点（D1 定稿：C 为最终方案）

转场是“片段之间”的概念，但本版不新增转场数据结构。**最终合同 = 候选 C：纯显式 clip / 关键帧**——不新增 `transition_in` / `transition_out`，不新增 `TRANSITION` 轨道类型，不新增运行时转场生成器。转场效果由现有 CAMERA / OVERLAY clip 的重叠与 opacity / mask / source-dest 关键帧表达；时长与曲线就是关键帧数据本身。

- **候选 A（clip 级衔接字段）**：不采用。它与“运行时只读只执行、效果 = 可追踪数据、不新增转场机制”的原则冲突。
- **候选 B（独立 TRANSITION 轨）**：不采用（远期也不作为本版路线）。它需要 TrackType 扩展，并把转场从 clip 数据里分离出去。
- **候选 C（手写两段关键帧）**：**最终方案，不是临时兜底**。黑场 / 白场、叠化、wipe 都是它的实例。
- **相机状态过渡仍归 `transition.md`**：CAMERA 的 cut / morph / 分参数过渡只是相机六参数状态，不是画面转场字段；两者正交，可组合。

---

#### 5. 与其他文档的边界（已确认）

| 文档 | 管什么 | 与本文的关系 |
|---|---|---|
| `transition.md` | **相机六参数**（position/yaw/pitch/roll/fov/zoom）的 A→B 状态过渡 | 正交：本文管"画面之间怎么换"，它管"相机状态怎么变"；叠化 + 相机 blend 可组合 |
| `camera-composition.md` | 叠化的**底层**（opacity 交叉、预热） | 本文只引用，不重复定义 |
| `overlay-color-mask.md` | **纯色覆盖层**的底层定义（FadeLayer：颜色 + 不透明度；D4–D7 渐变 / 混合 / 局部覆盖） | 黑场 / 白场 / 淡入淡出 / 颜色遮罩都是它的应用；CAMERA wipe 的 rect / circle 遮罩见 §3.5（D2） |
| `script-loop.md` | 循环回卷瞬间的画面衔接 | 回卷 = 时间轴末端折回，衔接处可复用本文转场（repeat 硬切 or 可选叠化——**开放**，非 D 轮待办，与 `script-loop.md` §11 衔接问题挂钩） |
| `wait-point-track.md` | 等待点后的继续 / 接播 | 接播瞬间用哪种转场，属脚本编排，引用本文 |

---

#### 6. 仍开放的应用层问题（D 轮关单后；非 D 待办）

D1–D3 已关闭的旧待定项：数据落点 A/B/C（= C，§4）、第一版是否纳入 wipe（= 纳入，D2，§3.5）、转场时长 / 曲线默认值（= 不存在此类字段，时长与曲线就是作者关键帧数据，§4）、转场期间时间线语义（= 无独立语义，§4）、z 序（= 显式 `z_index`，D3，§3.3）、lane opacity 的曲线表达（= 与其它数值关键帧一样由关键帧数据本身表达，§3.2 / §4；overlay 的 clip 级 `interpolation` 只作用于 overlay 层，维持现状）。以上均不再作为待定。

仍开放（均标「开放」，非 D 轮待办）：

- **开放**：叠化期间主相机是否可动（沿用 `multi-camera-rendering.md` §9 的开放问题）。
- **开放**：转场与跳过 / 打断的交互——跳过途中转场 = 立即硬切还是加速完成。
- **开放**：script-loop 回卷衔接——repeat 硬切 or 可选叠化，与 `script-loop.md` §11 挂钩。

已结清：转场不影响 GUI 层（与 `screen-color-adjust.md` §5-3 同结论）；黑场 / 白场与字幕的叠放次序 = 显式 `z_index`（§3.3）；黑 / 白 hold 与字幕矩形实测严格 0/255（§9）。

---

#### 7. D1–D3 收口结论

D1–D3 已收口：数据落点 = C（纯显式 clip / 关键帧），转场类型第一版包含 cut / dissolve / black / white / wipe，z 序为显式 `z_index`；不存在转场时长 / 曲线字段或独立时间线区间（时长与曲线就是作者关键帧数据）。应用层剩余讨论见 §6。

---

#### 8. 落地顺序

| # | 步骤 | 交付物 |
|---|---|---|
| 1 | ✅ 黑场 / 白场作为"转场"文档化 + 测试脚本（D1/D3） | 黑场/白场转场可用（手写 JSON） |
| 2 | ✅ 叠化（依赖 camera-composition 双 lane 上屏 + opacity 关键帧；D1 校正为底 A 不透明 + 上 B 淡入） | 两镜头 dissolve 可用 |
| 3 | ✅ 转场数据落点定稿（§4：C 为最终方案，无新字段 / 新轨） | 纯显式 clip / 关键帧合同 |
| 4 | ✅ wipe（D2：CAMERA `mask_shape` rect / circle + source/dest 关键帧） | 划像转场可用 |
| 5 | 编辑器转场面板 + 转场预览 | WebUI 内拖拽转场并预览（归编辑器阶段） |

---

#### 9. 实现与验收记录（2026-10-17）

D1–D3 按纯数据合同收口，D 轮（D1–D8 / D10）关单。以下为实际执行结果；本轮仅本地提交，不推送。

**当前状态（2026-10-18 回写）**：本节即 D 轮关单依据；下述边界（Forge 仅编译、Iris / Oculus 未实机）为已知未验面，不阻塞关单，也不冒充已验。

- **数据 / 合同**：真实 `ScriptParser` / `ScriptValidator` / `Clip` / `LaneMask` 探针 **337 OK / 0 FAIL**，覆盖缺省、端点、中间、四边界、短边半径、非法值、字段归属与既有正例；全量 168 支脚本仅 2 支历史问题（`test_camera_facing_origin_coordinate` id 过长、`test_camera_relative_axis` 缺 yaw）。五支正式 fixture：`test_dissolve` / `test_fade_black` / `test_fade_white` / `test_wipe` / `test_transition_zindex`，全部解析 / 校验通过。
- **渲染**：新真实 shader `ic_lane_blit` GL 探针 **80 OK / 0 FAIL**（full 与旧输出逐像素等值、rect / circle、窗口物理像素真圆、UV、source/dest 映射、opacity、alpha 不写）；旧 GL 14 套全绿。
- **编译**：`sh gradlew --offline compileJava` common / Fabric / Forge 三模块通过。Forge 仅编译，未运行 Forge 客户端。
- **Fabric 实机**：五案 `result=1`，自然结束 wipe 15.01s / black 8.01s / white 8.00s / dissolve 10.01s / zindex 10.01s；rect 横 / 纵与短边真圆实际可见；四个叠化稳定像素与端点均值误差 1；普通 fade 灰色像素误差 1 且字幕可见；黑 / 白 hold 的中心与字幕矩形严格 0/255。层序案定点截图略早于 3s 全黑边界（RGB 1–4），不夸为严格 0；全黑 hold 由 black fixture 证明。
- **边界**：Iris / Oculus 未实机；编辑器 / E–H / P1 / P3 不属本轮，未关单；客户端已正常关闭（exit 0）。历史执行轮（D8 / D10）见 [execution-round.md](execution-round.md)，D4–D7 见 [color-mask-execution.md](color-mask-execution.md)。
