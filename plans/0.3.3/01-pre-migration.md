# 01 — 迁移前修复

**策略**: Architectury 迁移前做完。不影响编辑器 UI，直接带走。  
**前置**: P0 已完成

---

## A — 运行时 bugfix（随手改）

### C2 — CAMERA 关键帧缺 position 导致逐帧 NPE

**文件**: `script/ScriptParser.java`、`script/KeyframeInterpolator.java`

**问题**: `parseCameraKeyframe()` 缺 position 时创建 null，`interpolatePosition()` 直接解引用抛 NPE，每帧重复。

**修复**: Parser 中强制 position 必填，缺则抛 `ScriptParseException`。

### G1 — Overlay 异常后 GL_ALWAYS 永久泄漏

**文件**: `editor/EditorScreen.java`

**修复**: `depthFunc` 改/恢复包裹 `try/finally`。

### G2 — PreviewCapture 修改 GL 状态不恢复

**文件**: `editor/PreviewCapture.java`

**修复**: 开头保存当前 FBO / colormask / clearcolor 状态，`finally` 恢复。

### P3 — EditorOutput.tick() 被双源调用

**文件**: `control/CinematicKeyBindings.java`、`editor/EditorScreen.java`

**修复**: 删 `CinematicKeyBindings` 中的调用，保留 `EditorScreen.render()` 中的一份。

---

## B — 性能优化

### P1 — Bezier LUT 无界缓存

**文件**: `script/BezierPathStrategy.java`

**修复**: 单例改工厂，每次创建脚本播放器时新建独立实例，脚本结束时释放。

### P4 — hasActiveCameraClip 每帧重复扫描

**文件**: `camera/CameraManager.java`、`mixin/CameraMixin.java`、`mixin/GameRendererMixin.java`、`ImmersiveCinematics.java`

**修复**: `CameraManager` 缓存计算结果，9 个 Mixin 入口读缓存而非遍历轨道。

---

## C — 代码整洁度（可选，建议做）

### C1 — Scale 封装

**文件**: 新增 `editor/Scale.java`；改 5 个 Area

**修复**: 新增 `Scale` 类提供静态 `sx/sy` + `update()`，全限定名 `EditorScreen.sx` 改 `Scale.sx`。

### D1 — 输入模型统一

**文件**: `editor/widget/UITextInput.java`、`editor/widget/UIFloatInput.java`

**修复**: `UITextInput` 改为失焦提交，与 `UIFloatInput` 一致。

### D2 — 菜单栏布局去魔法数

**文件**: `editor/area/MenuBarArea.java`

**修复**: 4 个硬编码数值提取为命名局部变量。

### C4 — 三个 Area mouseClicked 样板代码下沉

**文件**: `editor/area/TimelineArea.java` / `LeftPanelArea.java` / `MenuBarArea.java` / `PreviewArea.java`、`editor/widget/UIButton.java`

**修复**: `instanceof UIButton → 日志 → 点击转发` 样板下沉到 `UIButton.mouseClicked()` 自身。

### E2 — 文本框编辑能力增强（可跳过）

**文件**: `editor/widget/UITextInput.java`、`UIAutoCompleteInput.java`

**修复**: 方向键移动光标、光标位置插入、Ctrl+C/V。优先级低，可不做。

---

## D — 数据模型重构

### ⑤ — Clip/Keyframe 运行时数据模型统一

**文件**: `script/` 包约 10 个文件，零编辑器影响  
**详见**: `03-unified-clip-model.md`

**修复**: 5 个独立 Clip 类 + 2 个 Keyframe 类合并为通用 `Clip`/`Keyframe` 容器，`TimelineTrack` 5 个 `getXxxClips()` 合并为统一 `getClips()`。

可留到 Architectury 迁移时顺带做。
