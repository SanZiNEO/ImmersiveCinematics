package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;

import java.util.function.Consumer;

public class UIButton extends UIComponent {
    private final String text;
    private Consumer<UIButton> onClick;
    private int color;
    private int hoverColor;
    private int textColor = 0xFFFFFF;
    /** E1：显示中的颜色（向目标色逐帧插值，实现 hover 平滑过渡） */
    private int displayColor;

    public UIButton(int x, int y, int w, int h, String text, Consumer<UIButton> onClick) {
        super(x, y, w, h);
        this.text = text;
        this.onClick = onClick;
        this.color = 0xFF333333;
        this.hoverColor = 0xFF444444;
        this.displayColor = this.color;
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
        int target = isHovered(ctx) ? hoverColor : color;
        displayColor = com.immersivecinematics.immersive_cinematics.editor.EditorTheme.lerpColor(displayColor, target, 0.25f);
        ctx.graphics.fill(x, y, x + w, y + h, displayColor);
        ctx.graphics.renderOutline(x, y, w, h, com.immersivecinematics.immersive_cinematics.editor.EditorTheme.TEXT_DISABLED);
        int tw = ctx.font.width(text);
        ctx.graphics.drawString(ctx.font, text, x + (w - tw) / 2, y + (h - 8) / 2, textColor);
        renderTooltipIfHovered(ctx);
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (isHovered(ctx)) {
            EditorLogger.action(EditorLogger.SCREEN, "BUTTON_CLICK", "label=" + text);
            if (onClick != null) onClick.accept(this);
            return true;
        }
        return false;
    }
}
