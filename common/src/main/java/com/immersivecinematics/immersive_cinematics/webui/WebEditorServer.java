package com.immersivecinematics.immersive_cinematics.webui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 WebSocket 服务端。
 *
 * <p>mod 作为服务端，Editor 作为客户端连接。
 * 支持双向文本 JSON 消息和二进制帧流。只绑定 127.0.0.1。</p>
 */
public class WebEditorServer {

    public static final int DEFAULT_PORT = 8765;
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final String PAGE =
            "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "<meta charset=\"utf-8\">\n" +
            "<title>ImmersiveCinematics</title>\n" +
            "</head>\n" +
            "<body>\n" +
            "<h2>ImmersiveCinematics Editor server is running.</h2>\n" +
            "<p>Please start the standalone Editor client.</p>\n" +
            "</body>\n" +
            "</html>\n";

    public static final WebEditorServer INSTANCE = new WebEditorServer();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private ServerSocket serverSocket;
    private Thread acceptThread;

    private WebEditorServer() {
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean hasClients() {
        return !sessions.isEmpty();
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        try {
            serverSocket = new ServerSocket(DEFAULT_PORT, 4, InetAddress.getByName("127.0.0.1"));
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "IC-WebEditorServer");
            acceptThread.setDaemon(true);
            acceptThread.start();
            System.out.println("[IC-WebUI] server started at 127.0.0.1:" + DEFAULT_PORT);
            return true;
        } catch (IOException e) {
            System.err.println("[IC-WebUI] failed to start server: " + e.getMessage());
            return false;
        }
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        for (WebSocketSession s : sessions) {
            s.close();
        }
        sessions.clear();
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        System.out.println("[IC-WebUI] server stopped");
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                Thread t = new Thread(() -> handleSocket(socket), "IC-WebUIClient");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[IC-WebUI] accept error: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handleSocket(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String path = parsePath(requestLine);
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
                }
            }

            if ("/".equals(path)) {
                sendHtml(socket, PAGE);
                return;
            }
            if ("/ws".equals(path)) {
                upgradeWebSocket(socket, headers);
                return;
            }

            sendText(socket, 404, "Not Found", "text/plain", "not found");
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String parsePath(String requestLine) {
        String[] parts = requestLine.split(" ");
        return parts.length >= 2 ? parts[1] : "/";
    }

    private void sendHtml(Socket socket, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        socket.close();
    }

    private void sendText(Socket socket, int code, String status, String contentType, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void upgradeWebSocket(Socket socket, Map<String, String> headers) throws Exception {
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            sendText(socket, 400, "Bad Request", "text/plain", "missing sec-websocket-key");
            return;
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).getBytes(StandardCharsets.US_ASCII)));

        String header = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        socket.setSoTimeout(0);
        WebSocketSession session = new WebSocketSession(socket, out);
        sessions.add(session);
        session.startReader();
        System.out.println("[IC-WebUI] editor client connected: " + socket.getRemoteSocketAddress());
    }

    void removeSession(WebSocketSession session) {
        sessions.remove(session);
    }

    /** 发送一帧二进制消息给所有已连接的客户端。 */
    public void broadcastBinary(byte[] payload) {
        for (WebSocketSession s : sessions) {
            s.sendBinary(payload);
        }
    }

    /** 发送文本消息给所有已连接的客户端。 */
    public void broadcastText(String text) {
        for (WebSocketSession s : sessions) {
            s.sendText(text);
        }
    }
}
