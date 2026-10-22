package com.immersivecinematics.immersive_cinematics.overlay;

/**
 * 覆盖层统一几何 —— 分辨率转义与元素框解算（定义层，不做渲染）。
 *
 * 口径：取材与缩放参照素材自身，放置位置参照窗口（百分比）；分辨率转义为单一系数
 * {@code k = min(W播/W基, H播/H基)}（W播/H播 = 播放窗口，W基/H基 = 编辑基准分辨率）。
 * 本类只有默认值常量、{@link #resolutionEscape} 与 {@link #element}，不引用任何 Minecraft 类。
 *
 * 元素几何（像素）：
 * {@code B = (裁切后素材宽 · k, 裁切后素材高 · k)}（未缩放基准尺寸，scale = 1 时的上屏像素）；
 * {@code 中心 = (x · W播, y · H播)}（位置 = 未缩放基准矩形的中心）；
 * {@code TL0 = 中心 − B/2}；{@code A = TL0 + (anchor_x·B.x, anchor_y·B.y)}（锚点 = 缩放不动点）；
 * {@code TL = A − (anchor_x·B.x·s.x, anchor_y·B.y·s.y)}；{@code S = (B.x·s.x, B.y·s.y)}（上屏尺寸）。
 *
 * 缺省 {@code anchor = 0.5} 时元素中心恒为 {@code (x·W播, y·H播)}（绕中心缩放）；锚点非中心时缩放
 * 会改变元素视觉中心（同 CSS {@code transform-origin}）。归一化坐标不钳制：越界元素按公式落到窗口外。
 *
 * 全程浮点比例域，只在最终绘制那一步落到像素。绘制空间为 GUI 缩放空间（调用方传
 * {@code getGuiScaledWidth/Height}）；{@code k} 的口径是窗口帧缓冲物理像素，层内可用
 * {@code min(空间宽/W基, 空间高/H基)} 直接算（= 帧缓冲 {@code k} / {@code guiScale}，至多差 1px 舍入）。
 */
public final class CanvasTransform {

    /** 位置默认（窗口归一化，元素中心）：0.5 = 窗口正中。 */
    public static final float DEFAULT_POSITION = 0.5F;

    /** 锚点默认（元素自身归一化）：0.5 = 绕元素中心缩放。 */
    public static final float DEFAULT_ANCHOR = 0.5F;

    /** 缩放默认（相对素材原始像素尺寸的倍数）：1.0 = 素材 1:1 像素。 */
    public static final float DEFAULT_SCALE = 1.0F;

    /** 不透明度默认：1.0 = 不透明（与相机侧合成参数一致）。 */
    public static final float DEFAULT_OPACITY = 1.0F;

    /** 覆盖层顺序默认（z_index）：单一取值 10，越小越先绘制。 */
    public static final int DEFAULT_Z_INDEX = 10;

    /** 编辑基准分辨率缺省宽（像素）：脚本未声明 {@code base_resolution} 时的 W基。 */
    public static final float DEFAULT_BASE_WIDTH = 1920.0F;

    /** 编辑基准分辨率缺省高（像素）：脚本未声明 {@code base_resolution} 时的 H基。 */
    public static final float DEFAULT_BASE_HEIGHT = 1080.0F;

    private CanvasTransform() {
    }

    /** 元素框（绘制空间像素）：左上角 + 尺寸。全部浮点 —— 只在最终绘制那一步落到像素。 */
    public record Rect(float x, float y, float width, float height) {
    }

    /**
     * 分辨率转义系数：编辑基准分辨率 → 播放窗口的整体缩放，宽高共用单一系数。
     *
     * 返回 {@code min(wPlay / wBase, hPlay / hBase)}（contain 式：基准构图整体完整可见、不溢出播放窗口）。
     * 任一入参 ≤ 0 → 返回 0（调用方按退化不绘制处理）。
     *
     * @param wBase 编辑基准分辨率宽（像素）
     * @param hBase 编辑基准分辨率高（像素）
     * @param wPlay 播放窗口宽（像素）
     * @param hPlay 播放窗口高（像素）
     */
    public static float resolutionEscape(float wBase, float hBase, float wPlay, float hPlay) {
        if (wBase <= 0.0F || hBase <= 0.0F || wPlay <= 0.0F || hPlay <= 0.0F) return 0.0F;
        return Math.min(wPlay / wBase, hPlay / hBase);
    }

    /**
     * 解算一个覆盖层元素的绘制矩形（绘制空间像素；公式与缺省退化见类注释）。
     *
     * 位置 {@code x/y} 是窗口归一化坐标（未缩放基准矩形的中心）；基准尺寸以像素给出（已含 {@code k}）。
     * 基准尺寸 ≤ 0 → 返回全 0 矩形（调用方按不可见处理）。
     *
     * @param x          位置 x（窗口归一化，未缩放基准矩形中心）
     * @param y          位置 y（窗口归一化）
     * @param anchorX    锚点 x（元素自身归一化，0 = 左缘、1 = 右缘）
     * @param anchorY    锚点 y（元素自身归一化，0 = 上缘、1 = 下缘）
     * @param baseWidth  未缩放基准尺寸宽（像素 = 裁切后素材宽 × k，> 0）
     * @param baseHeight 未缩放基准尺寸高（像素 = 裁切后素材高 × k，> 0）
     * @param scaleX     横向缩放（相对素材原始像素尺寸的倍数，≥ 0）
     * @param scaleY     纵向缩放（相对素材原始像素尺寸的倍数，≥ 0）
     * @param playWidth  绘制空间宽 W播（像素，> 0）
     * @param playHeight 绘制空间高 H播（像素，> 0）
     * @return 元素在绘制空间中的矩形（浮点）；基准尺寸 ≤ 0 时尺寸为 0（调用方按不可见处理）
     */
    public static Rect element(float x, float y, float anchorX, float anchorY,
                               float baseWidth, float baseHeight,
                               float scaleX, float scaleY,
                               float playWidth, float playHeight) {
        if (baseWidth <= 0.0F || baseHeight <= 0.0F) return new Rect(0.0F, 0.0F, 0.0F, 0.0F);
        float boxWidth = baseWidth * scaleX;
        float boxHeight = baseHeight * scaleY;
        float centerX = x * playWidth;
        float centerY = y * playHeight;
        // 锚点相对元素中心的偏移：默认 0.5（绕中心缩放）时该项为 0 → 左上角 = 中心 − 尺寸/2
        float left = centerX - boxWidth / 2.0F + (anchorX - 0.5F) * (baseWidth - boxWidth);
        float top = centerY - boxHeight / 2.0F + (anchorY - 0.5F) * (baseHeight - boxHeight);
        return new Rect(left, top, boxWidth, boxHeight);
    }
}
