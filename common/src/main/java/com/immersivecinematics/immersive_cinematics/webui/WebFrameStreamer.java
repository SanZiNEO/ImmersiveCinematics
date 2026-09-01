package com.immersivecinematics.immersive_cinematics.webui;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 WebFrameCapture 读回的 RGBA 像素通过 WebSocket 发送。
 *
 * <p>原始 RGBA 路线：不编码，直接把最新帧打包发出去。
 * 读帧在渲染线程，翻转/网络发送在独立 worker 线程；跟不上时只保留最新一帧。</p>
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

    private static final AtomicReference<byte[]> PENDING = new AtomicReference<>();
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);
    private static final long SEND_INTERVAL_MS = 16; // 发送节流 ~60fps

    private static long lastSend;
    private static int frameCounter;

    private WebFrameStreamer() {
    }

    /** 由渲染线程每一帧调用；无客户端时直接返回，不产生读回开销。 */
    public static void onFrame() {
        if (!WebEditorServer.INSTANCE.isRunning() || !WebEditorServer.INSTANCE.hasClients()) {
            return;
        }

        byte[] rgba = WebFrameCapture.readPixels();
        if (rgba.length == 0) return;

        PENDING.set(rgba);
        scheduleSendIfNeeded();
    }

    private static void scheduleSendIfNeeded() {
        if (PENDING.get() != null && SCHEDULED.compareAndSet(false, true)) {
            SENDER.submit(() -> {
                try {
                    while (true) {
                        byte[] rgba = PENDING.getAndSet(null);
                        if (rgba == null) break;
                        sendRaw(rgba);
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

    private static void sendRaw(byte[] rgba) {
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

        int w = WebFrameCapture.getWidth();
        int h = WebFrameCapture.getHeight();
        int stride = w * 4;

        // OpenGL 读回自底向上，转成浏览器期待的从上到下。
        byte[] flipped = new byte[rgba.length];
        for (int y = 0; y < h; y++) {
            System.arraycopy(rgba, (h - 1 - y) * stride, flipped, y * stride, stride);
        }

        int headerSize = 7;
        ByteBuffer buf = ByteBuffer.allocate(headerSize + flipped.length);
        buf.put((byte) FRAME_TYPE_RAW_RGBA);
        buf.putShort((short) (frameCounter++ & 0xFFFF));
        buf.putShort((short) w);
        buf.putShort((short) h);
        buf.put(flipped);

        WebEditorServer.INSTANCE.broadcastBinary(buf.array());
    }
}
