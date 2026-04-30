package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 画幅比黑边层 — Letterbox（上下黑边）
 * <p>
 * 根据目标画幅比，在窗口上下绘制黑边，模拟电影宽银幕效果。
 * 永远只使用上下黑边（Letterbox），不使用左右黑边（Pillarbox），
 * 这与电影工业的标准做法一致。
 * <p>
 * 算法：
 * <ol>
 *   <li>计算目标画幅比下的内容高度：contentHeight = screenWidth / aspectRatio</li>
 *   <li>如果 contentHeight < screenHeight → 画上下黑边</li>
 *   <li>否则 → 不画（屏幕已经比目标窄，无需裁切）</li>
 * </ol>
 * <p>
 * 使用 GuiGraphics.fill() 绘制纯黑不透明矩形（0xFF000000），
 * 不涉及 OpenGL/FBO/shader，与光影包完美兼容。
 * <p>
 * aspectRatio = 0.0 时禁用黑边（isVisible() 返回 false）。
 */
public class LetterboxLayer implements OverlayLayer {

    /** 层级索引：黑边在最底层 */
    private static final int Z_INDEX = 0;

    /** 纯黑不透明颜色 (ARGB) */
    private static final int BLACK = 0xFF000000;

    /** 目标画幅比（宽/高），0.0 = 禁用黑边 */
    private float aspectRatio = 0.0f;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        // 计算目标画幅比下的内容高度
        float contentHeight = (float) screenWidth / aspectRatio;

        if (contentHeight < screenHeight) {
            // 内容比窗口矮 → 画上下黑边
            float barHeight = (screenHeight - contentHeight) / 2.0f;

            int topBarBottom = Math.round(barHeight);
            int bottomBarTop = screenHeight - Math.round(barHeight);

            // 顶部黑边
            guiGraphics.fill(0, 0, screenWidth, topBarBottom, BLACK);
            // 底部黑边
            guiGraphics.fill(0, bottomBarTop, screenWidth, screenHeight, BLACK);
        }
        // contentHeight >= screenHeight → 屏幕已比目标窄，不画黑边
    }

    @Override
    public boolean isVisible() {
        return aspectRatio > 0.0f;
    }

    @Override
    public int getZIndex() {
        return Z_INDEX;
    }

    @Override
    public void reset() {
        this.aspectRatio = 0.0f;
    }

    // ========== 画幅比控制 ==========

    /**
     * 设置目标画幅比
     *
     * @param ratio 宽/高比，0.0 = 禁用黑边；
     *              常见值：2.35（变形宽银幕）、2.0（2:1）、1.778（16:9）
     */
    public void setAspectRatio(float ratio) {
        this.aspectRatio = ratio;
    }

    /**
     * 获取当前目标画幅比
     */
    public float getAspectRatio() {
        return aspectRatio;
    }
}
