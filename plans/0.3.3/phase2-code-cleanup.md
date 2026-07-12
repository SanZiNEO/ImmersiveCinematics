# 阶段 2 — 代码整洁度

**目标版本**: 0.3.3  
**状态**: 待实施  
**前置依赖**: 阶段 1 完成（或至少 ② Timeline Widget 化完成，C1 和 ④ 与之相关）

---

## C1 — Scale 封装

**文件**: 新增 `editor/Scale.java`；修改 `editor/EditorScreen.java`、`editor/area/TimelineArea.java`、`editor/area/LeftPanelArea.java`、`editor/area/MenuBarArea.java`、`editor/area/PreviewArea.java`

### 问题

`EditorScreen` 定义了 `public static float sx` 和 `sy` 两个全局可变的缩放因子（第 32-33 行），供 4 个 Area 和内部 Widget 引用。这导致两个问题：

1. **全限定名刷屏**：`TimelineArea.java` 和 `LeftPanelArea.java` 中大量出现 `com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx`，约 38 处。每次引用都写完整包路径，可读性差。
2. **全局可变字段**：`sx/sy` 是 `public static` 且非 `final`，任何代码都可以意外修改。`EditorScreen.init()` 是唯一应该设置它们的地方。

### 修复方向

将 `sx/sy` 封装为 `Scale` 类：

- 新增 `Scale.java`：包含 `float sx, sy;` 的简单值对象，在 `EditorScreen.init()` 中创建并赋值
- 在各 Area 中通过 `import` 导入 `Scale`，使用 `Scale.sx` / `Scale.sy` 替代全限定名
- 或将 `sx/sy` 作为参数传给 Area 构造函数（但这会改变各 Area 的 API，改动较大）
- 更轻量的方案：在 `EditorScreen` 的 `init()` 中调用 `Scale.update(width, height)` 更新值，各 Area 引用 `Scale.sx` 即可

推荐轻量方案——新增 `Scale` 类，提供静态 `sx/sy` 和 `update(int width, int height)` 方法。各 Area 的 import 从 `EditorScreen.sx` 改为 `Scale.sx`。

---

## D1 — 输入模型统一

**文件**: `editor/widget/UITextInput.java`、`editor/widget/UIFloatInput.java`

### 问题

两个输入组件的数据提交策略不一致：

- `UITextInput`：每次 `charTyped()` 或 `keyPressed(backspace)` 后立即通过 `sink.accept(text)` 提交数据。这意味着每按一个键就触发一次源数据更新。
- `UIFloatInput`：只有失焦时（`mouseClicked()` 在其他位置触发）才提交数据。编辑过程中源数据不会变化。

行为不一致导致用户在文本框中输入时，编辑器的其他组件（如属性面板预览）会对每次按键立刻做出反应，而数字输入则只在离开输入框后更新。

### 修复方向

统一为失焦提交模型（`UIFloatInput` 的当前行为）：`UITextInput` 改为仅在失去焦点时通过 `sink` 提交数据。编辑过程中的临时文本只在内部 `text` 字段中维护，不推送至上层的 `EditorDocument`。

---

## D2 — 菜单栏布局去魔法数

**文件**: `editor/area/MenuBarArea.java`

### 问题

菜单栏中状态文本和动作文本的定位使用了 4 个硬编码数值：

- 第 94 行：`205 * sx`（动作文本右侧定位点）
- 第 100 行：`140 * sx`（状态文本右侧定位点，有动作时偏移）
- 第 100 行：`205 * sx`（状态文本右侧定位点，无动作时）
- 第 101 行：`80 * sx`（状态文本左侧边界限位）

这些数字的含义不直观——没有命名、没有注释、没有数学关系。后续调整布局时不知道各个数字代表什么、怎样联动修改。

### 修复方向

提取为方法级的命名局部变量，在 `render()` 方法开头一次性计算：

```java
int actionTextRight = x + w - (int)(205 * EditorScreen.sx);
int statusTextRight = showAction ? x + w - (int)(140 * EditorScreen.sx) : actionTextRight;
int statusTextLeftBound = x + (int)(80 * EditorScreen.sx);
```

或者通过 `width` 和各按钮的 X 坐标计算相对位置，使布局随按钮位置自动调整而非锁定特定像素。

---

## C4 — 三个 Area mouseClicked 样板代码重复

**文件**: `editor/area/TimelineArea.java`、`editor/area/LeftPanelArea.java`、`editor/area/MenuBarArea.java`、`editor/area/PreviewArea.java`、`editor/widget/UIButton.java`

### 问题

3 个 Area（MenuBar、Preview、LeftPanel）的 `mouseClicked()` 方法中有相同的样板逻辑：

```java
// 类型检查 + 调用
if (child instanceof UIButton btn && child.isHovered(ctx)) { ... }
if (child.mouseClicked(ctx)) return true;
```

这段逻辑是 `UIButton` 本身就应该做的事情——按钮自身判断是否被点击并触发回调。当前每个 Area 手动遍历 children，检查 `isHovered`、`instanceof`，再转发点击。这违背了 UI 树的"事件自动向下分发"设计：`UIComponent.mouseClicked()` 已经默认遍历 children，每个 Area 的 `mouseClicked` 覆写只是在默认行为之上加了一层 `instanceof UIButton` 的判断。

### 修复方向

将按钮点击的 hover 检查和日志逻辑下沉到 `UIButton.mouseClicked()` 自身。`UIButton` 覆写 `mouseClicked()`，在里面做：

1. 检查 `isHovered()`
2. 触发回调
3. 记录日志

各 Area 的 `mouseClicked()` 中删除手动遍历和 `instanceof` 检查，保留各 Area 特有的前置/后置逻辑（如 `EditorLogger.areaHit()` 的注册），然后交给 `super.mouseClicked(ctx)` 或仅保留必要的轨道级别处理。

---

## E2 — 文本框编辑能力增强（非紧急）

**文件**: `editor/widget/UITextInput.java`、`editor/widget/UIAutoCompleteInput.java`

### 问题

当前文本输入框的能力极简：
- 无光标移动（按键盘方向键无响应）
- 无文本选中
- 无复制/粘贴（Ctrl+C/V）
- 只有退格键删除末尾字符

编辑器脚本的 `name`/`author`/`description` 等字段的编辑体验不佳，用户输入错误后需要一次退格全部重来。

### 修复方向

在 `UITextInput` 中实现基本的文本编辑能力：
- 通过 `keyPressed` 处理方向键（左右移动光标插入位置）
- `charTyped` 时在光标位置插入而非末尾追加
- Ctrl+C/V 的复制粘贴通过检测 `keyPressed` 中的修饰键实现

`UIAutoCompleteInput` 同理——当前也是末尾追加模式，改为光标位置插入。

此任务优先级低，可在阶段 0-1 完成后评估是否有时间再做。
