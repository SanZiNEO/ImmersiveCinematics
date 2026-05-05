package com.immersivecinematics.immersive_cinematics.editor.widget;

import java.util.function.Consumer;

public class UIToggle extends UIComponent {
    private final String label;
    private final Consumer<Boolean> onToggle;
    private boolean value;

    public UIToggle(int x, int y, int w, int h, String label, boolean initial, Consumer<Boolean> onToggle) {
        super(x, y, w, h);
        this.label = label;
        this.value = initial;
        this.onToggle = onToggle;
    }

    public boolean value() {
        return value;
    }

    public void setValue(boolean v) {
        value = v;
    }

    @Override
    public void render(UIContext ctx) {
        int toggleW = 20;
        int bg = value ? 0xFF555555 : 0xFF333333;
        ctx.graphics.fill(x, y, x + toggleW, y + h, bg);
        ctx.graphics.renderOutline(x, y, toggleW, h, 0xFF555555);
        if (value) {
            ctx.graphics.fill(x + toggleW - h + 3, y + 3, x + toggleW - 3, y + h - 3, 0xFFAAAAAA);
        }
        ctx.graphics.drawString(ctx.font, label, x + toggleW + 5, y + (h - 8) / 2, 0xFF999999);
        renderTooltipIfHovered(ctx);
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        if (isHovered(ctx)) {
            value = !value;
            if (onToggle != null) onToggle.accept(value);
            return true;
        }
        return false;
    }
}
