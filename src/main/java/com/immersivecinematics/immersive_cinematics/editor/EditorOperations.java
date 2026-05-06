package com.immersivecinematics.immersive_cinematics.editor;

import com.immersivecinematics.immersive_cinematics.editor.model.*;

public class EditorOperations {

    public static EditorKeyframe makeKeyframe(float time) {
        EditorKeyframe kf = new EditorKeyframe();
        kf.time = time;
        kf.fov = 70;
        kf.position = new EditorPosition(5, 2, 0, true);
        return kf;
    }

    public static EditorClip makeDefaultClip() {
        EditorClip clip = new EditorClip();
        clip.startTime = 0;
        clip.duration = 10;
        clip.keyframes.add(makeKeyframe(0));
        clip.keyframes.add(makeKeyframe(10));
        return clip;
    }

    public static EditorClip addClip(EditorScript script, int trackIndex, float startTime, float duration, boolean autoSnap) {
        if (trackIndex < 0 || trackIndex >= script.tracks.size()) return null;
        EditorTrack track = script.tracks.get(trackIndex);
        EditorClip clip = new EditorClip();
        clip.startTime = startTime;
        clip.duration = duration;
        clip.keyframes.add(makeKeyframe(0));
        track.clips.add(clip);
        recalc(script);
        return clip;
    }

    public static void deleteClip(EditorScript script, EditorClip clip) {
        for (EditorTrack track : script.tracks) {
            if (track.clips.remove(clip)) break;
        }
        recalc(script);
    }

    public static EditorKeyframe addKeyframeAt(EditorScript script, EditorClip clip, float globalTime) {
        float localTime = globalTime - clip.startTime;
        if (localTime < 0 || localTime > clip.duration) return null;
        EditorKeyframe kf = makeKeyframe(localTime);
        clip.keyframes.add(kf);
        return kf;
    }

    public static boolean canAddKeyframeAt(EditorClip clip, float globalTime) {
        if (clip == null) return false;
        float localTime = globalTime - clip.startTime;
        return localTime >= 0 && localTime <= clip.duration;
    }

    public static void deleteKeyframe(EditorClip clip, EditorKeyframe kf) {
        if (clip != null) clip.keyframes.remove(kf);
    }

    public static void moveClip(EditorScript script, EditorClip clip, float newStart, float snapInterval) {
        clip.startTime = Math.max(0, snap(newStart, snapInterval));
        recalc(script);
    }

    public static void resizeClipLeft(EditorScript script, EditorClip clip, float newStart, float snapInterval) {
        float ns = Math.max(0, snap(newStart, snapInterval));
        float oldEnd = clip.endTime();
        if (ns < oldEnd) {
            clip.startTime = ns;
            clip.duration = oldEnd - ns;
            recalc(script);
        }
    }

    public static void resizeClipRight(EditorScript script, EditorClip clip, float newEnd, float snapInterval) {
        float ne = Math.max(clip.startTime + 0.1f, snap(newEnd, snapInterval));
        clip.duration = ne - clip.startTime;
        recalc(script);
    }

    public static void moveKeyframe(EditorScript script, EditorClip clip, EditorKeyframe kf, float newLocalTime, float snapInterval) {
        kf.time = Math.max(0, Math.min(clip.duration, snap(newLocalTime, snapInterval)));
    }

    public static void snapAllClips(EditorScript script) {
        for (EditorTrack track : script.tracks) {
            float cursor = 0;
            for (EditorClip clip : track.clips) {
                clip.startTime = cursor;
                cursor = clip.endTime();
            }
        }
        recalc(script);
    }

    public static void recalc(EditorScript script) {
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

    public static float snap(float t) {
        return snap(t, 0.5f);
    }

    public static float snap(float t, float interval) {
        return Math.round(t / interval) * interval;
    }
}
