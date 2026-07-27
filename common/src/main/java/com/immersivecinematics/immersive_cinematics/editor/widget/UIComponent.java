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

    public int absX() {
        return parent != null ? parent.absX() + x : x;
    }

    public int absY() {
        return parent != null ? parent.absY() + y : y;
    }

    public void setParent(UIComponent p) {
        this.parent = p;
    }

    public boolean isHovered(UIContext ctx) {
        return visible && ctx.isMouseIn(absX(), absY(), w, h);
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

    public final boolean mouseClicked(UIContext ctx) {
        if (!visible || !enabled) return false;
        List<UIComponent> ch = getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            if (ch.get(i).mouseClicked(ctx)) return true;
        }
        if (onClicked(ctx)) {
            requestFocus();
            return true;
        }
        return false;
    }

    protected boolean onClicked(UIContext ctx) { return false; }

    public final boolean mouseReleased(UIContext ctx) {
        if (!visible || !enabled) return false;
        List<UIComponent> ch = getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            if (ch.get(i).mouseReleased(ctx)) return true;
        }
        return onReleased(ctx);
    }

    protected boolean onReleased(UIContext ctx) { return false; }

    public final boolean mouseDragged(UIContext ctx) {
        if (!visible || !enabled) return false;
        List<UIComponent> ch = getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            if (ch.get(i).mouseDragged(ctx)) return true;
        }
        return onDragged(ctx);
    }

    protected boolean onDragged(UIContext ctx) { return false; }

    public final boolean mouseScrolled(UIContext ctx, double scroll) {
        if (!visible || !enabled) return false;
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
