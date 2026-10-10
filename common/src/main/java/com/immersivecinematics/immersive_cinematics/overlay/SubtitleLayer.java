package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 字幕覆盖层 — 在屏幕上渲染文字
 *
 * 统一参数（口径见 {@code plans/0.3.6/variable-frame.md} §4.2）：
 * x/y = <b>窗口</b>归一化位置（0~1）——文字块（未缩放基准矩形）的中心，0.5 = 窗口正中；
 * anchor_x/anchor_y = 缩放绕点（元素自身归一化；0.5 = 绕文字块中心缩放）；
 * scale_x/scale_y = 相对<b>素材原始像素尺寸</b>的倍数（文本层素材 = font_scale 下的文字块像素）；
 * font_scale = 字号倍数（1.0 = 原版 9px，矩阵缩放实现，同 MC title 机制），决定文字块本身的像素尺寸；
 * opacity = 透明度（0~1）。
 *
 * 基准尺寸 B = 文字块像素（font_scale 下）× k（k = 分辨率转义，见
 * {@link CanvasTransform#resolutionEscape}）；几何框与绘制尺寸同乘 k。几何全程在浮点比例域解算
 * （{@link CanvasTransform#element}），只在绘制那一步落到像素。
 */
public class SubtitleLayer implements OverlayLayer {

    private String text = "";
    /** 字段初值 0：未被关键帧驱动 = 不可见（脚本缺省 opacity = 1.0，由 OverlayTrackPlayer 补齐） */
    private float opacity = 0f;
    /** 窗口归一化位置（0~1，文字块中心） */
    private float x = CanvasTransform.DEFAULT_POSITION;
    private float y = CanvasTransform.DEFAULT_POSITION;
    /** 缩放绕点（元素自身归一化） */
    private float anchorX = CanvasTransform.DEFAULT_ANCHOR;
    private float anchorY = CanvasTransform.DEFAULT_ANCHOR;
    /** 字号倍数（1.0 = 原版 9px，矩阵缩放实现，同 MC title 机制） */
    private float fontScale = 1f;
    /** 相对文字块像素（font_scale 下）的缩放倍数（1.0 = 基准字号原尺寸） */
    private float scaleX = CanvasTransform.DEFAULT_SCALE;
    private float scaleY = CanvasTransform.DEFAULT_SCALE;
    /** 编辑基准分辨率（像素，k 的分子口径）：缺省 1920×1080，由 OverlayTrackPlayer 送入 */
    private float baseWidth = CanvasTransform.DEFAULT_BASE_WIDTH;
    private float baseHeight = CanvasTransform.DEFAULT_BASE_HEIGHT;
    private int zIndex = CanvasTransform.DEFAULT_Z_INDEX;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (opacity <= 0.001f || text == null || text.isEmpty()) return;

        int alpha = (int) (opacity * 255);
        // ⚠️ MC 透明度补全坑（Font.adjustColor）：drawString 传入的颜色若 alpha 高 6 位为 0
        // （即 alpha 0~3，透明度 < 1.6%），会被 MC 视为"未指定 alpha"并强制补成 0xFF（完全不透明）——
        // 0x00FFFFFF → 0xFFFFFFFF，低透明度文字反而以满透明度渲染。
        // 规避：alpha < 4 时肉眼本不可见，直接跳过渲染（不要用 alpha=1~3 的"几乎透明"颜色走 Font）。
        // 注意：仅 Font.drawString 系列有此补全；ImageLayer 走 RenderSystem.setShaderColor（GPU 混合）不受影响。
        // 未来实现任何走 Font 的 fade/文字动画时都必须避开该区间。
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

        // 分辨率转义 k：在绘制空间（GUI 缩放空间）直接算 min(空间宽/W基, 空间高/H基)；退化 → 本帧不绘制
        float k = CanvasTransform.resolutionEscape(baseWidth, baseHeight, screenWidth, screenHeight);
        if (k <= 0f) return;

        // 元素框：基准 = 文字块像素（font_scale 下）× k；位置 = 窗口百分比（基准矩形中心），缩放绕 anchor
        CanvasTransform.Rect box = CanvasTransform.element(x, y, anchorX, anchorY,
                maxLineWidth * fontScale * k, totalHeight * fontScale * k,
                scaleX, scaleY, screenWidth, screenHeight);
        if (box.width() <= 0f || box.height() <= 0f) return;

        // 亚像素平滑：pose 浮点平移（drawString 只收 int，直接传浮点坐标会量化成阶梯移动）
        // 字号缩放：两级合成一次矩阵——fontScale（原版 title 同款矩阵缩放）× scaleX/Y（素材倍数）× k（分辨率转义）；
        // k 必须与元素框同乘，否则锚点 / 位置错位
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(box.x(), box.y(), 0);
        pose.scale(fontScale * scaleX * k, fontScale * scaleY * k, 0f);
        for (int i = 0; i < lines.length; i++) {
            guiGraphics.drawString(font, Component.literal(lines[i]), 0, i * lineHeight, color, false);
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
        scaleX = CanvasTransform.DEFAULT_SCALE;
        scaleY = CanvasTransform.DEFAULT_SCALE;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    /** 设置窗口归一化位置（0~1，文字块中心） */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** 设置缩放绕点（元素自身归一化，0.5 = 绕文字块中心缩放） */
    public void setAnchor(float anchorX, float anchorY) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    /** 设置字号倍数（1.0 = 原版 9px；矩阵缩放实现，同 MC title 机制） */
    public void setFontScale(float fontScale) {
        this.fontScale = fontScale;
    }

    /** 设置相对文字块像素（font_scale 下）的缩放倍数（1.0 = 基准字号原尺寸） */
    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    /** 设置编辑基准分辨率（像素）：k 的 W基/H基，≤ 0 = 退化（本层不绘制） */
    public void setBaseResolution(float width, float height) {
        this.baseWidth = width;
        this.baseHeight = height;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
