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
    public double mouseDX;
    public double mouseDY;

    private int scrollOffsetY = 0;

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

    /** Push a scroll offset: translates both mouse Y and rendering matrix. */
    public void pushScroll(int offset) {
        this.scrollOffsetY += offset;
        if (graphics != null) graphics.pose().translate(0, -offset, 0);
    }

    /** Pop (restore) a scroll offset. Must be paired with a previous pushScroll. */
    public void popScroll(int offset) {
        this.scrollOffsetY -= offset;
        if (graphics != null) graphics.pose().translate(0, offset, 0);
    }

    /** Get the mouse Y adjusted for accumulated scroll offset. */
    public int getAdjustedMouseY() { return mouseY + scrollOffsetY; }

    /** @deprecated Use pushScroll/popScroll instead. */
    @Deprecated
    public void shiftY(int y) {
        this.mouseY += y;
        if (this.graphics != null) {
            this.graphics.pose().translate(0, -y, 0);
        }
    }
}
