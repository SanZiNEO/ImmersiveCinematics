package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

public class EditorDocument {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonObject root;
    private String fileName = "untitled";
    private boolean dirty;

    public EditorDocument() {
        reset();
    }

    public JsonObject getRoot() { return root; }
    public String getFileName() { return fileName; }
    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void clearDirty() { dirty = false; }

    public void setFileName(String name) {
        fileName = name.replaceAll("[^a-zA-Z0-9_]", "").replaceAll("_+", "_");
        if (fileName.isEmpty()) fileName = "untitled";
    }

    public void reset() {
        root = new JsonObject();
        JsonObject meta = new JsonObject();
        // C5/C6：meta 默认值由 schema.json 的 "meta" 段单源生成（tristate 无默认不写入）
        for (Map.Entry<String, com.immersivecinematics.immersive_cinematics.script.SchemaLoader.FieldDef> e
                : com.immersivecinematics.immersive_cinematics.script.SchemaLoader.getMetaFields().entrySet()) {
            Object def = e.getValue().defaultValue();
            if (def instanceof Boolean b) meta.addProperty(e.getKey(), b);
            else if (def instanceof Number n) meta.addProperty(e.getKey(), n.floatValue());
            else if (def instanceof String s) meta.addProperty(e.getKey(), s);
        }
        root.add("meta", meta);
        
        JsonObject timeline = new JsonObject();
        timeline.addProperty("total_duration", 10f);
        JsonArray tracks = new JsonArray();
        // C5：轨道列表跟随 TrackType 枚举（与 schema track_types 一致，含 OVERLAY）
        for (com.immersivecinematics.immersive_cinematics.script.TrackType t
                : com.immersivecinematics.immersive_cinematics.script.TrackType.values()) {
            JsonObject track = new JsonObject();
            track.addProperty("type", t.name());
            // 轨道 id（写进脚本 JSON，多轨道管理/layout 上下层引用）
            track.addProperty("id", com.immersivecinematics.immersive_cinematics.editor.EditorOperations.generateTrackId(tracks, t.name()));
            track.add("clips", new JsonArray());
            tracks.add(track);
        }
        timeline.add("tracks", tracks);
        root.add("timeline", timeline);
        fileName = "untitled";
        dirty = false;
    }

    public void loadFromJson(String json) {
        root = JsonParser.parseString(json).getAsJsonObject();
        dirty = false;
    }

    public String toJson() {
        return GSON.toJson(root);
    }

    public JsonObject getMeta() { return root.getAsJsonObject("meta"); }
    public JsonObject getTimeline() { return root.getAsJsonObject("timeline"); }
    public JsonArray getTracks() { return getTimeline().getAsJsonArray("tracks"); }
    public float getTotalDuration() { return getTimeline().get("total_duration").getAsFloat(); }
    public void setTotalDuration(float d) { getTimeline().addProperty("total_duration", d); }
}
