package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        meta.addProperty("id", "untitled");
        meta.addProperty("name", "Untitled");
        meta.addProperty("author", "Author");
        meta.addProperty("version", 3);
        meta.addProperty("block_keyboard", true);
        meta.addProperty("block_mouse", true);
        meta.addProperty("hide_hud", true);
        meta.addProperty("hide_arm", true);
        meta.addProperty("suppress_bob", true);
        meta.addProperty("skippable", true);
        meta.addProperty("interruptible", true);
        root.add("meta", meta);

        JsonObject timeline = new JsonObject();
        timeline.addProperty("total_duration", 10f);
        JsonArray tracks = new JsonArray();
        JsonObject cameraTrack = new JsonObject();
        cameraTrack.addProperty("type", "CAMERA");
        cameraTrack.add("clips", new JsonArray());
        tracks.add(cameraTrack);
        timeline.add("tracks", tracks);
        root.add("timeline", timeline);

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
