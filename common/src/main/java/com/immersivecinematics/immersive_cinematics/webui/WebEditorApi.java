package com.immersivecinematics.immersive_cinematics.webui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.ScriptValidator;
import com.immersivecinematics.immersive_cinematics.script.schema.SchemaExporter;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptSavedPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Editor 客户端消息路由。
 *
 * 协议：{ "type": "...", "data": {...}, "id": "..." }
 */
public final class WebEditorApi {

    private WebEditorApi() {
    }

    public static void handle(String message, WebSocketSession session) {
        try {
            JsonObject root = JsonParser.parseString(message).getAsJsonObject();
            String type = root.has("type") ? root.get("type").getAsString() : "";
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : new JsonObject();
            String id = root.has("id") ? root.get("id").getAsString() : "";
            System.out.println("[IC-WebUI-Backend] <= " + type + " id=" + id);

            switch (type) {
                case "hello" -> handleHello(session);
                case "script.list" -> handleScriptList(session, id);
                case "script.load" -> handleScriptLoad(session, data, id);
                case "script.save" -> handleScriptSave(session, data, id);
                case "script.delete" -> handleScriptDelete(session, data, id);
                case "script.new" -> handleScriptNew(session, id);
                case "script.validate" -> handleScriptValidate(session, data, id);
                case "registry.query" -> handleRegistryQuery(session, data, id);
                case "registry.get" -> handleRegistryGet(session, data, id);
                case "schema.get" -> handleSchemaGet(session, id);
                case "editor.seek" -> {
                    System.out.println("[IC-WebUI-Backend] seek " + data);
                    handleSeek(data);
                    pushPlaybackState();
                }
                case "editor.play" -> {
                    System.out.println("[IC-WebUI-Backend] play");
                    CameraManager.INSTANCE.resume();
                    pushPlaybackState();
                }
                case "editor.pause" -> {
                    System.out.println("[IC-WebUI-Backend] pause");
                    CameraManager.INSTANCE.pause();
                    pushPlaybackState();
                }
                case "editor.stop" -> {
                    System.out.println("[IC-WebUI-Backend] stop");
                    CameraManager.INSTANCE.stop();
                    pushPlaybackState();
                }
                case "editor.setCamera" -> handleSetCamera(data);
                case "editor.pushScript" -> handlePushScript(data);
                case "editor.enter_flight_mode" -> handleEnterFlightMode(data);
                default -> sendError(session, id, "unknown type: " + type);
            }
        } catch (Exception e) {
            System.err.println("[IC-WebUI] api error: " + e.getMessage());
        }
    }

    private static void handleHello(WebSocketSession session) {
        JsonObject data = new JsonObject();
        data.addProperty("version", "0.3.5");
        data.addProperty("name", "ImmersiveCinematics");
        session.sendText(wrap("hello_ack", data, ""));
    }

    private static void handleScriptList(WebSocketSession session, String id) {
        List<String> files = ScriptFileService.listScripts();
        JsonArray arr = new JsonArray();
        for (String f : files) arr.add(f);
        JsonObject data = new JsonObject();
        data.add("files", arr);
        session.sendText(wrap("script.list.result", data, id));
    }

    private static void handleScriptLoad(WebSocketSession session, JsonObject data, String id) {
        try {
            String path = data.get("path").getAsString();
            String json = ScriptFileService.loadScript(path);
            JsonObject doc = JsonParser.parseString(json).getAsJsonObject();
            JsonObject out = new JsonObject();
            out.addProperty("path", path);
            out.add("doc", doc);
            session.sendText(wrap("script.loaded", out, id));
        } catch (Exception e) {
            sendError(session, id, "load failed: " + e.getMessage());
        }
    }

    private static void handleScriptSave(WebSocketSession session, JsonObject data, String id) {
        try {
            String path = data.get("path").getAsString();
            String json = data.get("doc").toString();
            ScriptFileService.saveScript(path, json);
            JsonObject out = new JsonObject();
            out.addProperty("path", path);
            session.sendText(wrap("script.saved", out, id));
            notifyScriptSaved(path);
        } catch (Exception e) {
            sendError(session, id, "save failed: " + e.getMessage());
        }
    }

    /** 保存成功后沿旧编辑器 N2b 通知服务端：文件指纹登记 + 广播（失败不影响保存）。 */
    private static void notifyScriptSaved(String path) {
        try {
            String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            Minecraft.getInstance().execute(() -> {
                try {
                    NetworkHandler.sendToServer(new C2SScriptSavedPacket(fileName));
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void handleScriptValidate(WebSocketSession session, JsonObject data, String id) {
        try {
            JsonElement doc = data.get("doc");
            String json = doc == null ? "{}" : doc.toString();
            List<String> issues = ScriptValidator.validate(json);
            JsonObject out = new JsonObject();
            out.addProperty("ok", issues.isEmpty());
            JsonArray arr = new JsonArray();
            for (String issue : issues) arr.add(issue);
            out.add("issues", arr);
            session.sendText(wrap("script.validate.result", out, id));
        } catch (Exception e) {
            sendError(session, id, "validate failed: " + e.getMessage());
        }
    }

    private static void handleRegistryQuery(WebSocketSession session, JsonObject data, String id) {
        try {
            String kind = data.has("kind") ? data.get("kind").getAsString() : "";
            String query = data.has("query") ? data.get("query").getAsString() : "";
            int limit = data.has("limit") && data.get("limit").isJsonPrimitive()
                    ? data.get("limit").getAsInt() : 50;
            List<String> matches = WebRegistryService.query(kind, query, limit);
            JsonObject out = new JsonObject();
            out.addProperty("kind", kind);
            JsonArray arr = new JsonArray();
            for (String m : matches) arr.add(m);
            out.add("matches", arr);
            session.sendText(wrap("registry.query.result", out, id));
        } catch (Exception e) {
            sendError(session, id, "registry query failed: " + e.getMessage());
        }
    }

    private static void handleRegistryGet(WebSocketSession session, JsonObject data, String id) {
        try {
            String kind = data.has("kind") ? data.get("kind").getAsString() : "";
            List<String> values = WebRegistryService.getAll(kind);
            JsonObject out = new JsonObject();
            out.addProperty("kind", kind);
            JsonArray arr = new JsonArray();
            for (String v : values) arr.add(v);
            out.add("values", arr);
            session.sendText(wrap("registry.get.result", out, id));
        } catch (Exception e) {
            sendError(session, id, "registry get failed: " + e.getMessage());
        }
    }

    private static void handleScriptDelete(WebSocketSession session, JsonObject data, String id) {
        try {
            String path = data.get("path").getAsString();
            ScriptFileService.deleteScript(path);
            JsonObject out = new JsonObject();
            out.addProperty("path", path);
            session.sendText(wrap("script.deleted", out, id));
        } catch (Exception e) {
            sendError(session, id, "delete failed: " + e.getMessage());
        }
    }

    private static void handleScriptNew(WebSocketSession session, String id) {
        JsonObject data = new JsonObject();
        data.addProperty("path", "untitled.json");
        data.add("doc", JsonParser.parseString(ScriptFileService.newScriptJson()).getAsJsonObject());
        session.sendText(wrap("script.new.result", data, id));
    }

    private static void handleSchemaGet(WebSocketSession session, String id) {
        JsonObject schema = SchemaExporter.exportAll();
        JsonObject data = new JsonObject();
        data.add("schema", schema);
        session.sendText(wrap("schema.data", data, id));
    }

    private static void handleSeek(JsonObject data) {
        if (data.has("time")) {
            // 旧编辑器组 3：非播放按钮定位先暂停，避免拖动播放头时继续跑
            CameraManager.INSTANCE.pause();
            CameraManager.INSTANCE.setTime(data.get("time").getAsFloat());
        }
    }

    private static void handlePushScript(JsonObject data) {
        if (data.has("script")) {
            String json = data.get("script").getAsString();
            System.out.println("[IC-WebUI-Backend] pushScript len=" + json.length());
            CameraManager.INSTANCE.pushScript(json);
            // 编辑即预览：不要每次编辑都跳回 0，保留当前播放头位置。
            // 首次加载/新建由前端显式发送 editor.seek(0) 完成定位。
            pushPlaybackState();
        }
    }

    private static void handleEnterFlightMode(JsonObject data) {
        double x = data.has("x") ? data.get("x").getAsDouble() : 0;
        double y = data.has("y") ? data.get("y").getAsDouble() : 0;
        double z = data.has("z") ? data.get("z").getAsDouble() : 0;
        float yaw = data.has("yaw") ? data.get("yaw").getAsFloat() : 0f;
        float pitch = data.has("pitch") ? data.get("pitch").getAsFloat() : 0f;
        float roll = data.has("roll") ? data.get("roll").getAsFloat() : 0f;
        float fov = data.has("fov") ? data.get("fov").getAsFloat() : 70f;
        float zoom = data.has("zoom") ? data.get("zoom").getAsFloat() : 1f;
        boolean absolute = data.has("absolute") && data.get("absolute").getAsBoolean();
        // 旧编辑器语义：进入飞控前暂停播放并接管直控
        CameraManager.INSTANCE.pause();
        CameraManager.INSTANCE.setPreviewDirectControl(true);
        WebPreviewScreen.enterFlightMode(x, y, z, yaw, pitch, roll, fov, zoom, absolute);
    }

    private static void handleSetCamera(JsonObject data) {
        float yaw = data.has("yaw") ? data.get("yaw").getAsFloat() : 0f;
        float pitch = data.has("pitch") ? data.get("pitch").getAsFloat() : 0f;
        float roll = data.has("roll") ? data.get("roll").getAsFloat() : 0f;
        float fov = data.has("fov") ? data.get("fov").getAsFloat() : 70f;
        float zoom = data.has("zoom") ? data.get("zoom").getAsFloat() : 1f;
        CameraManager.INSTANCE.setCameraDirect(yaw, pitch, roll, fov, zoom);
    }

    /** 广播当前播放器状态（供 WebPreviewScreen 每帧调用，形成实时双向通信）。 */
    public static void pushPlaybackState() {
        JsonObject data = new JsonObject();
        data.addProperty("time", (float) CameraManager.INSTANCE.getGameTimeSeconds());
        // 旧 Java 编辑器语义：playing = 用户正在播放（未暂停），不是“脚本已加载”
        boolean playing = CameraManager.INSTANCE.isPreviewMode()
                && !CameraManager.INSTANCE.isPreviewPaused();
        data.addProperty("playing", playing);
        WebEditorServer.INSTANCE.broadcastText(wrap("playback.state", data, ""));
    }

    private static void sendError(WebSocketSession session, String id, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("error", message);
        session.sendText(wrap("error", data, id));
    }

    private static String wrap(String type, JsonObject data, String id) {
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        out.add("data", data);
        if (id != null && !id.isEmpty()) {
            out.addProperty("id", id);
        }
        return out.toString();
    }
}
