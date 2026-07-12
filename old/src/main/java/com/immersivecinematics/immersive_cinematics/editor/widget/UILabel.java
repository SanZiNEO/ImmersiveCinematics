package com.immersivecinematics.immersive_cinematics.editor.widget;

public class UILabel extends UIComponent {
    private String text;
    private int color;
    private boolean centered;

    public UILabel(int x, int y, String text, int color) {
        super(x, y, 0, 10);
        this.text = text;
        this.color = color;
    }

    public UILabel centered(boolean c) {
        centered = c;
        return this;
    }

    public void setText(String t) {
        text = t;
    }

    @Override
    public void render(UIContext ctx) {
        if (centered) {
            int tw = ctx.font.width(text);
            ctx.graphics.drawString(ctx.font, text, x + (w - tw) / 2, y, color);
        } else {
            ctx.graphics.drawString(ctx.font, text, x, y, color);
        }
    }
}
