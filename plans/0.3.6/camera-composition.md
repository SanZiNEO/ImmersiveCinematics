# 0.3.6 画面合成：取材区域 / 目标区域 / 不透明度（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [并行播放](./parallel-playback.md)
> - [多相机渲染](./multi-camera-rendering.md)
> - [Overlay 颜色遮罩](./overlay-color-mask.md)

---

## 1. 定位（已确认）

每个画面 lane 的输出是**一张纹理**。合成层的工作就是把若干张纹理铺到屏幕上，回答四个问题：

| 参数 | 含义 | 默认 |
|---|---|---|
| 取材区域（source rect） | 从 lane 画面里取哪一块（画面内归一化矩形） | 全幅 |
| 目标区域（dest rect） | 铺到屏幕哪一块（屏幕归一化矩形） | 全屏 |
| 不透明度（opacity） | 叠放时的透明度 | 1 |
| 叠放顺序（order） | lane 之间的上下关系 | **先按轨道层级，再按轨道内 clip 顺序（后面的 clip 在上），最后按 z_index**（已确认·2026-10-07；跨实例再叠一层实例启动顺序，见并行播放 §3.4） |

**四个参数全部走关键帧**（已确认）：随时间变化、可插值——这是 PIP 系统应有的能力。

**合成是顺序进行的**（渲染一张 → 贴到屏幕 → 复用缓冲）→ **显存不随 lane 数增长**；只有叠化预热需要同时保留 2 张（原型实测：1 张共用缓冲跑完 25 个画面，见 `quadrant-perf/summary.md`）。

由此，现有与计划中的所有画面形态都是**同一模型的特例**（已确认）：

| 形态 | 参数描述 |
|---|---|
| 主相机（现状） | dest = 全屏，opacity = 1 |
| PIP 画中画 | dest = 小矩形 |
| 分屏 | 两个 lane，dest 各占左 / 右（或上 / 下）半屏 |
| 叠化（dissolve） | 两个全屏 lane，opacity 交叉：A 1→0、B 0→1 |
| 数字变焦 / 局部取景 | source rect 取局部，dest 全屏 |
| 监视器墙 | N 个 lane，dest 网格排布 |

> **这些形态没有独立实现（已确认·2026-10-07）**：它们都是同一套合成参数关键帧的编排。以叠化为例——叠化是**对两个相机 clip 的状态（合成参数 opacity）做处理**：clip A 末尾 opacity 关键帧 1→0、clip B 开头 0→1；**追踪 = 调 clip 的关键帧参数**，没有独立转场轨 / 字段 / 代码。唯一机制性支持是叠化预热（渲染层，见 `multi-camera-rendering.md`）。详见[画面转场](./scene-transition.md) §3.2。

**取材区域语义（已确认）**：对相机**输出画面**的矩形裁剪（屏幕空间 UV），不是改相机视锥的世界空间取景。

**不属于本文**：

- 多张相机画面怎么渲染出来（渲染管线、FBO、模组兼容）→ [多相机渲染](./multi-camera-rendering.md)
- lane 从哪来（多脚本实例并行）→ [并行播放](./parallel-playback.md)

---

## 2. 合成参数的归属（待定·核心问题）

关键帧数据放在哪：

- **候选 A：自声明**——每个脚本声明自己 lane 的合成参数；跨脚本并行时各管各的，order 冲突按启动顺序。简单，第一版够用。
- **候选 B：合成导演**——独立的导演脚本 / 轨道统一引用并编排各 lane。表达力强，但依赖 lane 寻址（名字 / id）。

倾向：**A 先行，B 作为后续扩展**（结构上不堵死）。

关键帧通道的落点：lane 的合成参数是作为新轨道类型，还是挂在 CAMERA 轨 clip 上，执行时定。

---

## 3. 与现有 pip 层的关系（方向）

OVERLAY 现有 `pip` 层是静态占位（半透明黑填充 + 2px 白边，不绑定任何纹理/相机画面）；画面 lane 是动态纹理。方向：`pip` 层升级为可绑定 lane（lane 作为一种图层来源），合成参数即层的几何属性；或 pip 层被合成层取代。执行时定。

---

## 4. 叠化预热（方向，沿用多相机渲染文档的结论）

叠化开始那一刻，两个 lane 的画面必须同时有效：B lane 不能到叠化开始才第一次渲染，否则第一帧没准备好。

- 按脚本时间线**提前预热 B**：预热开始 = 叠化开始 − 预热时长；
- 不是渲染“未来的世界”（世界是动态的，未来帧不存在），是让 B 的 framebuffer 提前进入有效状态；
- 并行实例模型下，预热 = **提前启动 B 实例**（见[并行播放](./parallel-playback.md)）。

**时间轴允许重叠后的结论（已确认·2026-10-07）**：**预热 = 重叠窗口本身**。叠化 = 上一个 clip 的**末尾帧复制延长**（hold）+ 下一个 clip 的**首帧复制延长**（hold），两个冻结区时间交叉（见[脚本循环](./script-loop.md) §4）；B 从自己 clip 的开头（opacity 0 起，hold 区渲染的就是被复制的首帧）就一直在渲染，**不需要独立的预热调度机制**。"预热时长"退化为作者的编排（叠化时长 = 延长区长度），不再是一个框架参数。

---

## 5. 可能的问题

- 合成参数归属（自声明 vs 导演）。
- order 冲突的判定规则（已定：轨道层级 → 轨道内 clip 顺序 → z_index，见 §1）。
- dest 矩形之外的区域谁垫底：无 lane 覆盖时露原版视角还是黑场。（时间轴允许重叠后，作者可用重叠保证全屏总有覆盖、无空隙——露原版视角是"有空隙"的结果，靠作者编排避免；兜底策略仍待定。）
- 编辑器预览怎么表现多 lane 合成。
- 与 HUD / letterbox 的叠放顺序。
- 性能：合成本身是全屏 quad 拷贝，开销小；主要开销在渲染侧（见多相机渲染文档）。原型实测：每 lane ≈3.4–3.6 ms（主画面稳态 2–3 ms）、线性；且 **lane 级后处理（如发光描边）属于该 lane，必须在 lane 的 FBO 内完成**（见 `quadrant-prototype-results.md` §3.2）。

---

## 6. 待定

- 合成参数字段形态与归属轨道。
- 主 lane（听者归属用，见并行播放 §3.3）的判定规则。
- `pip` 层的去留。
- 跨脚本 lane 寻址（候选 B 的前置）。

---

## 7. 落地顺序（方向）

| # | 步骤 | 交付物（完成后我们要什么） |
|---|---|---|
| 1 | 合成参数模型定稿：字段表 + 默认值 + 校验规则 | 文档字段表；手写 JSON 即可表达分屏 / PIP / 叠化意图 |
| 2 | 单 lane 非全屏：主相机的 dest / opacity 可关键帧 | 单相机脚本可把画面缩到屏幕一角、淡入淡出，无需第二相机 |
| 3 | 双 lane 上屏（依赖多相机渲染原型）：order + dest 铺屏 | 分屏脚本可跑 |
| 4 | opacity 关键帧 → 叠化，含预热 | 两镜头 dissolve 过渡可用 |
| 5 | source rect 裁剪 | 同一相机画面取局部铺满全屏（数字变焦 / 局部取景） |
| 6 | 编辑器：合成参数属性面板 + 多 lane 预览 | WebUI 内可编排并预览分屏 / 叠化 |

> **落地状态（2026-10-07）**：步骤 1（数据层）与步骤 3–5 的**渲染侧合成层**已落地（见本节末）；
> 步骤 2 / 3 / 4 / 5 的**脚本侧接线**（关键帧 → lane 注册 → 合成参数）尚未落地。
>
> 步骤 1–2 不依赖多相机渲染，可先行验证关键帧链路；步骤 3 起依赖渲染原型。

### 步骤 1 定稿：合成参数字段表（已定稿·2026-10-07）

合成参数**全部挂在 CAMERA clip 的关键帧上**（`Keyframe.data` 通用容器，无需改类），随关键帧插值。字段形态与默认值：

| 字段 | 类型 | 默认 | 校验 | 说明 |
|---|---|---|---|---|
| `opacity` | float | `1.0` | `0`~`1` | 叠放不透明度 |
| `dest` | object `{x,y,w,h}` | `{0,0,1,1}`（全屏） | 各分量 `0`~`1` | 目标区域：画面铺到屏幕的归一化矩形 |
| `source` | object `{x,y,w,h}` | `{0,0,1,1}`（全幅） | 各分量 `0`~`1` | 取材区域：画面内归一化矩形 |

**`order` 不进字段表**：叠放层级由**轨道层级 → 轨道内 clip 顺序**决定（后面的 clip 在上，§1 默认规则），作者不写。

**为什么 `dest` / `source` 用对象而非平铺字段**：矩形是一个整体语义（四个分量同属一个参数），与既有 `position` / `look_at_target` 的复合对象写法一致；关键帧按"复合值整体插值"（`KeyframeInterpolator` 对 `position` 即如此），对象形态让矩形随时间整体插值更自然，字段表也更小。

**落地位置**：字段登记在 `script/schema/TrackSchemas.camera()` 的 `kfFields`；校验在 `ScriptValidator`（`checkUnitFloat` / `checkRect`）；文档见 `docs/SCRIPT_FORMAT.md` §4"合成参数"；测试脚本 `cinematics/tests/camera/test_compositing_params.json`。

**归属裁决**：本定稿取 §2 **候选 A（自声明）**——参数写在本 clip 自己的关键帧上，不引入独立合成轨/导演；候选 B 的结构空间未被堵死（将来可加寻址层）。

**范围**：本步骤只落数据层（能解析、能校验、能存进通用 Keyframe 容器）；渲染侧消费（把参数应用到上屏）属步骤 2 及以后。

### 步骤 3–5 渲染侧落地：合成层（已落地·2026-10-07）

合成层 = `client/lane/LaneCompositor.java`：`compose(RenderTarget texture, Rect source, Rect dest, float opacity)`
把一条 lane 的画面纹理铺到屏幕（主 framebuffer），**四个参数在同一次 quad 绘制里完成**（不拆两次绘制）：

| 参数 | 落点 |
|---|---|
| `source` | UV 子区域（只采样画面的一块） |
| `dest` | quad 顶点（屏幕归一化矩形 → 像素） |
| `opacity` | 混合 alpha（`ColorModulator.a` + `srcalpha / 1-srcalpha` 混合） |
| `order` | **绘制顺序**：合成即时进行、不缓存纹理（§1「渲染一张 → 贴到屏幕 → 复用缓冲」，lane 共用一张离屏缓冲），所以叠放顺序就是**调用顺序**——调用方按「轨道层级 → 轨道内 clip 顺序 → z_index」升序逐层调用，后调用者盖在先调用者之上 |

- **矩形口径**：`source` / `dest` 都是 0~1 归一化、**原点在左上角**（x 向右、y 向下）；`source` 相对 lane 画面，`dest` 相对屏幕。纹理 v 轴在合成器内部翻转（FBO 纹理 v=0 在画面底部，与原版 `RenderTarget.blitToScreen` 的 UV 口径一致）。
- **绘制路径**：复刻原版 `RenderTarget._blitToScreen` 的屏幕空间画法（屏幕正交投影 + 模型视图 z=−2000 + 4 顶点 quad），shader 用原版 `position_tex`（其自带 `srcalpha / 1-srcalpha` 混合，正是 opacity 需要的），不新建 shader 资产。
- **状态纪律**：一次合成会改动绑定的 framebuffer / 视口、全局投影与 VertexSorting、全局模型视图、shader 颜色与 0 号 shader 纹理、深度测试 / 深度写 / 颜色写 / 混合——合成器入口保存、出口还原（与 lane 渲染同一纪律）；alpha 通道不写（同 `blitToScreen`：主画面 alpha 不归合成层管）。
- **验证入口**：`-Dicinematics.quadrant=4`（或环境变量 `ICINEMATICS_QUADRANT=4`）的调试驱动已改走合成器上屏（dest = 网格格、source = 全幅、opacity = 1），替换掉临时的 `glBlitFrameBuffer`。
- **尚未落地**：脚本关键帧 → 合成参数的接线（分屏 / 叠化 / 局部取景的**编排**）属 lane 注册任务；本步骤只交付渲染消费侧，合成器接口已就位。

---

## 8. 与 0.4.0 旧稿的关系

本文取代 0.4.0 的 **G2（画中画）**中“画面怎么上屏”的部分（任意区域叠加 = dest 矩形的一个特例）；G2 渲染侧的技术证据（第二遍渲染降耗、模组兼容策略）并入[多相机渲染](./multi-camera-rendering.md)。G2 原文已从 0.4.0 抹除。

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **pip 的 opacity 不生效**：`overlay/PipLayer` 算出的 `fillArgb` / `borderArgb` 从未使用（直接用了常量）→ `opacity` 对 pip 填充与边框完全不起作用；且 pip 的 `x/y/width/height` 是原始像素而非屏幕百分比。

---

## 事实核查（2026-10-07）

> 依据文件：`common/src/main/java/com/immersivecinematics/immersive_cinematics/overlay/{PipLayer,ImageLayer,FadeLayer,SubtitleLayer,LetterboxLayer,OverlayManager}.java`、`.../proto/QuadrantProto.java`、`.../mixin/QuadrantProtoMixin.java`、`.../script/OverlayTrackPlayer.java`、`plans/0.3.6/quadrant-perf/summary.md`、`plans/0.3.6/quadrant-prototype-results.md`。核查针对本文事实性断言，不改设计方向。

### ① 核实为真

1. **pip 的 opacity 不生效**（已知缺陷条）：`PipLayer.render()` 里算出 `fillArgb`/`borderArgb` 后**均未使用**——填充写死 `FILL_COLOR`（`0x40000000`）、边框写死 `BORDER_COLOR`（`0xFFFFFFFF`）。证据：`overlay/PipLayer.java:14-15,40-45`。
2. **pip 的 x/y/width/height 是原始像素**：`render()` 里 `actualX = x - width*anchorX` 后直接 `(int)` 使用，**无** `* screenWidth/screenHeight`；对比 `ImageLayer.render()` 的 `x*screenWidth`。证据：`overlay/PipLayer.java:31-36`、`overlay/ImageLayer.java:55-56`。
3. **pip 层只画填充 + 边框（无纹理）**：`PipLayer.render()` 只有 5 次 `guiGraphics.fill(...)`（1 填充 + 4 边框），不绑定纹理/相机画面；类注释「Phase 1：仅渲染白色边框和半透明黑色填充，不包含实际摄像头画面」。证据：`overlay/PipLayer.java:5-9,41-53`。
4. **§5 性能数字**：`quadrant-perf/summary.md` 结论表「单画面」4/16/25 画面分别为 3.4 / 3.5 / 3.6 ms、主画面各档 2–3 ms、线性——绝对数值与本文一致；summary 原「≈1.2–1.4 × 主画面」比值经 2026-10-07 跨文档核查与 CSV 明细不符（CSV 推算 ≈1.6–2.0×），已按「晚修改者为准」（multi-camera-rendering.md 同日修正）在各引用文档删除该比值。
5. **§5 「lane 级后处理必须在 lane 的 FBO 内完成」**：`quadrant-prototype-results.md §3.2` 结论原文「lane 的 `renderLevel` 加上所有 lane 级后处理都必须在 lane 的 FBO 内完成，再缩放上屏」，与本文一致。
6. **§1「1 张共用缓冲跑完 25 个画面」**：`proto/QuadrantProto.java` 只持有一个静态 `RenderTarget target`，注释「共用的离屏缓冲（各画面顺序渲染、用完即贴，不必每画面一张）」；`QuadrantProtoMixin` 在每个 view 循环里都调 `QuadrantProto.target(w, h)` 复用同一缓冲。证据：`proto/QuadrantProto.java:70-71,221-227`、`mixin/QuadrantProtoMixin.java:113`。
7. **§1「合成顺序进行、显存不随 lane 数增长、缓冲复用」**：与 `summary.md`「成本线性、显存不随画面数增长（合成顺序进行，缓冲复用）」一致，且有上述单缓冲源码支撑。

### ② 已修正的断言

1. **§3「OVERLAY 现有 `pip` 层是静态贴图」→「静态占位（半透明黑填充 + 2px 白边，不绑定任何纹理/相机画面）」**：`PipLayer` 不绘制任何纹理（见①.3），「贴图」不准确。已就地改为精确描述，方向句未动。

### ③ 补全的信息（源码核对，原文未列出/未明确）

1. **overlay 五个内置层类的默认 z 常量**（0/10/20/30/40 的说法与源码一致）：
   - `LetterboxLayer.Z_INDEX = 0`（`LetterboxLayer.java:7`）
   - `FadeLayer.DEFAULT_Z_INDEX = 10`（`FadeLayer.java:13`）
   - `ImageLayer.DEFAULT_Z_INDEX = 20`（`ImageLayer.java:25`）
   - `SubtitleLayer.DEFAULT_Z_INDEX = 30`（`SubtitleLayer.java:18`）
   - `PipLayer.DEFAULT_Z_INDEX = 40`（`PipLayer.java:13`）
   - ⚠ 注意区分：这是**类默认常量**；脚本创建的层 z 值实际来自 clip 的 `z_index` 字段（`OverlayTrackPlayer.createLayer`：`int zIndex = clip.getInt("z_index", 10)`，默认 10），会 `setZIndex(...)` 覆盖类常量。即运行时 z 由脚本指定、缺省 10，而非直接用 0/10/20/30/40。
2. **`OverlayManager` 行为**：单例 `INSTANCE`（`OverlayManager.java:31`）；构造时只注册 `LetterboxLayer`；`addLayer()` 后按 `OverlayLayer::getZIndex` 升序排序（`OverlayManager.java:72-74`）；`render()` 顺序遍历可见层（`isVisible()` 过滤，`OverlayManager.java:55-58`）；`update()/reset()/isAnimating()/startFadeOut()` 均遍历所有注册层（`reset()` 见 `OverlayManager.java:163-165`）。
3. **`ImageLayer` 百分比坐标断言**：`x/y` 为屏幕百分比、元素中心（`actualX = x*screenWidth - dispW/2`），`scaleX/scaleY` 为相对原图尺寸的乘数。与 `variable-frame.md`（「现状对照」行：`ImageLayer` 的 x/y 已是屏幕百分比、元素中心）**一致，两文档无冲突**。证据：`ImageLayer.java:14-24,55-56`。
4. **pip 的合成参数入口**：`PipLayer` 已有 `setPosition/setSize/setAnchor/setOpacity/setZIndex`（`PipLayer.java:73-95`），`OverlayTrackPlayer.applyInitialClipValues` 从关键帧读 `x/y/width/height/anchor_x/anchor_y`（均按原始像素/浮点直传）。证据：`OverlayTrackPlayer.java`（`applyInitialClipValues` 的 `PipLayer` 分支）。

### ④ 无法核实的断言

- §1「只有叠化预热需要同时保留 2 张」：属设计推论（方向），无现状源码可证，**未验证**。

### ⑤ 跨文档冲突备注（未改他人文档）

- `quadrant-prototype-results.md §2` 写「每象限整尺寸渲染进**自己的** `RenderTarget`」，而原型源码 `QuadrantProto.java` 实际是**单个共享** `RenderTarget`（各 view 顺序复用）。本文 §1「1 张共用缓冲」与源码一致；差异仅在原型文档 §2 的措辞，未修改该文档，仅在此备注。
