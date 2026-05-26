package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        JsonArray kfs = new JsonArray();
        JsonObject kf0 = new JsonObject();
        kf0.addProperty("time", 0f);
        kfs.add(kf0);
        JsonObject kf1 = new JsonObject();
        kf1.addProperty("time", duration);
        kfs.add(kf1);
        clip.add("keyframes", kfs);
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
        if (kfs == null) return null;
        JsonObject kf = new JsonObject();
        kf.addProperty("time", 0f);
        kfs.add(kf);
        sortKeyframes(clip);
        for (int i = 0; i < kfs.size(); i++) {
            if (kfs.get(i).getAsJsonObject() == kf && i + 1 < kfs.size()) {
                copyKeyframeProperties(kf, kfs.get(i + 1).getAsJsonObject());
                break;
            }
        }
        return kf;
    }

    public static JsonObject addKeyframeAt(JsonObject clip, float globalTime) {
        float localTime = globalTime - getStart(clip);
        if (localTime < 0 || localTime > getDuration(clip)) return null;
        JsonArray kfs = keyframes(clip);
        if (kfs == null) return null;

        for (JsonElement ke : kfs) {
            if (Math.abs(ke.getAsJsonObject().get("time").getAsFloat() - localTime) < 0.001f) return null;
        }

        JsonObject kf = new JsonObject();
        kf.addProperty("time", localTime);
        kfs.add(kf);
        sortKeyframes(clip);

        for (int i = 0; i < kfs.size(); i++) {
            if (kfs.get(i).getAsJsonObject() == kf) {
                fillKeyframeProperties(kf, kfs, i);
                break;
            }
        }
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
            float oldDur = getDuration(clip);
            float newDur = oldEnd - ns;
            clip.addProperty("start_time", ns);
            clip.addProperty("duration", newDur);
            if (keyframes(clip) != null) {
                sortKeyframes(clip);
                moveEndBoundaryKeyframe(clip, oldDur, newDur);
                clampKeyframes(clip);
                ensureBoundaryKeyframes(clip);
                sortKeyframes(clip);
            }
        }
    }

    public static void resizeClipRight(JsonObject clip, float newEnd, float snapInterval) {
        float ne = Math.max(getStart(clip) + 0.1f, snap(newEnd, snapInterval));
        float oldDur = getDuration(clip);
        float newDur = ne - getStart(clip);
        clip.addProperty("duration", newDur);
        if (keyframes(clip) != null) {
            sortKeyframes(clip);
            moveEndBoundaryKeyframe(clip, oldDur, newDur);
            clampKeyframes(clip);
            ensureBoundaryKeyframes(clip);
            sortKeyframes(clip);
        }
    }

    public static void moveKeyframe(JsonObject clip, JsonObject kf, float newLocalTime, float snapInterval) {
        kf.addProperty("time", Math.max(0, Math.min(getDuration(clip), snap(newLocalTime, snapInterval))));
        sortKeyframes(clip);
    }

    /** Move the existing end-boundary keyframe (time ≈ oldDur) to newDur. */
    private static void moveEndBoundaryKeyframe(JsonObject clip, float oldDur, float newDur) {
        JsonArray kfs = keyframes(clip);
        if (kfs == null) return;
        for (JsonElement ke : kfs) {
            if (Math.abs(ke.getAsJsonObject().get("time").getAsFloat() - oldDur) < 0.001f) {
                ke.getAsJsonObject().addProperty("time", newDur);
                return;
            }
        }
    }

    public static void ensureBoundaryKeyframes(JsonObject clip) {
        JsonArray kfs = keyframes(clip);
        if (kfs == null) return;
        sortKeyframes(clip);
        float dur = getDuration(clip);
        boolean hasStart = false, hasEnd = false;
        for (JsonElement ke : kfs) {
            float t = ke.getAsJsonObject().get("time").getAsFloat();
            if (Math.abs(t) < 0.001f) hasStart = true;
            if (Math.abs(t - dur) < 0.001f) hasEnd = true;
        }
        if (!hasStart) {
            JsonObject kf = new JsonObject();
            kf.addProperty("time", 0f);
            if (kfs.size() > 0) {
                copyKeyframeProperties(kf, kfs.get(0).getAsJsonObject());
            }
            kfs.add(kf);
        }
        if (!hasEnd) {
            JsonObject kf = new JsonObject();
            kf.addProperty("time", dur);
            if (kfs.size() > 0) {
                copyKeyframeProperties(kf, kfs.get(kfs.size() - 1).getAsJsonObject());
            }
            kfs.add(kf);
        }
    }

    public static void clampKeyframes(JsonObject clip) {
        float dur = getDuration(clip);
        JsonArray kfs = keyframes(clip);
        if (kfs == null) return;
        for (JsonElement ke : kfs) {
            JsonObject kf = ke.getAsJsonObject();
            float t = kf.get("time").getAsFloat();
            kf.addProperty("time", Math.max(0, Math.min(dur, t)));
        }
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
    public static float snap(float t, float interval) {
        if (interval <= 0) return t;
        return Math.round(t / interval) * interval;
    }

    public static void sortKeyframes(JsonObject clip) {
        JsonArray kfs = keyframes(clip);
        if (kfs == null || kfs.size() < 2) return;
        List<JsonElement> list = new ArrayList<>(kfs.size());
        for (int i = 0; i < kfs.size(); i++) list.add(kfs.get(i));
        list.sort(Comparator.comparingDouble(e -> e.getAsJsonObject().get("time").getAsFloat()));
        for (int i = kfs.size() - 1; i >= 0; i--) kfs.remove(i);
        for (JsonElement e : list) kfs.add(e);
    }

    private static void fillKeyframeProperties(JsonObject kf, JsonArray kfs, int insertIdx) {
        JsonObject prevKf = insertIdx > 0 ? kfs.get(insertIdx - 1).getAsJsonObject() : null;
        JsonObject nextKf = insertIdx < kfs.size() ? kfs.get(insertIdx).getAsJsonObject() : null;

        if (prevKf != null && nextKf != null) {
            float t0 = prevKf.get("time").getAsFloat();
            float t1 = nextKf.get("time").getAsFloat();
            float localTime = kf.get("time").getAsFloat();
            float ratio = (t1 - t0 > 0.001f) ? (localTime - t0) / (t1 - t0) : 0f;
            interpolateKeyframe(kf, prevKf, nextKf, ratio);
        } else if (prevKf != null) {
            copyKeyframeProperties(kf, prevKf);
        } else if (nextKf != null) {
            copyKeyframeProperties(kf, nextKf);
        }
    }

    private static void interpolateKeyframe(JsonObject target, JsonObject prev, JsonObject next, float ratio) {
        if (prev.has("position") && next.has("position")) {
            JsonObject prevPos = prev.getAsJsonObject("position");
            JsonObject nextPos = next.getAsJsonObject("position");
            JsonObject pos = new JsonObject();
            if (prevPos.has("dx")) {
                pos.addProperty("dx", lerp(prevPos.get("dx").getAsFloat(), nextPos.get("dx").getAsFloat(), ratio));
                pos.addProperty("dy", lerp(prevPos.get("dy").getAsFloat(), nextPos.get("dy").getAsFloat(), ratio));
                pos.addProperty("dz", lerp(prevPos.get("dz").getAsFloat(), nextPos.get("dz").getAsFloat(), ratio));
            } else {
                pos.addProperty("x", lerp(prevPos.get("x").getAsFloat(), nextPos.get("x").getAsFloat(), ratio));
                pos.addProperty("y", lerp(prevPos.get("y").getAsFloat(), nextPos.get("y").getAsFloat(), ratio));
                pos.addProperty("z", lerp(prevPos.get("z").getAsFloat(), nextPos.get("z").getAsFloat(), ratio));
            }
            target.add("position", pos);
        }
        target.addProperty("yaw", lerp(prev.get("yaw").getAsFloat(), next.get("yaw").getAsFloat(), ratio));
        target.addProperty("pitch", lerp(prev.get("pitch").getAsFloat(), next.get("pitch").getAsFloat(), ratio));
        target.addProperty("roll", lerp(prev.get("roll").getAsFloat(), next.get("roll").getAsFloat(), ratio));
        target.addProperty("fov", lerp(prev.get("fov").getAsFloat(), next.get("fov").getAsFloat(), ratio));
        target.addProperty("zoom", lerp(
                prev.has("zoom") ? prev.get("zoom").getAsFloat() : 1.0f,
                next.has("zoom") ? next.get("zoom").getAsFloat() : 1.0f, ratio));
        target.addProperty("dof", lerp(
                prev.has("dof") ? prev.get("dof").getAsFloat() : 0f,
                next.has("dof") ? next.get("dof").getAsFloat() : 0f, ratio));
    }

    private static void copyKeyframeProperties(JsonObject target, JsonObject source) {
        if (source.has("position")) {
            target.add("position", source.getAsJsonObject("position").deepCopy());
        }
        target.addProperty("yaw", source.get("yaw").getAsFloat());
        target.addProperty("pitch", source.get("pitch").getAsFloat());
        target.addProperty("roll", source.get("roll").getAsFloat());
        target.addProperty("fov", source.get("fov").getAsFloat());
        if (source.has("zoom")) target.addProperty("zoom", source.get("zoom").getAsFloat());
        if (source.has("dof")) target.addProperty("dof", source.get("dof").getAsFloat());
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
