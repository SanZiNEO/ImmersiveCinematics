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
import java.util.concurrent.ConcurrentLinkedDeque;

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
 *
 * <p>缓冲池：内部保有 {@value #POOL_SIZE} 个 {@code width * height * 4} 的字节数组，读回时取一个、
 * 随 {@link CapturedFrame} 交出去，帧引用计数归零时自动归还。池空表示下游跟不上，本帧直接丢弃
 * 并计入 {@link #droppedFrames()}——不阻塞渲染线程、不额外分配。</p>
 */
public final class FrameCapture {

    /** 读回缓冲池容量。三个缓冲足以覆盖「编码器正在写 1 帧 + 队列里 1 帧 + 渲染线程 1 帧」。 */
    private static final int POOL_SIZE = 3;

    private final int width;
    private final int height;

    /**
     * 空闲读回缓冲（RGBA8，width * height * 4）。
     *
     * <p>{@link #capture} 在渲染线程取，{@link #recycle} 可能由编码器的写入线程在引用计数归零时调用，
     * 因此用并发容器。缓冲总数固定为 {@link #POOL_SIZE}，池里不可能超过这个数。</p>
     */
    private final ConcurrentLinkedDeque<byte[]> pool = new ConcurrentLinkedDeque<>();

    /**
     * glReadPixels 的落地缓冲（native 直连）。
     *
     * <p>必须是 direct：LWJGL 的 {@code glReadPixels(..., ByteBuffer)} 直接把
     * {@code Buffer.address} 当指针用，堆数组的 address 为 0（会往地址 0 写）。
     * 读回后一次性拷进池数组，仍保持每帧零分配。</p>
     */
    private final ByteBuffer readBuffer;

    /** 池空导致的丢帧数（渲染线程递增；统计线程读，故 volatile）。 */
    private volatile long droppedFrames;

    private int fboId = -1;
    private int texId = -1;

    /** 是否已 {@link #close()}；跨线程可见（编码线程归还缓冲时会读）。 */
    private volatile boolean closed;

    /** 下一帧的序号；每次成功读回后自增。 */
    private long nextFrameId;

    private FrameCapture(int width, int height) {
        this.width = width;
        this.height = height;
        int bytes = width * height * 4;
        this.readBuffer = BufferUtils.createByteBuffer(bytes);
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.addLast(new byte[bytes]);
        }
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
     * 不分配、不读回。缓冲池已被下游占满时本帧丢弃并计入 {@link #droppedFrames()}。</p>
     *
     * <p>sink 返回后本次投递的那一份引用即被释放；sink 若要把帧带出回调，必须先
     * {@link CapturedFrame#retain()}。</p>
     *
     * @param mc   客户端实例
     * @param sink 帧消费者；{@code null} 表示本帧不需要
     */
    public void capture(Minecraft mc, FrameSink sink) {
        if (sink == null) return;
        if (closed || fboId == -1) return;
        if (mc == null) return;
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) return;

        int w = width;
        int h = height;

        byte[] buffer = pool.pollFirst();
        if (buffer == null) {
            droppedFrames++;
            return;
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

        try {
            readBack(buffer, w, h);
            // OpenGL 读回自底向上，这里原地翻成自顶向下，消费者直接按屏幕行序使用。
            flipRows(buffer, w, h);
        } catch (RuntimeException e) {
            // 读回/翻转失败：缓冲不交给下游，直接还池
            recycle(buffer);
            throw e;
        }

        CapturedFrame frame = CapturedFrame.of(buffer, w, h, System.nanoTime(), nextFrameId++,
                () -> recycle(buffer));
        try {
            sink.onFrame(frame);
        } finally {
            frame.release();
        }
    }

    /**
     * 读回 FBO 颜色附件（RGBA8，OpenGL 自底向上）到调用方提供的数组。
     *
     * <p>用复用的 direct 缓冲落地再拷进池数组：每帧零分配，且不触碰 native 缓冲生命周期。
     * 拷贝量 = 一帧像素（720p 约 3.6 MB），相对 glReadPixels 本身的同步开销可忽略。</p>
     */
    private void readBack(byte[] out, int w, int h) {
        int prevReadFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
            GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            readBuffer.clear();
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readBuffer);
            readBuffer.get(out);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
        }
    }

    /**
     * 把缓冲归还池；已 close 或池已满时交给 GC。
     *
     * <p>调用线程不固定（可能是编码器的写入线程），池是并发容器；
     * {@code size()} 与 {@code addLast} 之间的竞态最多让缓冲晚一点回池，不会超出总数。</p>
     */
    private void recycle(byte[] buffer) {
        if (buffer == null) return;
        if (closed) return;
        if (pool.size() < POOL_SIZE) {
            pool.addLast(buffer);
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

    /** 因缓冲池被下游占满而丢弃的帧数；仅统计 {@link #capture} 阶段的丢弃。 */
    public long droppedFrames() {
        return droppedFrames;
    }

    /** 释放 FBO、纹理与缓冲池；幂等，可在渲染线程重复调用。 */
    public void close() {
        closed = true;
        pool.clear();
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
