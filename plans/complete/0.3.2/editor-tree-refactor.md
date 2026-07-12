# ⑤ 坐标统一 + 事件树 + Overlay 层 — 逐文件修复指南

**版本**: 0.3.2  
**类型**: 重构  
**文件数**: 3（UIComponent / UIContext / EditorScreen）  
**前置依赖**: ② Widget 焦点接口已完成  
**执行方式**: 按文件顺序，每步照做

---

## 概述

目前 `isHovered(ctx)` 用 raw `x/y` 做命中检测，都是屏幕绝对坐标。改为 `absX()/absY()` 链路后统一相对偏移布局。EditorScreen 改用 root 树统一事件分发，取代逐个 Area 调用。新增 overlay 层解决跨区组件事件截断。

---

## 文件 1 — UIComponent.java

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/widget/UIComponent.java`

### 1a) 改类声明 (Line 5-9)

```diff
  public abstract class UIComponent {
      public int x, y, w, h;
      public boolean visible = true;
      protected UIComponent parent;
      protected String tooltip;
```

不变，`parent` 字段已存在（line 8）。

### 1b) 新增 absX/absY 和 setParent (在 isHovered 前加)

在第 28 行 `public void setTooltip` 之后、第 29 行 `public boolean isHovered` 之前插入:

```java
    public int absX() {
        return parent != null ? parent.absX() + x : x;
    }

    public int absY() {
        return parent != null ? parent.absY() + y : y;
    }

    public void setParent(UIComponent p) {
        this.parent = p;
    }
```

### 1c) 改 isHovered (Line 29-31)

```diff
  public boolean isHovered(UIContext ctx) {
-     return visible && ctx.isMouseIn(x, y, w, h);
+     return visible && ctx.isMouseIn(absX(), absY(), w, h);
  }
```

---

## 文件 2 — UIContext.java（不需要改）

`isMouseIn(x, y, w, h)` 方法不变。absX/absY 在 UIComponent 层处理，UIContext 不受影响。

---

## 文件 3 — EditorScreen.java

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorScreen.java`

### 3a) 新增字段 (第 44 行后)

在 `private String renderPhase = "idle";` 之后加:

```java
private UIComponent rootComponent;
private UIComponent overlayComponent;
```

### 3b) 构建根节点树 (init() 方法内)

找到 `init()` 方法（约第 78 行）。在 4 个 Area 创建完毕后（`timeline = new TimelineArea(...)` 后），加构建根节点并注册 children:

```java
// ===== 构建 UI 树 =====
rootComponent = new UIComponent(0, 0, width, height) {
    @Override public void render(UIContext ctx) {}
};
// 4 个 Area 作为 root 的直接子节点
menuBar.setParent(rootComponent);
leftPanel.setParent(rootComponent);
preview.setParent(rootComponent);
timeline.setParent(rootComponent);
// 注册 children
rootComponent.children = new java.util.ArrayList<>();
rootComponent.children.add(menuBar);
rootComponent.children.add(leftPanel);
rootComponent.children.add(preview);
rootComponent.children.add(timeline);

// Overlay 层（最顶层，透明容器）
overlayComponent = new UIComponent(0, 0, width, height) {
    @Override public void render(UIContext ctx) {}
};
overlayComponent.children = new java.util.ArrayList<>();
overlayComponent.setParent(rootComponent);
rootComponent.children.add(overlayComponent);
```

### 3c) 让根节点渲染

找到 `render(GuiGraphics, int, int, float)` 方法。在方法顶部，添加根节点 render:

```java
UIContext renderCtx = new UIContext(guiGraphics, font, width, height, partialTick, mouseX, mouseY);
rootComponent.render(renderCtx);
// overlay render pass
rootComponent.renderOverlay(renderCtx);
```

在现有的 4 个 `xxArea.render(ctx)` 调用上方加 `rootComponent.render(ctx)`。保留旧的渲染调用作为备用（根节点 render 是空方法，只是遍历 children 做 render）。

> 重要: 额外需要确保每个 Area 的 `render()` 方法存在。MenuBar/LeftPanel/Preview/Timeline 都已 override 了 render。

### 3d) 覆盖 mouseClicked (第 610-630 行附近)

找到 `mouseClicked(double mx, double my, int button)`，把整个方法体改为:

```java
@Override
public boolean mouseClicked(double mx, double my, int button) {
    if (rootComponent == null) return false;
    UIContext ctx = makeCtx(mx, my, button);
    return rootComponent.mouseClicked(ctx);
}
```

删除原有的 4 个 `menuBar.mouseClicked(ctx); ...` 调用。

### 3e) 覆盖 mouseDragged

找到 `mouseDragged`，改为:

```java
@Override
public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
    if (rootComponent == null) return false;
    UIContext ctx = makeCtx(mx, my, button);
    ctx.mouseDX = dx; ctx.mouseDY = dy;
    return rootComponent.mouseDragged(ctx);
}
```

保留旧的 timeline.mouseDragged 调用作为备用，但加一层 root 分发。

### 3f) 覆盖 mouseReleased

由原来的 4 行 Area 调用改为:

```java
@Override
public boolean mouseReleased(double mx, double my, int button) {
    if (rootComponent == null) return false;
    UIContext ctx = makeCtx(mx, my, button);
    return rootComponent.mouseReleased(ctx);
}
```

### 3g) 覆盖 mouseScrolled

由原来的命中链:

```java
@Override
public boolean mouseScrolled(double mx, double my, double scroll) {
    if (rootComponent == null) return false;
    UIContext ctx = makeCtx(mx, my, 0);
    return rootComponent.mouseScrolled(ctx, scroll);
}
```

注意: 当前 mouseScrolled 签名只有一个 `double scroll` 参数，不是双参数版本。

---

## 步骤 3h — 验证 overlay 事件截断

Overlay 组件作为 root 的最后一个 child（最顶层），自动先于 lower children 消费事件。`UIDropdown` 展开时 attach 到 overlay：

```java
// 在 UIDropdown.java 展开时:
overlay.children.clear();
overlay.children.add(dropdownMenu);
dropdownMenu.setParent(overlay);
```

收起时 `overlay.children.clear()`。

---

## 步骤 3i — 更新 reinit()

`reinit()` 方法（约第 85 行，在 init 中调用或独立）需要重建 root.children 列表。

---

## UIComponent 新增 children 字段

当前 `getChildren()` 返回 null 作为默认值。为了方便树构建，新增 protected field:

```diff
  protected UIComponent parent;
  protected String tooltip;
+ protected List<UIComponent> children;

  // 修改默认 getChildren 实现
  public List<UIComponent> getChildren() {
-     return null;
+     return children;
  }
```

---

## 完成检查清单

- [ ] UIComponent.java 新增 absX()/absY()/setParent()/children 字段
- [ ] UIComponent.isHovered(ctx) 改用 absX()/absY()
- [ ] EditorScreen.java 新增 rootComponent/overlayComponent 字段
- [ ] EditorScreen.init() 构建树: root → 4 Areas + overlay
- [ ] EditorScreen mouseClicked/mouseDragged/mouseReleased/mouseScrolled 改为 root 分发
- [ ] 旧的手动 4 Area 调用已删除
- [ ] 编译通过
- [ ] 编辑器鼠标点击、拖拽、滚轮均正常工作
- [ ] 可继续改进: 后续把子 Widget 的坐标从绝对改为相对偏移
