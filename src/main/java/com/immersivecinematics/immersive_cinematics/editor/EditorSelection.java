package com.immersivecinematics.immersive_cinematics.editor;

import com.immersivecinematics.immersive_cinematics.editor.model.*;
import java.util.function.BiConsumer;

public class EditorSelection {
    private EditorClip selectedClip;
    private EditorKeyframe selectedKeyframe;
    private BiConsumer<EditorClip, EditorKeyframe> listener;

    public void setListener(BiConsumer<EditorClip, EditorKeyframe> l) { listener = l; }

    public EditorClip getClip() { return selectedClip; }
    public EditorKeyframe getKeyframe() { return selectedKeyframe; }

    public void selectClip(EditorClip clip) {
        selectedClip = clip;
        selectedKeyframe = null;
        fire();
    }

    public void selectKeyframe(EditorKeyframe kf) {
        selectedKeyframe = kf;
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
