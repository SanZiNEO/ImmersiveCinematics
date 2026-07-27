package com.immersivecinematics.immersive_cinematics.editor.widget;

import java.util.ArrayList;
import java.util.List;

public class ContextMenu extends UIComponent {
    private final List<MenuEntry> entries = new ArrayList<>();
    private int hoveredIndex = -1;
    private Runnable onClose;

    private static final int ENTRY_H = 20;
    private static final int PAD = 4;

    public ContextMenu() {
        super(0, 0, 0, 0);
        visible = false;
        zIndex = 1000;
    }

    public void show(int mx, int my) {
        this.visible = true;
        this.hoveredIndex = -1;

        int maxLabelW = 80;
        for (MenuEntry e : entries) {
            if (e.label != null) {
                int lw = e.label.length() * 7;
                if (lw > maxLabelW) maxLabelW = lw;
            }
        }
        int menuW = maxLabelW + PAD * 2 + 10;
        int sepCount = 0;
        for (MenuEntry e : entries) { if (e.isSeparator) sepCount++; }
        int menuH = (entries.size() - sepCount) * ENTRY_H + sepCount * 2 + PAD * 2;

        this.x = mx;
        this.y = my;
        this.w = menuW;
        this.h = menuH;
    }

    public void hide() {
        visible = false;
        if (onClose != null) onClose.run();
    }

    public boolean isVisible() { return visible; }

    public void setOnClose(Runnable r) { onClose = r; }

    public ContextMenu addEntry(String label, Runnable action) {
        entries.add(new MenuEntry(label, action, false, 0));
        return this;
    }

    public ContextMenu addEntry(String label, int color, Runnable action) {
        entries.add(new MenuEntry(label, action, false, color));
        return this;
    }

    public ContextMenu addSeparator() {
        entries.add(new MenuEntry(null, null, true, 0));
        return this;
    }

    public void clearEntries() { entries.clear(); }

    private int clampX(int rx) {
        if (rx + w > getScreenWidth()) rx = getScreenWidth() - w;
        if (rx < 0) rx = 0;
        return rx;
    }

    private int clampY(int ry) {
        if (ry + h > getScreenHeight()) ry = getScreenHeight() - h;
        if (ry < 0) ry = 0;
        return ry;
    }

    private int getScreenWidth() { return 1920; }  // fallback
    private int getScreenHeight() { return 1080; } // fallback

    @Override
    public void renderContent(UIContext ctx) {
        if (!visible) return;

        int rx = clampX(x);
        int ry = clampY(y);

        ctx.graphics.fill(rx, ry, rx + w, ry + h, 0xFF2A2A2A);
        ctx.graphics.renderOutline(rx, ry, w, h, 0xFF555555);

        int cy = ry + PAD;
        int idx = 0;
        for (MenuEntry e : entries) {
            if (e.isSeparator) {
                ctx.graphics.fill(rx + 4, cy, rx + w - 4, cy + 1, 0xFF444444);
                cy += 2;
                continue;
            }
            if (idx == hoveredIndex) {
                ctx.graphics.fill(rx + 2, cy, rx + w - 2, cy + ENTRY_H, 0xFF3A3A3A);
            }
            int textColor = e.color != 0 ? e.color : 0xFFCCCCCC;
            ctx.graphics.drawString(ctx.font, e.label, rx + PAD + 2, cy + (ENTRY_H - 8) / 2, textColor);
            cy += ENTRY_H;
            idx++;
        }
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (!visible) return false;

        int rx = clampX(x);
        int ry = clampY(y);

        if (ctx.mouseX >= rx && ctx.mouseX < rx + w && ctx.mouseY >= ry && ctx.mouseY < ry + h) {
            int cy = ry + PAD;
            int idx = 0;
            for (MenuEntry e : entries) {
                if (e.isSeparator) { cy += 2; continue; }
                if (ctx.mouseY >= cy && ctx.mouseY < cy + ENTRY_H) {
                    if (e.action != null) e.action.run();
                    hide();
                    return true;
                }
                cy += ENTRY_H;
                idx++;
            }
            return true;
        }
        hide();
        return false;
    }

    @Override
    protected boolean onScrolled(UIContext ctx, double scroll) {
        if (!visible) return false;
        int rx = clampX(x);
        int ry = clampY(y);

        if (ctx.mouseX >= rx && ctx.mouseX < rx + w && ctx.mouseY >= ry && ctx.mouseY < ry + h) {
            int localY = ctx.mouseY - (ry + PAD);
            int idx = 0;
            int yOff = 0;
            for (MenuEntry e : entries) {
                if (e.isSeparator) { yOff += 2; continue; }
                if (localY >= yOff && localY < yOff + ENTRY_H) {
                    hoveredIndex = idx;
                    return true;
                }
                yOff += ENTRY_H;
                idx++;
            }
            hoveredIndex = -1;
            return true;
        }
        hoveredIndex = -1;
        return false;
    }

    @Override
    protected boolean onDragged(UIContext ctx) { return visible; }
    @Override
    protected boolean onReleased(UIContext ctx) { return visible; }

    private static class MenuEntry {
        final String label;
        final Runnable action;
        final boolean isSeparator;
        final int color;

        MenuEntry(String label, Runnable action, boolean isSeparator, int color) {
            this.label = label;
            this.action = action;
            this.isSeparator = isSeparator;
            this.color = color;
        }
    }
}
