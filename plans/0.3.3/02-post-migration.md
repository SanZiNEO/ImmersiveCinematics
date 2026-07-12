# 02 — 迁移后重构

**策略**: Architectury 迁移完成后评估是否仍有必要。涉及编辑器 UI 大型重构，迁移后极大概率要重写。  
**前置**: Architectury 迁移完成

---

## ② Timeline Widget 化 ★

**文件**: 新增 `UIClipWidget.java` / `UIKeyframeDiamond.java` / `UITransitionZone.java`；改 `TimelineArea.java`

**问题**: `drawClip()` 70 行巨石方法，clip 主体/关键帧/过渡区三种逻辑混在一起。鼠标交互散落在 `clickCanvas()` / `mouseReleased()` 中。

**方向**: 拆为 3 个独立 Widget 类，通过 `UIComponent` 接口参与 UI 树渲染和事件分发。

---

## ① 多轨道时间轴渲染

**文件**: `editor/area/TimelineArea.java`

**问题**: 编辑器仅展示第 1 条 CAMERA 轨道。

**方向**: 每个轨道行渲染其 clips，按轨道类型颜色区分。

---

## ③ 双向 Schema 模板

**文件**: 新增 `track_schema.json`；改 `ScriptParser.java` / `LeftPanelArea.java` / `EditorOperations.java`

**问题**: 同一组字段名在编辑器写侧和运行时读侧各自手写。

**方向**: 一份 JSON 同时驱动编辑器字段白名单和 Parser 校验。

---

## ④ 子 Widget 相对坐标

**文件**: `UIComponent.java` 及所有 Widget 子类

**问题**: Widget 坐标是屏幕绝对坐标，容器移动需遍历更新所有子组件。

**方向**: 利用现有 `absX()/absY()`，坐标改为父容器偏移。可选——Widget 化后若组件都正常工作，可不做。
