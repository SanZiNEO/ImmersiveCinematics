package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 画中画覆盖层 — 占位边框实现
 * <p>
 * Phase 1：仅渲染白色边框和半透明黑色填充，不包含实际摄像头画面。
 * Phase 2（0.3.5+）：接入第二个摄像头帧缓冲。
 * <p>
 * 统一参数（0.3.6 起，定稿见 {@code plans/0.3.6/variable-frame.md} §3.1）——<b>已去像素化</b>：
 * <ul>
 *   <li>x/y = <b>参考画布</b>归一化位置（0~1）——未缩放基准矩形的中心，0.5 = 画布正中</li>
 *   <li>anchor_x/anchor_y = 缩放绕点（元素自身归一化；0.5 = 绕元素中心缩放）。
 *       旧口径的「定位锚」（{@code x − width·anchor}）已并入统一几何公式</li>
 *   <li>scale_x/scale_y = 相对<b>基准尺寸</b>的倍数（画面层基准 = (1,1) 铺满画布）——
 *       即占画布的比例：0.5 = 半个画布宽/高。旧的像素 {@code width}/{@code height} 字段已移除</li>
 *   <li>opacity = 透明度（0~1）</li>
 * </ul>
 * 无纹理，故不消费 {@code source} / {@code fit}（取材与适配是「素材 → 元素框」的映射，占位框没有素材）。
 * 边框粗细按画布高比例缩放（{@code 2 / 1080}：参考分辨率下即 2px），不写死像素。
 */
public class PipLayer implements OverlayLayer {

    /** 边框粗细（相对画布高）：2/1080 = 参考分辨率下 2px，随屏幕按比例缩放 */
    private static final float BORDER_RATIO = 2.0F / CanvasTransform.REFERENCE_HEIGHT;

    /** 填充不透明度系数（0.25）：与边框共用 opacity，边框用满 */
    private static final float FILL_ALPHA_RATIO = 0.25F;

    /** 字段初值 0：未被关键帧驱动 = 不可见（脚本缺省 opacity = 1.0，由 OverlayTrackPlayer 补齐） */
    private float opacity = 0f;
    /** 参考画布归一化位置（0~1，未缩放基准矩形中心） */
    private float x = CanvasTransform.DEFAULT_POSITION;
    private float y = CanvasTransform.DEFAULT_POSITION;
    /** 相对基准尺寸（铺满画布）的缩放倍数 */
    private float scaleX = CanvasTransform.DEFAULT_SCALE;
    private float scaleY = CanvasTransform.DEFAULT_SCALE;
    /** 缩放绕点（元素自身归一化） */
    private float anchorX = CanvasTransform.DEFAULT_ANCHOR;
    private float anchorY = CanvasTransform.DEFAULT_ANCHOR;
    private int zIndex = CanvasTransform.DEFAULT_Z_INDEX;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (opacity <= 0.001f) return;

        // 元素框：基准尺寸 = (1,1) 铺满画布（参考像素 = 参考分辨率）；位置 = 未缩放基准矩形中心
        CanvasTransform.Placement canvas = CanvasTransform.canvas(
                screenWidth, screenHeight, CanvasTransform.FitMode.FIT);
        CanvasTransform.Rect box = CanvasTransform.element(x, y, anchorX, anchorY,
                CanvasTransform.REFERENCE_WIDTH, CanvasTransform.REFERENCE_HEIGHT,
                scaleX, scaleY, canvas);
        if (box.width() <= 0f || box.height() <= 0f) return;

        // 最终绘制：唯一一次落到像素
        int ix = Math.round(box.x());
        int iy = Math.round(box.y());
        int iw = Math.round(box.width());
        int ih = Math.round(box.height());
        if (iw <= 0 || ih <= 0) return;
        int border = Math.max(1, Math.round(canvas.height() * BORDER_RATIO));

        // Semi-transparent fill
        int fillAlpha = (int) (opacity * FILL_ALPHA_RATIO * 255);
        int fillArgb = (fillAlpha << 24);
        guiGraphics.fill(ix, iy, ix + iw, iy + ih, fillArgb);

        // White border
        int borderArgb = ((int) (opacity * 255) << 24) | 0x00FFFFFF;
        // Top
        guiGraphics.fill(ix, iy, ix + iw, iy + border, borderArgb);
        // Bottom
        guiGraphics.fill(ix, iy + ih - border, ix + iw, iy + ih, borderArgb);
        // Left
        guiGraphics.fill(ix, iy + border, ix + border, iy + ih - border, borderArgb);
        // Right
        guiGraphics.fill(ix + iw - border, iy + border, ix + iw, iy + ih - border, borderArgb);
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
        opacity = 0f;
        scaleX = CanvasTransform.DEFAULT_SCALE;
        scaleY = CanvasTransform.DEFAULT_SCALE;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    /** 设置参考画布归一化位置（0~1，未缩放基准矩形中心） */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** 设置相对基准尺寸（铺满画布）的缩放倍数：0.5 = 半个画布宽/高 */
    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    /** 设置缩放绕点（元素自身归一化，0.5 = 绕元素中心缩放） */
    public void setAnchor(float anchorX, float anchorY) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
