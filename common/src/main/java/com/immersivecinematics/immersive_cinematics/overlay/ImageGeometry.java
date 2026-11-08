package com.immersivecinematics.immersive_cinematics.overlay;

/**
 * 图像类覆盖层的几何解算 —— 图片 / 视频共用的同一套数学（定义层，不做渲染）。
 *
 * <p>口径与 {@link ImageLayer} 完全一致：先按编辑基准分辨率算分辨率转义
 * {@code k = min(播放宽/W基, 播放高/H基)}，再以「裁切后素材像素 × k」为未缩放基准尺寸，
 * 交给 {@link CanvasTransform#element} 解算元素框（位置 = 窗口归一化，缩放绕 anchor）。</p>
 *
 * <p>本类只做纯算术，不引用任何 Minecraft 类，可被任意线程调用；
 * {@link Rect} 是绘制空间像素（浮点），只在最终 blit 那一步落到整数。</p>
 */
public final class ImageGeometry {

    /** 全 0 矩形：退化（k ≤ 0 或基准尺寸非法）时的返回值，调用方按不可见处理。 */
    private static final Rect ZERO = new Rect(0f, 0f, 0f, 0f);

    private ImageGeometry() {
    }

    /** 元素框（绘制空间像素）：左上角 + 尺寸。 */
    public record Rect(float x, float y, float width, float height) {
    }

    /**
     * 解算图像元素的绘制矩形。
     *
     * <p>与 {@link ImageLayer#render} 的历史实现逐字同构：
     * {@code k = CanvasTransform.resolutionEscape(baseWidth, baseHeight, screenWidth, screenHeight)}；
     * 基准尺寸 {@code (srcW·k, srcH·k)}；其余交给 {@link CanvasTransform#element}。</p>
     *
     * @param x            位置 x（窗口归一化，未缩放基准矩形中心）
     * @param y            位置 y（窗口归一化）
     * @param anchorX      锚点 x（元素自身归一化，0 = 左缘、1 = 右缘）
     * @param anchorY      锚点 y（元素自身归一化，0 = 上缘、1 = 下缘）
     * @param srcW         裁切后素材宽（素材像素，> 0）
     * @param srcH         裁切后素材高（素材像素，> 0）
     * @param scaleX       横向缩放（相对素材原始像素尺寸的倍数）
     * @param scaleY       纵向缩放（相对素材原始像素尺寸的倍数）
     * @param baseWidth    编辑基准分辨率宽（像素，k 的分母口径）
     * @param baseHeight   编辑基准分辨率高（像素）
     * @param screenWidth  绘制空间宽（像素）
     * @param screenHeight 绘制空间高（像素）
     * @return 元素在绘制空间中的矩形；{@code k <= 0}（任一入参 ≤ 0）时返回全 0 矩形
     */
    public static Rect compute(float x, float y, float anchorX, float anchorY,
                               float srcW, float srcH, float scaleX, float scaleY,
                               float baseWidth, float baseHeight,
                               int screenWidth, int screenHeight) {
        float k = CanvasTransform.resolutionEscape(baseWidth, baseHeight, screenWidth, screenHeight);
        if (k <= 0f) return ZERO;

        CanvasTransform.Rect box = CanvasTransform.element(x, y, anchorX, anchorY,
                srcW * k, srcH * k, scaleX, scaleY, screenWidth, screenHeight);
        return new Rect(box.x(), box.y(), box.width(), box.height());
    }
}
