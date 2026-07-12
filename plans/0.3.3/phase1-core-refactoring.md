# 阶段 1 — 核心重构

**目标版本**: 0.3.3  
**状态**: 待实施  
**前置依赖**: 阶段 0 完成（不改行为，先清场）

---

## ② Timeline Widget 化 ★

**文件**: 新增 `editor/widget/UIClipWidget.java`、`UIKeyframeDiamond.java`、`UITransitionZone.java`；修改 `editor/area/TimelineArea.java`

### 问题

`TimelineArea.drawClip()` 是一个约 70 行的巨石方法，囊括了三种不同类型的渲染逻辑：
- clip 主体的矩形背景、轮廓、选中高亮、尺寸标签
- 关键帧菱形的绘制、选中状态高亮
- 过渡区域（morph）的紫色矩形 + 时长标签

这三种逻辑共享同一块画布坐标，但彼此之间没有清晰的边界。后续要支持多轨道差异化渲染（AUDIO 轨道的波形预览、EVENT 轨道的命令标记等），会进一步推高 `drawClip()` 的复杂度。

此外，clip 的鼠标交互（拖拽移动、边缘缩放、关键帧拖拽）散落在 `clickCanvas()` 和 `mouseReleased()` 中，与渲染逻辑分离，状态变量（`draggingClip`、`draggingKeyframe`、`draggingResizeLeft` 等）散布在类顶部。

### 修复方向

将 clip 的三种视觉元素拆为独立的 Widget 类：

- **`UIClipWidget`**：负责 clip 主体的渲染（矩形背景、边框、选中状态、尺寸标签）和交互（移动、缩放、点击选中）
- **`UIKeyframeDiamond`**：负责单个关键帧菱形的渲染和拖拽交互
- **`UITransitionZone`**：负责过渡区域的渲染和标签

每个 Widget 通过 `UIComponent` 接口参与 EditorScreen 的 UI 树渲染和事件分发。`TimelineArea.render()` 遍历 clips 列表时为每个 clip 创建/更新对应的 Widget 实例，`mouseClicked`/`mouseDragged`/`mouseReleased` 交给 Widget 各自处理。

改造后 `TimelineArea` 从手动绘制管线变为 Widget 组装层，`:only` 新增轨道类型的渲染只需添加新的 Widget 类。

---

## ③ 双向 Schema 模板

**文件**: 新增 `script/track_schema.json`；修改 `script/ScriptParser.java`、`editor/area/LeftPanelArea.java`、`editor/EditorOperations.java`

### 问题

同一组字段名在编辑器写侧和运行时读侧各自手写：

- 编辑器侧：`EditorOperations.addClip()` 中为 CAMERA keyframe 写 `position/yaw/pitch/roll/fov/zoom/dof`，为 LETTERBOX 写 `aspect_ratio`。`LeftPanelArea.fillClipDefaults()` 和 `fillKeyframeDefaults()` 中定义各轨道类型的默认值和白名单。
- 运行时侧：`ScriptParser.parseCameraKeyframe()` 和 `parseLetterboxKeyframe()` 中硬编码字段名和默认值。

两侧维护同一套字段列表，增删字段需要改两处。`track_templates.json` 的概念在 0.3.2 讨论过但只覆盖了编辑器侧写默认值。升级为双向 schema 后，一份 JSON 定义同时驱动编辑器字段白名单和 Parser 的字段校验规则。

### 修复方向

设计 `track_schema.json`，按轨道类型定义：

- 每个轨道类型的字段列表
- 每个字段的类型（float/string/boolean/object/array）
- 默认值
- 是否必填

编辑器侧的 `EditorOperations.addClip()` 和 `LeftPanelArea.fillClipDefaults()` 从此 schema 读取默认值生成初始数据。Parser 侧的 `ScriptParser` 读同一份 schema 来校验字段、填充默认值。

schema 不接管所有解析逻辑——复杂验证（如关键帧时间单调递增、曲线控制点数量检查）仍保留在 Parser 中。schema 只负责字段级别的声明式定义。

---

## ① 多轨道时间轴渲染

**文件**: `editor/area/TimelineArea.java`

### 问题

当前 `drawTracks()` 方法遍历所有轨道行（`for ti in tracks`），依次渲染每个轨道的标签和 clips。但编辑器的实际行为仅展示第 1 条 CAMERA 轨道的内容——滚动/缩放/拖拽等操作都以第 1 条轨道为准。用户无法在时间轴上看到 LETTERBOX/AUDIO/EVENT 轨道的 clips 分布。

这不是功能缺失，而是编辑器只支持单轨道编辑的暂时状态。多轨道渲染是时间轴 Widget 化的自然延伸。

### 修复方向

在 TimelineArea 中确保每个轨道行都渲染其 clips，包括当前被忽略的非 CAMERA 轨道。轨道标签区域显示轨道类型名称，clips 区域渲染每个 clip 的矩形（按轨道类型的颜色区分）。关键帧菱形仅在 CAMERA 轨道显示。

LETTERBOX 轨道的关键帧不需要显示菱形——它们在属性面板中有专门的编辑入口。AUDIO/EVENT 轨道的 clip 显示为简化矩形（不渲染关键帧）。

---

## C2 — CAMERA 关键帧缺 position 导致逐帧 NPE

**文件**: `script/ScriptParser.java`、`script/KeyframeInterpolator.java`

### 问题

`ScriptParser.parseCameraKeyframe()` 第 285 行初始化 `PositionData position = null`，仅当 JSON 中有 `"position"` 字段时才解析赋值（第 286-287 行）。如果 JSON 缺少 position 字段，创建出的 `CameraKeyframe` 对象的 position 字段为 null。

`KeyframeInterpolator.interpolatePosition()` 第 117 行直接调用 `from.getPosition().toVec3()`，在 position 为 null 时抛出 NPE。该异常发生在每渲染帧的 `CameraTrackPlayer.onRenderFrame()` → `renderSingle()` → `writeAttributes()` → `interpolatePosition()` 调用链中。异常被 `ScriptPlayer.onRenderFrame()` 的 catch 块捕获，下一帧再次抛出，造成日志无限膨胀。

### 修复方向

在 CAMERA 关键帧解析时强制 `position` 字段必填——CAMERA 轨道的关键帧必须有位置信息才能运镜。如果 JSON 中缺少 position，Parser 应抛出 `ScriptParseException`，而不是创建 null 值留到运行时爆炸。

或者，在 `KeyframeInterpolator.interpolatePosition()` 中对 null position 做兜底处理——返回起始位置或 Vec3.ZERO。但这是被动防御，主动报错（建时验证）比运行时兜底更合理。推荐前者。

---

## G1 — Overlay 异常后 GL_ALWAYS 永久泄漏

**文件**: `editor/EditorScreen.java`

### 问题

`render()` 第 615-617 行在渲染 overlay 前设置 `GL_ALWAYS` 深度测试函数，渲染后恢复为 `GL_LEQUAL`。但两者之间没有 try/finally 保护——如果 `rootComponent.renderOverlay(ctx)` 抛出异常，`depthFunc` 不会恢复。后续所有 GUI 渲染在 `GL_ALWAYS` 下进行，导致文字和 UI 元素的深度测试行为异常（下拉菜单文本被穿透、Z-fighting）。

第 619-621 行的 catch 块在异常时 `return` 跳出了方法，跳过了第 617 行的 `depthFunc` 恢复。

### 修复方向

将 `depthFunc` 的修改和恢复包裹在 `try { ... } finally { ... }` 中，确保无论 `renderOverlay()` 是否抛异常，`depthFunc` 都被恢复为 `GL_LEQUAL`。

---

## G2 — PreviewCapture 修改 GL 状态不恢复

**文件**: `editor/PreviewCapture.java`

### 问题

`capture()` 方法直接修改多个 OpenGL 状态而不保存原值：

- 第 22-23 行：绑定 `READ_FRAMEBUFFER` 和 `DRAW_FRAMEBUFFER` 到不同的 FBO
- 第 27 行：`glColorMask(false, false, false, true)` 关闭了 RGB 通道的写入
- 第 28 行：`glClearColor(0f, 0f, 0f, 1f)` 改变了清除色
- 第 30 行：`glColorMask(true, true, true, true)` 尝试恢复
- 第 32 行：`glBindFramebuffer(FRAMEBUFFER, main.fboId)` 尝试恢复

如果 `capture()` 中的任一 GL 调用之间抛出异常，已修改的 GL 状态不会恢复。主渲染管线随后以错误的 FBO 绑定/颜色掩码/清除色渲染，导致画面异常。

### 修复方向

在 `capture()` 开头保存以下状态的当前值：
- 当前绑定的 `FRAMEBUFFER`
- 当前 `GL_COLOR_WRITEMASK` 四个分量
- 当前 `GL_COLOR_CLEAR_VALUE`

使用 `finally` 块恢复全部状态。推荐将所有 GL 操作包裹在 `try/finally` 中而不是分步保存恢复。

---

## P1 — Bezier LUT 无界缓存

**文件**: `script/BezierPathStrategy.java`

### 问题

`BezierPathStrategy` 维护了一个 `Map<LutKey, ArcLengthLUT> lutCache` 实例字段（第 20 行）。虽然该字段不是全局静态的，但 `PathStrategies` 的 `static` 初始化块（第 36 行）只创建一个 `BezierPathStrategy` 实例并注册，因此该缓存实际上被所有脚本、所有路径计算共享。

每次编辑器预览中调整外部控制点（P1/P2）时都会计算新的弧长查找表并缓存。LUT 的 key 是 `(from, p1, p2, to)` 四元组，编辑器持续推送不同控制点会使缓存无限增长，无上限、无清理机制。长时间编辑会话中内存持续增长。

### 修复方向

将 `BezierPathStrategy` 改为非单例模式——每次创建脚本播放器或编辑器预览时创建独立的 `BezierPathStrategy` 实例，脚本播放结束时释放（LUT 随实例 GC 回收）。`PathStrategies.get("bezier")` 返回工厂方法或新实例，而不是共享的单例。

---

## P3 — EditorOutput.tick() 被双源调用

**文件**: `control/CinematicKeyBindings.java`、`editor/EditorScreen.java`

### 问题

`EditorOutput.tick()` 在两条调用路径上各执行一次：

1. `CinematicKeyBindings.onClientTick()` 第 65 行：检测到当前屏幕为 `EditorScreen` 时调用 `editor.getEditorOutput().tick()`
2. `EditorScreen.render()` 第 595 行：每渲染帧直接调用 `output.tick()`

`EditorOutput.tick()` 内部会检查是否需要推送脚本数据到 CameraManager，每次调用都会做状态判断。虽然当前逻辑能通过内部去重避免重复推送，但多出的 tick 调用是不必要的性能浪费。

### 修复方向

删除 `CinematicKeyBindings.onClientTick()` 中的 `editor.getEditorOutput().tick()` 调用。`EditorScreen.render()` 是更合理的调用时机——tick 与渲染帧同步，且在 render() 的输出流程中。

---

## P4 — hasActiveCameraClip 每帧重复扫描

**文件**: `camera/CameraManager.java`、`mixin/CameraMixin.java`、`mixin/GameRendererMixin.java`、`ImmersiveCinematics.java`

### 问题

`hasActiveCameraClip()` 内部调用 `ScriptPlayer.hasActiveCameraTrack()`，后者遍历 `trackPlayers` 列表中的每个 `TrackPlayer`，检查 `isActiveAt(elapsed)`。该遍历在单渲染帧内被以下入口重复调用：

- `CameraMixin.onSetup()` — 第 87 行
- `CameraMixin.onGetEntity()` — 第 122 行
- `CameraMixin.onIsDetached()` — 第 140 行
- `GameRendererMixin.onGetFov()` — 第 23 行
- `GameRendererMixin.onRenderItemInHand()` — 第 45 行
- `GameRendererMixin.onBobHurt()` — 第 59 行
- `GameRendererMixin.onBobView()` — 第 66 行
- `GameRendererMixin.redirectSpinningIntensity()` — 第 91 行
- `ImmersiveCinematics.onComputeCameraAngles()` — 第 280 行

共 9 次调用。每帧重复 9 次轨道遍历，虽然当前轨道数少所以影响不明显，但随轨道类型增多（AUDIO/EVENT/ModEvent）和 clip 数量增加，遍历开销会增长。

### 修复方向

在 `CameraManager` 中缓存 `hasActiveCameraClip()` 的计算结果：在 `onRenderFrame()` 开始时计算一次并存储在一个 `boolean` 字段中。Mixin 层改为读取缓存值而不是每次都扫描。`onRenderFrame()` 结束时或脚本播放状态变化时清除缓存。

注意：Mixin 的调用顺序不同——`CameraMixin.onSetup()` 中调用了 `mgr.onRenderFrame()`，所以缓存可以在 `onRenderFrame()` 开头更新，后续的 Mixin 入口读取同一份缓存值。
