package com.immersivecinematics.immersive_cinematics.editor.widget;

import java.util.function.Consumer;

public class UIButton extends UIComponent {
    private final String text;
    private Consumer<UIButton> onClick;
    private int color;
    private int hoverColor;
    private int textColor = 0xFFFFFF;

    public UIButton(int x, int y, int w, int h, String text, Consumer<UIButton> onClick) {
        super(x, y, w, h);
        this.text = text;
        this.onClick = onClick;
        this.color = 0xFF333333;
        this.hoverColor = 0xFF444444;
    }

    public UIButton color(int c, int hc) {
        color = c;
        hoverColor = hc;
        return this;
    }

    public UIButton textColor(int c) {
        textColor = c;
        return this;
    }

    public String getLabel() { return text; }

    public void setOnClick(Consumer<UIButton> c) {
        onClick = c;
    }

    @Override
    public void render(UIContext ctx) {
        int bg = isHovered(ctx) ? hoverColor : color;
        ctx.graphics.fill(x, y, x + w, y + h, bg);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF555555);
        int tw = ctx.font.width(text);
        ctx.graphics.drawString(ctx.font, text, x + (w - tw) / 2, y + (h - 8) / 2, textColor);
        renderTooltipIfHovered(ctx);
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        if (isHovered(ctx)) {
            if (onClick != null) onClick.accept(this);
            return true;
        }
        return false;
    }
}
