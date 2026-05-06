package com.immersivecinematics.immersive_cinematics.editor.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class UIContext {
    public final GuiGraphics graphics;
    public final Font font;
    public final int screenWidth;
    public final int screenHeight;
    public final float partialTick;
    public int mouseX;
    public int mouseY;
    public int mouseButton;
    public boolean ctrlDown;
    public boolean shiftDown;

    public UIContext(GuiGraphics graphics, Font font, int screenWidth, int screenHeight, float partialTick,
                     int mouseX, int mouseY) {
        this.graphics = graphics;
        this.font = font;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.partialTick = partialTick;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
    }

    public boolean isCtrlDown() { return ctrlDown; }
    public boolean isShiftDown() { return shiftDown; }

    public boolean isMouseIn(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
