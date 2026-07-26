package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.JsonObject;
import java.util.function.BiConsumer;

public class EditorSelection {
    private JsonObject selectedClip;
    private JsonObject selectedKeyframe;
    private BiConsumer<JsonObject, JsonObject> listener;

    public void setListener(BiConsumer<JsonObject, JsonObject> l) { listener = l; }
    public JsonObject getClip() { return selectedClip; }
    public JsonObject getKeyframe() { return selectedKeyframe; }

    public void selectClip(JsonObject clip) {
        selectedClip = clip;
        selectedKeyframe = null;
        fire();
    }

    public void selectKeyframe(JsonObject kf, JsonObject clip) {
        selectedKeyframe = kf;
        selectedClip = clip;
        fire();
    }

    public void clear() {
        selectedClip = null;
        selectedKeyframe = null;
        fire();
    }

    private void fire() {
        if (listener != null) listener.accept(selectedClip, selectedKeyframe);
    }
}
