package com.immersivecinematics.immersive_cinematics.editor;

import com.immersivecinematics.immersive_cinematics.editor.model.*;
import java.util.function.BiConsumer;

public class EditorCore {

    private EditorScript script;
    private EditorClip selectedClip;
    private EditorKeyframe selectedKeyframe;
    private String fileName = "untitled";
    private boolean autoSnap;
    private Runnable onChanged;
    private BiConsumer<EditorClip, EditorKeyframe> onSelectionChanged;

    public EditorCore() {
        script = new EditorScript();
    }

    public EditorScript getScript() { return script; }
    public EditorClip getSelectedClip() { return selectedClip; }
    public EditorKeyframe getSelectedKeyframe() { return selectedKeyframe; }
    public String getFileName() { return fileName; }
    public boolean isAutoSnap() { return autoSnap; }

    public void setOnChanged(Runnable r) { onChanged = r; }
    public void setOnSelectionChanged(BiConsumer<EditorClip, EditorKeyframe> r) { onSelectionChanged = r; }

    public void toggleAutoSnap() {
        autoSnap = !autoSnap;
        if (autoSnap) snapAllClips();
        fireChanged();
    }

    public void newScript() {
        script = new EditorScript();
        script.name = "Untitled";
        script.author = "Author";
        fileName = "untitled";
        script.id = fileName;
        selectedClip = null;
        selectedKeyframe = null;

        EditorClip clip = new EditorClip();
        clip.startTime = 0;
        clip.duration = 10;
        clip.keyframes.add(makeKeyframe(0));
        clip.keyframes.add(makeKeyframe(10));
        script.tracks.get(0).clips.add(clip);
        recalc();
        fireChanged();
    }

    public void loadFromJson(String json) {
        script = EditorSerializer.deserialize(json);
        selectedClip = null;
        selectedKeyframe = null;
        recalc();
        fireChanged();
    }

    public String toJson() {
        script.id = fileName;
        return EditorSerializer.serialize(script);
    }

    public void setFileName(String name) {
        fileName = name.replaceAll("[^a-zA-Z0-9_]", "").replaceAll("_+", "_");
        if (fileName.isEmpty()) fileName = "untitled";
        script.id = fileName;
    }

    public EditorClip addClip() {
        EditorTrack track = script.tracks.get(0);
        float start = 0;
        if (autoSnap) {
            for (EditorClip c : track.clips) start = Math.max(start, c.endTime());
        } else {
            start = script.totalDuration;
        }
        EditorClip clip = new EditorClip();
        clip.startTime = start;
        clip.duration = 5;
        clip.keyframes.add(makeKeyframe(0));
        track.clips.add(clip);
        selectClip(clip);
        recalc();
        fireChanged();
        return clip;
    }

    public void deleteSelectedClip() {
        if (selectedClip == null) return;
        for (EditorTrack track : script.tracks) {
            if (track.clips.remove(selectedClip)) break;
        }
        selectedClip = null;
        selectedKeyframe = null;
        if (autoSnap) snapAllClips();
        recalc();
        fireChanged();
    }

    public EditorKeyframe addKeyframeAt(float globalTime) {
        if (selectedClip == null) return null;
        float localTime = globalTime - selectedClip.startTime;
        if (localTime < 0 || localTime > selectedClip.duration) return null;
        EditorKeyframe kf = makeKeyframe(localTime);
        selectedClip.keyframes.add(kf);
        selectKeyframe(kf);
        fireChanged();
        return kf;
    }

    public void deleteSelectedKeyframe() {
        if (selectedClip == null || selectedKeyframe == null) return;
        selectedClip.keyframes.remove(selectedKeyframe);
        selectedKeyframe = null;
        fireChanged();
    }

    public boolean canAddKeyframeAt(float globalTime) {
        if (selectedClip == null) return false;
        float localTime = globalTime - selectedClip.startTime;
        return localTime >= 0 && localTime <= selectedClip.duration;
    }

    public void selectClip(EditorClip clip) {
        selectedClip = clip;
        selectedKeyframe = null;
        if (onSelectionChanged != null) onSelectionChanged.accept(selectedClip, null);
    }

    public void selectKeyframe(EditorKeyframe kf) {
        selectedKeyframe = kf;
        if (onSelectionChanged != null) onSelectionChanged.accept(selectedClip, selectedKeyframe);
    }

    public void moveClip(EditorClip clip, float newStart) {
        clip.startTime = Math.max(0, snap(newStart));
        if (autoSnap) snapAllClips();
        recalc();
        fireChanged();
    }

    public void resizeClipLeft(EditorClip clip, float newStart) {
        float ns = Math.max(0, snap(newStart));
        float oldEnd = clip.endTime();
        if (ns < oldEnd) {
            clip.startTime = ns;
            clip.duration = oldEnd - ns;
            if (autoSnap) snapAllClips();
            recalc();
            fireChanged();
        }
    }

    public void resizeClipRight(EditorClip clip, float newEnd) {
        float ne = Math.max(clip.startTime + 0.1f, snap(newEnd));
        clip.duration = ne - clip.startTime;
        if (autoSnap) snapAllClips();
        recalc();
        fireChanged();
    }

    public void moveKeyframe(EditorKeyframe kf, EditorClip clip, float newLocalTime) {
        kf.time = Math.max(0, Math.min(clip.duration, snap(newLocalTime)));
        fireChanged();
    }

    public void setScriptName(String name) { script.name = name; fireChanged(); }
    public void setScriptAuthor(String author) { script.author = author; fireChanged(); }
    public void setScriptDescription(String desc) { script.description = desc; fireChanged(); }
    public void setTotalDuration(float d) { script.totalDuration = d; fireChanged(); }
    public void setBehaviorFlag(String field, boolean value) {
        switch (field) {
            case "blockKeyboard": script.blockKeyboard = value; break;
            case "blockMouse": script.blockMouse = value; break;
            case "hideHud": script.hideHud = value; break;
            case "hideArm": script.hideArm = value; break;
            case "suppressBob": script.suppressBob = value; break;
            case "skippable": script.skippable = value; break;
            case "holdAtEnd": script.holdAtEnd = value; break;
            case "interruptible": script.interruptible = value; break;
        }
        fireChanged();
    }

    private void recalc() {
        float maxEnd = 0;
        for (EditorTrack track : script.tracks) {
            for (EditorClip clip : track.clips) {
                float end = clip.endTime();
                if ("morph".equals(clip.transition)) {
                    end += clip.transitionDuration;
                }
                maxEnd = Math.max(maxEnd, end);
            }
        }
        script.totalDuration = Math.max(1, maxEnd);
    }

    private void snapAllClips() {
        for (EditorTrack track : script.tracks) {
            float cursor = 0;
            for (EditorClip clip : track.clips) {
                clip.startTime = cursor;
                cursor = clip.endTime();
            }
        }
    }

    private static float snap(float t) {
        return Math.round(t * 2) / 2f;
    }

    private static EditorKeyframe makeKeyframe(float time) {
        EditorKeyframe kf = new EditorKeyframe();
        kf.time = time;
        kf.fov = 70;
        kf.position = new EditorPosition(5, 2, 0, true);
        return kf;
    }

    private void fireChanged() {
        if (onChanged != null) onChanged.run();
    }
}
