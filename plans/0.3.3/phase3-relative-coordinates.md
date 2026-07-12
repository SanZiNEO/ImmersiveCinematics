# 阶段 3 — 子 Widget 相对坐标

**目标版本**: 0.3.3  
**状态**: 待实施  
**前置依赖**: 阶段 1 ② Timeline Widget 化完成

---

## ④ 子 Widget 相对坐标

**文件**: `editor/widget/UIComponent.java` 及所有 Widget 子类

### 问题

当前 UI Widget 的位置存储为 `(x, y)` 绝对坐标——即屏幕像素坐标。当 Widget 需要移动时（例如 `UIDropdown` 展开后位置跟随父组件），调用方需要手动重新计算屏幕绝对坐标并调用 `setBounds()`。

`UIComponent` 已经实现了 `absX()` / `absY()` 方法（通过遍历 `parent` 链累加偏移），这实际上是父组件相对偏移的基础设施。但所有 Widget 在 `render()` 和 `mouseClicked()` 中使用的 `x/y` 字段存储的仍是绝对坐标，没有利用这套相对偏移链路。

例如 `UITextInput.render()` 中的 `ctx.graphics.drawString(ctx.font, label, x, y + ...)`——这里的 `x` 和 `y` 是屏幕绝对坐标。如果这个 `UITextInput` 是一个父容器的子组件，容器移动后需要遍历更新所有子组件的 `x/y`。

### 修复方向

这是一个理念上的清理而非功能性变更。利用现有的 `absX()/absY()` 机制，将 Widget 坐标统一为相对于父容器的偏移：

- Widget 构造时 `x/y` 的含义从"屏幕绝对坐标"改为"相对于父容器的偏移"
- `render()` 和 `mouseClicked()` 中涉及位置判断的地方，用 `absX()/absY()` 代替直接引用 `x/y`
- `isHovered()` 已经使用了 `absX()/absY()`，不需要修改
- 不需要改 `render()` 中绘制内容的位置——绘制时使用的 `x/y` 保持不变，因为绘制坐标本身就是相对于绘制上下文的（`GuiGraphics` 的 `PoseStack`）

实际改动范围：`UIDropdown`、`UIAutoCompleteInput` 等需要动态定位的组件中，可能出现绝对坐标计算与相对偏移不一致的地方。

此任务**仅推荐在阶段 1 的 Widget 化完成后再评估是否需要做**。如果 Widget 化后所有组件都通过 UI 树 + 相对布局工作正常，则此阶段可以不实施。
