# 阶段 1 — 运行时健壮性

**目标版本**: 0.3.3  
**策略**: 立即做，不影响编辑器 UI，迁移 Architectury 时自动带走  
**前置依赖**: P0 已完成

---

## C2 — CAMERA 关键帧缺 position 导致逐帧 NPE

**文件**: `script/ScriptParser.java`、`script/KeyframeInterpolator.java`

### 问题

`ScriptParser.parseCameraKeyframe()` 初始化 `PositionData position = null`，仅当 JSON 中有 `"position"` 字段时才解析赋值。如果 JSON 缺少 position 字段，创建出的 `CameraKeyframe` 对象的 position 字段为 null。

`KeyframeInterpolator.interpolatePosition()` 直接调用 `from.getPosition().toVec3()`，在 position 为 null 时抛出 NPE。该异常在每渲染帧的调用链中抛出，被 catch 捕获后下一帧再次抛出，造成日志无限膨胀。

### 修复方向

CAMERA 关键帧解析时强制 position 字段必填。如果 JSON 中缺少 position，Parser 应抛出 `ScriptParseException`。

---

## G1 — Overlay 异常后 GL_ALWAYS 永久泄漏

**文件**: `editor/EditorScreen.java`

### 问题

`render()` 中渲染 overlay 前设置 `GL_ALWAYS` 深度测试函数，渲染后恢复为 `GL_LEQUAL`。但两者之间没有 try/finally 保护——如果 `rootComponent.renderOverlay(ctx)` 抛出异常，`depthFunc` 不会恢复。

### 修复方向

将 `depthFunc` 的修改和恢复包裹在 `try { ... } finally { ... }` 中。

---

## G2 — PreviewCapture 修改 GL 状态不恢复

**文件**: `editor/PreviewCapture.java`

### 问题

`capture()` 方法直接修改多个 OpenGL 状态（FBO 绑定、颜色掩码、清除色）而不保存原值。如果中间抛出异常，已修改的 GL 状态不会恢复。

### 修复方向

在 `capture()` 开头保存当前状态值，使用 `finally` 块恢复全部状态。

---

## P1 — Bezier LUT 无界缓存

**文件**: `script/BezierPathStrategy.java`

### 问题

`BezierPathStrategy` 的 `lutCache` 实际上是全局共享的（通过 `PathStrategies` 单例）。编辑器持续推送不同控制点会使缓存无限增长，无上限、无清理机制。

### 修复方向

将 `BezierPathStrategy` 改为非单例模式——每次创建脚本播放器或编辑器预览时创建独立实例，脚本播放结束时释放。

---

## P3 — EditorOutput.tick() 被双源调用

**文件**: `control/CinematicKeyBindings.java`、`editor/EditorScreen.java`

### 问题

`EditorOutput.tick()` 在 `CinematicKeyBindings.onClientTick()` 和 `EditorScreen.render()` 中各执行一次。

### 修复方向

删除 `CinematicKeyBindings.onClientTick()` 中的调用，保留 `EditorScreen.render()` 中的一份。

---

## P4 — hasActiveCameraClip 每帧重复扫描

**文件**: `camera/CameraManager.java`、`mixin/CameraMixin.java`、`mixin/GameRendererMixin.java`、`ImmersiveCinematics.java`

### 问题

`hasActiveCameraClip()` 每帧被 9 个 Mixin 入口重复调用，每次遍历轨道列表。随轨道数量增长，遍历开销会放大。

### 修复方向

在 `CameraManager` 中缓存 `hasActiveCameraClip()` 的结果：在 `onRenderFrame()` 开始时计算一次并存储。Mixin 层改为读取缓存值。

---

## ⑤ — Clip/Keyframe 运行时数据模型统一

**文件**: `script/` 包全量重构（新增 `Clip.java`、`Keyframe.java`，删除 5 个独立 Clip 类 + 2 个 Keyframe 类）

### 问题

当前每个轨道类型有独立的 Clip 和 Keyframe 类（共 5 个 Clip + 2 个 Keyframe）。新增轨道类型需要创建两个 Java 类，`TimelineTrack` 需要新增 `getXxxClips()` 方法。

### 修复方向

将多态 Clip/Keyframe 类合并为带类型的通用容器。`Clip` 包含 `startTime`、`duration`、轨道类型、`List<Keyframe>`。`Keyframe` 包含 `time` 和 `Map<String, Object> data`。`TimelineTrack` 删除 5 个 `getXxxClips()`，替换为统一的 `getClips()`。

**Editor 侧零改动**——编辑器操作的是 `JsonObject`/`JsonArray`，不读 Java POJO。

详见 `phase4-unified-clip-model.md`（保留为独立文件）。

---

## 改动汇总

| 编号 | 文件数 | 类型 | 复杂度 |
|------|--------|------|--------|
| C2 | 2 | bugfix | ★ 极低 |
| G1 | 1 | bugfix | ★ 极低 |
| G2 | 1 | bugfix | ★ 极低 |
| P1 | 1 | 性能 | ★★ 中 |
| P3 | 2 | 性能 | ★ 极低 |
| P4 | 4 | 性能 | ★★ 中 |
| ⑤ | ~10 | 数据模型重构 | ★★★ 高 |
