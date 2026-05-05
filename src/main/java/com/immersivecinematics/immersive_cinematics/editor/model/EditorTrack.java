package com.immersivecinematics.immersive_cinematics.editor.model;

import java.util.ArrayList;
import java.util.List;

public class EditorTrack {
    public String type;
    public List<EditorClip> clips = new ArrayList<>();

    public EditorTrack() {}

    public EditorTrack(String type) {
        this.type = type;
    }

    public EditorTrack copy() {
        EditorTrack t = new EditorTrack(type);
        for (EditorClip c : clips) {
            t.clips.add(c.copy());
        }
        return t;
    }
}
