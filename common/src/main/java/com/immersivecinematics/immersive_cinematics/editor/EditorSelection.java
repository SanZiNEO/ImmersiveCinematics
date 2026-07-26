package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class EditorSelection {
    private final List<JsonObject> selectedClips = new ArrayList<>();
    private JsonObject selectedKeyframe;
    private JsonObject keyframeClip;
    private BiConsumer<List<JsonObject>, JsonObject> listener;

    public void setListener(BiConsumer<List<JsonObject>, JsonObject> l) { listener = l; }

    public List<JsonObject> getClips() { return selectedClips; }
    public JsonObject getClip() {
        return selectedClips.isEmpty() ? null : selectedClips.get(0);
    }
    public JsonObject getKeyframe() { return selectedKeyframe; }

    public void selectClip(JsonObject clip) {
        selectedClips.clear();
        selectedClips.add(clip);
        selectedKeyframe = null;
        keyframeClip = null;
        fire();
    }

    public void toggleClip(JsonObject clip) {
        if (selectedClips.contains(clip)) {
            selectedClips.remove(clip);
        } else {
            selectedClips.add(clip);
        }
        selectedKeyframe = null;
        keyframeClip = null;
        fire();
    }

    public void selectClips(List<JsonObject> clips) {
        selectedClips.clear();
        selectedClips.addAll(clips);
        selectedKeyframe = null;
        keyframeClip = null;
        fire();
    }

    public void selectKeyframe(JsonObject kf, JsonObject clip) {
        selectedKeyframe = kf;
        keyframeClip = clip;
        selectedClips.clear();
        selectedClips.add(clip);
        fire();
    }

    public void clear() {
        selectedClips.clear();
        selectedKeyframe = null;
        keyframeClip = null;
        fire();
    }

    public boolean isSelected(JsonObject clip) {
        return selectedClips.contains(clip);
    }

    private void fire() {
        if (listener != null) listener.accept(selectedClips, selectedKeyframe);
    }
}
