package com.immersivecinematics.immersive_cinematics.editor.widget;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class UIDropdown extends UIComponent {
    private final List<String> options;
    private final Supplier<Integer> source;
    private final Consumer<Integer> sink;
    private boolean expanded;
    private int highlightIndex = -1;
    private int maxListHeight = Integer.MAX_VALUE;
    private int listScrollOffset;
    private Consumer<Integer> onRightClick;

    public UIDropdown(int x, int y, int w, int h, List<String> options,
                      Supplier<Integer> source, Consumer<Integer> sink) {
        super(x, y, w, h);
        this.options = options;
        this.source = source;
        this.sink = sink;
    }

    public UIDropdown setHighlightIndex(int idx) {
        this.highlightIndex = idx;
        return this;
    }

    public UIDropdown setOnRightClick(Consumer<Integer> r) {
        this.onRightClick = r;
        return this;
    }

    public UIDropdown setMaxListHeight(int h) {
        this.maxListHeight = h;
        return this;
    }

    @Override
    public void render(UIContext ctx) {
        String rawText = "";
        if (source != null) {
            int idx = source.get();
            if (idx >= 0 && idx < options.size()) rawText = options.get(idx);
        }
        String text = rawText;
        int maxTextW = w - 18;
        while (ctx.font.width(text) > maxTextW && !text.isEmpty())
            text = text.substring(0, text.length() - 1);
        if (!text.equals(rawText) && !rawText.isEmpty()) text += "..";

        ctx.graphics.fill(x, y, x + w, y + h, 0xFF2A2A2A);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF555555);
        ctx.graphics.drawString(ctx.font, text, x + 4, y + (h - 8) / 2, 0xFFCCCCCC);
        ctx.graphics.drawString(ctx.font, "\u25BC", x + w - 12, y + (h - 8) / 2, 0xFF888888);

        if (expanded) {
            int totalH = options.size() * h;
            int listH = Math.min(totalH, maxListHeight);
            int maxScroll = Math.max(0, totalH - listH);
            if (listScrollOffset > maxScroll) listScrollOffset = maxScroll;

            ctx.graphics.fill(x, y + h, x + w, y + h + listH, 0xFF2F2F2F);
            ctx.graphics.renderOutline(x, y + h, w, listH, 0xFF555555);

            int itemStart = listScrollOffset / h;
            int visible = listH / h + 1;
            for (int i = itemStart; i < Math.min(options.size(), itemStart + visible); i++) {
                int itemY = y + h + i * h - listScrollOffset;
                int absItemBottom = y + h + i * h + h;
                if (absItemBottom < y + h || absItemBottom > y + h + listH) continue;

                boolean cur = (i == highlightIndex);
                int bg = cur ? 0xFF3A3A3A : ctx.isMouseIn(x, itemY, w, h) ? 0xFF444444 : 0xFF2F2F2F;
                ctx.graphics.fill(x, itemY, x + w, itemY + h, bg);
                if (!cur) {
                    ctx.graphics.drawString(ctx.font, options.get(i), x + 4, itemY + (h - 8) / 2, 0xFFBBBBBB);
                }
            }

            if (totalH > listH) {
                float barH = listH * listH / (float) totalH;
                float barY = listH * listScrollOffset / (float) totalH;
                ctx.graphics.fill(x + w - 3, (int) (y + h + barY), x + w - 1, (int) (y + h + barY + barH), 0xFF666666);
            }
        }
        renderTooltipIfHovered(ctx);
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        if (expanded) {
            int totalH = options.size() * h;
            int listH = Math.min(totalH, maxListHeight);
            for (int i = 0; i < options.size(); i++) {
                int itemY = y + h + i * h - listScrollOffset;
                if (itemY + h < y + h || itemY > y + h + listH) continue;
                if (!ctx.isMouseIn(x, itemY, w, h)) continue;
                expanded = false;
                if (i == highlightIndex) return true;
                if (ctx.mouseButton == 1 && onRightClick != null)
                    onRightClick.accept(i);
                else if (ctx.mouseButton == 0 && sink != null)
                    sink.accept(i);
                return true;
            }
            expanded = false;
            return true;
        }
        if (isHovered(ctx)) { expanded = true; return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(UIContext ctx, double scroll) {
        if (!expanded) return false;
        int totalH = options.size() * h;
        int listH = Math.min(totalH, maxListHeight);
        int scrollAreaY = y + h;
        if (!ctx.isMouseIn(x, scrollAreaY, w, listH)) return false;
        listScrollOffset -= (int) scroll * h;
        int maxScroll = Math.max(0, totalH - listH);
        if (listScrollOffset < 0) listScrollOffset = 0;
        if (listScrollOffset > maxScroll) listScrollOffset = maxScroll;
        return true;
    }
}
