package com.immersivecinematics.immersive_cinematics.editor.widget;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class UIDropdown extends UIComponent {
    private final List<String> options;
    private final Supplier<Integer> source;
    private final Consumer<Integer> sink;
    private boolean expanded;

    public UIDropdown(int x, int y, int w, int h, List<String> options,
                      Supplier<Integer> source, Consumer<Integer> sink) {
        super(x, y, w, h);
        this.options = options;
        this.source = source;
        this.sink = sink;
    }

    @Override
    public void render(UIContext ctx) {
        int displayIndex = source != null ? source.get() : 0;
        ctx.graphics.fill(x, y, x + w, y + h, 0xFF2A2A2A);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF555555);

        String text = displayIndex >= 0 && displayIndex < options.size() ? options.get(displayIndex) : "";
        int tw = ctx.font.width(text);
        ctx.graphics.drawString(ctx.font, text, x + 4, y + (h - 8) / 2, 0xFFCCCCCC);
        ctx.graphics.drawString(ctx.font, "\u25BC", x + w - 12, y + (h - 8) / 2, 0xFF888888);

        if (expanded) {
            int listH = Math.min(options.size() * h, 120);
            ctx.graphics.fill(x, y + h, x + w, y + h + listH, 0xFF2F2F2F);
            ctx.graphics.renderOutline(x, y + h, w, listH, 0xFF555555);
            for (int i = 0; i < options.size(); i++) {
                int cy = y + h + i * h;
                if (ctx.isMouseIn(x, cy, w, h)) {
                    ctx.graphics.fill(x, cy, x + w, cy + h, 0xFF444444);
                }
                ctx.graphics.drawString(ctx.font, options.get(i), x + 4, cy + (h - 8) / 2, 0xFFBBBBBB);
            }
        }
        renderTooltipIfHovered(ctx);
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        if (expanded) {
            int optY = y + h;
            for (int i = 0; i < options.size(); i++) {
                int cy = optY + i * h;
                if (ctx.isMouseIn(x, cy, w, h)) {
                    expanded = false;
                    if (sink != null) sink.accept(i);
                    return true;
                }
            }
            expanded = false;
            return true;
        }
        if (isHovered(ctx)) {
            expanded = true;
            return true;
        }
        return false;
    }
}
