package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.joml.Matrix4f;

public class UIAutoCompleteInput extends UIComponent implements IFocusable {
    private final String label;
    private final Supplier<String> source;
    private final Consumer<String> sink;
    private final List<String> candidates;
    private String text;
    private boolean focused;

    private List<String> filtered = List.of();
    private int selectedIndex = -1;
    private boolean showSuggestions;
    private int listScrollOffset;

    private static final int SUGGESTION_H = 14;
    private static final int MAX_VISIBLE = 8;

    public UIAutoCompleteInput(int x, int y, int w, int h, String label,
                                Supplier<String> source, Consumer<String> sink,
                                List<String> candidates) {
        super(x, y, w, h);
        this.label = label;
        this.source = source;
        this.sink = sink;
        this.candidates = candidates;
        this.text = source != null ? source.get() : "";
    }

    @Override
    public void render(UIContext ctx) {
        if (source != null && !focused) {
            text = source.get();
        }
        int labelW = ctx.font.width(label) + 4;
        ctx.graphics.drawString(ctx.font, label, x, y + (h - 8) / 2, EditorTheme.TEXT_MUTED);

        int inputX = x + labelW;
        int inputW = w - labelW;
        int bg = focused ? EditorTheme.BORDER_LIGHT : EditorTheme.BG_HOVER;
        ctx.graphics.fill(inputX, y, inputX + inputW, y + h, bg);
        ctx.graphics.renderOutline(inputX, y, inputW, h, EditorTheme.TEXT_DISABLED);

        String display = text;
        int tw = ctx.font.width(display);
        if (tw > inputW - 6) {
            while (ctx.font.width(display + "...") > inputW - 6 && !display.isEmpty()) {
                display = display.substring(0, display.length() - 1);
            }
            display += "...";
        }
        ctx.graphics.drawString(ctx.font, display, inputX + 3, y + (h - 8) / 2, EditorTheme.TEXT_PRIMARY);

        if (focused) {
            int cursorX = inputX + 3 + ctx.font.width(display);
            ctx.graphics.fill(cursorX, y + 2, cursorX + 1, y + h - 2, 0xFFFFFFFF);
        }
        renderTooltipIfHovered(ctx);
    }

    /** 建议弹层高度（限高 + 不超出屏幕底部；用绝对坐标计算弹层视觉位置） */
    private int popupHeight(UIContext ctx) {
        int popupH = Math.min(filtered.size(), MAX_VISIBLE) * SUGGESTION_H;
        int maxToBottom = ctx.screenHeight - (hitY() + h);
        if (popupH > maxToBottom) popupH = Math.max(0, maxToBottom);
        return popupH;
    }

    /** 浮层命中：建议弹层区域优先于所有普通组件 */
    @Override
    protected boolean overlayHit(UIContext ctx) {
        return isShowingSuggestions() && isInSuggestionArea(ctx);
    }

    @Override
    public void renderOverlay(UIContext ctx) {
        if (!showSuggestions || filtered.isEmpty()) return;

        int popupH = popupHeight(ctx);
        int totalH = filtered.size() * SUGGESTION_H;
        int maxScroll = Math.max(0, totalH - popupH);
        if (listScrollOffset > maxScroll) listScrollOffset = maxScroll;

        Matrix4f m = ctx.graphics.pose().last().pose();
        int px = x;
        int py = y + h;

        // Background
        BufferBuilder bb = new BufferBuilder(256);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        vertex(bb, m, px, py, 0xFF2F2F2F);
        vertex(bb, m, px, py + popupH, 0xFF2F2F2F);
        vertex(bb, m, px + w, py + popupH, 0xFF2F2F2F);
        vertex(bb, m, px + w, py, 0xFF2F2F2F);
        RenderSystem.enableBlend();
        BufferUploader.drawWithShader(bb.end());

        // Outline
        drawOutline(m, px, py, w, popupH, 0xFF555555);

        // Items
        int start = Math.max(0, listScrollOffset / SUGGESTION_H);
        int visible = popupH / SUGGESTION_H + 1;
        for (int i = start; i < Math.min(filtered.size(), start + visible); i++) {
            int itemY = py + i * SUGGESTION_H - listScrollOffset;
            if (itemY + SUGGESTION_H < py || itemY > py + popupH) continue;
            if (i == selectedIndex) {
                BufferBuilder hb = new BufferBuilder(64);
                hb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                vertex(hb, m, px, itemY, 0xFF444466);
                vertex(hb, m, px, itemY + SUGGESTION_H, 0xFF444466);
                vertex(hb, m, px + w, itemY + SUGGESTION_H, 0xFF444466);
                vertex(hb, m, px + w, itemY, 0xFF444466);
                BufferUploader.drawWithShader(hb.end());
            } else if (ctx.isMouseIn(hitX(), hitY() + h + i * SUGGESTION_H - listScrollOffset, w, SUGGESTION_H)) {
                BufferBuilder hb = new BufferBuilder(64);
                hb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                vertex(hb, m, px, itemY, 0xFF444444);
                vertex(hb, m, px, itemY + SUGGESTION_H, 0xFF444444);
                vertex(hb, m, px + w, itemY + SUGGESTION_H, 0xFF444444);
                vertex(hb, m, px + w, itemY, 0xFF444444);
                BufferUploader.drawWithShader(hb.end());
            }
            ctx.graphics.drawString(ctx.font, filtered.get(i), px + 4, itemY + (SUGGESTION_H - 8) / 2, 0xFFBBBBBB);
        }

        // Scroll bar
        if (totalH > popupH) {
            float barH = popupH * popupH / (float) totalH;
            float barY = popupH * listScrollOffset / (float) totalH;
            BufferBuilder sb = new BufferBuilder(64);
            sb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            vertex(sb, m, px + w - 3, (int) (py + barY), 0xFF666666);
            vertex(sb, m, px + w - 3, (int) (py + barY + barH), 0xFF666666);
            vertex(sb, m, px + w - 1, (int) (py + barY + barH), 0xFF666666);
            vertex(sb, m, px + w - 1, (int) (py + barY), 0xFF666666);
            BufferUploader.drawWithShader(sb.end());
        }
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (showSuggestions && filtered.size() > 0) {
            int popupH = popupHeight(ctx);
            int baseY = hitY() + h;
            for (int i = 0; i < filtered.size(); i++) {
                int itemY = baseY + i * SUGGESTION_H - listScrollOffset;
                if (itemY + SUGGESTION_H < baseY || itemY > baseY + popupH) continue;
                if (!ctx.isMouseIn(hitX(), itemY, w, SUGGESTION_H)) continue;
                selectSuggestion(i);
                return true;
            }
            showSuggestions = false;
            return true;
        }
        focused = isHovered(ctx);
        if (focused) {
            if (source != null) text = source.get();
            filterSuggestions();
            showSuggestions = true;
        }
        return focused;
    }

    public boolean isShowingSuggestions() { return showSuggestions && !filtered.isEmpty(); }

    /** Returns true if the given mouse position is within the suggestion popup area. */
    public boolean isInSuggestionArea(UIContext ctx) {
        if (!showSuggestions || filtered.isEmpty()) return false;
        int popupH = popupHeight(ctx);
        int hx = hitX(), hy = hitY() + h;
        return ctx.mouseX >= hx && ctx.mouseX < hx + w
            && ctx.mouseY >= hy && ctx.mouseY < hy + popupH;
    }

    @Override
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;

        if (showSuggestions && !filtered.isEmpty()) {
            if (keyCode == 265) {
                selectedIndex = Math.max(0, selectedIndex - 1);
                ensureVisible();
                return true;
            }
            if (keyCode == 264) {
                int max = filtered.size() - 1;
                selectedIndex = selectedIndex < 0 ? 0 : Math.min(max, selectedIndex + 1);
                ensureVisible();
                return true;
            }
            if (keyCode == 257 || keyCode == 258) {
                if (selectedIndex >= 0 && selectedIndex < filtered.size()) {
                    selectSuggestion(selectedIndex);
                } else if (!filtered.isEmpty()) {
                    selectSuggestion(0);
                }
                return true;
            }
            if (keyCode == 256) {
                showSuggestions = false;
                return true;
            }
        }

        if (keyCode == 259) {
            if (!text.isEmpty()) {
                text = text.substring(0, text.length() - 1);
                if (sink != null) sink.accept(text);
                filterSuggestions();
                showSuggestions = true;
            }
            return true;
        }
        return false;
    }

    public boolean charTyped(char c) {
        if (!focused) return false;
        if (!Character.isISOControl(c)) {
            text += c;
            if (sink != null) sink.accept(text);
            filterSuggestions();
            showSuggestions = true;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onScrolled(UIContext ctx, double scroll) {
        if (!showSuggestions || filtered.isEmpty()) return false;
        int totalH = filtered.size() * SUGGESTION_H;
        int popupH = popupHeight(ctx);
        if (!ctx.isMouseIn(hitX(), hitY() + h, w, popupH)) return false;
        listScrollOffset -= (int) scroll * SUGGESTION_H;
        int maxScroll = Math.max(0, totalH - popupH);
        if (listScrollOffset < 0) listScrollOffset = 0;
        if (listScrollOffset > maxScroll) listScrollOffset = maxScroll;
        return true;
    }

    public void clearFocus() {
        focused = false;
        showSuggestions = false;
    }
    public boolean isFocused() { return focused; }

    private void filterSuggestions() {
        String t = text.toLowerCase();
        filtered = candidates.stream()
                .filter(s -> s.toLowerCase().contains(t))
                .limit(100)
                .collect(Collectors.toList());
        selectedIndex = filtered.isEmpty() ? -1 : 0;
        listScrollOffset = 0;
    }

    private void selectSuggestion(int idx) {
        if (idx < 0 || idx >= filtered.size()) return;
        String val = filtered.get(idx);
        text = val;
        if (sink != null) sink.accept(val);
        showSuggestions = false;
        selectedIndex = -1;
        listScrollOffset = 0;
    }

    private void ensureVisible() {
        int popupH = Math.min(filtered.size(), MAX_VISIBLE) * SUGGESTION_H;
        int itemTop = selectedIndex * SUGGESTION_H;
        int itemBot = itemTop + SUGGESTION_H;
        if (itemTop < listScrollOffset) {
            listScrollOffset = itemTop;
        } else if (itemBot > listScrollOffset + popupH) {
            listScrollOffset = itemBot - popupH;
        }
    }

    private static void vertex(BufferBuilder b, Matrix4f m, float x, float y, int argb) {
        b.vertex(m, x, y, 0).color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >> 24) & 0xFF).endVertex();
    }

    private void drawOutline(Matrix4f m, int rx, int ry, int rw, int rh, int argb) {
        BufferBuilder bb = new BufferBuilder(256);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        vertex(bb, m, rx, ry, argb); vertex(bb, m, rx + rw, ry, argb); vertex(bb, m, rx + rw, ry + 1, argb); vertex(bb, m, rx, ry + 1, argb);
        vertex(bb, m, rx, ry + rh - 1, argb); vertex(bb, m, rx + rw, ry + rh - 1, argb); vertex(bb, m, rx + rw, ry + rh, argb); vertex(bb, m, rx, ry + rh, argb);
        vertex(bb, m, rx, ry, argb); vertex(bb, m, rx + 1, ry, argb); vertex(bb, m, rx + 1, ry + rh, argb); vertex(bb, m, rx, ry + rh, argb);
        vertex(bb, m, rx + rw - 1, ry, argb); vertex(bb, m, rx + rw, ry, argb); vertex(bb, m, rx + rw, ry + rh, argb); vertex(bb, m, rx + rw - 1, ry + rh, argb);
        BufferUploader.drawWithShader(bb.end());
    }
}
