package com.immersivecinematics.immersive_cinematics.editor;

import java.util.ArrayDeque;

public class EditorUndoManager {
    private final ArrayDeque<String> undoStack = new ArrayDeque<>();
    private final ArrayDeque<String> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 50;

    public void push(String json) {
        undoStack.addLast(json);
        redoStack.clear();
        if (undoStack.size() > MAX_UNDO) undoStack.pollFirst();
    }

    public String undo() {
        if (undoStack.isEmpty()) return null;
        String state = undoStack.pollLast();
        redoStack.addLast(state);
        if (redoStack.size() > MAX_UNDO) redoStack.pollFirst();
        return undoStack.isEmpty() ? null : undoStack.peekLast();
    }

    public String redo() {
        if (redoStack.isEmpty()) return null;
        String state = redoStack.pollLast();
        undoStack.addLast(state);
        return state;
    }
}
