package com.immersivecinematics.immersive_cinematics.webui;

import com.immersivecinematics.immersive_cinematics.media.capture.CapturedFrame;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把捕获层（{@link com.immersivecinematics.immersive_cinematics.media.capture.FrameCapture}）产出的
 * {@link CapturedFrame} 通过 WebSocket 发送。
 *
 * 原始 RGBA 路线：不编码，直接把最新帧打包发出去。
 * 捕获与行翻转在渲染线程（捕获层完成），网络发送在独立 worker 线程；跟不上时只保留最新一帧。
 *
 * 帧格式：
 * [1 byte type = 0x01]
 * [2 bytes frameId, big-endian]
 * [2 bytes width]
 * [2 bytes height]
 * [width * height * 4 bytes RGBA]
 */
public class WebFrameStreamer {

    private static final int FRAME_TYPE_RAW_RGBA = 0x01;

    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IC-WebFrameSender");
        t.setDaemon(true);
        return t;
    });

    /** 单槽待发帧：新帧覆盖旧帧，跟不上时只保留最新一帧。 */
    private static final AtomicReference<CapturedFrame> PENDING = new AtomicReference<>();
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);
    private static final long SEND_INTERVAL_MS = 16; // 发送节流 ~60fps

    private static long lastSend;

    private WebFrameStreamer() {
    }

    /**
     * 当前是否有客户端需要帧。渲染线程每帧调用；返回 false 时调用方应跳过捕获，
     * 不产生读回开销。
     */
    public static boolean shouldCapture() {
        return WebEditorServer.INSTANCE.isRunning() && WebEditorServer.INSTANCE.hasClients();
    }

    /**
     * 捕获层回调（渲染线程调用）：把帧放进单槽并调度发送。
     *
     * <p>无客户端时直接丢弃；发送侧跟不上时旧帧被新帧覆盖（单槽语义）。</p>
     *
     * @param frame 捕获帧（RGBA8、自顶向下，捕获层已完成翻转）
     */
    public static void onCapturedFrame(CapturedFrame frame) {
        if (frame == null) return;
        if (!shouldCapture()) return;

        PENDING.set(frame);
        scheduleSendIfNeeded();
    }

    private static void scheduleSendIfNeeded() {
        if (PENDING.get() != null && SCHEDULED.compareAndSet(false, true)) {
            SENDER.submit(() -> {
                try {
                    while (true) {
                        CapturedFrame frame = PENDING.getAndSet(null);
                        if (frame == null) break;
                        sendRaw(frame);
                    }
                } finally {
                    SCHEDULED.set(false);
                    if (PENDING.get() != null) {
                        scheduleSendIfNeeded();
                    }
                }
            });
        }
    }

    private static void sendRaw(CapturedFrame frame) {
        byte[] rgba = frame.rgba();
        try {
            // 发送节流：采样无上限，这里控制实际发送约 60fps。
            long now = System.currentTimeMillis();
            long wait = SEND_INTERVAL_MS - (now - lastSend);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            lastSend = System.currentTimeMillis();

            int w = frame.width();
            int h = frame.height();

            // 行序已是自顶向下（捕获层 flipRows 完成），这里直接打包。
            int headerSize = 7;
            ByteBuffer buf = ByteBuffer.allocate(headerSize + rgba.length);
            buf.put((byte) FRAME_TYPE_RAW_RGBA);
            buf.putShort((short) ((int) (frame.frameId() & 0xFFFF)));
            buf.putShort((short) w);
            buf.putShort((short) h);
            buf.put(rgba);

            WebEditorServer.INSTANCE.broadcastBinary(buf.array());
        } catch (RuntimeException e) {
            // 线程边界：单帧失败不杀 worker，丢弃本帧继续下一帧（限频留全栈供定位）
            FatalErrors.rethrowIfFatal(e);
            ErrorLog.logRateLimited("WebUI", "frame-send", 5000L,
                    "帧发送失败（bytes=" + rgba.length + "）：本帧丢弃，worker 继续", e);
        }
    }
}
