package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 字幕覆盖层 — 在屏幕上渲染文字
 * <p>
 * 通过关键帧控制位置和透明度。
 * 支持多行文字（\n 分隔）。
 */
public class SubtitleLayer implements OverlayLayer {

    private static final int DEFAULT_Z_INDEX = 30;

    private String text = "";
    private float opacity = 0f;
    private float x = 0f;
    private float y = 0f;
    private float anchorX = 0.5f;
    private float anchorY = 0.5f;
    private int zIndex = DEFAULT_Z_INDEX;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (opacity <= 0.001f || text == null || text.isEmpty()) return;

        int alpha = (int) (opacity * 255);
        int color = (alpha << 24) | 0x00FFFFFF;

        var font = Minecraft.getInstance().font;
        String[] lines = text.split("\n", -1);
        int lineHeight = font.lineHeight;

        // Compute total text block dimensions for anchor
        int maxLineWidth = 0;
        for (String line : lines) {
            int w = font.width(line);
            if (w > maxLineWidth) maxLineWidth = w;
        }
        int totalHeight = lines.length * lineHeight;

        float blockX = x - maxLineWidth * anchorX;
        float blockY = y - totalHeight * anchorY;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineX = (int) blockX;
            int lineY = (int) (blockY + i * lineHeight);
            guiGraphics.drawString(font, Component.literal(line), lineX, lineY, color, false);
        }
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
        text = "";
        opacity = 0f;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void setAnchor(float anchorX, float anchorY) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
