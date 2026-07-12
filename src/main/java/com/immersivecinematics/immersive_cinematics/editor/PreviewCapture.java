package com.immersivecinematics.immersive_cinematics.editor;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

public class PreviewCapture {
    private static int fboId = -1;
    private static int texId = -1;
    private static int prevW, prevH;

    public static void capture(Minecraft mc) {
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.viewWidth;
        int h = main.viewHeight;
        if (fboId == -1 || w != prevW || h != prevH) {
            initFbo(w, h);
        }

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fboId);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        GL11.glColorMask(false, false, false, true);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glColorMask(true, true, true, true);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId);
    }

    private static void initFbo(int w, int h) {
        destroy();
        texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texId, 0);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        prevW = w;
        prevH = h;
    }

    public static int getTextureId() {
        return texId;
    }

    public static int getWidth() { return prevW; }
    public static int getHeight() { return prevH; }

    public static void destroy() {
        if (fboId != -1) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = -1;
        }
        if (texId != -1) {
            GL11.glDeleteTextures(texId);
            texId = -1;
        }
    }
}
