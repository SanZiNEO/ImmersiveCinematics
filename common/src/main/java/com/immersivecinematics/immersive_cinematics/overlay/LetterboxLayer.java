package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 画幅层（letterbox）— 黑边
 * <p>
 * 统一参数（0.3.6 起，定稿见 {@code plans/0.3.6/variable-frame.md} §3.1）：画幅层基准尺寸 = (1, 1)
 * 铺满画布，故 x/y/anchor/scale 对它不生效；<b>opacity 有效</b>（黑边整体透明度，见
 * {@link #setOpacity}）。
 * <p>
 * 画两件事，都在画布范围内：
 * <ol>
 *   <li><b>画布留边</b>：参考画布按 Fit 映射到实际屏幕，屏幕宽高比 ≠ 16:9 时留下的边
 *       （屏幕更宽 → 左右留边，更窄 → 上下留边）。该区域不属画布（§4.1），画成黑边。</li>
 *   <li><b>画幅比黑边</b>：目标画幅比（{@code aspect_ratio}）比画布更宽时，画布上下留出的黑边
 *       （沿用原算法：{@code contentHeight = 画布宽 / ratio}，{@code contentHeight < 画布高} 时画上下两条）。</li>
 * </ol>
 * 16:9 屏幕下画布 = 屏幕，行为与旧实现逐像素一致（同一处 Math.round 舍入）。
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

        // 画布 → 屏幕的 Fit 映射；只在最终绘制那一步落到像素
        CanvasTransform.Placement canvas = CanvasTransform.canvas(
                screenWidth, screenHeight, CanvasTransform.FitMode.FIT);
        int color = alpha << 24;
        int canvasLeft = Math.round(canvas.offsetX());
        int canvasTop = Math.round(canvas.offsetY());
        int canvasRight = Math.round(canvas.offsetX() + canvas.width());
        int canvasBottom = Math.round(canvas.offsetY() + canvas.height());

        // ① 画布留边（黑边区不属画布）：16:9 屏幕下留边为空，一条都不画
        if (canvasLeft > 0) {
            guiGraphics.fill(0, 0, canvasLeft, screenHeight, color);
        }
        if (canvasRight < screenWidth) {
            guiGraphics.fill(canvasRight, 0, screenWidth, screenHeight, color);
        }
        if (canvasTop > 0) {
            guiGraphics.fill(0, 0, screenWidth, canvasTop, color);
        }
        if (canvasBottom < screenHeight) {
            guiGraphics.fill(0, canvasBottom, screenWidth, screenHeight, color);
        }

        // ② 画幅比黑边（画布内）
        float contentHeight = canvas.width() / targetAspectRatio;
        if (contentHeight < canvas.height()) {
            int barHeight = Math.round((canvas.height() - contentHeight) / 2.0f);
            guiGraphics.fill(canvasLeft, canvasTop, canvasRight, canvasTop + barHeight, color);
            guiGraphics.fill(canvasLeft, canvasBottom - barHeight, canvasRight, canvasBottom, color);
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

    /** 设置黑边不透明度（0~1，默认 1）：黑边整体（含画布留边）的 alpha */
    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }
}
