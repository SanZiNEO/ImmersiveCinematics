package com.immersivecinematics.immersive_cinematics.webui;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.media.capture.CaptureHub;
import com.immersivecinematics.immersive_cinematics.media.capture.CapturedFrame;
import com.immersivecinematics.immersive_cinematics.media.video.Ffmpeg;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 编辑器预览流：捕获帧 → 每会话 H.264 编码（ffmpeg 不可用/失败时回退原始 RGBA）→ 会话发送队列。
 *
 * <p>线程模型：{@link #onFrame}、{@link #needsFrame}、{@link #setPaused}、{@link #tick}
 * 在渲染线程；{@link #onSessionOpened} / {@link #onSessionClosed} 在 WebSocket 网络线程。共享状态用并发容器 +
 * 每会话同步块保护。</p>
 *
 * <p>捕获由 {@link CaptureHub} 统一调度：本类只声明是否需要帧与期望规格，帧率节流在
 * {@link #needsFrame} 完成，导出期间 hub 的覆盖规格会让预览自动跟随导出尺寸/帧率。</p>
 *
 * <p>每个会话独立：编码器（独立 ffmpeg 进程）、RGBA 回退标志、相对该会话首帧的 PTS 基准。
 * 编码器失败只影响本会话，其他会话与渲染线程不受影响。</p>
 */
public final class PreviewStreamer implements CaptureHub.Consumer {

    /** 进程内单例：预览是客户端全局能力，会话由 {@link WebEditorServer} 统一登记。 */
    public static final PreviewStreamer INSTANCE = new PreviewStreamer();

    /** 统计推送间隔（ms）：1s 一次。 */
    private static final long STATS_INTERVAL_MS = 1000L;

    /**
     * 暂停刷新的帧预算上限。
     *
     * <p>为什么不是 1 帧：AU 切分以 AUD 为边界，单帧的 AU 要等**下一个** AUD 才被输出；同时
     * 编码器输入队列容量只有 1，ffmpeg 启动慢时连续提交会被挤掉。因此刷新期间持续供帧，
     * 直到每个会话都**真正产出了一帧**（H.264 以 AU 回调为准，RGBA 以入队为准）或预算耗尽。</p>
     */
    private static final int PAUSED_REFRESH_FRAME_BUDGET = 20;

    private static final String LOG_CATEGORY = "WebUI";

    /**
     * 编码器关闭器：单线程常驻 daemon。
     *
     * <p>{@code PreviewEncoder.close()} 要销毁进程并等待退出（最长 ~600ms），绝不能在渲染线程同步执行。
     * 每个编码器实例只在字段清空时提交一次，关闭器串行回收，不留下僵尸进程。</p>
     */
    private static final ExecutorService ENCODER_CLOSER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IC-PreviewEnc-closer");
        t.setDaemon(true);
        return t;
    });

    /** 已连接会话 → 该会话的流状态；键集合与 {@link WebEditorServer} 的会话表同源。 */
    private final Map<WebSocketSession, SessionStream> sessions = new ConcurrentHashMap<>();

    private final PreviewConfig config = PreviewConfig.fixed();

    /** 预览暂停（编辑器播放已激活且处于暂停）：不捕获、不编码，帧直接释放。 */
    private volatile boolean paused;

    /** 暂停期间的待发刷新帧数（见 {@link #PAUSED_REFRESH_FRAME_BUDGET}）。 */
    private volatile int pausedRefreshFrames;

    private long lastStatsMs;

    private PreviewStreamer() {
    }

    /** 新会话登记（WebSocket 升级成功后立即调用）。 */
    public void onSessionOpened(WebSocketSession session) {
        if (session == null) return;
        sessions.put(session, new SessionStream(session));
    }

    /** 会话关闭：释放该会话编码器与待发帧，不留下编码进程。 */
    public void onSessionClosed(WebSocketSession session) {
        if (session == null) return;
        SessionStream stream = sessions.remove(session);
        if (stream != null) {
            stream.closeStream();
        }
    }

    /**
     * 本帧是否需要捕获（渲染线程，禁止阻塞/分配）。
     *
     * <p>服务在跑且有客户端是前提；暂停时只供刷新帧；未暂停时每帧都采（采样无上限），
     * 发送节流在 {@link SessionStream#submitLocked} 按预览帧率执行。</p>
     */
    @Override
    public boolean needsFrame(long nowNanos) {
        if (!WebEditorServer.INSTANCE.isRunning() || !WebEditorServer.INSTANCE.hasClients()) {
            return false;
        }
        if (paused) {
            return pausedRefreshFrames > 0;
        }
        // 采样无上限：未暂停时每帧都交给 hub；发送节流在 submitLocked 按预览 fps 做。
        return true;
    }

    /** 预览固定 1920×1080@60。 */
    @Override
    public CaptureHub.CaptureSpec captureSpec(Minecraft mc) {
        return new CaptureHub.CaptureSpec(1920, 1080, 60);
    }

    /** 捕获规格变化（导出开始/结束、配置变更）：关闭编码器，下一帧按新尺寸/帧率重建。 */
    @Override
    public void onCaptureFormatChanged(int width, int height, int fps) {
        for (SessionStream stream : sessions.values()) {
            stream.resetForConfigChange();
        }
    }

    /**
     * 请求刷新画面（seek / pushScript / setCamera 后调用）。
     *
     * <p>未暂停时帧流本来就在跑，置位由下一帧消费，不会产生额外语义；暂停时持续供帧，
     * 直到每个会话都真正产出一帧（见 {@link #PAUSED_REFRESH_FRAME_BUDGET}），然后回到暂停。
     * 本暂停周期的第一个刷新帧会新建编码器（首帧 IDR），同一周期后续刷新复用同一进程。</p>
     */
    public void requestFrame() {
        pausedRefreshFrames = PAUSED_REFRESH_FRAME_BUDGET;
        for (SessionStream stream : sessions.values()) {
            stream.beginRefresh();
        }
    }

    /**
     * 捕获层回调（渲染线程）：传入的这份引用归消费者所有，本方法必须恰好释放一次。
     *
     * <p>暂停时只放行刷新帧：每个会话产出过一次后立即停止供帧，其余情况直接丢弃；
     * 未暂停时把帧交给各会话（每个会话 retain 一份，{@link SessionStream#submit} 接管并释放）。</p>
     */
    @Override
    public void onFrame(CapturedFrame frame) {
        if (frame == null) return;
        try {
            if (paused) {
                if (pausedRefreshFrames <= 0) {
                    return;
                }
                pausedRefreshFrames--;
                boolean allSatisfied = true;
                for (SessionStream stream : sessions.values()) {
                    stream.submitRefreshFrame(frame.retain());
                    allSatisfied &= stream.refreshSatisfied();
                }
                if (allSatisfied) {
                    pausedRefreshFrames = 0;
                }
                return;
            }
            pausedRefreshFrames = 0;
            for (SessionStream stream : sessions.values()) {
                stream.submit(frame.retain());
            }
        } finally {
            frame.release();
        }
    }

    /**
     * 暂停/恢复预览。
     *
     * <p>暂停：关闭全部会话编码器（清空编码输入队列，已入队帧释放）。
     * 恢复：清空各会话回退标志并丢弃编码器，下一帧用新进程重建，首帧即 IDR。</p>
     */
    public void setPaused(boolean value) {
        if (paused == value) return;
        paused = value;
        if (!value) {
            // 恢复播放：丢弃暂停期间未消费的刷新帧，编码器按新进程重建（首帧 IDR）
            pausedRefreshFrames = 0;
        }
        for (SessionStream stream : sessions.values()) {
            if (value) {
                stream.shutdownEncoder();
            } else {
                stream.resetForResume();
            }
        }
    }

    /** 当前配置（渲染线程读取；不可变值对象，可安全跨线程）。 */
    public PreviewConfig config() {
        return config;
    }

    /**
     * 每渲染帧调用：1s 节流，向每个会话推送 {@code preview.stats}，并落一行限频统计日志。
     *
     * <p>字段：{@code mode/width/height/fps/encoder/sent/dropped/inputDropped/encoderDropped}。
     * {@code width/height} 是捕获（编码输入）尺寸。
     * {@code sent}/{@code dropped} 来自会话发送队列，{@code inputDropped} 是本层节流丢弃的帧数，
     * {@code encoderDropped} 是编码器输入队列（容量 1）挤掉的帧数（含已关闭实例的累计）。</p>
     */
    public void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastStatsMs < STATS_INTERVAL_MS) return;
        lastStatsMs = now;
        if (sessions.isEmpty()) return;

        int hubWidth = CaptureHub.INSTANCE.width();
        int hubHeight = CaptureHub.INSTANCE.height();
        int width = hubWidth > 0 ? hubWidth : config.width();
        int height = hubHeight > 0 ? hubHeight : config.height();
        int fps = config.fps();
        long totalSent = 0L;
        long totalDropped = 0L;
        long totalInputDropped = 0L;
        long totalEncoderDropped = 0L;

        for (SessionStream stream : sessions.values()) {
            long sent = stream.session.sentFrames();
            long dropped = stream.session.droppedFrames();
            long inputDropped = stream.inputDroppedCount();
            long encoderDropped = stream.encoderDroppedCount();
            totalSent += sent;
            totalDropped += dropped;
            totalInputDropped += inputDropped;
            totalEncoderDropped += encoderDropped;

            JsonObject data = new JsonObject();
            data.addProperty("mode", config.wireName());
            data.addProperty("width", width);
            data.addProperty("height", height);
            data.addProperty("fps", fps);
            data.addProperty("encoder", stream.encoderState());
            data.addProperty("sent", sent);
            data.addProperty("dropped", dropped);
            data.addProperty("inputDropped", inputDropped);
            data.addProperty("encoderDropped", encoderDropped);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "preview.stats");
            msg.add("data", data);
            stream.session.sendText(msg.toString());
        }

        ErrorLog.warnRateLimited(LOG_CATEGORY, "preview-stats", STATS_INTERVAL_MS,
                String.format("预览流统计：%s %dx%d@%dfps 会话=%d sent=%d dropped=%d inputDropped=%d encoderDropped=%d",
                        config.wireName(), width, height, fps,
                        sessions.size(), totalSent, totalDropped, totalInputDropped, totalEncoderDropped));
    }

    /**
     * 关闭全部会话编码器并清空会话表；幂等，可重复调用。
     *
     * <p>编码器关闭提交给 {@link #ENCODER_CLOSER} 异步执行，本方法不等待进程退出——
     * 调用方可能在渲染线程（关屏）。</p>
     */
    public synchronized void close() {
        CaptureHub.INSTANCE.unregister(this);
        for (SessionStream stream : sessions.values()) {
            stream.closeStream();
        }
        sessions.clear();
    }

    /**
     * 单个会话的预览流状态：编码器 + 回退标志 + 节流/PTS 基准 + 本层丢帧计数。
     *
     * <p>所有可变字段都在实例锁内读写：渲染线程提交、网络线程关闭会话可能并发。</p>
     */
    private final class SessionStream {

        private final WebSocketSession session;

        /** 当前 H.264 编码器；{@code null} 表示尚未创建或已关闭。 */
        private PreviewEncoder encoder;
        private int encoderWidth;
        private int encoderHeight;
        /** 编码器帧率（固定为预览配置帧率）。 */
        private int encoderFps;

        /** 编码不可用/失败后固定走原始 RGBA，直到恢复播放或改配置。 */
        private boolean rgbaFallback;

        /** 会话已关闭：不再提交、不再入队。 */
        private boolean closed;

        /** 本会话真正产出的帧数：H.264 = AU 回调，RGBA = 成功入队。 */
        private long producedFrames;

        /** 刷新开始时的 {@link #producedFrames} 快照；{@code -1} = 当前没有待确认的刷新。 */
        private long refreshMarker = -1L;

        /** 该会话首帧 PTS（RGBA 帧头用）；-1 = 尚未提交。 */
        private long basePtsNanos = -1L;

        /** 上一个对外发出的 H.264 PTS（微秒）；-1 = 尚未发出。 */
        private long lastAuPtsMicros = -1L;

        /** 上次向编码器提交的时刻（{@link System#nanoTime()}）；0 = 尚未提交。 */
        private long lastSubmitNanos;

        /** 本层因节流丢弃的帧数。 */
        private long inputDropped;

        /** 已关闭编码器累计的输入队列丢帧数（{@link PreviewEncoder#droppedFrames()}）。 */
        private long encoderDroppedTotal;

        SessionStream(WebSocketSession session) {
            this.session = session;
        }

        /** 渲染线程：提交一帧（所有权转移给本方法，内部保证恰好释放一次）。 */
        synchronized void submit(CapturedFrame frame) {
            submitLocked(frame, false);
        }

        /**
         * 暂停刷新：由 {@link #onFrame} 的刷新预算驱动，与常规提交走同一路径。
         * 编码器为空（本暂停周期首帧 / 上次失败后）时由 {@link #ensureEncoder} 新建，首帧即 IDR；
         * 已有编码器则复用，PTS 保持单调。
         */
        synchronized void submitRefreshFrame(CapturedFrame frame) {
            submitLocked(frame, true);
        }

        private void submitLocked(CapturedFrame frame, boolean force) {
            boolean handedOff = false;
            try {
                if (closed || session.isClosed()) {
                    return;
                }
                // 采样无上限；发送/编码节流在消费者侧按预览 fps 做（force = 暂停刷新，不节流）。
                if (!force) {
                    long now = System.nanoTime();
                    long intervalNanos = 1_000_000_000L / Math.max(1, config.fps());
                    if (lastSubmitNanos != 0L && now - lastSubmitNanos < intervalNanos) {
                        inputDropped++;
                        return;
                    }
                    lastSubmitNanos = now;
                }
                if (basePtsNanos < 0L) {
                    basePtsNanos = frame.ptsNanos();
                }

                PreviewEncoder enc = ensureEncoder(frame.width(), frame.height());
                if (enc != null) {
                    // 编码器接管该引用（满则丢最旧并自行 release）
                    enc.submit(frame);
                    handedOff = true;
                    if (enc.isFailed()) {
                        disableEncoderLocked("编码器运行期失败");
                    }
                    return;
                }

                sendRgba(frame);
            } catch (RuntimeException e) {
                FatalErrors.rethrowIfFatal(e);
                ErrorLog.logRateLimited(LOG_CATEGORY, "preview-submit", 5000L,
                        "预览帧提交失败（本帧丢弃）", e);
            } finally {
                if (!handedOff) {
                    frame.release();
                }
            }
        }

        /** 原始 RGBA 回退：帧头 + 像素直接入会话队列（pack 内部已复制 payload）。 */
        private void sendRgba(CapturedFrame frame) {
            long ptsMicros = (frame.ptsNanos() - basePtsNanos) / 1000L;
            byte[] payload = PreviewProtocol.pack(
                    PreviewProtocol.TYPE_RGBA,
                    PreviewProtocol.FLAG_KEYFRAME,
                    frame.frameId(),
                    ptsMicros,
                    frame.width(),
                    frame.height(),
                    frame.rgba());
            if (session.enqueueFrame(payload)) {
                producedFrames++;
            }
        }

        /** 需要时创建编码器；返回 {@code null} 表示本帧走 RGBA。 */
        private PreviewEncoder ensureEncoder(int width, int height) {
            if (rgbaFallback || !config.encoderEnabled()) {
                return null;
            }
            if (!Ffmpeg.encoderAvailable()) {
                rgbaFallback = true;
                return null;
            }
            int targetFps = config.fps();
            if (encoder != null && !encoder.isFailed()
                    && encoderWidth == width && encoderHeight == height
                    && encoderFps == targetFps) {
                return encoder;
            }
            // 尺寸/帧率变化或上一进程已失败：丢弃旧进程，按新参数重建
            closeEncoderLocked();
            Path ffmpeg = Ffmpeg.ffmpeg();
            if (ffmpeg == null) {
                rgbaFallback = true;
                return null;
            }
            try {
                // 本编码器的 AU PTS 从 0 起：会话级偏移在创建时定死，旧进程在途 AU 不受影响
                long frameMicros = 1_000_000L / Math.max(1, targetFps);
                long offsetMicros = lastAuPtsMicros < 0L ? 0L : lastAuPtsMicros + frameMicros;
                PreviewEncoder created = new PreviewEncoder(ffmpeg, width, height, targetFps,
                        config.keyframeIntervalSeconds(),
                        (keyframe, frameId, ptsMicros, annexB) ->
                                onAu(keyframe, frameId, ptsMicros, annexB, width, height, offsetMicros));
                if (created.isFailed()) {
                    // 进程起不来：本帧不能丢（暂停刷新时可能没有下一帧），立即改走 RGBA
                    ENCODER_CLOSER.execute(created::close);
                    encoder = null;
                    rgbaFallback = true;
                    ErrorLog.warnRateLimited(LOG_CATEGORY, "preview-encoder-start", 5000L,
                            "预览 H.264 编码器启动失败，本会话回退原始 RGBA");
                    return null;
                }
                encoder = created;
                encoderWidth = width;
                encoderHeight = height;
                encoderFps = targetFps;
                return encoder;
            } catch (RuntimeException e) {
                FatalErrors.rethrowIfFatal(e);
                encoder = null;
                rgbaFallback = true;
                ErrorLog.logRateLimited(LOG_CATEGORY, "preview-encoder-start", 5000L,
                        "预览 H.264 编码器启动失败，本会话回退原始 RGBA", e);
                return null;
            }
        }

        /** 关闭并清空编码器（锁内调用）：实例只提交一次到关闭器，不在调用线程等进程退出。 */
        private void closeEncoderLocked() {
            PreviewEncoder enc = encoder;
            if (enc != null) {
                // 关闭后实例不再可查询，先把它自己的输入队列丢帧计入累计
                encoderDroppedTotal += enc.droppedFrames();
                encoder = null;
                ENCODER_CLOSER.execute(enc::close);
            }
        }

        /** 编码器输出的一个 Annex B access unit：打包帧头后入会话队列（编码线程回调）。 */
        private synchronized void onAu(boolean keyframe, long frameId, long ptsMicros, byte[] annexB,
                                       int width, int height, long offsetMicros) {
            if (closed) return;
            long pts = offsetMicros + ptsMicros;
            if (lastAuPtsMicros >= 0L && pts <= lastAuPtsMicros) {
                pts = lastAuPtsMicros + 1L;
            }
            lastAuPtsMicros = pts;
            int flags = keyframe ? PreviewProtocol.FLAG_KEYFRAME : 0;
            byte[] payload = PreviewProtocol.pack(
                    PreviewProtocol.TYPE_H264,
                    flags,
                    frameId,
                    pts,
                    width,
                    height,
                    annexB);
            if (session.enqueueFrame(payload)) {
                producedFrames++;
            }
        }

        /** 刷新开始：记录产出基线（渲染线程 / 主线程调用）。 */
        synchronized void beginRefresh() {
            refreshMarker = producedFrames;
        }

        /** 本轮刷新是否已经真正产出一帧（H.264 等 AU 回调，RGBA 等入队）。 */
        synchronized boolean refreshSatisfied() {
            return refreshMarker < 0L || producedFrames != refreshMarker;
        }

        /** 关闭编码器并标记本会话走 RGBA（锁内调用）。 */
        private void disableEncoderLocked(String reason) {
            if (encoder != null) {
                closeEncoderLocked();
                if (reason != null) {
                    ErrorLog.warnRateLimited(LOG_CATEGORY, "preview-encoder-fallback", 5000L,
                            "预览编码器已关闭，本会话回退原始 RGBA：" + reason);
                }
            }
            rgbaFallback = true;
        }

        /** 暂停：关闭编码器并清空编码输入队列（回退标志保留，恢复播放时才重试编码）。 */
        synchronized void shutdownEncoder() {
            closeEncoderLocked();
        }

        /** 恢复播放：重建编码器（下一帧首帧 IDR），重新允许编码尝试。 */
        synchronized void resetForResume() {
            closeEncoderLocked();
            rgbaFallback = false;
        }

        /** 配置变更：尺寸/帧率可能都变了，编码器下一帧按新参数重建。 */
        synchronized void resetForConfigChange() {
            closeEncoderLocked();
            rgbaFallback = false;
        }

        /** 会话结束：释放编码器并阻止后续提交。 */
        synchronized void closeStream() {
            closed = true;
            closeEncoderLocked();
        }

        /** 统计用编码状态名：{@code h264} / {@code rgba} / {@code off} / {@code pending}。 */
        synchronized String encoderState() {
            if (encoder != null && !encoder.isFailed()) {
                return "h264";
            }
            if (rgbaFallback) {
                return "rgba";
            }
            return config.encoderEnabled() ? "pending" : "off";
        }

        synchronized long inputDroppedCount() {
            return inputDropped;
        }

        /** 编码器输入队列丢帧总数：已关闭实例的累计 + 当前实例的实时值。 */
        synchronized long encoderDroppedCount() {
            return encoderDroppedTotal + (encoder == null ? 0L : encoder.droppedFrames());
        }
    }
}
