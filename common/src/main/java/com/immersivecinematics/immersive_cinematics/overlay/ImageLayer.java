package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.util.TextureLoader;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片覆盖层 — 在屏幕上渲染一张纹理
 * <p>
 * 坐标语义（百分比，跨屏幕一致）：
 * <ul>
 *   <li>x/y = 屏幕宽高的百分比（0~1）——元素左上角位置；0 = 贴屏幕零点，1 = 左上角在屏幕右/下边缘</li>
 *   <li>scale_x/scale_y = 相对原图分辨率的百分比乘数（1 = 原尺寸，0.5 = 半尺寸）</li>
 *   <li>opacity = 透明度（0~1）</li>
 * </ul>
 * 原图按像素分辨率载入，显示尺寸 = 原图宽高 × scale 乘数。
 */
public class ImageLayer implements OverlayLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Overlay");
    private static final int DEFAULT_Z_INDEX = 20;

    private float opacity = 0f;
    /** 屏幕百分比位置（0~1，元素左上角） */
    private float x = 0f;
    private float y = 0f;
    /** 相对原图尺寸的百分比乘数（1 = 原尺寸） */
    private float scaleX = 1f;
    private float scaleY = 1f;
    private ResourceLocation texture = null;
    private String fileName = null;
    private int zIndex = DEFAULT_Z_INDEX;
    /** 诊断：位置日志节流 */
    private long lastPosLog;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (opacity <= 0.001f || texture == null) return;

        int[] texSize = TextureLoader.getTextureSize(fileName);
        if (texSize == null) return;
        float dispW = texSize[0] * scaleX;
        float dispH = texSize[1] * scaleY;
        if (dispW <= 0f || dispH <= 0f) return;

        // 左上角锚点：位置百分比 × 屏幕尺寸
        float actualX = x * screenWidth;
        float actualY = y * screenHeight;

        // 诊断：屏幕尺寸 + 图片实际渲染位置（节流 1s，控制台可见）
        long now = System.currentTimeMillis();
        if (now - lastPosLog >= 1000) {
            lastPosLog = now;
            LOGGER.info("OVERLAY image: screen={}x{} pos=({}, {}) size={}x{} scale=({}, {}) opacity={}",
                    screenWidth, screenHeight, Math.round(actualX), Math.round(actualY),
                    Math.round(dispW), Math.round(dispH), scaleX, scaleY, opacity);
        }

        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, opacity);
        // 亚像素平滑：pose 浮点平移（blit 只收 int，直接传浮点坐标会量化成阶梯移动）
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(actualX, actualY, 0);
        guiGraphics.blit(texture,
                0, 0,
                (int) dispW, (int) dispH,
                0, 0,
                (int) dispW, (int) dispH,
                (int) dispW, (int) dispH);
        pose.popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
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
    }

    public void setTexture(String fileName, ResourceLocation texture) {
        this.fileName = fileName;
        this.texture = texture;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    /** 设置屏幕百分比位置（0~1，元素左上角） */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** 设置相对原图尺寸的百分比乘数（1 = 原尺寸） */
    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
