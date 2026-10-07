package com.immersivecinematics.immersive_cinematics.webui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 WebSocket 服务端。
 *
 * <p>mod 作为服务端，Editor 作为客户端连接。
 * 支持双向文本 JSON 消息和二进制帧流。只绑定 127.0.0.1。</p>
 *
 * <p><b>握手身份校验</b>（防止本地网页 / 本地程序冒充编辑器）：仅绑定回环地址不足以拦住
 * 用户浏览器里的任意网页（浏览器会替网页连 127.0.0.1）。因此在 {@code /ws} 升级前做两道校验，
 * 任一不过直接 403 + 关连接 + 日志：</p>
 * <ol>
 *   <li><b>Origin 白名单</b>：浏览器在 WebSocket 握手里强制带上 {@code Origin}，网页无法伪造。
 *       只放行 Electron 打包态（{@code file://} / {@code null}）、本机 dev server（localhost / 127.0.0.1 / ::1），
 *       其余（含 {@code https://evil.com}）拒绝；无 Origin 的非浏览器客户端放行到第二道。</li>
 *   <li><b>每次启动随机 token</b>：{@code start()} 生成 32 字节随机 token 并写入
 *       {@code <user.home>/.immersivecinematics/webui-token}（可用环境变量 {@code IC_WEBUI_TOKEN_FILE} 覆盖路径），
 *       客户端以 {@code ws://127.0.0.1:8765/ws?token=...} 携带，常量时间比对，{@code stop()} 删除文件。
 *       网页读不到该文件，因此即使 Origin 落在白名单内也无法连接。</li>
 * </ol>
 * <p>协议信封（{@code {type,data,id}}）与消息类型不受影响：token 只出现在握手 URL 里。</p>
 */
public class WebEditorServer {

    public static final int DEFAULT_PORT = 8765;
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** token 文件所在目录（用户主目录下；编辑器是独立进程，无法得知游戏目录）。 */
    private static final String TOKEN_DIR_NAME = ".immersivecinematics";
    private static final String TOKEN_FILE_NAME = "webui-token";
    /** 覆盖 token 文件路径的环境变量（Editor 侧同名读取，便于多实例 / 测试）。 */
    private static final String TOKEN_FILE_ENV = "IC_WEBUI_TOKEN_FILE";
    private static final int TOKEN_BYTES = 32;

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
    /** 本次运行期的握手 token；服务未运行时为 null（此时一律拒绝升级）。 */
    private volatile String token;

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
            token = generateToken();
            writeTokenFile(token);
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "IC-WebEditorServer");
            acceptThread.setDaemon(true);
            acceptThread.start();
            System.out.println("[IC-WebUI] server started at 127.0.0.1:" + DEFAULT_PORT
                    + " (token file: " + tokenFilePath() + ")");
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
        token = null;
        deleteTokenFile();
        System.out.println("[IC-WebUI] server stopped");
    }

    // ── 握手身份校验：token 文件 ──────────────────────────────

    /** token 文件路径：{@code IC_WEBUI_TOKEN_FILE} 优先，否则 {@code <user.home>/.immersivecinematics/webui-token}。 */
    static Path tokenFilePath() {
        String override = System.getenv(TOKEN_FILE_ENV);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim()).toAbsolutePath();
        }
        return Paths.get(System.getProperty("user.home"), TOKEN_DIR_NAME, TOKEN_FILE_NAME).toAbsolutePath();
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void writeTokenFile(String value) {
        Path path = tokenFilePath();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 写不进去 = 没有任何客户端能连上；明确报错，不静默降级为“无校验”。
            System.err.println("[IC-WebUI] failed to write token file " + path + ": " + e.getMessage());
        }
    }

    private void deleteTokenFile() {
        try {
            Files.deleteIfExists(tokenFilePath());
        } catch (IOException e) {
            System.err.println("[IC-WebUI] failed to delete token file: " + e.getMessage());
        }
    }

    /** 常量时间比对，避免通过响应时间差爆破 token。 */
    private boolean tokenMatches(String supplied) {
        String expected = token;
        if (expected == null || supplied == null || supplied.isEmpty()) return false;
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Origin 白名单：浏览器强制附带且网页无法伪造，用来挡掉“用户浏览器里的任意网页”。
     *
     * <p>放行：无 Origin（非浏览器客户端，交 token 把关）、{@code null} / {@code file://}（Electron 打包态页面）、
     * {@code http(s)://localhost|127.0.0.1|::1[:port]}（vite dev server）。其余一律拒绝。</p>
     */
    private static boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isEmpty()) return true;
        String value = origin.trim().toLowerCase(Locale.ROOT);
        if (value.equals("null") || value.equals("file://")) return true;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
            String host = uri.getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private void reject(Socket socket, String reason) {
        System.err.println("[IC-WebUI] rejected " + socket.getRemoteSocketAddress() + ": " + reason);
        try {
            sendText(socket, 403, "Forbidden", "text/plain", reason);
        } catch (IOException ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
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
                String origin = headers.get("origin");
                if (!isAllowedOrigin(origin)) {
                    reject(socket, "origin not allowed: " + origin);
                    return;
                }
                if (!tokenMatches(parseQueryParam(requestLine, "token"))) {
                    reject(socket, "invalid or missing token");
                    return;
                }
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
        String target = parseTarget(requestLine);
        int query = target.indexOf('?');
        return query >= 0 ? target.substring(0, query) : target;
    }

    /** 从请求行的 query string 取一个参数（握手 token 走这里，协议消息结构不变）。 */
    private static String parseQueryParam(String requestLine, String name) {
        String target = parseTarget(requestLine);
        int query = target.indexOf('?');
        if (query < 0) return null;
        for (String pair : target.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String parseTarget(String requestLine) {
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
