package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 字幕覆盖层 — 在屏幕上渲染文字
 * <p>
 * 通过关键帧控制位置和透明度；x/y 为文字块中心（屏幕百分比，0.5 = 屏幕正中）。
 * 支持多行文字（\n 分隔）。
 */
public class SubtitleLayer implements OverlayLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Overlay");
    private static final int DEFAULT_Z_INDEX = 30;

    private String text = "";
    private float opacity = 0f;
    /** 屏幕百分比位置（0~1，文字块中心） */
    private float x = 0f;
    private float y = 0f;
    /** 字号倍数（1.0 = 原版 9px，矩阵缩放实现，同 MC title 机制） */
    private float fontScale = 1f;
    /** 固定字号后的百分比缩放（1.0 = 基准字号原尺寸，与 ImageLayer scale 语义一致） */
    private float scaleX = 1f;
    private float scaleY = 1f;
    private int zIndex = DEFAULT_Z_INDEX;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (opacity <= 0.001f || text == null || text.isEmpty()) return;

        int alpha = (int) (opacity * 255);
        // 幽灵文本根因：alpha 量化为 0~3 时，MC Font.adjustColor() 会把颜色补成全不透明
        // （0x00FFFFFF → 0xFFFFFFFF），导致低透明度文字以完全不透明渲染；肉眼本不可见，直接跳过
        if (alpha < 4) return;
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

        // 中心锚点：x/y 指向文字块中心，左上角 = 中心 − 缩放后块尺寸/2（fontScale×scale 已在 pose 中应用）
        float blockX = x * screenWidth - (maxLineWidth * fontScale * scaleX) / 2f;
        float blockY = y * screenHeight - (totalHeight * fontScale * scaleY) / 2f;

        // 亚像素平滑：pose 浮点平移（drawString 只收 int，直接传浮点坐标会量化成阶梯移动）
        // 字号缩放：两级合成一次矩阵——fontScale（原版 title 同款矩阵缩放）× scaleX/Y（图片同款百分比缩放）
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(blockX, blockY, 0);
        pose.scale(fontScale * scaleX, fontScale * scaleY, 0f);
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
        fontScale = 1f;
        scaleX = 1f;
        scaleY = 1f;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    /** 设置屏幕百分比位置（0~1，文字块中心） */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** 设置字号倍数（1.0 = 原版 9px；矩阵缩放实现，同 MC title 机制） */
    public void setFontScale(float fontScale) {
        this.fontScale = fontScale;
    }

    /** 设置固定字号后的百分比缩放（1.0 = 基准字号原尺寸） */
    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
