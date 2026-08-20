package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;

import java.util.function.Consumer;

public class UIButton extends UIComponent {
    private String text;
    private String icon;
    private Consumer<UIButton> onClick;
    private int color;
    private int hoverColor;
    private int textColor = 0xFFFFFF;
    /** 文字左对齐（默认居中；折叠组标题等需要靠左的场景使用） */
    private boolean leftAlign;
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

    public UIButton leftAlign() {
        this.leftAlign = true;
        return this;
    }

    public UIButton icon(String name) {
        this.icon = name;
        return this;
    }

    public void setIcon(String name) {
        this.icon = name;
    }

    public String getLabel() { return text; }

    /** 动态改文本(如播放/暂停 toggle 图标切换) */
    public void setText(String t) {
        this.text = t;
    }

    public void setOnClick(Consumer<UIButton> c) {
        onClick = c;
    }

    @Override
    public void render(UIContext ctx) {
        int target = isHovered(ctx) ? hoverColor : color;
        displayColor = com.immersivecinematics.immersive_cinematics.editor.EditorTheme.lerpColor(displayColor, target, 0.25f);
        ctx.graphics.fill(x, y, x + w, y + h, displayColor);
        ctx.graphics.renderOutline(x, y, w, h, com.immersivecinematics.immersive_cinematics.editor.EditorTheme.TEXT_DISABLED);
        if (icon != null) {
            int iconSize = Math.max(8, Math.min(w, h) - 4);
            int iconColor = enabled ? textColor : 0xFF555555;
            EditorIcons.render(ctx, icon, x + (w - iconSize) / 2, y + (h - iconSize) / 2, iconSize, iconColor);
        } else {
            int tw = ctx.font.width(text);
            int tx = leftAlign ? x + 4 : x + (w - tw) / 2;
            ctx.graphics.drawString(ctx.font, text, tx, y + (h - 8) / 2, textColor);
        }
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
