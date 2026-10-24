# 完整颜色遮罩

> 状态：已实现（2026-10-17）
> 范围：OVERLAY 轨 `layer_type=fade` 的颜色层能力——纯色、线性/径向多色渐变、矩形/圆形局部覆盖、五种混合模式。
> 关联：[画面转场](scene-transition.md) · [可变画面](variable-frame.md) · [画面颜色调整](../../complete/0.3.6/A-color/screen-color-adjust.md) · [脚本格式 §9](../../../docs/SCRIPT_FORMAT.md)

## 一、摘要

颜色遮罩是在游戏画面之上盖一层可动画的颜色：整窗纯色、局部矩形/圆形，或多色线性/径向渐变，并按普通、正片叠底、屏幕、柔光、叠加五种方式与下方画面混合。它沿用 OVERLAY 轨既有的 fade 图层，不新增图层类型或轨道；纯色全屏且普通混合时保持旧的全屏填充行为，其余形态由专用渲染承担。当前能力已全部落地，WebUI 配置面板尚未提供。

## 二、为什么做

### 2.1 问题

- 基础 fade 只能铺满整窗的单一颜色，做不出渐变、局部压暗或只盖一角的遮罩。
- 半透明纯色在复杂画面上对比弱，作者难以判断是否生效。
- 需要把颜色层与下方画面混合（如正片叠底压暗）时，没有可用的混合参数。
- 颜色层与调色容易混为一谈：调色改已有画面，遮罩是往上盖一层颜色，两者需要明确分工。

### 2.2 目标

- 全屏颜色遮罩、透明度关键帧、层级分层继续保留。
- 同一片段可声明纯色或至少两段的多色渐变，支持线性与径向，渐变方向、中心、半径可动画。
- 覆盖范围可限制为矩形或圆形，中心与尺寸可指定并动画，区域外像素完全不变。
- 可在普通、正片叠底、屏幕、柔光、叠加五种混合模式中选择，混合只作用于颜色层与下方已画好的画面。
- 纯色全屏普通混合的旧脚本行为不变，不引入兼容分支。
- 高对比层级演示能一眼看出层序：不同颜色、足够不透明度、不同位置与层级，并带说明文字。

### 2.3 非目标

- 不新增图层类型、轨道或转场机制；仍是既有的 fade 图层。
- 不做逐帧改变色标数量或顺序的拓扑动画；色标在片段级固定，关键帧只驱动几何。
- 不对图片或字幕做裁切；局部遮罩只定义颜色覆盖范围。
- 不接管调色（RGB / HSL、LUT、曝光等），那属于画面颜色调整。
- 不提供 WebUI 配置面板（编辑器阶段）。

## 三、方案

在覆盖层轨道写一个颜色片段，指定颜色或不指定（缺省黑色），整窗铺满；不透明度用关键帧控制，就是最基础的淡入淡出或黑场。

声明渐变后，片段按多色色标铺出线性或径向渐变。色标只需给出位置与颜色，至少两段，首尾不必落在 0/1；超出首尾的区域保持端点颜色。渐变的方向角、中心、半径都可以打关键帧。

声明覆盖形状后，颜色只出现在矩形或圆形区域内，区域外完全透明；中心与宽高、半径可动画。圆形在任何窗口比例下都是真圆，零尺寸等于不显示。

选择混合模式后，颜色层与它下方已经画好的画面按该模式混合；普通模式就是直接盖上去。混合只改 RGB，不改整幅画面的 alpha，也不影响之后绘制的界面。

纯色、整窗、普通模式保持旧的全屏填充行为，观感与旧版一致；只有声明了渐变、局部形状或非普通混合时才启用专用渲染。

层级：片段显式写层级值，数值越大越靠上；不写时是 10。同一条覆盖层轨道同一时刻只显示第一个活跃片段，要同时叠多层请分轨。

边界：字段有严格层级——渐变、形状、混合模式写在片段级；几何与颜色动画写在关键帧级；写错层级会被拒绝。

## 四、技术细节

### 4.1 核心模型

- 颜色层（`FadeLayer`）：持有颜色、渐变、覆盖形状、混合模式与不透明度；纯色、整窗、普通混合走全屏填充，其余形态交给专用渲染器。
- 颜色遮罩定义（`ColorMaskSpec`）：解析期从片段建成的不可变快照，持有基色、渐变类型与色标、覆盖形状、混合模式、关键帧颜色缓存；运行时只读。
- 专用渲染器（`ColorMaskRenderer`）：按定义绘制渐变、局部形状与非普通混合，负责自己的 GPU 资源。
- 播放器（`OverlayTrackPlayer`）：建层时取定义，逐帧按定义能力采样几何与颜色并送入层。
- 管理器（`OverlayManager`）：按层级升序调度颜色层与其他覆盖层。

### 4.2 契约

- 定义在解析期一次建成；非法结构在解析或校验期拒绝，并带字段路径；运行时不再解析字符串。
- 颜色层由播放侧在片段切换时创建，定义在建层时绑定一次；层销毁时释放其渲染资源。
- 播放器只送定义实际消费的通道：整窗不读遮罩几何，矩形读中心与宽高，圆形读中心与半径；无渐变不读渐变几何。
- 专用渲染器只影响本层：区域外像素、后续 GUI 与主画面 alpha 不受影响。
- 每个颜色层持有自己的渲染器；没有渐变就不创建渐变资源。

### 4.3 数据形态

片段级：`color`（`#RRGGBB`，缺省 `#000000`）、`gradient`（`{type, stops}`，可选）、`mask_shape`（`full|rect|circle`，缺省 `full`）、`blend_mode`（`normal|multiply|screen|soft_light|overlay`，缺省 `normal`）、`z_index`（int，缺省 10）。

关键帧级：`opacity`（0~1，缺省 1）、`color`（可选，缺省回落片段级颜色）、`mask_x` / `mask_y`（窗口比例，缺省 0.5，可越界）、`mask_width` / `mask_height`（窗口比例，缺省 1，非负，零不绘制）、`mask_radius`（窗口短边比例，缺省 0.5，非负，零不绘制）、`gradient_angle`（度，缺省 0，有限）、`gradient_center_x` / `gradient_center_y`（窗口比例，缺省 0.5，有限）、`gradient_radius`（短边比例，缺省 0.5，必须大于 0）。

- 渐变色标至少 2 个，offset 有限且 0~1、严格递增；颜色为 `#RRGGBB`；不设数量上限；首尾不必是 0/1。
- 覆盖形状按窗口像素判定，硬边：矩形用中心与半宽半高，圆形用中心与短边半径。
- 关键帧 `color` 只服务纯色模式，按 RGB 通道线性插值；渐变模式颜色来自色标。

### 4.4 边界

- `image` / `subtitle` 不接受 `gradient` / `mask_shape` / `blend_mode` / `mask_*` / `gradient_*`；字幕不做裁切。
- CAMERA 片段不接受 fade 的 `gradient` / `gradient_*` / `blend_mode`；CAMERA 的同名 `mask_shape` / `mask_*` 是 lane 合成遮罩，作用对象是画面 RGB。
- 规则动作 `overlay_set` 的临时 fade 只支持 `layer_type` / `color` / `opacity` / `z_index`，不扩高级字段。
- 调色归 ADJUST 轨与画面颜色调整；fade 的混合模式只作用于颜色层。
- 层级只在模组覆盖层之间排序，不改原版 HUD。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 新增图层类型 | 为渐变、遮罩各建一种图层 | 字段与校验双份，旧脚本要迁移 | 扩展既有颜色图层即可覆盖全部形态 |
| 只用固定 GL 混合因子 | 靠混合因子组合实现混合模式 | 柔光、叠加需要读取下方画面，固定因子做不到 | 必须用专用着色器采样背景 |
| 用 scissor 做局部矩形 | 裁剪绘制区域 | 只能矩形，圆形与渐变做不到，且与 GUI 批次状态耦合 | 着色器内按几何判定覆盖范围 |
| 用调色轨实现颜色覆盖 | 把颜色当调整层 | 调色改已有画面，无法只盖局部，也无法表达渐变叠加 | 遮罩与调色职责不同 |
| 每帧解析颜色、重建渐变纹理 | 运行时读字符串、逐帧建纹理 | 逐帧分配与解析开销 | 解析期缓存，渐变资源每层只建一次 |
| 渐变做成逐帧拓扑动画 | 关键帧改变色标数量与顺序 | 结构可变，校验与编辑器复杂，收益低 | 色标固定，关键帧只驱动几何 |

**本方案的缺点**：

- 渐变采样精度对极窄或密集色标不保证逐像素等于解析式，未声明误差上限。
- 高级路径每层持有渲染器与渐变资源，层数多时 GPU 资源与状态切换成本上升。
- 非普通混合需要读取下方画面，全屏混合有额外带宽开销。
- 局部形状只有硬边矩形与圆形，没有羽化、圆角、斜线。
- 色标固定在片段级，不能逐帧改变数量。
- 混合模式与调色轨的同名字段语义对象不同，作者容易混淆。

## 六、实现结果

状态：已实现（2026-10-17）

渐变、五种混合、矩形/圆形局部覆盖均按设计落地；沿用既有 fade，未新增图层类型或轨道，纯色全屏普通混合保持原行为。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 扩展既有 fade，支持渐变 / 五种混合 / 矩形圆形局部覆盖 | 与设计一致 | — |
| 纯色全屏普通混合保持原行为 | 与设计一致 | — |
| 高对比层级演示（颜色、不透明度、位置、层级、说明文字） | 与设计一致 | — |

无偏离。设计未指定、由实现补齐的细节：渐变采样用 1024×1 LUT；矩形用窗口比例、圆形用短边比例；混合按 W3C 口径并与调色 shader 对齐。验证记录：数据合同探针 278 项、`ic_color_mask` GL 像素探针 334 项、生产渲染器 129 项通过；Fabric 实机三色渐变与正片叠底像素与独立公式一致，F3+T 重载后渐变资源按代次重建。Forge 仅编译，Iris / Oculus 未实机；编辑器面板未实现。详见[执行记录](color-mask-execution.md)。

## 附录：重写前原文

### 0.3.6 计划：完整颜色遮罩（Color Mask / Full Overlay）

> **2026-10-17 实现落点**：D4–D7 扩展既有 `layer_type: "fade"`，包含线性 / 径向多色渐变、五种混合模式、矩形 / 圆形局部覆盖和高对比层级演示。字段合同与本轮验收见 [执行记录](color-mask-execution.md)，对外格式见 `docs/SCRIPT_FORMAT.md` §9。D1–D3 已另行收口：纯显式 clip / 关键帧（C 方案），转场 fade 的显式 z 序见 [scene-transition.md](scene-transition.md) §3.3。以下背景及末尾事实核查保留为历史快照，不代表当前缺口。
>
> **当前状态（2026-10-18 回写）**：D 轮已关单（2026-10-17）——D4–D7 全部实现并验收，§2.2 三项能力均已落地，无遗留待办。仍开放的只有 WebUI 配置面板（归编辑器阶段）；Forge / Iris / Oculus 未实机是验收边界，不是未完成项。

#### 1. 背景

0.3.5 已实现基础的全屏遮罩能力：

- `FadeLayer`：渲染全屏 ARGB 矩形
- 脚本写法（`layer_type` / `color` 为 clip 级字段，`opacity` 为关键帧级字段）：
  ```json
  {
    "layer_type": "fade",
    "color": "#000000",
    "keyframes": [ { "time": 0, "opacity": 0.4 } ]
  }
  ```
- 黑色全屏遮罩在测试中可观察到，效果正常（`cinematics/tests/overlay/test_overlay_fade.json` 存在；视觉效果未在本次核查中运行验证）。

但在 `test_overlay_zindex` 中放置的红色半透明遮罩（`#FF0000` + `opacity 0.4`）没有明显看出来。

结论：

- 基础 fade 保留在 0.3.5
- **完整/更高级的颜色遮罩放到 0.3.6 做**

#### 2. 0.3.6 目标

构建一套"完整颜色遮罩"能力，至少覆盖：

> **底层工具只有一个（已确认·2026-10-07）：纯色覆盖层**（`FadeLayer`：颜色 + 不透明度，全部可关键帧）——"盖一层任意颜色的像素"。淡入淡出、黑场、白场、颜色遮罩都是对它的参数化应用。**应用形态**：黑场 / 白场 / 颜色遮罩是 **OVERLAY 轨上已调好参数的 clip**（`layer_type=fade` + color / opacity 关键帧），由轨道追踪——作者只需决定"放哪、多长"（转场场景见[画面转场](scene-transition.md)；这类 clip 也可由[模板](../E-template/templates.md)片段级模板一键生成）。本节下面列的都是这个工具的增强方向。

##### 2.1 基础增强
- 全屏颜色遮罩（已有，继续保留）
- 透明度关键帧控制（已有）
- z_index 分层（已有；D3 定稿为**显式** z 序：普通 fade 缺省 10、转场 fade 显式 1000、转场示例字幕 100；需要同时显示的 OVERLAY 层各自独立轨，不自动置顶，也不改原版 HUD）

##### 2.2 新增能力（已定型并实现）
- **渐变色遮罩**
  - 线性渐变 / 径向渐变
  - 多色 stops
- **混合模式**
  - 普通 / 正片叠底 / 屏幕 / 柔光 / 叠加等
- **局部遮罩**
  - 矩形 / 圆形区域
  - 可指定 x/y 与尺寸
  - 注：这是 **fade 颜色层**的覆盖范围；CAMERA lane 侧另有 clip 级 `mask_shape` full / rect / circle（D2，见 `scene-transition.md` §3.5）。image / subtitle 不裁切。
- ~~颜色调色/滤镜~~ → **已移交 `screen-color-adjust.md`**：RGBA 通道拆分、完整 HSL、亮度/对比度等“改已有画面颜色”的能力归该文档；本文只管“往上盖一层颜色”（遮罩）。

##### 2.3 测试可视化
- 重做 `test_overlay_zindex`
- 要求一眼能看出层级：
  - 高对比色（如纯红 / 纯蓝 / 纯黄）
  - 足够不透明度
  - 不同位置 + 不同 z_index
  - 添加说明文字层

#### 3. 涉及文件

- `common/.../overlay/FadeLayer.java`
- `common/.../overlay/OverlayManager.java`
- `common/.../overlay/OverlayLayer.java`
- `common/.../script/OverlayTrackPlayer.java`
- 脚本格式文档：
  - `docs/SCRIPT_FORMAT.md`
  - `docs/AI_SCRIPTING_GUIDE.md`
- 测试脚本：
  - `cinematics/tests/overlay/test_overlay_zindex.json`

#### 4. 本轮范围裁决

- 渐变、混合与局部覆盖均纳入 D4–D6；D7 同步重做层级演示。
- 扩展现有 `layer_type: "fade"`，不新建图层类型、轨道或转场机制。默认全屏纯色保持原行为，不引入格式兼容分支。
- 字幕完整呈现，不做取材裁切；局部遮罩只定义颜色覆盖范围。
- WebUI 配置面板留在编辑器阶段，本轮只提供运行时数据工具；D1–D3 已于 2026-10-17 另行收口（纯显式 clip / 关键帧，见 `scene-transition.md`）。

##### 4.1 旧待定项销项（2026-10-17）

| 旧待定 | 结论 |
|---|---|
| 渐变是否做（D4） | 已做：linear / radial + 多 stops，首尾可非 0 / 1 |
| 混合模式是否做 + 优先级（D5） | 已做五种；层按当前 `z_index` 升序顺次合成 |
| 局部遮罩是否必须（D6） | 已做：rect / circle，窗口比例几何，真圆 |
| 字段兼容形态（扩展 `fade` vs 新增类型） | 扩展既有 `layer_type=fade`，不新增图层类型 / 轨道 |
| 与 `screen-color-adjust` 的 `blend_mode` 边界 | 本文 = 往上盖一层颜色（上层来源是颜色遮罩）；调色归 `screen-color-adjust.md`，两者不共用语义 |
| WebUI 面板 | 仍开放，归编辑器阶段（不是 D 待办） |

#### 5. 验收标准

- [x] 颜色遮罩能明显显示，不再依赖“仔细看”
- [x] z_index 层级一眼可辨
- [x] 支持纯色、渐变、局部区域三种形态
- [x] 正式测试脚本校验通过，数据 / GL / 真实生产渲染器探针与实机像素验收通过
- [x] 文档同步更新；详细结果与未测边界见 `color-mask-execution.md`

---

#### 事实核查（2026-10-07）

核查范围：`overlay/FadeLayer.java`、`overlay/OverlayManager.java`、`overlay/OverlayLayer.java`、`script/OverlayTrackPlayer.java`、`script/ScriptValidator.java`、`script/schema/TrackSchemas.java`、`docs/SCRIPT_FORMAT.md`、`docs/AI_SCRIPTING_GUIDE.md`、`cinematics/tests/overlay/*.json`。当前 `gradle.properties` `version=0.3.5`。

##### ① 核实为真的断言

- **§1「FadeLayer 渲染全屏 ARGB 矩形」**：`overlay/FadeLayer.java` `render(GuiGraphics, int, int)` 计算 `int argb = (alpha << 24) | (color & 0x00FFFFFF)`，调用 `guiGraphics.fill(0, 0, screenWidth, screenHeight, argb)`（全屏填充）。✅
- **§1「脚本字段 layer_type: fade + color」**：`script/OverlayTrackPlayer.java` `createLayer(Clip)` 读取 `clip.getString("layer_type", "fade")`、`clip.getString("color", "#000000")`；`ScriptValidator.checkEnum(... "layer_type", ..., "fade", "image", "subtitle", "pip")`；`schema/TrackSchemas.overlay()` 中 `clips.put("layer_type", enum 默认 "fade")`、`clips.put("color", string 默认 "#000000")`。✅（`opacity` 的层级见 ②）
- **§1「z_index 分层存在」**：`overlay/OverlayManager.java` `addLayer(OverlayLayer)` 执行 `layers.sort(Comparator.comparingInt(OverlayLayer::getZIndex))`；`render(...)` 按列表顺序（升序）绘制，注释「zIndex 越小越先绘制（底层），越大越后绘制（顶层）」。`FadeLayer.getZIndex()` 返回自身 `zIndex`。✅
- **§2.1「透明度关键帧控制（已有）」**：`script/OverlayTrackPlayer.java` `onRenderFrame(float)` 从 `clip.getKeyframes()` 用 `interpolateFloat(kfs, localTime, "opacity", 0f, smooth)` 取值，`updateLayer(...)` 对 `FadeLayer` 调用 `fl.setOpacity(opacity)`；`schema/TrackSchemas.overlay()` 将 `opacity` 定义在关键帧字段表（`kfs.put("opacity", ...)`）。✅
- **§3 涉及文件全部存在、路径正确**：`common/src/main/java/com/immersivecinematics/immersive_cinematics/overlay/FadeLayer.java`、`.../overlay/OverlayManager.java`、`.../overlay/OverlayLayer.java`、`.../script/OverlayTrackPlayer.java`、`docs/SCRIPT_FORMAT.md`、`docs/AI_SCRIPTING_GUIDE.md`、`cinematics/tests/overlay/test_overlay_zindex.json` 均存在。✅
- **§1「红色半透明遮罩结论属 0.3.5 时代记录」**：`cinematics/tests/overlay/test_overlay_zindex.json` 最后修改 `2026-08-30T20:22:20+08:00`（commit "feat: Forge/Fabric HUD 白名单工具…"）；`overlay/FadeLayer.java` 最后修改 `2026-08-18T22:58:38+08:00`；`script/OverlayTrackPlayer.java` 最后修改 `2026-08-20T13:12:47+08:00`。均落在 `version=0.3.5` 开发期，确为 0.3.5 时代结论。✅

##### ② 已修正的断言

- **§1 脚本示例把 `opacity` 写成 clip 级字段（旧）→ `opacity` 是关键帧级字段（新）**。
  - 证据：`OverlayTrackPlayer.onRenderFrame` 只从 `clip.getKeyframes()` 插值 `opacity`；`updateLayer` 仅在关键帧路径下 `setOpacity`；clip 级 `opacity` 无任何读取点（`applyInitialClipValues` 不读 opacity）。`docs/AI_SCRIPTING_GUIDE.md` 第 203、214 行明示「没有 clip 级简写」「淡入淡出 = 关键帧里写 opacity 0→1→0」。
  - 修正：§1 示例已改为 `"keyframes": [ { "time": 0, "opacity": 0.4 } ]`，并注明字段层级。

##### ③ 补全的信息

- **§1 fade 无 ScriptParser 专用解析分支**：`script/ScriptParser.java` 无 `fade`/`layer_type`/`opacity` 相关匹配；字段读取为通用的 `Clip.getString/getInt`，合法性由 `ScriptValidator.checkEnum` 与 `schema/TrackSchemas.overlay()` 约束。原文「ScriptParser 的 fade 解析」若被理解为独立解析逻辑则不成立。
- **`z_index` 默认值不一致（源码内部，2026-10-07 历史记录）**：当时 `OverlayTrackPlayer.createLayer` 用 `clip.getInt("z_index", 10)`（默认 10）；`schema/TrackSchemas.overlay()` 的 FieldDef 默认值为 20；`docs/SCRIPT_FORMAT.md` 第 450 行文档默认值亦为 20。**现行（2026-10-17）**：运行时与 schema 缺省统一为 **10**；D3 定稿为显式 z 序（普通 fade 10 / 转场 fade 1000 / 转场示例字幕 100）。
- **FadeLayer 颜色解析**：`setColor(String)` 只解析 `#RRGGBB`（`Integer.parseInt(clean,16) & 0x00FFFFFF`），alpha 完全由 `targetOpacity` 决定；非法颜色 WARN 后回退黑色。故 `#FF0000` + 关键帧 opacity 0.4 → ARGB `0x66FF0000`，颜色通道本身正确。

##### ④ 无法核实的断言（未验证）

- **§1「黑色全屏遮罩在测试中可观察到，效果正常」**：测试脚本 `test_overlay_fade.json` 存在，但本次核查未运行游戏客户端，视觉效果**未验证**。
- **§1「`test_overlay_zindex` 中红色半透明遮罩没有明显看出来」**：脚本内容确为 `"layer_type":"fade","color":"#FF0000","z_index":20` + 关键帧 opacity 0→0.4→0.4→0；但「不明显」为主观观察结论，源码无法证实，**未验证**（记录于 0.3.5 时代）。

##### 跨文档冲突（只报不改）

- `plans/0.3.6/camera-composition.md` 第 124 行称「pip 的 opacity 不生效：`overlay/PipLayer` 算出的 `fillArgb`/`borderArgb` 从未使用（直接用了常量）」。本文档未对 pip 的 opacity 作任何断言，两者不构成直接冲突；仅记录该断言存在（未在本次核查中验证 PipLayer 源码）。
