package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

public class LetterboxLayer implements OverlayLayer {

    private static final int Z_INDEX = 0;
    private static final int BLACK = 0xFF000000;

    private float targetAspectRatio = 0.0f;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (targetAspectRatio <= 0f) return;

        float contentHeight = (float) screenWidth / targetAspectRatio;

        if (contentHeight < screenHeight) {
            float targetBarHeight = (screenHeight - contentHeight) / 2.0f;
            int topBarBottom = Math.round(targetBarHeight);
            int bottomBarTop = screenHeight - Math.round(targetBarHeight);
            guiGraphics.fill(0, 0, screenWidth, topBarBottom, BLACK);
            guiGraphics.fill(0, bottomBarTop, screenWidth, screenHeight, BLACK);
        }
    }

    @Override
    public boolean isVisible() {
        return targetAspectRatio > 0f;
    }

    @Override
    public int getZIndex() {
        return Z_INDEX;
    }

    @Override
    public void reset() {
        targetAspectRatio = 0f;
    }

    public void setAspectRatio(float ratio) {
        this.targetAspectRatio = ratio;
    }

    public float getAspectRatio() {
        return targetAspectRatio;
    }
}
