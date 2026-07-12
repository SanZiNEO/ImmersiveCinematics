# 阶段 3 — 编辑器 UI 重构

**目标版本**: 0.3.3  
**策略**: **等待 Architectury 迁移后再做**。这些项涉及编辑器 UI 的大型重构，迁移到新架构后极大概率要重写，现在改浪费工作量。  
**前置依赖**: Architectury 迁移完成

---

## ② Timeline Widget 化 ★

**文件**: 新增 `editor/widget/UIClipWidget.java`、`UIKeyframeDiamond.java`、`UITransitionZone.java`；修改 `editor/area/TimelineArea.java`

### 问题

`TimelineArea.drawClip()` 是一个约 70 行的巨石方法，囊括三种不同渲染逻辑：clip 主体、关键帧菱形、过渡区域。鼠标交互散落在 `clickCanvas()` 和 `mouseReleased()` 中。

### 修复方向

将 clip 的三种视觉元素拆为独立的 Widget 类，通过 `UIComponent` 接口参与 UI 树渲染和事件分发。

---

## ① 多轨道时间轴渲染

**文件**: `editor/area/TimelineArea.java`

### 问题

编辑器仅展示第 1 条 CAMERA 轨道的内容，多轨道渲染是 Widget 化的自然延伸。

### 修复方向

确保每个轨道行都渲染其 clips，按轨道类型颜色区分。关键帧菱形仅在 CAMERA 轨道显示。

---

## ③ 双向 Schema 模板

**文件**: 新增 `script/track_schema.json`；修改 `script/ScriptParser.java`、`editor/area/LeftPanelArea.java`、`editor/EditorOperations.java`

### 问题

同一组字段名在编辑器写侧和运行时读侧各自手写，增删字段需要改两处。

### 修复方向

设计 `track_schema.json`，按轨道类型定义字段列表、类型、默认值、是否必填。编辑器侧和 Parser 侧都从此 schema 读取。

---

## ④ 子 Widget 相对坐标

**文件**: `editor/widget/UIComponent.java` 及所有 Widget 子类

### 问题

Widget 坐标是屏幕绝对坐标，容器移动后需要遍历更新所有子组件的 `x/y`。

### 修复方向

利用已有的 `absX()/absY()` 机制，将 Widget 坐标统一为相对于父容器的偏移。

此任务可选——如果 Widget 化后所有组件都通过 UI 树 + 相对布局工作正常，则可以不实施。
