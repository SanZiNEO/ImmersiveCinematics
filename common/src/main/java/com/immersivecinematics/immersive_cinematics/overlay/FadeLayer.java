package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.script.ColorMaskSpec;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 全屏颜色覆盖层 — 用于淡入淡出和色彩滤镜（0.3.6 起兼高级颜色遮罩）。
 * <p>
 * 纯色 + full + normal 仍走原有的 {@link GuiGraphics#fill} 快路径：铺满窗口的 ARGB 矩形，
 * 颜色由 clip 的 color / 关键帧 color 决定，透明度由关键帧 opacity 插值控制。
 * <p>
 * 声明了渐变 / rect / circle / 非 normal 混合时走 {@link ColorMaskRenderer}（专用 core shader）；
 * 几何与渐变参数由播放侧逐帧送入 {@link #setMaskGeometry} / {@link #setGradientGeometry}，
 * 颜色由 {@link #setColorRgb} 送入（{@link ColorMaskSpec#sampleColor(float)} 的缓存采样结果）。
 * <p>
 * 效果层无素材，<b>x/y/anchor/scale 对它不生效</b>（脚本写了也不读取）；填充<b>不乘 k</b>
 * （分辨率转义只作用于有素材的层）。有效 opacity 始终乘既有退场系数。
 */
public class FadeLayer implements OverlayLayer {

    /** 渐出时长（秒）——内部常量速率，不做脚本字段（设计 §6-2 只要求不误伤他实例） */
    private static final float FADE_OUT_SECONDS = 0.5f;

    /** 层归属（不透明 token = 建层方的播放实例引用）；{@code null} = 无主层 */
    private final Object owner;

    private int zIndex = CanvasTransform.DEFAULT_Z_INDEX;

    /** ARGB 颜色值（不含 alpha — alpha 由 targetOpacity 决定） */
    private int color = 0x000000;
    private float targetOpacity = 0f;

    /** 渐出进度（秒）；{@code <0} = 未在渐出 */
    private float fadeOutElapsed = -1f;

    /** 高级颜色遮罩定义（建层时一次；{@code null} = 旧 fade / 规则临时 fade，永远走快路径）。 */
    private ColorMaskSpec maskSpec;
    /** 高级路径渲染器（仅高级层持有；关闭时释放其 LUT 与共享资源引用）。 */
    private ColorMaskRenderer colorMaskRenderer;

    /** 关键帧 color 的逐帧缓存（0xRRGGBB）；{@code hasColorRgb=false} 时用 clip 级 {@link #color}。 */
    private int colorRgb;
    private boolean hasColorRgb;
    /** 已从 OverlayManager 移除并释放资源；之后不再绘制 / 重建 GPU 资源。 */
    private volatile boolean closed;

    // 逐帧几何（窗口比例 / 度；缺省值与执行契约一致）
    private float maskX = 0.5f;
    private float maskY = 0.5f;
    private float maskWidth = 1.0f;
    private float maskHeight = 1.0f;
    private float maskRadius = 0.5f;
    private float gradientAngle = 0.0f;
    private float gradientCenterX = 0.5f;
    private float gradientCenterY = 0.5f;
    private float gradientRadius = 0.5f;

    public FadeLayer(Object owner) {
        this.owner = owner;
    }

    @Override
    public Object owner() {
        return owner;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (closed) return;
        float opacity = effectiveOpacity();
        if (!(opacity > 0f)) return;

        ColorMaskSpec spec = maskSpec;
        if (spec == null || !isAdvancedMask(spec)) {
            // 纯色 + full + normal：保留既有 GuiGraphics.fill 快路径（不建 FBO / shader）
            int alpha = (int) (opacity * 255);
            if (alpha <= 0) return;
            int rgb = hasColorRgb ? colorRgb : color;
            guiGraphics.fill(0, 0, screenWidth, screenHeight, (alpha << 24) | (rgb & 0x00FFFFFF));
            return;
        }

        if (colorMaskRenderer == null) {
            colorMaskRenderer = new ColorMaskRenderer(spec);
        }
        colorMaskRenderer.render(guiGraphics, screenWidth, screenHeight, spec,
                hasColorRgb ? colorRgb : color, opacity,
                maskX, maskY, maskWidth, maskHeight, maskRadius,
                gradientAngle, gradientCenterX, gradientCenterY, gradientRadius);
    }

    /** 是否需要专用 shader：渐变 / 非 full 形状 / 非 normal 混合，任一成立即高级路径。 */
    private static boolean isAdvancedMask(ColorMaskSpec spec) {
        return spec.hasGradient()
                || !ColorMaskSpec.DEFAULT_MASK_SHAPE.equals(spec.maskShape())
                || !ColorMaskSpec.DEFAULT_BLEND_MODE.equals(spec.blendMode());
    }

    @Override
    public boolean isVisible() {
        return effectiveOpacity() > 0.001f;
    }

    @Override
    public int getZIndex() {
        return zIndex;
    }

    @Override
    public void reset() {
        targetOpacity = 0f;
        fadeOutElapsed = -1f;
    }

    @Override
    public void tick(float deltaTime) {
        if (!isAnimating()) return;
        fadeOutElapsed = Math.min(FADE_OUT_SECONDS, fadeOutElapsed + deltaTime);
    }

    /** 启动渐出：渲染不透明度从当前值（系数 1）线性降到 0；重复调用幂等。 */
    @Override
    public void startFadeOut() {
        if (fadeOutElapsed >= 0f) return;
        fadeOutElapsed = 0f;
    }

    /** 渐出中（含未启动渐出 = false；渐出走完 = false → 帧驱动随即硬停本实例）。 */
    @Override
    public boolean isAnimating() {
        return fadeOutElapsed >= 0f && fadeOutElapsed < FADE_OUT_SECONDS;
    }

    /**
     * 实际渲染不透明度 = 关键帧 opacity × 渐出系数（1 → 0）。
     * 关键帧在退场期间继续驱动 opacity，渐出只在其上叠加，不互相覆盖。
     */
    private float effectiveOpacity() {
        if (fadeOutElapsed < 0f) return targetOpacity;
        return targetOpacity * (1f - Math.min(1f, fadeOutElapsed / FADE_OUT_SECONDS));
    }

    /**
     * 从 #RRGGBB 十六进制字符串解析颜色值
     *
     * @param hex 颜色字符串，如 "#FF0000"
     */
    public void setColor(String hex) {
        if (hex == null || hex.isEmpty()) {
            color = 0x000000;
            return;
        }
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            int rgb = Integer.parseInt(clean, 16);
            color = rgb & 0x00FFFFFF;
        } catch (NumberFormatException e) {
            // 脚本配置写了非法颜色：这是作者该看到的错，WARN 可见（回退黑色继续渲染）
            org.slf4j.LoggerFactory.getLogger("ImmersiveCinematics/FadeLayer")
                    .warn("无效的 fade 颜色 '{}' → 使用黑色: {}", hex, e.getMessage());
            color = 0x000000;
        }
    }

    /**
     * 设置高级颜色遮罩定义（建层时一次）。{@code null} = 旧 fade / 规则临时 fade，保持快路径。
     * 高级层在这里创建唯一的 {@link ColorMaskRenderer}；LUT 等 GL 资源在首次绘制时惰性创建。
     */
    public void setMaskSpec(ColorMaskSpec spec) {
        this.maskSpec = spec;
        if (closed) return;
        if (colorMaskRenderer != null) {
            colorMaskRenderer.close();
            colorMaskRenderer = null;
        }
        if (spec != null && isAdvancedMask(spec)) {
            colorMaskRenderer = new ColorMaskRenderer(spec);
        }
    }

    /** 逐帧覆盖范围（窗口比例；circle 用 {@code radius}，rect 用 width / height）。 */
    public void setMaskGeometry(float x, float y, float width, float height, float radius) {
        this.maskX = x;
        this.maskY = y;
        this.maskWidth = width;
        this.maskHeight = height;
        this.maskRadius = radius;
    }

    /** 逐帧渐变几何（角度为度；中心为窗口比例；半径为短边比例）。 */
    public void setGradientGeometry(float angle, float centerX, float centerY, float radius) {
        this.gradientAngle = angle;
        this.gradientCenterX = centerX;
        this.gradientCenterY = centerY;
        this.gradientRadius = radius;
    }

    /** 逐帧纯色（0xRRGGBB；{@link ColorMaskSpec#sampleColor(float)} 的缓存结果）。 */
    public void setColorRgb(int rgb) {
        this.colorRgb = rgb & 0x00FFFFFF;
        this.hasColorRgb = true;
    }

    /** 释放本层持有的 GPU 资源（LUT + 共享 shader 引用计数）。由 OverlayManager 移除层时调用。 */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ColorMaskRenderer renderer = colorMaskRenderer;
        colorMaskRenderer = null;
        if (renderer != null) renderer.close();
    }

    public void setOpacity(float opacity) {
        this.targetOpacity = opacity;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
