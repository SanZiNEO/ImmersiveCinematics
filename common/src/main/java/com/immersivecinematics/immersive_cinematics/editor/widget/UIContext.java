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

    // ======== Viewport stack (bbs-style) ========
    private int viewportShiftX;
    private int viewportShiftY;

    /** Push a viewport: set scissor adjusted for cumulative viewport shift.
     *  The scissor rect moves with viewport shifts, creating the visual scroll effect. */
    public void pushViewport(int x, int y, int w, int h) {
        int sx = x - viewportShiftX;
        int sy = y - viewportShiftY;
        graphics.enableScissor(sx, sy, sx + w, sy + h);
    }

    /** Pop (disable) the current viewport scissor. */
    public void popViewport() {
        graphics.disableScissor();
    }

    /** Shift the viewport offset (called when scrolling).
     *  Only affects scissor placement, NOT mouseX/Y or the pose matrix. */
    public void shiftViewport(int dx, int dy) {
        viewportShiftX += dx;
        viewportShiftY += dy;
    }

    // ======== 滚动渲染平移（仅渲染矩阵；命中统一走绝对坐标 + UIComponent.scrollCompensation，不再改 mouseY） ========

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

    /** 滚动容器渲染平移：内容绘制下移 offset（视觉上内容上移）。只动渲染矩阵，不修改 mouseX/mouseY。 */
    public void pushScroll(int offset) {
        if (graphics != null) graphics.pose().translate(0, -offset, 0);
    }

    public void popScroll(int offset) {
        if (graphics != null) graphics.pose().translate(0, offset, 0);
    }
}
