package com.immersivecinematics.immersive_cinematics.media.capture;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 唯一捕获入口：主 RenderTarget → 固定规格 FBO（GL_LINEAR 缩放、alpha 强制 1）→ 三缓冲 PBO 异步读回。
 *
 * <p>线程边界：{@link #register}、{@link #unregister}、{@link #onHudRendered}、{@link #close} 以及
 * {@link Consumer} 的全部回调都<b>必须</b>在渲染线程调用（内部直接发 GL 调用）。</p>
 *
 * <p>按需捕获：每帧先问所有消费者 {@link Consumer#needsFrame}，无人需要时零 GL 调用、零分配。
 * 规格取第一个非 null 的 {@link Consumer#captureSpec}；
 * 规格变化时重建 FBO / PBO / 缓冲池并通知所有消费者 {@link Consumer#onCaptureFormatChanged}。</p>
 *
 * <p>读回延迟：PBO 环有 3 个槽。第 N 帧把像素写进 {@code pbo[write]}，同时映射并交付
 * {@code pbo[(write + 1) % 3]}（即两帧之前写入的那份）；因此首两帧只写不读，稳态下每帧交付
 * 两帧前的画面。{@link CapturedFrame#ptsNanos()} 取该帧写入 PBO 的时刻。</p>
 *
 * <p>缓冲池：{@value #POOL_SIZE} 个 {@code width * height * 4} 字节数组，随 {@link CapturedFrame}
 * 交出去，引用计数归零时自动归还。池空表示下游跟不上，本帧直接丢弃并计入
 * {@link #droppedFrames()}——不阻塞渲染线程、不额外分配。</p>
 *
 * <p>异常隔离：{@link #onHudRendered} 整体兜底，任何异常只记录限频日志，绝不打断渲染帧；
 * GL 绑定状态在 finally 中恢复。</p>
 */
public final class CaptureHub {

    /** 全局唯一实例。 */
    public static final CaptureHub INSTANCE = new CaptureHub();

    /** PBO 环槽数。 */
    private static final int PBO_COUNT = 3;

    /** 读回缓冲池容量：覆盖「两帧在 PBO 环里 + 一帧在消费者手里 + 抖动余量」。 */
    private static final int POOL_SIZE = 6;

    /** 捕获尺寸下限（像素）。 */
    private static final int MIN_SIZE = 2;

    /** 捕获尺寸上限（像素）。 */
    private static final int MAX_SIZE = 4096;

    /** 帧率上限（仅作规格参数校验）。 */
    private static final int MAX_FPS = 1000;

    /** 异常 / 非法规格日志的限频间隔。 */
    private static final long LOG_INTERVAL_MS = 5000L;

    /**
     * 捕获规格。
     *
     * @param width  宽度（像素，偶数，2..4096）
     * @param height 高度（像素，偶数，2..4096）
     * @param fps    帧率（&gt; 0）
     */
    public record CaptureSpec(int width, int height, int fps) {}

    /** 捕获消费者。全部回调都在渲染线程执行，不得阻塞。 */
    public interface Consumer {

        /**
         * 本帧是否需要一帧画面。禁止阻塞、禁止分配。
         *
         * @param nowNanos {@link System#nanoTime()}
         */
        boolean needsFrame(long nowNanos);

        /**
         * 收到一帧。渲染线程调用，禁止阻塞。
         *
         * <p>{@code frame} 的这一份引用归消费者所有，必须 {@link CapturedFrame#release()}；
         * 需要跨线程持有时先 {@link CapturedFrame#retain()}。</p>
         *
         * @param frame 帧数据（RGBA8，行序自顶向下）
         */
        void onFrame(CapturedFrame frame);

        /** 捕获规格已重建。 */
        default void onCaptureFormatChanged(int width, int height, int fps) {}

        /** 本消费者期望的捕获规格；{@code null} 表示不表态。 */
        default CaptureSpec captureSpec(Minecraft mc) {
            return null;
        }

    }

    /** 消费者表：只在渲染线程增删与遍历。 */
    private final List<Consumer> consumers = new ArrayList<>();

    /** 空闲读回缓冲；归还可能来自持有帧的其他线程，故用并发容器。 */
    private final ConcurrentLinkedDeque<byte[]> pool = new ConcurrentLinkedDeque<>();

    /** PBO 名；-1 = 未创建。 */
    private final int[] pboIds = new int[PBO_COUNT];

    /** 每个 PBO 槽写入时刻（{@link System#nanoTime()}）。 */
    private final long[] slotNanos = new long[PBO_COUNT];

    /** 每个 PBO 槽是否已写入过有效数据。 */
    private final boolean[] slotValid = new boolean[PBO_COUNT];

    /** 池空导致的丢帧数（渲染线程递增，统计线程读）。 */
    private volatile long droppedFrames;

    /** 池内缓冲的期望字节数；-1 = 池正在重建，拒绝一切归还。 */
    private volatile int poolBytes = -1;

    /** 是否已 {@link #close()}。 */
    private volatile boolean closed;

    private int fboId = -1;
    private int texId = -1;

    private int captureWidth;
    private int captureHeight;
    private int captureFps;

    /** 下一次 {@code glReadPixels} 落地的 PBO 槽。 */
    private int pboWriteIndex;

    /** 下一帧的序号；每次成功交付后自增。 */
    private long nextFrameId;

    private CaptureHub() {
        Arrays.fill(pboIds, -1);
    }

    /** 注册消费者；重复注册忽略。 */
    public void register(Consumer consumer) {
        if (consumer == null || closed) return;
        if (!consumers.contains(consumer)) {
            consumers.add(consumer);
        }
    }

    /** 注销消费者；未注册时无操作。 */
    public void unregister(Consumer consumer) {
        if (consumer == null) return;
        consumers.remove(consumer);
    }

    /**
     * 每帧捕获入口：在 HUD 渲染完成之后调用（主 framebuffer 即最终画面）。
     *
     * <p>无人需要帧时零开销返回；其余路径的异常只记录限频日志，不打断渲染帧。</p>
     */
    public void onHudRendered(Minecraft mc) {
        if (closed) return;
        try {
            if (consumers.isEmpty()) return;

            long now = System.nanoTime();

            boolean need = false;
            for (int i = 0; i < consumers.size(); i++) {
                Consumer c = consumers.get(i);
                try {
                    need |= c.needsFrame(now);
                } catch (Exception e) {
                    ErrorLog.logRateLimited("Capture", "capture-needs-frame", LOG_INTERVAL_MS,
                            "消费者 needsFrame 回调异常（已跳过）：" + c.getClass().getName(), e);
                }
            }
            if (!need) return;
            if (mc == null) return;

            CaptureSpec spec = null;
            {
                for (int i = 0; i < consumers.size(); i++) {
                    Consumer c = consumers.get(i);
                    CaptureSpec s;
                    try {
                        s = c.captureSpec(mc);
                    } catch (Exception e) {
                        ErrorLog.logRateLimited("Capture", "capture-spec-query", LOG_INTERVAL_MS,
                                "消费者 captureSpec 回调异常（已跳过）：" + c.getClass().getName(), e);
                        continue;
                    }
                    if (s != null) {
                        spec = s;
                        break;
                    }
                }
            }
            if (spec == null) return;

            int w = spec.width();
            int h = spec.height();
            int fps = spec.fps();
            if (w < MIN_SIZE || w > MAX_SIZE || h < MIN_SIZE || h > MAX_SIZE
                    || (w & 1) != 0 || (h & 1) != 0 || fps <= 0 || fps > MAX_FPS) {
                ErrorLog.logRateLimited("Capture", "capture-spec", LOG_INTERVAL_MS,
                        "非法捕获规格 " + w + "x" + h + "@" + fps + "（要求偶数尺寸 " + MIN_SIZE
                                + ".." + MAX_SIZE + "、fps 1.." + MAX_FPS + "）：本帧跳过");
                return;
            }

            if (fboId == -1 || w != captureWidth || h != captureHeight || fps != captureFps) {
                rebuild(w, h, fps);
            }

            captureFrame(mc, now, w, h);
        } catch (Exception e) {
            ErrorLog.logRateLimited("Capture", "capture-hub", LOG_INTERVAL_MS,
                    "捕获异常（本帧跳过，下一帧重试）", e);
        }
    }

    /** 当前捕获宽度；尚未捕获过时为 0。 */
    public int width() {
        return captureWidth;
    }

    /** 当前捕获高度；尚未捕获过时为 0。 */
    public int height() {
        return captureHeight;
    }

    /** 当前捕获帧率；尚未捕获过时为 0。 */
    public int fps() {
        return captureFps;
    }

    /** 因缓冲池被下游占满而丢弃的帧数。 */
    public long droppedFrames() {
        return droppedFrames;
    }

    /** 释放 FBO / PBO / 缓冲池；幂等，可在渲染线程重复调用。 */
    public void close() {
        closed = true;
        poolBytes = -1;
        pool.clear();
        consumers.clear();
        deleteFbo();
        deletePbos();
        Arrays.fill(slotValid, false);
        pboWriteIndex = 0;
        captureWidth = 0;
        captureHeight = 0;
        captureFps = 0;
    }

    // ===== 捕获 =====

    /** 取缓冲 → blit → PBO 读回 → 交付两帧前的那份。 */
    private void captureFrame(Minecraft mc, long now, int w, int h) {
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.viewWidth <= 0 || main.viewHeight <= 0) return;

        int expectedBytes = w * h * 4;
        byte[] buffer = pool.pollFirst();
        if (buffer == null) {
            droppedFrames++;
            return;
        }
        if (buffer.length != expectedBytes) {
            // 旧规格残留（理论上回收端已按 poolBytes 拦下）：本帧丢弃，不还池。
            droppedFrames++;
            return;
        }

        int prevReadFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        boolean delivered = false;
        try {
            blitToFbo(main, w, h);

            int write = pboWriteIndex;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[write]);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
            GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);

            slotNanos[write] = now;
            slotValid[write] = true;

            // 两帧前写入的槽：首两帧尚未写入，只写不读。
            int read = (write + 1) % PBO_COUNT;
            if (slotValid[read]) {
                delivered = deliver(buffer, read, w, h);
            }
            pboWriteIndex = read;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
            if (!delivered) {
                // 未交付（首两帧 / 映射失败 / 异常）：本帧缓冲直接还池。
                recycle(buffer);
            }
        }
    }

    /** 主 RT → 固定规格 FBO，并把 alpha 统一刷成 1；结束后恢复 READ/DRAW、color mask、clear color。 */
    private void blitToFbo(RenderTarget main, int w, int h) {
        int prevReadFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer colorMaskBuf = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMaskBuf);
            boolean prevRed = colorMaskBuf.get(0) != 0;
            boolean prevGreen = colorMaskBuf.get(1) != 0;
            boolean prevBlue = colorMaskBuf.get(2) != 0;
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

                // 只写 alpha：不透明画面统一 alpha=1，避免下游整帧透明。
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

    /**
     * 映射 {@code pbo[read]} 并交付给所有消费者。
     *
     * @return {@code true} 表示缓冲所有权已转移给 {@link CapturedFrame}（由引用计数归还）；
     *         {@code false} 表示未交付，调用方负责 {@link #recycle(byte[])}
     */
    private boolean deliver(byte[] buffer, int read, int w, int h) {
        int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[read]);
        ByteBuffer mapped = null;
        try {
            mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
            if (mapped == null) {
                return false;
            }
            mapped.position(0);
            mapped.get(buffer, 0, buffer.length);

            // OpenGL 读回自底向上，翻成自顶向下后消费者直接按屏幕行序使用。
            flipRows(buffer, w, h);

            CapturedFrame frame = CapturedFrame.of(buffer, w, h, slotNanos[read], nextFrameId++,
                    () -> recycle(buffer));
            try {
                for (int i = 0; i < consumers.size(); i++) {
                    Consumer c = consumers.get(i);
                    frame.retain();
                    try {
                        c.onFrame(frame);
                    } catch (Exception e) {
                        frame.release();
                        ErrorLog.logRateLimited("Capture", "capture-consumer", LOG_INTERVAL_MS,
                                "消费者 onFrame 回调异常（本帧跳过该消费者）：" + c.getClass().getName(), e);
                    }
                }
            } finally {
                frame.release();
            }
            return true;
        } finally {
            if (mapped != null) {
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
        }
    }

    /** 原地翻转行序（自底向上 ↔ 自顶向下）。纯函数：只依赖入参。 */
    private static void flipRows(byte[] rgba, int width, int height) {
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

    /** 规格重建：删旧 FBO/PBO、建新、重填池、重置 PBO 环并通知消费者。 */
    private void rebuild(int w, int h, int fps) {
        deleteFbo();
        deletePbos();

        // 先封锁回收，避免旧尺寸缓冲在重建窗口里混进新池。
        poolBytes = -1;
        pool.clear();

        captureWidth = w;
        captureHeight = h;
        captureFps = fps;

        initFbo(w, h);
        initPbos(w, h);

        int bytes = w * h * 4;
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.addLast(new byte[bytes]);
        }
        poolBytes = bytes;

        Arrays.fill(slotValid, false);
        pboWriteIndex = 0;

        for (int i = 0; i < consumers.size(); i++) {
            Consumer c = consumers.get(i);
            try {
                c.onCaptureFormatChanged(w, h, fps);
            } catch (Exception e) {
                ErrorLog.logRateLimited("Capture", "capture-format-changed", LOG_INTERVAL_MS,
                        "消费者 onCaptureFormatChanged 回调异常：" + c.getClass().getName(), e);
            }
        }
    }

    // ===== GL 资源 =====

    /** 建固定规格的 RGBA8 FBO 与颜色附件纹理（GL_LINEAR 缩放）；结束后恢复 FBO / 纹理 / clear color 绑定。 */
    private void initFbo(int w, int h) {
        int prevFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer clearColorBuf = stack.mallocFloat(4);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColorBuf);
            float prevClearR = clearColorBuf.get(0);
            float prevClearG = clearColorBuf.get(1);
            float prevClearB = clearColorBuf.get(2);
            float prevClearA = clearColorBuf.get(3);

            texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            fboId = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texId, 0);
            GL11.glClearColor(0f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL11.glClearColor(prevClearR, prevClearG, prevClearB, prevClearA);
        }
    }

    /** 建 {@value #PBO_COUNT} 个 {@code w * h * 4} 的 {@code GL_STREAM_READ} PBO；结束后恢复绑定。 */
    private void initPbos(int w, int h) {
        int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        long bytes = (long) w * h * 4L;
        for (int i = 0; i < PBO_COUNT; i++) {
            pboIds[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
    }

    private void deleteFbo() {
        if (fboId != -1) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = -1;
        }
        if (texId != -1) {
            GL11.glDeleteTextures(texId);
            texId = -1;
        }
    }

    private void deletePbos() {
        for (int i = 0; i < PBO_COUNT; i++) {
            if (pboIds[i] != -1) {
                GL15.glDeleteBuffers(pboIds[i]);
                pboIds[i] = -1;
            }
        }
    }

    /**
     * 归还缓冲：已 close、池已满或尺寸与当前规格不符时交给 GC。
     *
     * <p>调用线程不固定（持有帧的消费者可能跨线程释放）；{@code size()} 与 {@code addLast}
     * 之间的竞态最多让缓冲晚一点回池，不会超出总数。</p>
     */
    private void recycle(byte[] buffer) {
        if (buffer == null || closed) return;
        if (buffer.length != poolBytes) return;
        if (pool.size() < POOL_SIZE) {
            pool.addLast(buffer);
        }
    }
}
