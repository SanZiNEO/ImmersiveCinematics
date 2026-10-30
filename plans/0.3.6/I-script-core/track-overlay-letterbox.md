# OVERLAY / LETTERBOX 轨道对象

> 状态：未实现
> 范围：OVERLAY fade/image/subtitle 的对象路径、颜色/遮罩/渐变叶子、LETTERBOX 对象、片段级 value-only 字段与执行口径
> 关联：`object-model.md`、`runtime-evaluation.md`；语义参考 `D-transition/overlay-color-mask.md`、`D-transition/variable-frame.md`、`docs/SCRIPT_FORMAT.md` §9–§10

## 一、摘要

OVERLAY 的几何、透明度、取材、颜色、遮罩、渐变全部拆成叶子对象；LETTERBOX 的 `aspect_ratio`/`opacity` 也是对象。层类型、路径/文本、渐变结构、遮罩形状、混合模式、z 序作为片段级 value-only 字段放进 `objects`；clip 外层只留播放控制；规则声明在脚本级独立集合，不在 clip 上承载。运行时用同一个 `SampledClip` 生成图层参数；`ColorMaskSpec` 不再缓存关键帧颜色数组，`OverlayManager` 仍按 `z_index` 在加入时排序。LETTERBOX 保持独立轨道的 0.5 秒淡出和 z=0 口径。

## 二、为什么做

### 2.1 问题

- `source` 是 map，只能整体取材；不能只动 `source.w`。
- fade 的 `color` 是关键帧字符串，`ColorMaskSpec` 自己解析/缓存颜色数组；颜色不能单通道调，缓存与对象模型重复。
- 遮罩几何/渐变参数散在 `mask_*`/`gradient_*` 标量里，运行时逐字段 `interpolateChannel`；新增一个通道要改播放器。
- OVERLAY 的 `opacity`/`x/y`/`anchor_*`/`scale_*` 各自 `interpolateFloat`，与相机/lane 的采样口径不统一。
- LETTERBOX 只有两个字段，但仍走共享快照/循环折叠；循环时和相机/lane 可能不同步。

### 2.2 目标

- 每个可独立调的量是一个字段；矩形四边、颜色三通道、遮罩几何、渐变参数都能单独加关键帧。
- 一个 `SampledClip` 提供所有 OVERLAY 参数；不再逐字段采样。
- `ColorMaskSpec` 只持有 value-only 结构字段（base color、gradient、shape、blend），颜色从采样对象读。
- 保持现有图层语义：fade 的 0.5 秒淡出、image 的 `source`/`scale`/`anchor`、subtitle 的 `font_scale`、`z_index` 加入时排序。
- LETTERBOX 的 `aspect_ratio`/`opacity` 对象化；其余不变。

### 2.3 非目标

- 不改变图层绘制/着色器；`ColorMaskRenderer`、`ImageLayer`、`SubtitleLayer`、`FadeLayer` 的数学不变。
- 不把 `layer_type`/`path`/`text`/`gradient`/`mask_shape`/`blend_mode`/`z_index` 做成 animated/event；它们是 value-only 字段。
- 不做编辑器图层 UI/时间轴分组；只定义运行时格式。
- 不改变 LETTERBOX 的 z=0/淡出口径。

## 三、方案

### 3.1 OVERLAY 对象

| 对象路径 | 类型 | 插值 | 适用层 | 说明 |
|---|---|---|---|---|
| `x` / `y` | float | linear | 全部 | 窗口归一化中心，0~1，不钳制 |
| `anchor_x` / `anchor_y` | float | linear | 全部 | 元素自身归一化锚点，0=左/上，1=右/下 |
| `scale_x` / `scale_y` | float | linear | 全部 | 相对素材原始像素的倍数 |
| `source.x/y/w/h` | float | linear | image | 素材归一化取材矩形，缺省整幅 |
| `opacity` | float | linear | 全部 | 0~1，缺省 1 |
| `font_scale` | float | linear | subtitle | 字号倍数，1=原版 9px；与 scale 叠加 |
| `color.r/g/b` | float | linear | fade | 关键帧颜色通道；缺省回落 value-only `color` 基色 |
| `mask_x` / `mask_y` | float | linear | fade | 遮罩中心，窗口比例，可越界 |
| `mask_width` / `mask_height` | float | linear | fade | rect 宽高，窗口比例，非负 |
| `mask_radius` | float | linear | fade | circle 半径，窗口短边比例，非负 |
| `gradient_angle` | float | linear | fade（gradient） | linear 方向角，0=右，90=下 |
| `gradient_center_x` / `gradient_center_y` | float | linear | fade（gradient） | radial 中心，窗口比例 |
| `gradient_radius` | float | linear | fade（gradient） | radial 半径，短边比例，>0 |

- `color.r/g/b` 的对象值/关键帧覆盖 value-only `color` 字段的对应通道；没有对象/关键帧的通道回落基色。这样只动红色通道也可以，未写的绿/蓝保持基色。
- `source` 只对 `layer_type=image` 有效；`font_scale` 只对 `subtitle`；`color.*`/`mask_*`/`gradient_*` 只对 `fade`。validator 按 `appliesTo` 拒绝错层字段，不做静默忽略。
- `gradient_*` 只在 value-only `gradient` 字段声明时生效；没有 gradient 时 validator 警告“对象无效果”。
- `mask_*` 的形状由 value-only `mask_shape` 字段决定：`full` 不消费；`rect` 读 x/y/width/height；`circle` 读 x/y/radius。
- 对象路径保持现有标量名（`anchor_x`、`mask_width`、`gradient_center_x`），不改成 `anchor.x`/`mask.width`；只有 JSON map `source` 展开成 `.x/.y/.w/.h`。编辑器可以按 section 分组显示。

### 3.2 OVERLAY 片段级 value-only 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `layer_type` | enum `fade`/`image`/`subtitle` | 层类型；决定哪些对象适用 |
| `color` | string `#RRGGBB` | fade 基色；`color.r/g/b` 的回落 |
| `gradient` | map `{type: linear|radial, stops: [...]}` | 渐变结构；解析期校验 |
| `mask_shape` | enum `full`/`rect`/`circle` | 遮罩形状 |
| `blend_mode` | enum `normal`/`multiply`/`screen`/`soft_light`/`overlay` | 混合模式 |
| `path` | string | image 素材路径 |
| `text` | string | subtitle 文本 |
| `z_index` | int | 图层顺序；加入时排序 |

- 规则声明属于脚本级独立集合，通过片段引用定位，不是图层数值属性；本轨道字段表不预定 `rules` 容器，也不在 clip 上新增规则结构。规则的时间门、条件、门前事实采集、实例级挂起、事实接力与延迟动作由规则集合负责。
- 路径曲线集合只引用位置对象/对象组；OVERLAY/LETTERBOX 不提供位置曲线目标，不参与曲线绑定。
- `layer_type=image` 的 `path` 支持 `.mp4` / `.webm` / `.mkv`：运行时按扩展名创建 `VideoLayer`，复用 `x/y`、`scale_*`、`source`、`opacity`、`z_index`，不新增字段、不新增 `layer_type` 值。视频时间取片段局部时间；音轨不随视频播放。见 `media-video.md`。
- `z_index` 是 value-only 字段（无 keyframes）：`OverlayManager.addLayer` 只在加入时按 z 排序；运行中改 z 不会重排。保持现行行为。
- `gradient` 的 stops 是结构数据，放在 value-only `gradient` 字段里；颜色 stops 不随时间变。若以后要动画渐变颜色，再按字段模型加 `gradient.stop.N.*`，本轮不做。

### 3.3 LETTERBOX 对象

| 对象路径 | 类型 | 插值 | 说明 |
|---|---|---|---|
| `aspect_ratio` | float | linear | 黑边比例，缺省 2.35 |
| `opacity` | float | linear | 黑边不透明度，0~1，缺省 1 |

clip 级不承载规则；规则声明在脚本级独立集合，通过引用定位片段。非活跃 clip 设 aspect=0、opacity=1；内部 0.5 秒淡出；z 固定 0。这些行为不变。

### 3.4 执行

- `OverlayTrackPlayer`/`LetterboxTrackPlayer` 从 `SampledClip` 读对象槽位，构造 `OverlayParams`/`LetterboxParams`；不再逐字段 `interpolateFloat`/`interpolateChannel`。
- `ColorMaskSpec` 只持有 value-only base color/gradient/mask_shape/blend 字段；颜色/遮罩/渐变采样值由 `OverlayParams` 传入。删除 `keyframeTimes`/`keyframeColors` 缓存。
- `FadeLayer` 的有效透明度 = 采样 `opacity` × 内部 0.5 秒淡出因子；颜色从 `color.r/g/b` 采样值转 RGB。
- `ImageLayer` 的 `source` 读 `source.x/y/w/h`；`x/y` 是中心，`anchor_*` 是变换原点，`scale_*` 是素材像素倍数，`k` 仍是分辨率逃逸（非脚本字段）。
- `SubtitleLayer` 的 `font_scale` 读对象；`scale_x/y` 叠加；alpha < 4 跳过（MC 字体 alpha 限制）不变。
- `OverlayManager` 仍按 `z_index` 在 `addLayer` 时排序；`removeLayer` 关闭 GPU 资源；`startFadeOut` 按 owner。
- 循环片段按次数执行；对象使用当前执行的 `executionLocalTime` 采样。图层是否仍活跃由调度执行状态和显式播放控制决定，派生 `windowEnd` 只作定位／显示参考，不能到点就代替执行结束事件。边界不额外重置图层／淡出，仍活跃资源继续，释放随实际结束或控制事件（见 `loop-execution.md` §3.5）。

### 3.5 静态图层

- 没有 keyframes 的 OVERLAY/LETTERBOX 片段合法：所有对象用 `value`/默认值；`x/y=0.5`、`opacity=1`、`anchor=0.5`、`scale=1`、`aspect_ratio=2.35`。
- 静态图层不需要边界关键帧；编辑器不再强制 `0`/`duration` 关键帧。

## 四、技术细节

### 4.1 核心模型

```text
OverlayClipRuntime
  SampledClip sample
  OverlayParams params
  ColorMaskSpec maskSpec     // value-only 结构，不含 keyframe color cache

LetterboxClipRuntime
  SampledClip sample
  LetterboxParams params
```

- `OverlayParams`：x/y/anchor/scale/source/opacity/fontScale/color RGB/mask/gradient。
- `ColorMaskSpec`：baseColor、gradient（type/stops）、maskShape、blendMode；无时间数组。
- `SampledClip` 的 overlay 槽位按 schema 分配；`source.x/y/w/h` 只在 image 层存在，`font_scale` 只在 subtitle，`color.*`/`mask_*`/`gradient_*` 只在 fade；槽位布局可以按层类型共享大数组 + `appliesTo` 标记。

### 4.2 契约

- **创建**：clip 加载时构建 runtime；采样器编译；`OverlayParams`/`ColorMaskSpec` 预分配。
- **销毁**：随 clip 销毁；图层移除时释放 GPU 资源（仍由 `OverlayManager` 负责）。
- **状态归谁**：图层生命周期/淡出状态归 `OverlayManager`/`FadeLayer`；采样器纯函数；颜色/遮罩结构归 `ColorMaskSpec`。
- **边界**：OVERLAY 不碰相机/lane；LETTERBOX 不碰 overlay 图层栈；两者都只读 `SampledClip`。

### 4.3 数据形态

- `color.r/g/b` 是 0~1 还是 0~255？保持现有 `ColorMaskSpec` 的 0~1 浮点（从 `#RRGGBB` 解析后归一化）；schema 的 `min/max` 0~1。
- `source`/`dest` 归一化矩形：0~1，不钳制；负宽高由 validator 拒绝（`source.w/h` 非负，`dest.w/h` 可正可负？现有 rect 允许负缩放？保持现有校验）。
- `mask_width`/`mask_height`/`mask_radius` 非负；0 面积不绘制。
- `gradient_radius` > 0；`gradient_angle` 任意有限。
- `z_index` int；`layer_type` enum；`blend_mode` enum；`mask_shape` enum；`path`/`text` string。

### 4.4 边界

- 不改变 `ColorMaskRenderer` 的 shader/混合；只改变参数来源。
- 不改变 `CanvasTransform` 的坐标映射；`x/y/anchor/scale/source` 语义保持。
- 不改变 LETTERBOX 的 z=0/淡出；只改变采样来源。
- 不把 `z_index` 变成对象；运行中改 z 不重排，保持现行。
- 不把 `gradient.stops` 变成对象；本轮只动画几何/颜色通道。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| `source` 保持复合对象 | `source` 一个对象，内部 x/y/w/h | 不能只动宽度；与叶子对象冲突 | 拆成四叶 |
| `color` 保持字符串对象 | 关键帧 `color` 是 `#RRGGBB`，自定义插值 | 不能单通道调；`ColorMaskSpec` 继续缓存/解析 | 拆成 `color.r/g/b` |
| 遮罩/渐变保持逐字段采样 | 每个字段各自 `interpolateChannel` | 新增通道要改播放器；循环时多套采样 | 一个 `SampledClip` |
| `z_index` 对象化 | z 随时间变，运行时重排 | 图层顺序频繁变化，GPU 资源/排序成本高；现行只在加入时排序 | 保持 value-only 离散字段 |
| `gradient.stops` 对象化 | 每个 stop 的颜色/offset 可动画 | 结构复杂、shader 参数多；本轮无需求 | 结构固定，几何可动画 |

**本方案的缺点**：

- OVERLAY 字段数量增加（尤其 fade 的 19 个），原始 JSON 冗长；作者入口是编辑器，手写脚本不是推荐路径。
- `color.r/g/b` 的“缺省回落 value-only 基色”是动态默认，schema 的静态 `default` 表达不了；parser/validator/编辑器要特判。
- `appliesTo` 让同一轨道的对象集合随层类型变化，schema/编辑器表单需要条件显示。
- 删除 `ColorMaskSpec` 的 keyframe 缓存会改变内部 API；所有读缓存的地方要迁移到 `OverlayParams`。
- 执行边界不一律重置图层／淡出；长循环节奏由作者内容和执行状态决定，不能靠派生窗口截断仍在运行的内容。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
