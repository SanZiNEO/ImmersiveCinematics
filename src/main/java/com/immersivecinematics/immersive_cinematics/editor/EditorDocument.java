package com.immersivecinematics.immersive_cinematics.editor;

import com.immersivecinematics.immersive_cinematics.editor.model.*;

public class EditorDocument {
    private EditorScript script;
    private String fileName = "untitled";
    private boolean dirty;

    public EditorDocument() {
        reset();
    }

    public EditorScript getScript() { return script; }
    public String getFileName() { return fileName; }
    public boolean isDirty() { return dirty; }

    public void markDirty() { dirty = true; }
    public void clearDirty() { dirty = false; }

    public void setFileName(String name) {
        fileName = name.replaceAll("[^a-zA-Z0-9_]", "").replaceAll("_+", "_");
        if (fileName.isEmpty()) fileName = "untitled";
    }

    public void reset() {
        script = new EditorScript();
        script.name = "Untitled";
        script.author = "Author";
        fileName = "untitled";
        script.id = fileName;
        dirty = false;
    }

    public void loadFromJson(String json) {
        script = EditorSerializer.deserialize(json);
        dirty = false;
    }

    public String toJson() {
        script.id = fileName;
        return EditorSerializer.serialize(script);
    }
}
