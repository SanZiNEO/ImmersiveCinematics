package com.immersivecinematics.immersive_cinematics.webui;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;

/**
 * 单个 WebSocket 客户端会话。
 *
 * 负责读取客户端帧、串行发送出站消息（文本 + 二进制帧流），并在断开时从服务端移除。
 *
 * <p>发送侧模型：所有出站消息先进入每会话一个有界的 {@link OutboundQueue}（容量 16），
 * 由独立的 {@code IC-WSSender} daemon 线程串行写出。调用方（渲染线程 / 主线程 / 广播循环）
 * 只做入队，永不阻塞在 socket 写操作上；队列满时丢最旧的帧而不是卡住渲染。
 */
class WebSocketSession {

    /** 单帧文本消息头（FIN + opcode 0x1）。 */
    private static final int OPCODE_TEXT = 0x81;
    /** 单帧二进制消息头（FIN + opcode 0x2）。 */
    private static final int OPCODE_BINARY = 0x82;
    /** 单帧 pong 消息头（FIN + opcode 0xA）。 */
    private static final int OPCODE_PONG = 0x8A;

    private final Socket socket;
    private final OutputStream out;
    private final OutboundQueue queue = new OutboundQueue();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 写出锁：发送线程与读线程（pong）共用，保证 socket 上的字节流不会交错。 */
    private final Object writeLock = new Object();
    /** 上一次记录「文本被丢弃」日志时的计数器值。 */
    private volatile long droppedTextsSeen;

    WebSocketSession(Socket socket, OutputStream out) {
        this.socket = socket;
        this.out = out;
    }

    /** 启动读线程与每会话发送线程。 */
    void startReader() {
        Thread reader = new Thread(this::readLoop, "IC-WSReader");
        reader.setDaemon(true);
        reader.start();
        Thread sender = new Thread(this::sendLoop, "IC-WSSender");
        sender.setDaemon(true);
        sender.start();
    }

    private void readLoop() {
        try (InputStream in = socket.getInputStream()) {
            while (!closed.get()) {
                Frame frame = readFrame(in);
                if (frame == null) break;
                switch (frame.opcode) {
                    case 0x1 -> {
                        String text = new String(frame.payload, StandardCharsets.UTF_8);
                        // 所有 CameraManager/播放器操作必须在 Minecraft 主线程执行。
                        Minecraft.getInstance().execute(() -> WebEditorApi.handle(text, this));
                    }
                    case 0x9 -> {
                        // pong 是控制帧：不走数据队列（否则会被当成二进制帧流），用写锁直接串行写。
                        if (!closed.get()) {
                            writeSocket(OPCODE_PONG, frame.payload);
                        }
                    }
                    case 0x8 -> {
                        close();
                        return;
                    }
                    default -> { /* ignore */ }
                }
            }
        } catch (Exception ignored) {
        } finally {
            close();
        }
    }

    private void sendLoop() {
        try {
            for (OutboundQueue.Outbound m = queue.poll(); m != null; m = queue.poll()) {
                writeFrame(m);
            }
        } finally {
            close();
        }
    }

    /**
     * 入队一帧二进制消息（opcode 0x82，帧流走这里）。非阻塞；队列满时丢最旧帧。
     *
     * @return 本帧是否成功入队（会话已关闭或只能丢掉本帧时为 false）
     */
    boolean enqueueFrame(byte[] payload) {
        return queue.enqueueFrame(payload);
    }

    /** 入队一帧文本消息（UTF-8，opcode 0x81）；会话已关闭时静默丢弃。 */
    void sendText(String text) {
        queue.enqueueText(text);
        long dropped = queue.droppedTexts();
        if (dropped > droppedTextsSeen) {
            droppedTextsSeen = dropped;
            ErrorLog.warnRateLimited("webui", "ws-text-drop", 5000L,
                    "outbound text dropped (queue full of text); total dropped texts=" + dropped);
        }
    }

    /** 已出队的帧数。 */
    long sentFrames() {
        return queue.sentFrames();
    }

    /** 因队列满被丢弃的帧数。 */
    long droppedFrames() {
        return queue.droppedFrames();
    }

    /** 会话是否已关闭。 */
    boolean isClosed() {
        return closed.get();
    }

    /** 关闭 socket 并从服务端会话表移除；幂等，可被收发两端并发调用。 */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        queue.close();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        WebEditorServer.INSTANCE.removeSession(this);
    }

    /** 按队列消息类型写出：二进制帧流用 0x82，文本用 0x81。 */
    private void writeFrame(OutboundQueue.Outbound message) {
        writeSocket(message.frame() ? OPCODE_BINARY : OPCODE_TEXT, message.payload());
    }

    /** 在写锁内串行写出一帧；写失败即关闭会话。 */
    private void writeSocket(int opcode, byte[] payload) {
        synchronized (writeLock) {
            if (closed.get()) return;
            try {
                int len = payload.length;
                out.write(opcode);
                if (len <= 125) {
                    out.write(len);
                } else if (len <= 0xFFFF) {
                    out.write(126);
                    out.write((len >>> 8) & 0xFF);
                    out.write(len & 0xFF);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) ((long) len >>> (8 * i)) & 0xFF);
                    }
                }
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = readByte(in);
        int opcode = b0 & 0x0F;
        int b1 = readByte(in);
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte(in);
            }
        }
        if (len < 0 || len > 64 * 1024 * 1024) {
            throw new IOException("ws frame too large");
        }
        byte[] mask = null;
        if (masked) {
            // WebSocket 协议：mask key 在 payload 之前，必须先读掩码
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        return new Frame(opcode, payload);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

    private record Frame(int opcode, byte[] payload) {
    }
}
