package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;

import java.util.ArrayList;
import java.util.List;

public class ContextMenu extends UIComponent {
    private final List<MenuEntry> entries = new ArrayList<>();
    private int hoveredIndex = -1;
    private Runnable onClose;
    private long showTime;

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
        this.showTime = System.currentTimeMillis();

        // 宽度在 renderContent 用 font.width 精确计算（show 无 ctx）
        int sepCount = 0;
        for (MenuEntry e : entries) { if (e.isSeparator) sepCount++; }
        int menuH = (entries.size() - sepCount) * ENTRY_H + sepCount * 2 + PAD * 2;

        this.x = mx;
        this.y = my;
        this.w = 100; // 占位，renderContent 首帧修正
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

    private int clampX(int rx, UIContext ctx) {
        if (rx + w > ctx.screenWidth) rx = ctx.screenWidth - w;
        if (rx < 0) rx = 0;
        return rx;
    }

    private int clampY(int ry, UIContext ctx) {
        if (ry + h > ctx.screenHeight) ry = ctx.screenHeight - h;
        if (ry < 0) ry = 0;
        return ry;
    }

    @Override
    public void renderContent(UIContext ctx) {
        if (!visible) return;

        // B3：宽度用 font 精确计算（渲染先行，onClicked 读同一 w）
        int maxLabelW = 0;
        for (MenuEntry e : entries) if (e.label != null) maxLabelW = Math.max(maxLabelW, ctx.font.width(e.label));
        this.w = maxLabelW + PAD * 2 + 10;

        // E2：120ms 淡入
        int a = (int)(Math.min(1f, (System.currentTimeMillis() - showTime) / 120f) * 255);

        int rx = clampX(x, ctx);
        int ry = clampY(y, ctx);

        ctx.graphics.fill(rx, ry, rx + w, ry + h, (a << 24) | (EditorTheme.BG_HOVER & 0x00FFFFFF));
        ctx.graphics.renderOutline(rx, ry, w, h, (a << 24) | (EditorTheme.TEXT_DISABLED & 0x00FFFFFF));

        int cy = ry + PAD;
        int idx = 0;
        for (MenuEntry e : entries) {
            if (e.isSeparator) {
                ctx.graphics.fill(rx + 4, cy, rx + w - 4, cy + 1, (a << 24) | (EditorTheme.SEPARATOR & 0x00FFFFFF));
                cy += 2;
                continue;
            }
            if (idx == hoveredIndex) {
                ctx.graphics.fill(rx + 2, cy, rx + w - 2, cy + ENTRY_H, (a << 24) | (EditorTheme.BORDER_LIGHT & 0x00FFFFFF));
            }
            int textColor = e.color != 0 ? e.color : EditorTheme.TEXT_PRIMARY;
            ctx.graphics.drawString(ctx.font, e.label, rx + PAD + 2, cy + (ENTRY_H - 8) / 2, (textColor & 0x00FFFFFF) | (a << 24));
            cy += ENTRY_H;
            idx++;
        }
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (!visible) return false;

        int rx = clampX(x, ctx);
        int ry = clampY(y, ctx);

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
        int rx = clampX(x, ctx);
        int ry = clampY(y, ctx);

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

    /** F1：键盘导航 — ↑/↓ 移动高亮、Enter 执行、Esc 关闭 */
    @Override
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) return false;
        if (keyCode == 265 || keyCode == 264) {  // ↑(265)/↓(264)
            int step = keyCode == 265 ? -1 : 1;
            int idx = hoveredIndex;
            do { idx += step; } while (idx >= 0 && idx < entries.size() && entries.get(idx).isSeparator);
            if (idx >= 0 && idx < entries.size()) hoveredIndex = idx;
            return true;
        }
        if (keyCode == 257 && hoveredIndex >= 0 && hoveredIndex < entries.size()) {  // Enter
            MenuEntry e = entries.get(hoveredIndex);
            if (!e.isSeparator) {
                if (e.action != null) e.action.run();
                hide();
            }
            return true;
        }
        if (keyCode == 256) { hide(); return true; }  // Esc
        return false;
    }

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
