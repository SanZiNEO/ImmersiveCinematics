package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class UITextInput extends UIComponent implements IFocusable {
    private final String label;
    private final Supplier<String> source;
    private final Consumer<String> sink;
    private String text;
    private boolean focused;
    /** A10：光标位置（UTF-16 索引），支持方向键/Home/End/插入/Ctrl+C/V/X */
    private int cursorPos;

    public UITextInput(int x, int y, int w, int h, String label,
                       Supplier<String> source, Consumer<String> sink) {
        super(x, y, w, h);
        this.label = label;
        this.source = source;
        this.sink = sink;
        this.text = source != null ? source.get() : "";
        this.cursorPos = text.length();
    }

    @Override
    public void render(UIContext ctx) {
        if (source != null) {
            if (!focused) {
                text = source.get();
                if (cursorPos > text.length()) cursorPos = text.length();
            }
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
            // A10：光标跟随 cursorPos（前缀宽度，clamp 到输入框内）
            int prefixW = ctx.font.width(text.substring(0, Math.min(cursorPos, text.length())));
            int cursorX = Math.min(inputX + 3 + prefixW, inputX + inputW - 4);
            ctx.graphics.fill(cursorX, y + 2, cursorX + 1, y + h - 2, 0xFFFFFFFF);
        }
        renderTooltipIfHovered(ctx);
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        focused = isHovered(ctx);
        if (focused && source != null) {
            text = source.get();
            cursorPos = text.length();
        }
        if (focused) {
            // A10：按点击位置估算字符索引
            int labelW = ctx.font.width(label) + 4;
            int inputX = x + labelW;
            int clickRel = ctx.mouseX - (inputX + 3);
            if (clickRel <= 0) {
                cursorPos = 0;
            } else {
                int idx = 0;
                for (; idx <= text.length(); idx++) {
                    if (ctx.font.width(text.substring(0, idx)) >= clickRel) break;
                }
                cursorPos = Math.min(idx, text.length());
            }
        }
        return focused;
    }

    private void commitText() {
        if (sink != null && text != null) {
            sink.accept(text);
        }
    }

    @Override
    public void clearFocus() {
        if (focused) {
            commitText();
            focused = false;
        }
    }

    @Override
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        boolean ctrl = (modifiers & 2) != 0;
        switch (keyCode) {
            case 263: cursorPos = Math.max(0, cursorPos - 1); return true;  // ←
            case 262: cursorPos = Math.min(text.length(), cursorPos + 1); return true;  // →
            case 268: cursorPos = 0; return true;  // Home
            case 269: cursorPos = text.length(); return true;  // End
            case 259:  // Backspace
                if (cursorPos > 0) {
                    text = text.substring(0, cursorPos - 1) + text.substring(cursorPos);
                    cursorPos--;
                }
                return true;
            case 261:  // Delete
                if (cursorPos < text.length()) {
                    text = text.substring(0, cursorPos) + text.substring(cursorPos + 1);
                }
                return true;
            case 257:  // Enter
                commitText();
                return true;
            case 67:  // Ctrl+C
                if (ctrl) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(text);
                    return true;
                }
                break;
            case 86:  // Ctrl+V
                if (ctrl) {
                    String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                    if (clip != null) {
                        StringBuilder sb = new StringBuilder();
                        for (char c : clip.toCharArray()) {
                            if (!Character.isISOControl(c)) sb.append(c);
                        }
                        text = text.substring(0, cursorPos) + sb + text.substring(cursorPos);
                        cursorPos += sb.length();
                    }
                    return true;
                }
                break;
            case 88:  // Ctrl+X
                if (ctrl) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(text);
                    text = "";
                    cursorPos = 0;
                    return true;
                }
                break;
        }
        return false;
    }

    public boolean charTyped(char c) {
        if (!focused) return false;
        if (!Character.isISOControl(c)) {
            text = text.substring(0, cursorPos) + c + text.substring(cursorPos);
            cursorPos++;
            return true;
        }
        return false;
    }

    @Override
    public boolean isFocused() { return focused; }

}
