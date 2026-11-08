package com.immersivecinematics.immersive_cinematics.media.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * 通用帧捕获层：主 RenderTarget → 固定尺寸 FBO（GL_LINEAR 缩放）→ 强制 alpha=1 → glReadPixels 读回。
 *
 * <p>线程边界：{@link #create}、{@link #capture}、{@link #close} 都<b>必须</b>在渲染线程调用
 * （内部直接发 GL 调用）。{@link FrameSink#onFrame} 也在渲染线程同步回调。</p>
 *
 * <p>捕获源 = 主 RenderTarget（完整游戏画面，含 HUD 与后期合成）。输出尺寸由构造参数固定，
 * 缩放参数、alpha 强制为 1 的处理与源选择均不提供配置项。</p>
 *
 * <p>输出契约：{@link CapturedFrame} 的 RGBA 行序为<b>自顶向下</b>（第 0 行 = 屏幕顶部），
 * 即读回后已在捕获层内完成行翻转，消费者无需再翻转。</p>
 */
public final class FrameCapture {

    private final int width;
    private final int height;

    /** 复用的读回缓冲（RGBA8，width * height * 4），避免每帧分配 native 内存。 */
    private final ByteBuffer readBuffer;

    private int fboId = -1;
    private int texId = -1;

    /** 下一帧的序号；每次成功读回后自增。 */
    private long nextFrameId;

    private FrameCapture(int width, int height) {
        this.width = width;
        this.height = height;
        this.readBuffer = BufferUtils.createByteBuffer(width * height * 4);
        initFbo();
    }

    /**
     * 创建捕获器并立即初始化 FBO / 纹理。必须在渲染线程调用。
     *
     * @param width  捕获宽度（像素，&gt; 0）
     * @param height 捕获高度（像素，&gt; 0）
     * @return 捕获器实例；用完必须 {@link #close()}
     */
    public static FrameCapture create(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid capture size: " + width + "x" + height);
        }
        return new FrameCapture(width, height);
    }

    /**
     * 捕获一帧并回调 sink。渲染线程调用。
     *
     * <p>sink 为 {@code null}、FBO 未初始化（已 close）或源 RenderTarget 不可用时直接返回，
     * 不分配、不读回。</p>
     *
     * @param mc   客户端实例
     * @param sink 帧消费者；{@code null} 表示本帧不需要
     */
    public void capture(Minecraft mc, FrameSink sink) {
        if (sink == null) return;
        if (fboId == -1) return;
        if (mc == null) return;
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return;

        int w = width;
        int h = height;

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

                // 只写 alpha：把不透明画面统一刷成 alpha=1，避免浏览器端整帧透明。
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

        byte[] rgba = readBack(w, h);

        // OpenGL 读回自底向上，这里原地翻成自顶向下，消费者直接按屏幕行序使用。
        flipRows(rgba, w, h);

        sink.onFrame(new CapturedFrame(rgba, w, h, System.nanoTime(), nextFrameId++));
    }

    /** 读回 FBO 颜色附件（RGBA8，OpenGL 自底向上），复用字段缓冲；返回新分配的数组。 */
    private byte[] readBack(int w, int h) {
        int prevReadFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
            GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            readBuffer.clear();
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readBuffer);
            byte[] rgba = new byte[w * h * 4];
            readBuffer.get(rgba);
            return rgba;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
        }
    }

    /** 捕获宽度（像素）。 */
    public int width() {
        return width;
    }

    /** 捕获高度（像素）。 */
    public int height() {
        return height;
    }

    /** 释放 FBO 与纹理；幂等，可在渲染线程重复调用。 */
    public void close() {
        if (fboId != -1) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = -1;
        }
        if (texId != -1) {
            GL11.glDeleteTextures(texId);
            texId = -1;
        }
    }

    /**
     * 原地翻转行序（自底向上 ↔ 自顶向下）。纯函数：只依赖入参，不持有状态。
     *
     * @param rgba   RGBA8 像素，长度必须为 {@code width * height * 4}
     * @param width  宽度（像素，&gt; 0）
     * @param height 高度（像素，&gt; 0）
     */
    public static void flipRows(byte[] rgba, int width, int height) {
        if (rgba == null) {
            throw new IllegalArgumentException("rgba must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid frame size: " + width + "x" + height);
        }
        int stride = width * 4;
        if (rgba.length != stride * height) {
            throw new IllegalArgumentException(
                    "rgba length mismatch: expected " + (stride * height) + " but got " + rgba.length);
        }
        if (height < 2) return;

        byte[] row = new byte[stride];
        for (int y = 0; y < height / 2; y++) {
            int top = y * stride;
            int bottom = (height - 1 - y) * stride;
            System.arraycopy(rgba, top, row, 0, stride);
            System.arraycopy(rgba, bottom, rgba, top, stride);
            System.arraycopy(row, 0, rgba, bottom, stride);
        }
    }

    /** 建固定尺寸的 RGBA8 FBO 与颜色附件纹理（GL_LINEAR 缩放）。 */
    private void initFbo() {
        int w = width;
        int h = height;

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
    }
}
