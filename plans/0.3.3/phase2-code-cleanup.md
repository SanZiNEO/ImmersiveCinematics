# 阶段 2 — 代码整洁度

**目标版本**: 0.3.3  
**策略**: P1 完成后选择性实施。全是小改，迁移前做更好，不做也行。  
**前置依赖**: P1 完成

---

## C1 — Scale 封装

**文件**: 新增 `editor/Scale.java`；修改 `editor/EditorScreen.java`、`editor/area/TimelineArea.java`、`editor/area/LeftPanelArea.java`、`editor/area/MenuBarArea.java`、`editor/area/PreviewArea.java`

### 问题

`EditorScreen.sx/sy` 全局可变 `public static float`，约 38 处全限定名引用。

### 修复方向

新增 `Scale` 类，提供静态 `sx/sy` 和 `update(int width, int height)` 方法。各 Area 的 import 从 `EditorScreen.sx` 改为 `Scale.sx`。

---

## D1 — 输入模型统一

**文件**: `editor/widget/UITextInput.java`、`editor/widget/UIFloatInput.java`

### 问题

`UITextInput` 每键提交 vs `UIFloatInput` 失焦提交，行为不一致。

### 修复方向

统一为失焦提交模型：`UITextInput` 改为仅在失去焦点时通过 `sink` 提交数据。

---

## D2 — 菜单栏布局去魔法数

**文件**: `editor/area/MenuBarArea.java`

### 问题

状态栏 4 个硬编码数值（`205`、`140`、`80`、`128`、`72`）含义不直观。

### 修复方向

提取为方法级的命名局部变量，在 `render()` 方法开头一次性计算。

---

## C4 — 三个 Area mouseClicked 样板代码重复

**文件**: `editor/area/TimelineArea.java`、`editor/area/LeftPanelArea.java`、`editor/area/MenuBarArea.java`、`editor/area/PreviewArea.java`、`editor/widget/UIButton.java`

### 问题

3 个 Area 的 `mouseClicked()` 中有相同的 `instanceof UIButton → 日志 → 点击转发` 样板代码，本应由 `UIButton` 自身完成。

### 修复方向

将按钮点击的 hover 检查和日志逻辑下沉到 `UIButton.mouseClicked()` 自身。各 Area 删除手动遍历和 `instanceof` 检查。

---

## E2 — 文本框编辑能力增强（非紧急）

**文件**: `editor/widget/UITextInput.java`、`editor/widget/UIAutoCompleteInput.java`

### 问题

无光标移动、无文本选中、无复制粘贴、只有退格删除末尾字符。

### 修复方向

实现方向键移动光标、光标位置插入、Ctrl+C/V。

此任务优先级低，可跳过。
