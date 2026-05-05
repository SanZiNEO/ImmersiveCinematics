package com.immersivecinematics.immersive_cinematics.editor.widget;

import java.util.function.Consumer;

public class UITextInput extends UIComponent {
    private final String label;
    private String text;
    private final Consumer<String> onChange;
    private boolean focused;

    public UITextInput(int x, int y, int w, int h, String label, String initial, Consumer<String> onChange) {
        super(x, y, w, h);
        this.label = label;
        this.text = initial != null ? initial : "";
        this.onChange = onChange;
    }

    public String value() { return text; }
    public void setValue(String v) { text = v != null ? v : ""; }

    @Override
    public void render(UIContext ctx) {
        int labelW = ctx.font.width(label) + 4;
        ctx.graphics.drawString(ctx.font, label, x, y + (h - 8) / 2, 0xFF999999);

        int inputX = x + labelW;
        int inputW = w - labelW;
        int bg = focused ? 0xFF3A3A3A : 0xFF2A2A2A;
        ctx.graphics.fill(inputX, y, inputX + inputW, y + h, bg);
        ctx.graphics.renderOutline(inputX, y, inputW, h, 0xFF555555);

        String display = text;
        int tw = ctx.font.width(display);
        if (tw > inputW - 6) {
            while (ctx.font.width(display + "...") > inputW - 6 && !display.isEmpty()) {
                display = display.substring(0, display.length() - 1);
            }
            display += "...";
        }
        ctx.graphics.drawString(ctx.font, display, inputX + 3, y + (h - 8) / 2, 0xFFCCCCCC);

        if (focused) {
            int cursorX = inputX + 3 + ctx.font.width(display);
            ctx.graphics.fill(cursorX, y + 2, cursorX + 1, y + h - 2, 0xFFFFFFFF);
        }
        renderTooltipIfHovered(ctx);
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        focused = isHovered(ctx);
        return focused;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        if (keyCode == 259) {
            if (!text.isEmpty()) {
                text = text.substring(0, text.length() - 1);
                fire();
            }
            return true;
        }
        return false;
    }

    public boolean charTyped(char c) {
        if (!focused) return false;
        if (c >= 32 && c < 127) {
            text += c;
            fire();
            return true;
        }
        return false;
    }

    public void clearFocus() { focused = false; }
    public boolean isFocused() { return focused; }

    private void fire() {
        if (onChange != null) onChange.accept(text);
    }
}
