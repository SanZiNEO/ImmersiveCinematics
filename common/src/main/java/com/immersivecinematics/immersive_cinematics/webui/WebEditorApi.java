package com.immersivecinematics.immersive_cinematics.webui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.script.schema.SchemaExporter;

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

            switch (type) {
                case "hello" -> handleHello(session);
                case "script.list" -> handleScriptList(session, id);
                case "script.load" -> handleScriptLoad(session, data, id);
                case "script.save" -> handleScriptSave(session, data, id);
                case "script.delete" -> handleScriptDelete(session, data, id);
                case "script.new" -> handleScriptNew(session, id);
                case "schema.get" -> handleSchemaGet(session, id);
                case "editor.seek" -> {
                    handleSeek(data);
                    pushPlaybackState();
                }
                case "editor.play" -> {
                    CameraManager.INSTANCE.resume();
                    pushPlaybackState();
                }
                case "editor.pause" -> {
                    CameraManager.INSTANCE.pause();
                    pushPlaybackState();
                }
                case "editor.stop" -> {
                    CameraManager.INSTANCE.stop();
                    pushPlaybackState();
                }
                case "editor.setCamera" -> handleSetCamera(data);
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
        } catch (Exception e) {
            sendError(session, id, "save failed: " + e.getMessage());
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
            CameraManager.INSTANCE.setTime(data.get("time").getAsFloat());
        }
    }

    private static void handleSetCamera(JsonObject data) {
        float yaw = data.has("yaw") ? data.get("yaw").getAsFloat() : 0f;
        float pitch = data.has("pitch") ? data.get("pitch").getAsFloat() : 0f;
        float roll = data.has("roll") ? data.get("roll").getAsFloat() : 0f;
        float fov = data.has("fov") ? data.get("fov").getAsFloat() : 70f;
        float zoom = data.has("zoom") ? data.get("zoom").getAsFloat() : 1f;
        CameraManager.INSTANCE.setCameraDirect(yaw, pitch, roll, fov, zoom);
    }

    private static void pushPlaybackState() {
        JsonObject data = new JsonObject();
        data.addProperty("time", (float) CameraManager.INSTANCE.getGameTimeSeconds());
        data.addProperty("playing", CameraManager.INSTANCE.getScriptPlayer().isPlaying());
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
