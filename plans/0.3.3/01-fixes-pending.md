# 01 — 待修复（原迁移前修复，尚未做）

Architectury 迁移已完成，以下原计划的修复项尚未实施。

---

## A — 运行时 bugfix

### G1 — Overlay 异常后 GL_ALWAYS 永久泄漏

**文件**: `editor/EditorScreen.java`

**问题**: `depthFunc` 修改后未恢复，异常时永久泄漏 GL_ALWAYS。

**修复**: `depthFunc` 改/恢复包裹 `try/finally`。

### G2 — PreviewCapture 修改 GL 状态不恢复

**文件**: `editor/PreviewCapture.java`

**问题**: 修改 FBO / colormask / clearcolor 后不恢复。

**修复**: 开头保存当前状态，`finally` 恢复。

### P3 — EditorOutput.tick() 被双源调用

**文件**: `control/CinematicKeyBindings.java`、`editor/EditorScreen.java`

**问题**: `EditorOutput.tick()` 在 `CinematicKeyBindings.onClientTick()` 和 `EditorScreen.render()` 各被调用一次。

**修复**: 删 `CinematicKeyBindings` 中的调用，保留 `EditorScreen.render()` 中的一份。

---

## B — 性能优化

### P1 — Bezier LUT 无界缓存

**文件**: `script/BezierPathStrategy.java`

**问题**: Bezier LUT 在静态 MAP 中无限增长，每次新曲线追加一条，游戏不重启不释放。

**修复**: 单例改工厂，每次创建脚本播放器时新建独立实例，脚本结束时释放。

---

## C — 代码整洁度

### D1 — 输入模型统一

**文件**: `editor/widget/UITextInput.java`、`editor/widget/UIFloatInput.java`

**问题**: `UITextInput` 实时提交 vs `UIFloatInput` 失焦提交，行为不一致。

**修复**: `UITextInput` 改为失焦提交，与 `UIFloatInput` 一致。

### D2 — 菜单栏布局去魔法数

**文件**: `editor/area/MenuBarArea.java`

**问题**: 坐标/宽度使用硬编码数值。

**修复**: 4 个硬编码数值提取为命名局部变量。

### C4 — 三个 Area mouseClicked 样板代码下沉

**文件**: `editor/area/TimelineArea.java` / `LeftPanelArea.java` / `MenuBarArea.java` / `PreviewArea.java`、`editor/widget/UIButton.java`

**问题**: 每个 Area 的 `mouseClicked` 都重复 `instanceof UIButton → 日志 → 点击转发` 三段代码。

## D — 数据模型重构

### ⑤ — Schema 驱动的统一 Clip/Keyframe 数据模型

**文件**: `script/` 包 + `editor/` + 新增 `schema.json`
**详见**: `03-unified-clip-model.md`

**问题**: 5 个独立 Clip 类 + 2 个 Keyframe 类，编辑器硬编码字段定义。

**修复**: schema 定义所有轨道字段 → 通用 Clip/Keyframe 容器 → Parser 统一解析 → 编辑器属性面板自动渲染。

**问题**: 不能方向键移动光标、光标位置插入、Ctrl+C/V。

**修复**: 方向键移动光标、光标位置插入、Ctrl+C/V。优先级低，可不做。

---

## D — 数据模型重构

### ⑤ — Clip/Keyframe 运行时数据模型统一

**文件**: `script/` 包约 10 个文件  
**详见**: `03-unified-clip-model.md`

**问题**: 5 个独立 Clip 类 + 2 个 Keyframe 类，新增轨道类型需建两个类并改 `TimelineTrack`。

**修复**: 合并为通用 `Clip`/`Keyframe` 容器，`TimelineTrack` 统一 `getClips()`。
