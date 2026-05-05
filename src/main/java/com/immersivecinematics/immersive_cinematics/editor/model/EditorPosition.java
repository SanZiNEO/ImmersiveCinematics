package com.immersivecinematics.immersive_cinematics.editor.model;

public class EditorPosition {
    public float x, y, z;
    public boolean relative = true;

    public EditorPosition() {}

    public EditorPosition(float x, float y, float z, boolean relative) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.relative = relative;
    }

    public EditorPosition copy() {
        return new EditorPosition(x, y, z, relative);
    }
}
