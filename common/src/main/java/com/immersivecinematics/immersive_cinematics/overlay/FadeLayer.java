package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 全屏颜色覆盖层 — 用于淡入淡出和色彩滤镜
 * <p>
 * 渲染一个全屏的 ARGB 矩形，颜色由 clip 的 color 字段定义，
 * 透明度由关键帧的 opacity 插值控制。
 */
public class FadeLayer implements OverlayLayer {

    private static final int DEFAULT_Z_INDEX = 10;

    /** ARGB 颜色值（不含 alpha — alpha 由 targetOpacity 决定） */
    private int color = 0x000000;
    private float targetOpacity = 0f;
    private int zIndex = DEFAULT_Z_INDEX;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        int alpha = (int) (targetOpacity * 255);
        if (alpha <= 0) return;

        int argb = (alpha << 24) | (color & 0x00FFFFFF);
        guiGraphics.fill(0, 0, screenWidth, screenHeight, argb);
    }

    @Override
    public boolean isVisible() {
        return targetOpacity > 0.001f;
    }

    @Override
    public int getZIndex() {
        return zIndex;
    }

    @Override
    public void reset() {
        targetOpacity = 0f;
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

    public void setOpacity(float opacity) {
        this.targetOpacity = opacity;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
