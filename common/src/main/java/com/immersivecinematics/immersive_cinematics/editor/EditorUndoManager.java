package com.immersivecinematics.immersive_cinematics.editor;

import java.util.ArrayDeque;

public class EditorUndoManager {
    private final ArrayDeque<String> undoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 50;

    public void push(String json) {
        if (undoStack.size() >= MAX_UNDO) undoStack.pollFirst();
        undoStack.addLast(json);
    }

    public String pop() {
        return undoStack.isEmpty() ? null : undoStack.pollLast();
    }
}
