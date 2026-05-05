package com.immersivecinematics.immersive_cinematics.editor.model;

import java.util.ArrayList;
import java.util.List;

public class EditorClip {
    public float startTime;
    public float duration;
    public String transition = "cut";
    public float transitionDuration = 0.5f;
    public String interpolation = "linear";
    public String positionMode = "relative";
    public boolean loop;
    public int loopCount = -1;
    public List<EditorKeyframe> keyframes = new ArrayList<>();

    public EditorClip() {}

    public EditorClip copy() {
        EditorClip c = new EditorClip();
        c.startTime = startTime;
        c.duration = duration;
        c.transition = transition;
        c.transitionDuration = transitionDuration;
        c.interpolation = interpolation;
        c.positionMode = positionMode;
        c.loop = loop;
        c.loopCount = loopCount;
        for (EditorKeyframe k : keyframes) {
            c.keyframes.add(k.copy());
        }
        return c;
    }

    public float endTime() {
        return startTime + duration;
    }
}
