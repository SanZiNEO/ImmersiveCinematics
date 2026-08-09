package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 字幕覆盖层 — 在屏幕上渲染文字
 * <p>
 * 通过关键帧控制位置和透明度。
 * 支持多行文字（\n 分隔）。
 */
public class SubtitleLayer implements OverlayLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Overlay");
    private static final int DEFAULT_Z_INDEX = 30;

    private String text = "";
    private float opacity = 0f;
    /** 屏幕百分比位置（0~1，文字块左上角） */
    private float x = 0f;
    private float y = 0f;
    private int zIndex = DEFAULT_Z_INDEX;
    /** 诊断：位置日志节流 */
    private long lastPosLog;

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

        // 左上角锚点：位置百分比 × 屏幕尺寸
        float blockX = x * screenWidth;
        float blockY = y * screenHeight;

        // 诊断：屏幕尺寸 + 文字实际渲染位置（节流 1s，控制台可见）
        long now = System.currentTimeMillis();
        if (now - lastPosLog >= 1000) {
            lastPosLog = now;
            LOGGER.info("OVERLAY subtitle: screen={}x{} pos=({}, {}) text={} opacity={}",
                    screenWidth, screenHeight, Math.round(blockX), Math.round(blockY),
                    text.replace('\n', ' '), opacity);
        }

        // 亚像素平滑：pose 浮点平移（drawString 只收 int，直接传浮点坐标会量化成阶梯移动）
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(blockX, blockY, 0);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineX = 0;
            int lineY = i * lineHeight;
            guiGraphics.drawString(font, Component.literal(line), lineX, lineY, color, false);
        }
        pose.popPose();
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

    /** 设置屏幕百分比位置（0~1，文字块左上角） */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
