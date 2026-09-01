package com.immersivecinematics.immersive_cinematics.webui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * WebUI 专用低分辨率帧捕获。
 *
 * <p>把主 RenderTarget 缩放到固定 16:9 小 FBO，再 glReadPixels 读回 CPU。
 * 与旧 EditorScreen 的 PreviewCapture 分离，避免影响游戏内 Java 编辑器预览。</p>
 */
public class WebFrameCapture {

    public static final int TARGET_W = 1280;
    public static final int TARGET_H = 720;

    private static int fboId = -1;
    private static int texId = -1;
    private static int prevW = -1;
    private static int prevH = -1;

    private WebFrameCapture() {
    }

    /** 必须在渲染线程调用：主 RenderTarget → 小 FBO。 */
    public static void capture(Minecraft mc) {
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return;
        int w = TARGET_W;
        int h = TARGET_H;
        if (fboId == -1 || prevW != w || prevH != h) {
            initFbo(w, h);
        }

        int prevReadFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer colorMaskBuf = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMaskBuf);
            boolean prevRed   = colorMaskBuf.get(0) != 0;
            boolean prevGreen = colorMaskBuf.get(1) != 0;
            boolean prevBlue  = colorMaskBuf.get(2) != 0;
            boolean prevAlpha = colorMaskBuf.get(3) != 0;

            FloatBuffer clearColorBuf = stack.mallocFloat(4);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColorBuf);
            float prevClearR = clearColorBuf.get(0);
            float prevClearG = clearColorBuf.get(1);
            float prevClearB = clearColorBuf.get(2);
            float prevClearA = clearColorBuf.get(3);

            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fboId);
                GL30.glBlitFramebuffer(0, 0, main.viewWidth, main.viewHeight,
                        0, 0, w, h,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

                GL11.glColorMask(false, false, false, true);
                GL11.glClearColor(0f, 0f, 0f, 1f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
                GL11.glColorMask(prevRed, prevGreen, prevBlue, prevAlpha);
                GL11.glClearColor(prevClearR, prevClearG, prevClearB, prevClearA);
            }
        }
    }

    /** 读回小 FBO 的 RGBA 字节（OpenGL 自底向上）。 */
    public static byte[] readPixels() {
        if (fboId == -1) return new byte[0];
        int w = prevW;
        int h = prevH;
        if (w <= 0 || h <= 0) return new byte[0];

        int prevReadFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
            GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            byte[] bytes = new byte[w * h * 4];
            buf.get(bytes);
            return bytes;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
        }
    }

    public static int getWidth() {
        return TARGET_W;
    }

    public static int getHeight() {
        return TARGET_H;
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

    public static void destroy() {
        if (fboId != -1) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = -1;
        }
        if (texId != -1) {
            GL11.glDeleteTextures(texId);
            texId = -1;
        }
        prevW = -1;
        prevH = -1;
    }
}
