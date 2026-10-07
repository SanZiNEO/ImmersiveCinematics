package com.immersivecinematics.immersive_cinematics.overlay;

/**
 * 参考画布与设备适配 —— 覆盖层统一参数的坐标基准（<b>定义层</b>，不做任何渲染）。
 *
 * <p>见 {@code plans/0.3.6/variable-frame.md} §3（统一参数字段表）/ §4（参考画布与设备适配）。
 * 本类只承载三样东西：参考画布的常量、适配模式枚举、以及「素材 → 目标框」的纯映射函数；
 * 不引用任何 Minecraft 类，可独立验证。</p>
 *
 * <h2>参考画布</h2>
 * 所有覆盖层参数（位置 / 锚点 / 缩放 / 取材 / 适配）都以一个<b>固定宽高比</b>的参考画布为口径：
 * 归一化坐标 {@code 0~1}、原点在左上角（x 向右、y 向下），与既有 {@code ImageLayer} 的
 * x/y 口径、{@link com.immersivecinematics.immersive_cinematics.client.lane.LaneCompositor.Rect}
 * 的矩形口径一致。实际屏幕按 {@link FitMode} 映射过去。
 *
 * <p>参考画布宽高比取 {@code 16:9}（{@link #REFERENCE_ASPECT_RATIO}），分辨率取
 * {@code 1920×1080}（{@link #REFERENCE_WIDTH} / {@link #REFERENCE_HEIGHT}）作为归一化坐标的像素锚点。
 * 取 1920×1080 的意义：<b>在 1920×1080 的屏幕上、Fit 模式下，本模型退化为今天的
 * {@code ImageLayer} 语义</b>（{@code screenX(x) = x * 1920}，显示尺寸 = 原图像素 × scale），
 * 因此对现有脚本零语义漂移，只是把「屏幕百分比」换成「画布百分比」。</p>
 *
 * <h2>适配（Fit / Fill / Stretch）</h2>
 * 同一个 {@link #map} 同时服务两处，二者数学同构、只是两端不同：
 * <ul>
 *   <li><b>参考画布 → 实际屏幕</b>（{@link #canvas}）：解决不同设备 / 分辨率 / 宽高比下构图一致；</li>
 *   <li><b>素材 → 元素框</b>（{@link #map} 直接调用）：实现 §3 的「适配」参数（图片 / 相机纹理
 *       怎么铺进自己的元素框）。</li>
 * </ul>
 *
 * <p>宽高比不一致时的边界行为：<b>Fit</b> 完整放下、余量留边（屏幕比画布宽 → 左右黑边，
 * 反之 → 上下黑边；与 {@link LetterboxLayer} 现有算法同源）；<b>Fill</b> 等比铺满、溢出被裁
 * （偏移为负，画布超出的部分落在屏幕外）；<b>Stretch</b> 两轴独立缩放、直接铺满（会改变宽高比）。
 * 归一化坐标允许越界（不钳制）——越界的元素自然落在留边区 / 屏幕外，由屏幕边界裁剪。</p>
 */
public final class CanvasTransform {

    /** 参考画布宽高比（16:9）。全局唯一口径，所有覆盖层参数相对它表达。 */
    public static final float REFERENCE_ASPECT_RATIO = 16.0F / 9.0F;

    /** 参考画布宽（像素锚点）：归一化 x = 1 对应 1920 像素。 */
    public static final float REFERENCE_WIDTH = 1920.0F;

    /** 参考画布高（像素锚点）= {@link #REFERENCE_WIDTH} / {@link #REFERENCE_ASPECT_RATIO} = 1080。 */
    public static final float REFERENCE_HEIGHT = REFERENCE_WIDTH / REFERENCE_ASPECT_RATIO;

    private CanvasTransform() {
    }

    /**
     * 适配模式：素材怎么映射到目标框（与 olive Transform 的 {@code AutoScaleType} 三档一致）。
     */
    public enum FitMode {
        /** 完整放得下：等比缩到放得下为止，余量留边，不裁切、不变形。 */
        FIT,
        /** 铺满目标框：等比放大到覆盖整个目标框，溢出部分被裁掉。 */
        FILL,
        /** 直接拉伸：两轴各自缩放到目标框尺寸，会改变素材宽高比。 */
        STRETCH
    }

    /**
     * 一次适配的结果：素材左上角相对目标框左上角的偏移、素材在目标框内的显示尺寸、
     * 以及两个轴向的缩放系数（像素/像素）。
     *
     * <p>Fit / Fill 下 {@code scaleX == scaleY}（等比）；Stretch 下两者独立。
     * {@code width = srcWidth * scaleX}、{@code height = srcHeight * scaleY}。</p>
     */
    public record Placement(float offsetX, float offsetY, float width, float height,
                            float scaleX, float scaleY) {

        /** 退化结果（任一入参 ≤ 0 时返回）：不可见。 */
        public static final Placement EMPTY = new Placement(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    /**
     * 把尺寸为 {@code srcWidth × srcHeight} 的素材按 {@code mode} 映射进
     * {@code dstWidth × dstHeight} 的目标框。
     *
     * <p>返回的偏移以目标框左上角为原点（x 向右、y 向下），目标框居中：
     * Fit 时偏移 ≥ 0（留边），Fill 时偏移 ≤ 0（溢出被裁），Stretch 时偏移 = 0。</p>
     *
     * @param srcWidth  素材宽（> 0）
     * @param srcHeight 素材高（> 0）
     * @param dstWidth  目标框宽（> 0）
     * @param dstHeight 目标框高（> 0）
     * @param mode      适配模式（非 null）
     * @return 适配结果；任一尺寸 ≤ 0 时返回 {@link Placement#EMPTY}
     */
    public static Placement map(float srcWidth, float srcHeight, float dstWidth, float dstHeight,
                                FitMode mode) {
        if (srcWidth <= 0.0F || srcHeight <= 0.0F || dstWidth <= 0.0F || dstHeight <= 0.0F) {
            return Placement.EMPTY;
        }
        float scaleX;
        float scaleY;
        if (mode == FitMode.STRETCH) {
            scaleX = dstWidth / srcWidth;
            scaleY = dstHeight / srcHeight;
        } else {
            float byWidth = dstWidth / srcWidth;
            float byHeight = dstHeight / srcHeight;
            float scale = (mode == FitMode.FILL) ? Math.max(byWidth, byHeight)
                                                 : Math.min(byWidth, byHeight);
            scaleX = scale;
            scaleY = scale;
        }
        float width = srcWidth * scaleX;
        float height = srcHeight * scaleY;
        return new Placement((dstWidth - width) / 2.0F, (dstHeight - height) / 2.0F,
                width, height, scaleX, scaleY);
    }

    /**
     * 参考画布 → 实际屏幕的适配结果（{@link #map} 的特例，源 = {@link #REFERENCE_WIDTH} ×
     * {@link #REFERENCE_HEIGHT}，目标 = 屏幕像素尺寸）。
     *
     * <p>结果的 {@code offsetX/offsetY} 是画布左上角在屏幕上的像素位置，
     * {@code width/height} 是画布在屏幕上的像素尺寸（Fit 时 ≤ 屏幕，Fill 时 ≥ 屏幕）。</p>
     *
     * @param screenWidth  屏幕宽（像素，> 0）
     * @param screenHeight 屏幕高（像素，> 0）
     * @param mode         适配模式（非 null）
     */
    public static Placement canvas(float screenWidth, float screenHeight, FitMode mode) {
        return map(REFERENCE_WIDTH, REFERENCE_HEIGHT, screenWidth, screenHeight, mode);
    }

    /**
     * 画布归一化 x（0~1，元素中心 / 锚点）→ 屏幕像素 x。
     *
     * <p>{@code canvas} 为 {@link #canvas} 的结果。1920×1080 + Fit 下即 {@code x * 1920}。</p>
     */
    public static float screenX(float normalizedX, Placement canvas) {
        return canvas.offsetX() + normalizedX * canvas.width();
    }

    /**
     * 画布归一化 y（0~1，元素中心 / 锚点）→ 屏幕像素 y。
     *
     * <p>{@code canvas} 为 {@link #canvas} 的结果。1920×1080 + Fit 下即 {@code y * 1080}。</p>
     */
    public static float screenY(float normalizedY, Placement canvas) {
        return canvas.offsetY() + normalizedY * canvas.height();
    }
}
