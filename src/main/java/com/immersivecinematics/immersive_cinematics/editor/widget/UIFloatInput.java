package com.immersivecinematics.immersive_cinematics.editor.widget;

import java.util.function.Consumer;

public class UIFloatInput extends UIComponent {
    private final String label;
    private String text;
    private final Consumer<Float> onChange;
    private boolean focused;
    private float value;
    private float min;
    private float max;
    private float step;

    public UIFloatInput(int x, int y, int w, int h, String label, float initial, float min, float max, float step,
                        Consumer<Float> onChange) {
        super(x, y, w, h);
        this.label = label;
        this.value = initial;
        this.min = min;
        this.max = max;
        this.step = step;
        this.onChange = onChange;
        this.text = formatValue(value);
    }

    public float value() {
        return value;
    }

    public void setValue(float v) {
        value = v;
        text = formatValue(v);
    }

    @Override
    public void render(UIContext ctx) {
        int labelW = ctx.font.width(label) + 4;
        ctx.graphics.drawString(ctx.font, label, x, y + (h - 8) / 2, 0xFF999999);

        int inputX = x + labelW;
        int inputW = w - labelW;
        int bg = focused ? 0xFF3A3A3A : 0xFF2A2A2A;
        ctx.graphics.fill(inputX, y, inputX + inputW, y + h, bg);
        ctx.graphics.renderOutline(inputX, y, inputW, h, 0xFF555555);

        int tw = ctx.font.width(text);
        ctx.graphics.drawString(ctx.font, text, inputX + 4, y + (h - 8) / 2, 0xFFCCCCCC);

        if (focused) {
            int cursorX = inputX + 4 + tw;
            ctx.graphics.fill(cursorX, y + 2, cursorX + 1, y + h - 2, 0xFFFFFFFF);
        }
        renderTooltipIfHovered(ctx);
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        focused = isHovered(ctx);
        return focused;
    }

    @Override
    public boolean mouseScrolled(UIContext ctx, double scroll) {
        if (focused) {
            value = clamp(value + (float) (scroll > 0 ? step : -step));
            text = formatValue(value);
            if (onChange != null) onChange.accept(value);
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        if (keyCode == 259) {
            if (!text.isEmpty()) {
                text = text.substring(0, text.length() - 1);
            }
            return true;
        }
        if (keyCode == 257) {
            commitText();
            return true;
        }
        return false;
    }

    public boolean charTyped(char c) {
        if (!focused) return false;
        if (c == '-' && text.isEmpty()) {
            text = "-";
            return true;
        }
        if (c == '.' && !text.contains(".")) {
            text += ".";
            return true;
        }
        if (Character.isDigit(c)) {
            text += c;
            return true;
        }
        return false;
    }

    public void clearFocus() {
        if (focused) {
            commitText();
            focused = false;
        }
    }

    private void commitText() {
        try {
            value = clamp(Float.parseFloat(text));
        } catch (NumberFormatException ignored) {}
        text = formatValue(value);
        if (onChange != null) onChange.accept(value);
    }

    private float clamp(float v) {
        return Math.max(min, Math.min(max, v));
    }

    private static String formatValue(float v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.format("%.2f", v);
    }
}
