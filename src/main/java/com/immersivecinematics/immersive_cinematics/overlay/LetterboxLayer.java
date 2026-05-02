package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 画幅比黑边层 — Letterbox（上下黑边）+ 渐变动画
 * <p>
 * 根据目标画幅比，在窗口上下绘制黑边，模拟电影宽银幕效果。
 * 永远只使用上下黑边（Letterbox），不使用左右黑边（Pillarbox），
 * 这与电影工业的标准做法一致。
 * <p>
 * 渐变动画：
 * <ul>
 *   <li>入场（FADE_IN）：黑边从 0 高度缓动到目标高度，产生"锁定"感</li>
 *   <li>退场（FADE_OUT）：黑边从目标高度缓动到 0，产生"交还操控"感</li>
 * </ul>
 * 缓动函数：smooth5(t) = 6t⁵ - 15t⁴ + 10t³（五次 Hermite，更平滑的缓入缓出）
 * <p>
 * 算法：
 * <ol>
 *   <li>计算目标画幅比下的内容高度：contentHeight = screenWidth / aspectRatio</li>
 *   <li>如果 contentHeight < screenHeight → 画上下黑边</li>
 *   <li>黑边高度 = 目标高度 × smooth(progress)，progress 由 tick() 驱动</li>
 *   <li>否则 → 不画（屏幕已经比目标窄，无需裁切）</li>
 * </ol>
 * <p>
 * 使用 GuiGraphics.fill() 绘制纯黑不透明矩形（0xFF000000），
 * 不涉及 OpenGL/FBO/shader，与光影包完美兼容。
 */
public class LetterboxLayer implements OverlayLayer {

    /** 层级索引：黑边在最底层 */
    private static final int Z_INDEX = 0;

    /** 纯黑不透明颜色 (ARGB) */
    private static final int BLACK = 0xFF000000;

    /** 过渡状态 */
    private enum TransitionState {
        HIDDEN,     // 不可见，aspectRatio = 0
        FADE_IN,    // 入场动画中，progress: 0 → 1
        VISIBLE,    // 完全可见，progress = 1
        FADE_OUT    // 退场动画中，progress: 1 → 0
    }

    private TransitionState transitionState = TransitionState.HIDDEN;

    /** 动画进度 [0, 1]，0 = 无黑边，1 = 完整黑边 */
    private float progress = 0f;

    /** 目标画幅比（宽/高），0.0 = 禁用黑边 */
    private float targetAspectRatio = 0.0f;

    /** 入场动画时长（秒），0 = 无动画即时出现 */
    private float fadeIn = 0.5f;

    /** 退场动画时长（秒），0 = 无动画即时消失 */
    private float fadeOut = 0.5f;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        // 显式守卫：避免 targetAspectRatio <= 0 时的隐式除零
        if (targetAspectRatio <= 0f) return;

        // 计算目标画幅比下的内容高度
        float contentHeight = (float) screenWidth / targetAspectRatio;

        if (contentHeight < screenHeight) {
            // 目标黑边高度
            float targetBarHeight = (screenHeight - contentHeight) / 2.0f;

            // 用缓动后的 progress 缩放黑边高度
            float easedProgress = MathUtil.smooth5(progress);
            float animatedBarHeight = targetBarHeight * easedProgress;

            int topBarBottom = Math.round(animatedBarHeight);
            int bottomBarTop = screenHeight - Math.round(animatedBarHeight);

            // 顶部黑边
            guiGraphics.fill(0, 0, screenWidth, topBarBottom, BLACK);
            // 底部黑边
            guiGraphics.fill(0, bottomBarTop, screenWidth, screenHeight, BLACK);
        }
        // contentHeight >= screenHeight → 屏幕已比目标窄，不画黑边
    }

    @Override
    public boolean isVisible() {
        return transitionState != TransitionState.HIDDEN;
    }

    @Override
    public int getZIndex() {
        return Z_INDEX;
    }

    @Override
    public void tick(float deltaTime) {
        switch (transitionState) {
            case FADE_IN -> {
                if (fadeIn <= 0f) {
                    progress = 1f;
                    transitionState = TransitionState.VISIBLE;
                } else {
                    progress += deltaTime / fadeIn;
                    if (progress >= 1f) {
                        progress = 1f;
                        transitionState = TransitionState.VISIBLE;
                    }
                }
            }
            case FADE_OUT -> {
                if (fadeOut <= 0f) {
                    progress = 0f;
                    transitionState = TransitionState.HIDDEN;
                } else {
                    progress -= deltaTime / fadeOut;
                    if (progress <= 0f) {
                        progress = 0f;
                        transitionState = TransitionState.HIDDEN;
                    }
                }
            }
        }
    }

    @Override
    public void startFadeOut() {
        if (transitionState == TransitionState.VISIBLE || transitionState == TransitionState.FADE_IN) {
            transitionState = TransitionState.FADE_OUT;
            // progress 保持当前值，从当前位置开始退出
        }
    }

    @Override
    public void reset() {
        transitionState = TransitionState.HIDDEN;
        progress = 0f;
        targetAspectRatio = 0f;
        // fadeIn / fadeOut 不重置，它们是配置项
    }

    // ========== 画幅比控制 ==========

    /**
     * 设置目标画幅比
     * <p>
     * 如果当前处于 HIDDEN 状态且 ratio > 0，自动触发 FADE_IN 入场动画。
     *
     * @param ratio 宽/高比，0.0 = 禁用黑边；
     *              常见值：2.35（变形宽银幕）、2.0（2:1）、1.778（16:9）
     */
    public void setAspectRatio(float ratio) {
        this.targetAspectRatio = ratio;
        if (ratio > 0f && transitionState == TransitionState.HIDDEN) {
            // 从隐藏到显示 → 启动 fade-in
            transitionState = TransitionState.FADE_IN;
            progress = 0f;
        }
        // VISIBLE 状态下直接更新目标比，无需动画
    }

    /**
     * 获取当前目标画幅比
     */
    public float getAspectRatio() {
        return targetAspectRatio;
    }

    // ========== 动画配置 ==========

    /**
     * 设置入场动画时长
     *
     * @param seconds 时长（秒），0 = 无动画即时出现
     */
    public void setFadeIn(float seconds) {
        this.fadeIn = seconds;
    }

    /**
     * 设置退场动画时长
     *
     * @param seconds 时长（秒），0 = 无动画即时消失
     */
    public void setFadeOut(float seconds) {
        this.fadeOut = seconds;
    }

    public float getFadeIn() {
        return fadeIn;
    }

    public float getFadeOut() {
        return fadeOut;
    }

    /**
     * 是否正在执行过渡动画（fade-in 或 fade-out）
     */
    @Override
    public boolean isAnimating() {
        return transitionState == TransitionState.FADE_IN || transitionState == TransitionState.FADE_OUT;
    }
}
