package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 画幅层（letterbox）— 黑边
 *
 * 画幅层无素材、铺满窗口，故 x/y/anchor/scale 对它不生效；<b>opacity 有效</b>（黑边整体透明度，
 * 见 {@link #setOpacity}）。黑边由窗口尺寸与目标画幅比（{@code aspect_ratio}）决定，<b>不乘 k</b>
 * （分辨率转义只作用于有素材的层）。
 *
 * 按目标画幅比与窗口宽高比的关系画一种黑边（两者相等时一条不画）：
 * 目标画幅比更宽（如 2.35:1 电影黑边）→ 上下黑边，{@code contentHeight = 窗口宽 / ratio < 窗口高}；
 * 目标画幅比更窄（如 16:9 窗口设 1.0）→ 左右黑边（pillarbox），{@code contentWidth = 窗口高 · ratio < 窗口宽}。
 *
 * 黑边是唯一写死 z（0，最底层）的内置层 —— 不在脚本 {@code z_index} 口径内。
 */
public class LetterboxLayer implements OverlayLayer {

    private static final int Z_INDEX = 0;

    private float targetAspectRatio = 0.0f;
    /** 黑边不透明度（0~1）：默认 1 = 纯黑边 */
    private float opacity = 1f;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (targetAspectRatio <= 0f) return;
        int alpha = (int) (opacity * 255);
        if (alpha <= 0) return;

        int color = alpha << 24;
        float contentHeight = screenWidth / targetAspectRatio;
        if (contentHeight < screenHeight) {
            // 目标画幅比窗口更宽 → 上下黑边（黑边高度 = 窗口高与内容高之差的一半，只在绘制落到像素）
            int barHeight = Math.round((screenHeight - contentHeight) / 2.0f);
            guiGraphics.fill(0, 0, screenWidth, barHeight, color);
            guiGraphics.fill(0, screenHeight - barHeight, screenWidth, screenHeight, color);
        } else if (contentHeight > screenHeight) {
            // 目标画幅比窗口更窄 → 左右黑边（pillarbox，与上下黑边同一遮幅语义）
            float contentWidth = screenHeight * targetAspectRatio;
            int barWidth = Math.round((screenWidth - contentWidth) / 2.0f);
            guiGraphics.fill(0, 0, barWidth, screenHeight, color);
            guiGraphics.fill(screenWidth - barWidth, 0, screenWidth, screenHeight, color);
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
        opacity = 1f;
    }

    public void setAspectRatio(float ratio) {
        this.targetAspectRatio = ratio;
    }

    public float getAspectRatio() {
        return targetAspectRatio;
    }

    /** 设置黑边不透明度（0~1，默认 1）：黑边整体（上下 / 左右）的 alpha */
    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }
}
