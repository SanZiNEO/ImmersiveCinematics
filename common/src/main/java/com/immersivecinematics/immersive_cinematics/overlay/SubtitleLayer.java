package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 字幕覆盖层 — 在屏幕上渲染文字
 * <p>
 * 统一参数（0.3.6 起，定稿见 {@code plans/0.3.6/variable-frame.md} §3.1）：
 * <ul>
 *   <li>x/y = <b>参考画布</b>归一化位置（0~1）——文字块（基准矩形）的中心，0.5 = 画布正中</li>
 *   <li>anchor_x/anchor_y = 缩放绕点（元素自身归一化；0.5 = 绕文字块中心缩放）</li>
 *   <li>scale_x/scale_y = 相对<b>基准尺寸</b>的倍数（文本层基准 = 当前字号下的文字块 ÷ 参考分辨率）</li>
 *   <li>font_scale = 字号倍数（1.0 = 原版 9px，矩阵缩放实现，同 MC title 机制）；它决定文字块本身的
 *       大小，即改变基准尺寸</li>
 *   <li>opacity = 透明度（0~1）</li>
 * </ul>
 * 几何全程在浮点比例域解算（{@link CanvasTransform#element}），只在绘制那一步落到像素。
 * 1920×1080 屏幕（画布 = 屏幕）+ 缺省参数下，与旧「屏幕百分比 + 文字块中心」口径逐像素等价。
 */
public class SubtitleLayer implements OverlayLayer {

    private String text = "";
    /** 字段初值 0：未被关键帧驱动 = 不可见（脚本缺省 opacity = 1.0，由 OverlayTrackPlayer 补齐） */
    private float opacity = 0f;
    /** 参考画布归一化位置（0~1，文字块中心） */
    private float x = CanvasTransform.DEFAULT_POSITION;
    private float y = CanvasTransform.DEFAULT_POSITION;
    /** 缩放绕点（元素自身归一化） */
    private float anchorX = CanvasTransform.DEFAULT_ANCHOR;
    private float anchorY = CanvasTransform.DEFAULT_ANCHOR;
    /** 字号倍数（1.0 = 原版 9px，矩阵缩放实现，同 MC title 机制） */
    private float fontScale = 1f;
    /** 固定字号后的百分比缩放（1.0 = 基准字号原尺寸，与 ImageLayer scale 语义一致） */
    private float scaleX = CanvasTransform.DEFAULT_SCALE;
    private float scaleY = CanvasTransform.DEFAULT_SCALE;
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

        // 元素框：基准尺寸 = 当前字号下的文字块（参考像素）；位置 = 文字块中心，缩放绕 anchor
        CanvasTransform.Placement canvas = CanvasTransform.canvas(
                screenWidth, screenHeight, CanvasTransform.FitMode.FIT);
        CanvasTransform.Rect box = CanvasTransform.element(x, y, anchorX, anchorY,
                maxLineWidth * fontScale, totalHeight * fontScale, scaleX, scaleY, canvas);

        // 亚像素平滑：pose 浮点平移（drawString 只收 int，直接传浮点坐标会量化成阶梯移动）
        // 字号缩放：两级合成一次矩阵——fontScale（原版 title 同款矩阵缩放）× scaleX/Y（百分比缩放）
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(box.x(), box.y(), 0);
        pose.scale(fontScale * scaleX, fontScale * scaleY, 0f);
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

    /** 设置参考画布归一化位置（0~1，文字块中心） */
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

    /** 设置固定字号后的百分比缩放（1.0 = 基准字号原尺寸） */
    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
