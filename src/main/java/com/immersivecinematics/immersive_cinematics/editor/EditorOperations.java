package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class EditorOperations {

    public static float getStart(JsonObject clip) {
        return clip.get("start_time").getAsFloat();
    }

    public static float getDuration(JsonObject clip) {
        return clip.get("duration").getAsFloat();
    }

    public static float getEnd(JsonObject clip) {
        return getStart(clip) + getDuration(clip);
    }

    public static JsonObject addClip(JsonArray tracks, int trackIndex, float startTime, float duration) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) return null;
        JsonObject clip = new JsonObject();
        clip.addProperty("start_time", startTime);
        clip.addProperty("duration", duration);
        tracks.get(trackIndex).getAsJsonObject().getAsJsonArray("clips").add(clip);
        recalc(tracks);
        return clip;
    }

    public static void deleteClip(JsonArray tracks, JsonObject clip) {
        for (JsonElement te : tracks) {
            JsonArray clips = te.getAsJsonObject().getAsJsonArray("clips");
            for (int i = 0; i < clips.size(); i++) {
                if (clips.get(i).getAsJsonObject() == clip) {
                    clips.remove(i);
                    recalc(tracks);
                    return;
                }
            }
        }
    }

    public static JsonObject addDefaultKeyframe(JsonObject clip) {
        JsonArray kfs = keyframes(clip);
        if (kfs == null) {
            kfs = new JsonArray();
            clip.add("keyframes", kfs);
        }
        JsonObject kf = new JsonObject();
        kf.addProperty("time", 0f);
        kfs.add(kf);
        return kf;
    }

    public static JsonObject addKeyframeAt(JsonObject clip, float globalTime) {
        float localTime = globalTime - getStart(clip);
        if (localTime < 0 || localTime > getDuration(clip)) return null;
        JsonArray kfs = keyframes(clip);
        if (kfs == null) {
            kfs = new JsonArray();
            clip.add("keyframes", kfs);
        }
        JsonObject kf = new JsonObject();
        kf.addProperty("time", localTime);
        kfs.add(kf);
        return kf;
    }

    public static boolean canAddKeyframeAt(JsonObject clip, float globalTime) {
        if (clip == null) return false;
        float localTime = globalTime - getStart(clip);
        return localTime >= 0 && localTime <= getDuration(clip);
    }

    public static void deleteKeyframe(JsonObject clip, JsonObject kf) {
        JsonArray kfs = keyframes(clip);
        if (kfs == null) return;
        for (int i = 0; i < kfs.size(); i++) {
            if (kfs.get(i).getAsJsonObject() == kf) {
                kfs.remove(i);
                return;
            }
        }
    }

    public static void moveClip(JsonObject clip, float newStart, float snapInterval) {
        clip.addProperty("start_time", Math.max(0, snap(newStart, snapInterval)));
    }

    public static void resizeClipLeft(JsonObject clip, float newStart, float snapInterval) {
        float ns = Math.max(0, snap(newStart, snapInterval));
        float oldEnd = getEnd(clip);
        if (ns < oldEnd) {
            clip.addProperty("start_time", ns);
            clip.addProperty("duration", oldEnd - ns);
        }
    }

    public static void resizeClipRight(JsonObject clip, float newEnd, float snapInterval) {
        float ne = Math.max(getStart(clip) + 0.1f, snap(newEnd, snapInterval));
        clip.addProperty("duration", ne - getStart(clip));
    }

    public static void moveKeyframe(JsonObject clip, JsonObject kf, float newLocalTime, float snapInterval) {
        kf.addProperty("time", Math.max(0, Math.min(getDuration(clip), snap(newLocalTime, snapInterval))));
    }

    public static void recalc(JsonArray tracks) {
        float maxEnd = 0;
        for (JsonElement te : tracks) {
            JsonArray clips = te.getAsJsonObject().getAsJsonArray("clips");
            for (JsonElement ce : clips) {
                JsonObject clip = ce.getAsJsonObject();
                float end = getEnd(clip);
                if (clip.has("transition") && "morph".equals(clip.get("transition").getAsString())) {
                    end += clip.has("transition_duration") ? clip.get("transition_duration").getAsFloat() : 0;
                }
                maxEnd = Math.max(maxEnd, end);
            }
        }
        // total_duration is in timeline, not accessible from here; caller handles it
    }

    public static float recalcDuration(JsonArray tracks) {
        float maxEnd = 0;
        for (JsonElement te : tracks) {
            JsonArray clips = te.getAsJsonObject().getAsJsonArray("clips");
            for (JsonElement ce : clips) {
                JsonObject clip = ce.getAsJsonObject();
                float end = getEnd(clip);
                if (clip.has("transition") && "morph".equals(clip.get("transition").getAsString())) {
                    end += clip.has("transition_duration") ? clip.get("transition_duration").getAsFloat() : 0;
                }
                maxEnd = Math.max(maxEnd, end);
            }
        }
        return Math.max(1, maxEnd);
    }

    public static void snapAllClips(JsonArray tracks) {
        for (JsonElement te : tracks) {
            JsonArray clips = te.getAsJsonObject().getAsJsonArray("clips");
            float cursor = 0;
            for (JsonElement ce : clips) {
                JsonObject clip = ce.getAsJsonObject();
                clip.addProperty("start_time", cursor);
                cursor = getEnd(clip);
            }
        }
    }

    public static JsonArray keyframes(JsonObject clip) {
        return clip.has("keyframes") ? clip.getAsJsonArray("keyframes") : null;
    }

    public static float snap(float t) { return snap(t, 0.5f); }
    public static float snap(float t, float interval) { return Math.round(t / interval) * interval; }
}
