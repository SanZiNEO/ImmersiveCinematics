package com.immersivecinematics.immersive_cinematics.editor.widget;

public interface IFocusable {
    boolean isFocused();
    void clearFocus();
    boolean keyPressed(int keyCode, int scanCode, int modifiers);
    boolean charTyped(char codePoint);
}
