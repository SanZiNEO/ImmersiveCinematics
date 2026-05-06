package com.immersivecinematics.immersive_cinematics.editor.widget;

import java.util.List;

public abstract class UIComponent {
    public int x, y, w, h;
    public boolean visible = true;
    protected UIComponent parent;
    protected String tooltip;

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

    public boolean isHovered(UIContext ctx) {
        return visible && ctx.isMouseIn(x, y, w, h);
    }

    public abstract void render(UIContext ctx);

    public boolean mouseClicked(UIContext ctx) {
        if (!visible) return false;
        List<UIComponent> children = getChildren();
        if (children != null) {
            for (int i = children.size() - 1; i >= 0; i--) {
                if (children.get(i).mouseClicked(ctx)) return true;
            }
        }
        return false;
    }

    public boolean mouseReleased(UIContext ctx) {
        if (!visible) return false;
        List<UIComponent> children = getChildren();
        if (children != null) {
            for (int i = children.size() - 1; i >= 0; i--) {
                if (children.get(i).mouseReleased(ctx)) return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(UIContext ctx) {
        if (!visible) return false;
        List<UIComponent> children = getChildren();
        if (children != null) {
            for (int i = children.size() - 1; i >= 0; i--) {
                if (children.get(i).mouseDragged(ctx)) return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(UIContext ctx, double scroll) {
        if (!visible) return false;
        List<UIComponent> children = getChildren();
        if (children != null) {
            for (int i = children.size() - 1; i >= 0; i--) {
                if (children.get(i).mouseScrolled(ctx, scroll)) return true;
            }
        }
        return false;
    }

    protected List<UIComponent> getChildren() {
        return null;
    }

    protected void renderTooltipIfHovered(UIContext ctx) {
        if (tooltip != null && isHovered(ctx)) {
            ctx.graphics.renderTooltip(ctx.font, net.minecraft.network.chat.Component.literal(tooltip), ctx.mouseX, ctx.mouseY);
        }
    }
}
