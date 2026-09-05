package com.immersivecinematics.immersive_cinematics.webui;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;

/**
 * 单个 WebSocket 客户端会话。
 *
 * <p>负责读取客户端帧、发送文本/二进制帧，并在断开时从服务端移除。</p>
 */
class WebSocketSession {

    private final Socket socket;
    private final OutputStream out;
    private volatile boolean closed;

    WebSocketSession(Socket socket, OutputStream out) {
        this.socket = socket;
        this.out = out;
    }

    void startReader() {
        Thread t = new Thread(this::readLoop, "IC-WSReader");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() {
        try (InputStream in = socket.getInputStream()) {
            while (!closed) {
                Frame frame = readFrame(in);
                if (frame == null) break;
                switch (frame.opcode) {
                    case 0x1 -> {
                        String text = new String(frame.payload, StandardCharsets.UTF_8);
                        // 对齐旧 Java 编辑器：所有 CameraManager/播放器操作必须在 Minecraft 主线程执行。
                        Minecraft.getInstance().execute(() -> WebEditorApi.handle(text, this));
                    }
                    case 0x9 -> sendFrame(0xA, frame.payload); // pong
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

    synchronized void sendText(String text) {
        sendFrame(0x81, text.getBytes(StandardCharsets.UTF_8));
    }

    synchronized void sendBinary(byte[] payload) {
        sendFrame(0x82, payload);
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        WebEditorServer.INSTANCE.removeSession(this);
    }

    private void sendFrame(int opcode, byte[] payload) {
        if (closed) return;
        try {
            int len = payload.length;
            out.write(0x80 | opcode);
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
