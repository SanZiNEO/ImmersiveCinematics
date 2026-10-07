# 0.3.6 可变画面：从固定输出到可摆放的画面元素（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [画面合成](./camera-composition.md)
> - [多相机渲染](./multi-camera-rendering.md)
> - [并行播放](./parallel-playback.md)
> - [脚本模型](./script-model.md)

---

## 1. 定位（已确认）

多相机原型验证通过之后，**相机画面不再是“固定的整屏输出”，而是一个可以被裁切、缩放、摆放的画面元素**。

再进一步（已确认）：**我们所有的画面内容都是盖在游戏画面上的覆盖层**，只是分了类。相机画面、图片、字幕、遮罩、黑边是同一类东西的不同类别，用**同一套参数**描述“放在哪、多大、裁哪块、多透明”。

本文只回答**摆放**。不回答：

- 画面怎么渲染出来 → [多相机渲染](./multi-camera-rendering.md)
- 谁在播、同时播几条 → [并行播放](./parallel-playback.md)
- 参数怎么进关键帧 → [脚本模型](./script-model.md)

### 1.1 画面系统：本文是核心（统筹定位·已确认 2026-10-07）

**画面系统** = 以「对象的**位置 / 大小 / 缩放 / 透明度**等参数」为唯一控制面，用**关键帧之间的平滑（插值）**驱动，并在此基础上组合出多种复杂效果的体系：

```text
画面系统
 ├─ 参数模型（本文）：位置 / 锚点 / 缩放 / 取材 / 适配 / 不透明度 / 顺序 —— 全部走关键帧
 ├─ 关键帧平滑：
 │    关键帧间插值 → script-model.md（关键帧级插值 / 贝塞尔手柄、参数寻址到分量）
 │    帧间平滑（tick 与渲染帧之间）→ temporal-interpolation.md
 ├─ 画面来源（同一参数模型的不同类别）：
 │    相机 lane（multi-camera-rendering.md 渲染）、图片 / GIF（resource/）、
 │    纯色（overlay-color-mask.md 的纯色覆盖层）、文字（字幕）
 └─ 复杂效果（应用层，全部是参数组合，不新造"效果类型"）：
      PIP / 分屏 / 叠化 / 数字变焦 / 监视器墙 → camera-composition.md 特例表
      转场（叠化 / 黑场 / 白场 / wipe）→ scene-transition.md
      调色（只动 RGB / HSL）→ screen-color-adjust.md
```

| 文档 | 在画面系统里的角色 |
|---|---|
| **本文（variable-frame）** | **核心 / 总纲**：统一参数模型 + 参考画布 + 设备无关 |
| script-model / temporal-interpolation | 关键帧平滑的机制（插值 + 帧间平滑） |
| multi-camera-rendering / camera-composition | 画面来源与上屏（lane 渲染 + 合成参数） |
| overlay-color-mask | 纯色覆盖工具（黑场 / 白场 / 颜色遮罩 clip 的归属） |
| scene-transition | 应用层：转场 = 参数组合调用 |
| screen-color-adjust | 应用层：调色（RGB / HSL，不碰 alpha） |

**原则（已确认）**：复杂效果 = 同一套参数的新取值 / 新组合，**不新造"效果类型"**；发现参数模型表达不了的新效果，先回来给本文加字段，再谈应用。**效果写成系统可追踪的数据（轨道 → clip → 关键帧参数），不写死代码**——机制性能力最小化（实证：ReplayMod 路径系统全数据化、olive 节点参数全关键帧，见 `scene-transition.md` §1）。

---

## 2. 画面 = 覆盖层（已确认）

游戏画面之上的一切内容都是覆盖层，按顺序叠放。分类只体现“内容来源”，摆放参数是同一套：

| 类别 | 内容 | 来源 |
|---|---|---|
| 画面层 | 相机 lane 的输出纹理 | 每条 CAMERA 轨 / 每个相机一条 |
| 图形层 | 图片 / GIF | `resource/` 目录 |
| 文本层 | 字幕 | 脚本 |
| 效果层 | 颜色遮罩 / 淡入淡出 | 脚本 |
| 画幅层 | 黑边（letterbox） | 脚本 |

**主画面也是覆盖层之一**（全屏那一层）——“100% 原画面”就是它的默认形态。

> 现状对照（可验证）：`overlay/` 下已有 Letterbox / Fade / Image / Subtitle / Pip 五类实现（均 `implements OverlayLayer`）；其中 Pip 是“静态占位框”形态（只画 2px 白框 + 半透明黑填充，无纹理、无相机画面），去留见 §6。

---

## 3. 参数（方向）

每个覆盖层用同一组参数描述：

| 参数 | 语义 | 参考系 |
|---|---|---|
| 位置 | 元素中心在哪 | **画布百分比** |
| 锚点 | 缩放（将来含旋转）绕哪个点 | 元素自身百分比（0~1） |
| 缩放 | 相对“基准尺寸”的倍数 | 百分比 |
| 取材 | 裁掉素材的哪几条边 | **素材百分比（0~1）** |
| 适配 | 素材怎么映射到画布：Fit / Fill / Stretch | — |
| 不透明度 | 叠放时的透明度 | 0~1 |
| 顺序 | 叠放次序 | 整数 |

**全部走关键帧**（见[脚本模型](./script-model.md)）。

**“基准尺寸”必须逐类定义**（缩放 = 1 时是多大），否则百分比没有参照物：

| 类别 | 基准尺寸 |
|---|---|
| 画面层 | 铺满画布（由适配模式决定怎么铺） |
| 图形层 | 原图尺寸 |
| 文本层 | 基准字号下的文字块 |
| 效果层 / 画幅层 | 铺满画布 |

> 现状对照（可验证）：`ImageLayer` 的 `x`/`y` 已是“屏幕百分比、元素中心”（`actualX = x * screenWidth - dispW / 2`），`scaleX`/`scaleY` 是“相对原图尺寸的百分比乘数”（`dispW = 原图宽 × scaleX`，原图尺寸来自 `TextureLoader.getTextureSize`）；`SubtitleLayer` 同构（x/y 屏幕百分比 + 文字块中心、scale 百分比乘数，另多一级 `fontScale` 矩阵缩放：`pose.scale(fontScale * scaleX, fontScale * scaleY, 0)`）；`PipLayer` 有 `anchorX`/`anchorY`（默认 0.5，按 `x - width * anchorX` 定位），但坐标用的是**原始像素**。
> → 方向与现状一致，要做的是**统一并补齐“取材 / 适配”两项**（现状 OVERLAY schema 确无取材 / 适配 / 锚点字段，见 `script/schema/TrackSchemas.java` 的 `overlay()`）。

---

## 4. 设备无关性（方向·核心）

要求：**不同设备、不同分辨率、不同宽高比下，构图一致**。全部用百分比是必要条件，但不充分——画布本身的宽高比会变。

剪辑软件的做法（参考 `example/editor/olive` 的 Transform 节点，源码 `app/node/distort/transform/transformdistortnode.{h,cpp}`）：序列有**固定分辨率/宽高比**（`class Sequence : public ViewerOutput`；分辨率来自 `ViewerOutput::GetVideoParams()` 返回的 `VideoParams`，含 `resolution()` / `pixel_aspect_ratio()`），变换先按序列归一化（`AdjustMatrixByResolutions()` 里 `adjusted_matrix.scale(2.0 / sequence_res.x(), 2.0 / sequence_res.y(), 1.0)` 把序列映射成 [-1,1] 方块），再适配到实际输出。

方向：**定义参考画布**（一个固定宽高比 + 归一化坐标），所有参数相对它表达；实际屏幕按适配规则映射。这样“同一套参数在任何设备上都是同一个构图”。

适配规则沿用剪辑软件的三档（olive `TransformDistortNode::AutoScaleType`：`kAutoScaleFit` / `kAutoScaleFill` / `kAutoScaleStretch`，UI 下拉项 `None / Fit / Fill / Stretch`）：**Fit**（完整放得下，可能留边）/ **Fill**（铺满，裁掉溢出）/ **Stretch**（直接拉伸）。

---

## 5. 编辑器（方向）

编辑器的主形态是**参数编辑 + 脚本生成**（见[编辑器 WebUI 迁移](./editor-webui-migration.md)）：本节这些参数在编辑器里首先是**可填写的字段**。

**可选增强**：参考剪辑软件的 Gizmo——在预览画布上直接拖拽 / 缩放 / 裁切每个覆盖层，与参数面板双向同步。有它更省事，没有它也要能完整产出脚本。

（现有 `WebPreviewScreen` 的帧推流通道可作为预览画布的基础；具体形态执行时定。）

---

## 6. 与现有 pip 层的关系（方向）

现有 `pip` 层是“静态占位框（2px 白框 + 半透明黑填充，无纹理）+ 原始像素坐标”。方向：**并入本文模型**（百分比 + 锚点 + 取材 + 适配），或者由画面层取代——画面层本身就可以放在任意位置，天然是画中画。

---

## 7. 可能的问题

- 参考画布与实际设备宽高比不一致时的裁切 / 留黑策略。
- **画面层在 z 序里的基线**：它应该在 letterbox **之下**（现在 letterbox 的 z=0 已是最小，需要给画面层留更低的基线）。
- 素材没有内在尺寸时（纯色遮罩、文本），“取材”的语义。
- 取材超出素材边界时的行为（钳制 / 黑 / 循环）。
- 旋转是否纳入：不纳入的话锚点的意义减半，但第一版可以只服务缩放。
- 每类的默认参数要能“什么都不写就是合理形态”。
- 性能：小尺寸画面是否按显示尺寸渲染（现计划写的是“正式合成不降分辨率”）。

---

## 8. 待定

- 参考宽高比的取值与声明位置。
- 参数的具体字段名与轨道归属。
- 取材的边界语义。
- 与 HUD / letterbox 的叠放顺序细节。
- pip 层是被取代还是升级。

---

## 9. 落地顺序（方向）

1. 定义覆盖层统一参数与“基准尺寸”表
2. 定义参考画布与设备适配规则
3. 画面层接入（多相机 lane 作为覆盖层的一类）
4. 现有 overlay 层按统一参数对齐（含 pip 去像素化）
5. 取材 / 适配补齐到所有类别
6. 编辑器 Gizmo

---

## 10. 相关文档

- [画面合成](./camera-composition.md)
- [多相机渲染](./multi-camera-rendering.md)
- [并行播放](./parallel-playback.md)
- [脚本模型](./script-model.md)
- [Overlay 颜色遮罩](./overlay-color-mask.md)

---

## 事实核查（2026-10-07）

> 依据：本仓库 `common/src/main/java/com/immersivecinematics/immersive_cinematics/`（overlay / script / webui）、`example/editor/olive/app/`（Transform / Crop 节点、shader、renderer）、以及 `plans/0.3.6/` 内已存在的旁证文档。每条给到文件与符号。

### ① 核实为真

1. **§2**“`overlay/` 下已有 Letterbox / Fade / Image / Subtitle / Pip 五类实现”——`overlay/` 下 `LetterboxLayer.java`、`FadeLayer.java`、`ImageLayer.java`、`SubtitleLayer.java`、`PipLayer.java` 均存在且 `implements OverlayLayer`；`OverlayLayer.java` 为接口。
2. **§3**“`ImageLayer` 的 `x`/`y` 已是屏幕百分比、元素中心”——`ImageLayer.render()`：`float actualX = x * screenWidth - dispW / 2f; float actualY = y * screenHeight - dispH / 2f;`；字段注释“屏幕百分比位置（0~1，元素中心）”。
3. **§3**“`scaleX`/`scaleY` 是相对原图尺寸的百分比乘数”——`ImageLayer.render()`：`float dispW = texSize[0] * scaleX; float dispH = texSize[1] * scaleY;`，`texSize` 来自 `TextureLoader.getTextureSize(fileName)`；`setScale()` 注释“1 = 原尺寸”。
4. **§3**“`SubtitleLayer` 同构”——`SubtitleLayer.render()`：`blockX = x * screenWidth - (maxLineWidth * fontScale * scaleX) / 2f;`（x/y 屏幕百分比 + 文字块中心、scale 百分比乘数）。
5. **§3**“`PipLayer` 有 `anchorX`/`anchorY`，但坐标用的是原始像素”——`PipLayer.render()`：`float actualX = x - width * anchorX; int ix = (int) actualX; guiGraphics.fill(ix, iy, ix + iw, iy + ih, FILL_COLOR);`，x/y/width/height 直接当像素用、不乘屏幕尺寸；`setAnchor(anchorX, anchorY)`，字段默认 `0.5f`。
6. **§4**“序列有固定分辨率/宽高比”——`example/editor/olive/app/node/project/sequence/sequence.h:32` `class Sequence : public ViewerOutput`；`app/node/output/viewer/viewer.h:64` `VideoParams GetVideoParams(int index = 0) const`、`:99` `void SetVideoParams(...)`、`:197` `static const QString kVideoParamsInput`（`viewer.cpp:29` 值为 `"video_param_in"`）；`app/render/videoparams.h:87` `QVector2D resolution()`、`:92` `square_resolution()`、`:182` `pixel_aspect_ratio()`。
7. **§4**“`scale(2.0 / sequence_res)` 把序列映射成 [-1,1] 方块”——`app/node/distort/transform/transformdistortnode.cpp:296-297`：注释 `// Scale it to a square based on the sequence's resolution` + `adjusted_matrix.scale(2.0 / sequence_res.x(), 2.0 / sequence_res.y(), 1.0);`。佐证 [-1,1]：`app/render/opengl/openglrenderer.cpp:34-37` 的 `blit_vertices` 即 `(-1,-1) (1,-1) (1,1) …` 的 NDC 方块，`app/shaders/default.vert:9` `gl_Position = ove_mvpmat * a_position;`；矩阵链为 `scale(2/seq_res) · translate(offset) · mat · scale(tex_res*0.5)`（Qt 后乘、最外层最后作用于向量）→ 序列像素空间被映到 [-1,1] 方块。
8. **§4**“Fit / Fill / Stretch 三档”——`transformdistortnode.h:62-66` `enum AutoScaleType { kAutoScaleNone, kAutoScaleFit, kAutoScaleFill, kAutoScaleStretch };`；`transformdistortnode.cpp:80` `SetComboBoxStrings(kAutoscaleInput, {tr("None"), tr("Fit"), tr("Fill"), tr("Stretch")});`；分支 `:309-333`（Stretch 用 `scale(sequence_res/texture_res)`，Fit/Fill 用 `scale_by_x`/`scale_by_y` 单一系数）。
9. **§4**“取材四边 0~1 归一化（crop 节点 `CreateCropSideInput`）”——`app/node/distort/crop/cropdistortnode.cpp:152-158` `CreateCropSideInput()`：`AddInput(id, NodeValue::kFloat, 0.0)` + `SetInputProperty(id, "min", 0.0)` + `"max", 1.0` + `"view", FloatSlider::kPercentage`；`UpdateGizmoPositions` `:109-112` `left_pt = resolution.x() * row[kLeftInput].toDouble()`（边值 × 分辨率）；shader `app/shaders/crop.frag` 拿 `ove_texcoord`（0~1）与 `left_in` / `1.0-right_in` / `top_in` / `1.0-bottom_in` 比较。
10. **§5**“现有 `WebPreviewScreen` 的帧推流通道”——`common/.../webui/WebPreviewScreen.java`（`class WebPreviewScreen extends Screen`，类注释“画面传输（把游戏画面编码后通过 WebSocket 发给前端）”，`render` 中 `WebFrameCapture.capture(minecraft); WebFrameStreamer.onFrame();`）；同目录另有 `WebFrameCapture.java` / `WebFrameStreamer.java` / `WebSocketSession.java`。
11. **§7**“现在 letterbox 的 z=0 已是最小”——`LetterboxLayer.java`：`private static final int Z_INDEX = 0;` 且 `getZIndex()` 返回该常量、无 `setZIndex`；`OverlayManager.java:74` `layers.sort(Comparator.comparingInt(OverlayLayer::getZIndex))`、`:14` 注释“zIndex 越小越先绘制（底层）”。
12. **§7**“现计划写的是‘正式合成不降分辨率’”——`plans/0.3.6/multi-camera-rendering.md:98`、`:127` 与 `plans/0.3.6/render-routes.md:21-22`（跨文档引用，未修改他人文档）。
13. **§1**“多相机原型验证通过之后”——`plans/0.3.6/quadrant-prototype-results.md:3` `**状态**: ✅ 验收通过（2026-10-05）`。

### ② 已修正

1. **§2 / §6 把 Pip 说成“静态贴图”**：旧说法“Pip 是‘静态贴图’形态” / “现有 `pip` 层是‘静态贴图 + 原始像素坐标’” → 新事实：`PipLayer` **没有任何纹理**，只画 `FILL_COLOR = 0x40000000` 半透明黑填充 + `BORDER_COLOR = 0xFFFFFFFF` 2px 白框（`BORDER_WIDTH = 2`），类注释“Phase 1：仅渲染白色边框和半透明黑色填充，不包含实际摄像头画面”；字段只有 `x/y/width/height/anchorX/anchorY/opacity/zIndex`。已改为“静态占位框（2px 白框 + 半透明黑填充，无纹理）”，保留原意与 §6 去留方向。
   - **冲突裁决**：`plans/0.3.6/variable-frame.md` 最后修改 `2026-10-06T21:57:01+08:00`；`common/.../overlay/PipLayer.java` 最后修改 `2026-07-27T16:39:22+08:00`（`git log` 仅一条提交，自 0.3.4 起未变）。文档时间戳较晚，但该断言是对 PipLayer **现行实现**的描述、源码未变，故以源码为准修正措辞。

### ③ 补全的信息

1. **§3 文本层基准尺寸**：`SubtitleLayer` 除 `x`/`y`/`scale_x`/`scale_y` 外还有一级 `fontScale`（注释“字号倍数（1.0 = 原版 9px，矩阵缩放实现，同 MC title 机制）”，render 中 `pose.scale(fontScale * scaleX, fontScale * scaleY, 0f)`）——即“基准字号下的文字块”= `fontScale = 1` 时的块尺寸。
2. **§3 现状字段来源与缺口**：`script/OverlayTrackPlayer.java` 的 `applyInitialClipValues()` / `updateLayer()` 从关键帧读 `x`/`y`/`scale_x`/`scale_y`（image/subtitle）与 `x`/`y`/`width`/`height`/`anchor_x`/`anchor_y`（pip），缺省值分别为 `0/1` 与 `0/0/0.5/0.5`；`script/schema/TrackSchemas.java:143-158` 的 `overlay()` schema 字段为 `layer_type` / `color` / `path` / `text` / `z_index` / `interpolation`（clip）与 `opacity` / `x` / `y` / `font_scale` / `scale_x` / `scale_y`（关键帧）——**无取材 / 适配 / 锚点字段**，pip 的 `anchor_x/anchor_y` 也不在 schema 中（播放器按默认 0.5 读）。佐证 §3“要补齐取材 / 适配”。
3. **§7“letterbox 的 z=0 已是最小”的边界**：`z_index` 是脚本可写 `int`（schema 默认 `20`，`OverlayTrackPlayer` 回退默认 `10`），`LetterboxLayer` 的 `0` 是硬编码且不可脚本调整——脚本写负值即可排到 letterbox 之下。故“0 是最小”只在**内置默认值**层面成立，不是全局钳制；给画面层留更低基线时需一并决定 `z_index` 是否允许负值。
4. **§4 olive 侧文件路径补齐**：Transform 节点 `app/node/distort/transform/transformdistortnode.{h,cpp}`；Crop 节点 `app/node/distort/crop/cropdistortnode.{h,cpp}`；crop shader `app/shaders/crop.frag`；默认顶点 quad / 着色器 `app/render/opengl/openglrenderer.cpp`（`blit_vertices`）+ `app/shaders/default.vert`；Gizmo 证据 = Transform 节点的 `point_gizmo_` / `poly_gizmo_` / `rotation_gizmo_`（缩放 / 旋转）与 Crop 节点的 `poly_gizmo_` / `point_gizmo_`（拖边裁切）。
5. **§2 五类之外的实现**：`overlay/` 还有 `CinematicOverlay.java` 与 `OverlayManager.java`（调度器：按 zIndex 升序渲染，`addLayer` 后自动重排）。

### ④ 无法核实（未验证）

1. §2 分类表“画面层 = 相机 lane 的输出纹理 / 每条 CAMERA 轨或每个相机一条”、§3 参数表字段名、§5 Gizmo 与参数面板双向同步、§8 待定项、§9 落地顺序——均为**方向 / 待定**，无对应现状实现可核。
2. §7“小尺寸画面是否按显示尺寸渲染”属取舍问题，非事实断言；其引用的“正式合成不降分辨率”已在 ①.12 核实为真。
3. olive 侧“变换先按序列归一化”的完整语义链（`MatrixGenerator::GenerateMatrix` 生成矩阵的坐标系约定）未逐行追到基类实现，本核查只验证到 `AdjustMatrixByResolutions` 的缩放/适配与 NDC 顶点约定（见 ①.7）。
