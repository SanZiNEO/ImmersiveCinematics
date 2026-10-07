package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.util.GifAnimation;
import com.immersivecinematics.immersive_cinematics.util.TextureLoader;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片覆盖层 — 在屏幕上渲染一张纹理
 * <p>
 * 统一参数（0.3.6 起，定稿见 {@code plans/0.3.6/variable-frame.md} §3.1）：
 * <ul>
 *   <li>x/y = <b>参考画布</b>归一化位置（0~1）——未缩放基准矩形的中心，0.5 = 画布正中</li>
 *   <li>anchor_x/anchor_y = 缩放绕点（元素自身归一化；0.5 = 绕元素中心缩放）</li>
 *   <li>scale_x/scale_y = 相对<b>基准尺寸</b>的倍数（图形层基准 = 原图像素 ÷ 参考分辨率 1920×1080）；
 *       1 = 原图 1:1 像素</li>
 *   <li>source = {x,y,w,h} 取材（素材归一化）：先裁出素材子矩形</li>
 *   <li>fit = fit / fill / stretch：再把子矩形按该模式铺进元素框</li>
 *   <li>opacity = 透明度（0~1）</li>
 * </ul>
 * 几何全程在浮点比例域解算（{@link CanvasTransform#element}），只在 blit 那一步落到像素。
 * 1920×1080 屏幕（画布 = 屏幕）+ 缺省参数下，与旧「屏幕百分比 + 原图乘数」口径逐像素等价。
 */
public class ImageLayer implements OverlayLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Overlay");

    /** 字段初值 0：未被关键帧驱动 = 不可见（脚本缺省 opacity = 1.0，由 OverlayTrackPlayer 补齐） */
    private float opacity = 0f;
    /** 参考画布归一化位置（0~1，未缩放基准矩形中心） */
    private float x = CanvasTransform.DEFAULT_POSITION;
    private float y = CanvasTransform.DEFAULT_POSITION;
    /** 缩放绕点（元素自身归一化，0 = 左/上缘，1 = 右/下缘） */
    private float anchorX = CanvasTransform.DEFAULT_ANCHOR;
    private float anchorY = CanvasTransform.DEFAULT_ANCHOR;
    /** 相对基准尺寸（原图像素 ÷ 参考分辨率）的缩放倍数（1 = 原图 1:1 像素） */
    private float scaleX = CanvasTransform.DEFAULT_SCALE;
    private float scaleY = CanvasTransform.DEFAULT_SCALE;
    /** 取材（素材归一化 0~1）：裁出的子矩形，缺省整幅 */
    private float sourceX = 0f;
    private float sourceY = 0f;
    private float sourceW = 1f;
    private float sourceH = 1f;
    /** 适配：素材子矩形怎么铺进元素框 */
    private CanvasTransform.FitMode fit = CanvasTransform.FitMode.FIT;
    private ResourceLocation texture = null;
    private String fileName = null;
    private GifAnimation gif = null;
    private float time = 0f;
    private int zIndex = CanvasTransform.DEFAULT_Z_INDEX;
    /** 诊断：位置日志节流 */
    private long lastPosLog;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (opacity <= 0.001f || texture == null) return;

        if (gif != null) gif.update(time);

        int[] texSize = TextureLoader.getTextureSize(fileName);
        if (texSize == null || texSize[0] <= 0 || texSize[1] <= 0) return;
        int texW = texSize[0];
        int texH = texSize[1];

        // 取材：素材归一化 → 纹理像素（越界钳制到素材内），裁出子矩形
        float u0 = clamp01(sourceX);
        float v0 = clamp01(sourceY);
        float srcW = (clamp01(sourceX + sourceW) - u0) * texW;
        float srcH = (clamp01(sourceY + sourceH) - v0) * texH;
        if (srcW <= 0f || srcH <= 0f) return;

        // 元素框：位置 = 未缩放基准矩形中心，缩放绕 anchor；基准尺寸 = 原图像素（参考像素口径）
        CanvasTransform.Placement canvas = CanvasTransform.canvas(
                screenWidth, screenHeight, CanvasTransform.FitMode.FIT);
        CanvasTransform.Rect box = CanvasTransform.element(x, y, anchorX, anchorY,
                texW, texH, scaleX, scaleY, canvas);
        if (box.width() <= 0f || box.height() <= 0f) return;

        // 适配：子矩形按 fit 铺进元素框（与画布映射同一个纯函数）
        CanvasTransform.Placement content = CanvasTransform.map(srcW, srcH, box.width(), box.height(), fit);
        if (content.width() <= 0f || content.height() <= 0f) return;

        // 最终绘制：唯一一次落到像素
        float drawX = box.x() + content.offsetX();
        float drawY = box.y() + content.offsetY();
        int dispW = (int) content.width();
        int dispH = (int) content.height();
        if (dispW <= 0 || dispH <= 0) return;

        // fill 等比铺满时长边会溢出元素框：溢出部分裁掉（fit 留边 / stretch 精确铺满都不溢出，不裁剪）
        boolean clipped = content.width() > box.width() || content.height() > box.height();
        if (clipped) {
            guiGraphics.enableScissor(Math.round(box.x()), Math.round(box.y()),
                    Math.round(box.x() + box.width()), Math.round(box.y() + box.height()));
        }

        // 诊断：屏幕尺寸 + 图片实际渲染位置（节流 1s，控制台可见）
        long now = System.currentTimeMillis();
        if (now - lastPosLog >= 1000) {
            lastPosLog = now;
            LOGGER.info("OVERLAY image: screen={}x{} pos=({}, {}) size={}x{} scale=({}, {}) opacity={}",
                    screenWidth, screenHeight, Math.round(drawX), Math.round(drawY),
                    dispW, dispH, scaleX, scaleY, opacity);
        }

        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, opacity);
        // 亚像素平滑：pose 浮点平移（blit 只收 int，直接传浮点坐标会量化成阶梯移动）
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(drawX, drawY, 0);
        guiGraphics.blit(texture,
                0, 0,
                dispW, dispH,
                u0 * texW, v0 * texH,
                (int) srcW, (int) srcH,
                texW, texH);
        pose.popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        if (clipped) {
            guiGraphics.disableScissor();
        }
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
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
        texture = null;
        gif = null;
    }

    public void setTexture(String fileName, ResourceLocation texture) {
        this.fileName = fileName;
        this.texture = texture;
        this.gif = TextureLoader.getGif(fileName);
    }

    /** 设置当前全局时间（秒），GIF 层据此计算帧索引 */
    public void setTime(float globalTime) {
        this.time = globalTime;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    /** 设置参考画布归一化位置（0~1，未缩放基准矩形中心） */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** 设置缩放绕点（元素自身归一化，0.5 = 绕元素中心缩放） */
    public void setAnchor(float anchorX, float anchorY) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    /** 设置相对基准尺寸的缩放倍数（1 = 原图 1:1 像素） */
    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    /** 设置取材（素材归一化 0~1，裁出子矩形）：缺省 {0, 0, 1, 1} 整幅 */
    public void setSource(float x, float y, float width, float height) {
        this.sourceX = x;
        this.sourceY = y;
        this.sourceW = width;
        this.sourceH = height;
    }

    /** 设置适配模式：素材子矩形怎么铺进元素框 */
    public void setFit(CanvasTransform.FitMode fit) {
        this.fit = fit != null ? fit : CanvasTransform.FitMode.FIT;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
