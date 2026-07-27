package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 画中画覆盖层 — 占位边框实现
 * <p>
 * Phase 1：仅渲染白色边框和半透明黑色填充，不包含实际摄像头画面。
 * Phase 2（0.3.5+）：接入第二个摄像头帧缓冲。
 */
public class PipLayer implements OverlayLayer {

    private static final int DEFAULT_Z_INDEX = 40;
    private static final int BORDER_COLOR = 0xFFFFFFFF;
    private static final int FILL_COLOR = 0x40000000;
    private static final int BORDER_WIDTH = 2;

    private float opacity = 0f;
    private float x = 0f;
    private float y = 0f;
    private float width = 0f;
    private float height = 0f;
    private float anchorX = 0.5f;
    private float anchorY = 0.5f;
    private int zIndex = DEFAULT_Z_INDEX;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (opacity <= 0.001f || width <= 0f || height <= 0f) return;

        float actualX = x - width * anchorX;
        float actualY = y - height * anchorY;
        int ix = (int) actualX;
        int iy = (int) actualY;
        int iw = (int) width;
        int ih = (int) height;

        // Semi-transparent fill
        int fillAlpha = (int) (opacity * 64); // 25% of full opacity
        int fillArgb = (fillAlpha << 24) | 0x00000000;
        guiGraphics.fill(ix, iy, ix + iw, iy + ih, FILL_COLOR);

        // White border (2px)
        int borderAlpha = (int) (opacity * 255);
        int borderArgb = (borderAlpha << 24) | 0x00FFFFFF;
        // Top
        guiGraphics.fill(ix, iy, ix + iw, iy + BORDER_WIDTH, BORDER_COLOR);
        // Bottom
        guiGraphics.fill(ix, iy + ih - BORDER_WIDTH, ix + iw, iy + ih, BORDER_COLOR);
        // Left
        guiGraphics.fill(ix, iy + BORDER_WIDTH, ix + BORDER_WIDTH, iy + ih - BORDER_WIDTH, BORDER_COLOR);
        // Right
        guiGraphics.fill(ix + iw - BORDER_WIDTH, iy + BORDER_WIDTH, ix + iw, iy + ih - BORDER_WIDTH, BORDER_COLOR);
    }

    @Override
    public boolean isVisible() {
        return opacity > 0.001f;
    }

    @Override
    public int getZIndex() {
        return zIndex;
    }

    @Override
    public void reset() {
        opacity = 0f;
        width = 0f;
        height = 0f;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void setSize(float width, float height) {
        this.width = width;
        this.height = height;
    }

    public void setAnchor(float anchorX, float anchorY) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
