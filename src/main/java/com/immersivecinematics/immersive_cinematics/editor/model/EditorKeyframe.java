package com.immersivecinematics.immersive_cinematics.editor.model;

import java.util.ArrayList;
import java.util.List;

public class EditorKeyframe {
    public float time;
    public EditorPosition position = new EditorPosition();
    public float yaw;
    public float pitch;
    public float roll;
    public float fov = 70f;
    public float zoom = 1f;
    public float dof;

    public EditorKeyframe() {}

    public EditorKeyframe copy() {
        EditorKeyframe k = new EditorKeyframe();
        k.time = time;
        k.position = position.copy();
        k.yaw = yaw;
        k.pitch = pitch;
        k.roll = roll;
        k.fov = fov;
        k.zoom = zoom;
        k.dof = dof;
        return k;
    }
}
