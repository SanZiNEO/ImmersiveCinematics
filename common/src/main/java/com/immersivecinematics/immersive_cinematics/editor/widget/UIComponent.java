package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

public abstract class UIComponent {
    public int x, y, w, h;
    public boolean visible = true;
    public boolean enabled = true;
    protected UIComponent parent;
    protected String tooltip;
    protected int zIndex = 0;

    public int getZIndex() { return zIndex; }
    private int childrenVersion = 0;
    private List<UIComponent> sortedChildren = Collections.emptyList();
    private final List<UIComponent> children = new ArrayList<>();
    protected UIComponent focused = null;

    public UIComponent(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void setBounds(int nx, int ny, int nw, int nh) {
        x = nx;
        y = ny;
        w = nw;
        h = nh;
    }

    public void setTooltip(String tip) {
        tooltip = tip;
    }

    /**
     * 本编辑器的组件坐标体系：所有组件 x/y 均为屏幕绝对坐标
     * （子组件创建时用父容器坐标 + 偏移计算，渲染直接绘制，父容器不做 pose 平移）。
     * 因此 absX/absY 直接返回 x/y——沿父链累加会把父偏移双加（如 PreviewArea x=346 时
     * 子组件命中区整体右移 346），导致点击错位。
     */
    public int absX() {
        return x;
    }

    public int absY() {
        return y;
    }

    public void setParent(UIComponent p) {
        this.parent = p;
    }

    /** 滚动容器内容偏移量（子类覆盖，如 LeftPanelArea 返回 scrollY）；沿父链累加得到子孙的滚动修正 */
    public int getScrollOffset() { return 0; }

    /** 固定在父容器顶部、不随父容器滚动（如面板 tab 栏）；渲染与命中都豁免滚动偏移 */
    public boolean fixedToParent = false;

    /** 沿父链累计的滚动偏移：子孙组件命中坐标 = 绝对坐标 − 该值（滚动是树的语义，不是 ctx 的临时状态） */
    public int scrollCompensation() {
        if (fixedToParent) return 0;
        int comp = 0;
        for (UIComponent p = parent; p != null; p = p.parent) comp += p.getScrollOffset();
        return comp;
    }

    /** 命中 X（绝对屏幕坐标） */
    public int hitX() { return absX(); }

    /** 命中 Y（绝对屏幕坐标 − 累计滚动偏移） */
    public int hitY() { return absY() - scrollCompensation(); }

    public boolean isHovered(UIContext ctx) {
        return visible && ctx.isMouseIn(hitX(), hitY(), w, h);
    }
    public void clearChildren() {
        for (UIComponent child : children) {
            child.setParent(null);
        }
        children.clear();
        childrenVersion++;
    }


    // ── Tree structure ──

    public void addChild(UIComponent child) {
        children.add(child);
        child.setParent(this);
        childrenVersion++;
    }

    public void removeChild(UIComponent child) {
        children.remove(child);
        child.setParent(null);
        childrenVersion++;
    }

    public final List<UIComponent> getChildren() { return children; }


    // ── Focus system ──

    public void requestFocus() {
        if (parent != null) parent.setFocused(this);
    }

    public void setFocused(UIComponent child) {
        if (focused != null && focused != child) focused.onFocusLost();
        if (child != null) {
            focused = child;
            focused.onFocusGained();
        } else {
            focused = null;
        }
    }

    protected void onFocusGained() {}
    protected void onFocusLost() {}

    // ── Render ──

    public void render(UIContext ctx) {
        if (!visible) return;
        renderContent(ctx);
        if (sortedChildren.isEmpty() || childrenVersion > 0) {
            sortedChildren = new ArrayList<>(children);
            if (sortedChildren.size() > 1) {
                sortedChildren.sort(Comparator.comparingInt(UIComponent::getZIndex).reversed());
            }
            childrenVersion = 0;
        }
        for (UIComponent child : sortedChildren) {
            if (child.visible) child.render(ctx);
        }
    }

    protected void renderContent(UIContext ctx) {}

    /** Render pass that runs after all normal rendering, for overlays. */
    public void renderOverlay(UIContext ctx) {
        List<UIComponent> children = getChildren();
        for (UIComponent c : children) c.renderOverlay(ctx);
    }

    // ── Mouse events (final template methods) ──

    /** 鼠标是否在本容器可视矩形内（容器自身不滚动，用绝对坐标；滚出可视区的内容由 hitY 修正自然 miss） */
    private boolean mouseInsideSelf(UIContext ctx) {
        return ctx.isMouseIn(absX(), absY(), w, h);
    }

    public final boolean mouseClicked(UIContext ctx) {
        if (!visible || !enabled) return false;
        List<UIComponent> ch = getChildren();
        // pass 1：浮层优先（展开下拉列表/自动补全建议——后添加的优先）。
        // 展开列表可能溢出父容器（画在兄弟区域之上），必须先于普通命中检查，
        // 否则点击被下层组件（时间轴/预览区）吃掉。浮层不参与容器裁剪。
        for (int i = ch.size() - 1; i >= 0; i--) {
            if (ch.get(i).overlayClicked(ctx)) return true;
        }
        // pass 2：普通命中前先做容器裁剪——鼠标在容器可视区外不向子组件分发
        // （滚动容器滚出可视区的内容在容器外仍会被命中，必须在此拦截）。
        if (mouseInsideSelf(ctx)) {
            for (int i = ch.size() - 1; i >= 0; i--) {
                if (ch.get(i).mouseClicked(ctx)) return true;
            }
        }
        if (onClicked(ctx)) {
            requestFocus();
            return true;
        }
        return false;
    }

    /** 浮层区域命中判断（默认 false；UIDropdown/UIAutoCompleteInput 重写为展开列表/建议弹层区域） */
    protected boolean overlayHit(UIContext ctx) { return false; }

    /**
     * 浮层命中通道（递归）：自身浮层区域命中则走完整点击流程；否则递归子树浮层。
     * 用于把展开列表的点击提升到所有普通组件之上。
     */
    public boolean overlayClicked(UIContext ctx) {
        if (!visible || !enabled) return false;
        if (overlayHit(ctx)) return mouseClicked(ctx);
        List<UIComponent> ch = getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            if (ch.get(i).overlayClicked(ctx)) return true;
        }
        return false;
    }

    protected boolean onClicked(UIContext ctx) { return false; }

    public final boolean mouseReleased(UIContext ctx) {
        if (!visible || !enabled) return false;
        if (mouseInsideSelf(ctx)) {
            List<UIComponent> ch = getChildren();
            for (int i = ch.size() - 1; i >= 0; i--) {
                if (ch.get(i).mouseReleased(ctx)) return true;
            }
        }
        return onReleased(ctx);
    }

    protected boolean onReleased(UIContext ctx) { return false; }

    public final boolean mouseDragged(UIContext ctx) {
        if (!visible || !enabled) return false;
        if (mouseInsideSelf(ctx)) {
            List<UIComponent> ch = getChildren();
            for (int i = ch.size() - 1; i >= 0; i--) {
                if (ch.get(i).mouseDragged(ctx)) return true;
            }
        }
        return onDragged(ctx);
    }

    protected boolean onDragged(UIContext ctx) { return false; }

    public final boolean mouseScrolled(UIContext ctx, double scroll) {
        if (!visible || !enabled) return false;
        // 不做容器裁剪：浮层（展开下拉列表）可能溢出容器，滚轮需能作用其上；
        // 滚动命中由各组件 onScrolled 自行检查鼠标范围。
        List<UIComponent> ch = getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            if (ch.get(i).mouseScrolled(ctx, scroll)) return true;
        }
        return onScrolled(ctx, scroll);
    }

    protected boolean onScrolled(UIContext ctx, double scroll) { return false; }

    // ── Keyboard events (final template methods) ──

    public final boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible || !enabled) return false;
        if (focused != null && focused.keyPressed(keyCode, scanCode, modifiers)) return true;
        List<UIComponent> ch = getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            if (ch.get(i).keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return onKeyPressed(keyCode, scanCode, modifiers);
    }

    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) { return false; }

    public final boolean charTyped(char codePoint, int modifiers) {
        if (!visible || !enabled) return false;
        if (focused != null && focused.charTyped(codePoint, modifiers)) return true;
        List<UIComponent> ch = getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            if (ch.get(i).charTyped(codePoint, modifiers)) return true;
        }
        return onCharTyped(codePoint, modifiers);
    }

    protected boolean onCharTyped(char codePoint, int modifiers) { return false; }

    // ── Tooltip ──

    protected void renderTooltipIfHovered(UIContext ctx) {
        if (tooltip != null && isHovered(ctx)) {
            ctx.graphics.renderTooltip(ctx.font, net.minecraft.network.chat.Component.literal(tooltip), ctx.mouseX, ctx.mouseY);
        }
    }
}
