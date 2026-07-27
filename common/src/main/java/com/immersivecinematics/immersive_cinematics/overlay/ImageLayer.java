package com.immersivecinematics.immersive_cinematics.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 图片覆盖层 — 在屏幕上渲染一张纹理
 * <p>
 * 通过关键帧控制位置、大小和透明度。
 * 图片纹理需通过 ResourceLocation 加载（打包为资源或动态注册）。
 */
public class ImageLayer implements OverlayLayer {

    private static final int DEFAULT_Z_INDEX = 20;

    private float opacity = 0f;
    private float x = 0f;
    private float y = 0f;
    private float width = 0f;
    private float height = 0f;
    private float anchorX = 0.5f;
    private float anchorY = 0.5f;
    private ResourceLocation texture = null;
    private int zIndex = DEFAULT_Z_INDEX;

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (opacity <= 0.001f || texture == null || width <= 0f || height <= 0f) return;

        float actualX = x - width * anchorX;
        float actualY = y - height * anchorY;

        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, opacity);
        guiGraphics.blit(texture,
                (int) actualX, (int) actualY,
                (int) width, (int) height,
                0, 0,
                (int) width, (int) height,
                (int) width, (int) height);
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

    public void setTexture(ResourceLocation texture) {
        this.texture = texture;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void setSize(float width, float height) {
        this.width = width;
        this.height = height;
    }

    public void setAnchor(float anchorX, float anchorY) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
