package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.editor.model.*;

import java.util.Map;

public class EditorSerializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static String serialize(EditorScript script) {
        JsonObject root = new JsonObject();

        JsonObject meta = new JsonObject();
        meta.addProperty("id", script.id);
        meta.addProperty("name", script.name);
        meta.addProperty("author", script.author);
        meta.addProperty("version", script.version);
        if (!script.description.isEmpty()) {
            meta.addProperty("description", script.description);
        }
        if (script.dimension != null) {
            meta.addProperty("dimension", script.dimension);
        }

        meta.addProperty("block_keyboard", script.blockKeyboard);
        meta.addProperty("block_mouse", script.blockMouse);
        meta.addProperty("block_mob_ai", script.blockMobAi);
        meta.addProperty("hide_hud", script.hideHud);
        meta.addProperty("hide_arm", script.hideArm);
        meta.addProperty("suppress_bob", script.suppressBob);
        addNullableBoolean(meta, "hide_chat", script.hideChat);
        addNullableBoolean(meta, "hide_scoreboard", script.hideScoreboard);
        addNullableBoolean(meta, "hide_action_bar", script.hideActionBar);
        addNullableBoolean(meta, "hide_title", script.hideTitle);
        addNullableBoolean(meta, "hide_subtitles", script.hideSubtitles);
        addNullableBoolean(meta, "hide_hotbar", script.hideHotbar);
        addNullableBoolean(meta, "hide_crosshair", script.hideCrosshair);
        meta.addProperty("render_player_model", script.renderPlayerModel);
        meta.addProperty("pause_when_game_paused", script.pauseWhenGamePaused);
        meta.addProperty("interruptible", script.interruptible);
        meta.addProperty("skippable", script.skippable);
        meta.addProperty("hold_at_end", script.holdAtEnd);

        if (!script.triggers.isEmpty()) {
            JsonArray triggers = new JsonArray();
            for (EditorTrigger t : script.triggers) {
                JsonObject tj = new JsonObject();
                tj.addProperty("id", t.id);
                tj.addProperty("type", t.type);
                if (!t.conditions.isEmpty()) {
                    tj.add("conditions", GSON.toJsonTree(t.conditions));
                }
                tj.addProperty("repeatable", t.repeatable);
                if (t.delay != 0) {
                    tj.addProperty("delay", t.delay);
                }
                triggers.add(tj);
            }
            meta.add("triggers", triggers);
        }

        root.add("meta", meta);

        JsonObject timeline = new JsonObject();
        timeline.addProperty("total_duration", script.totalDuration);
        JsonArray tracks = new JsonArray();
        for (EditorTrack track : script.tracks) {
            JsonObject tj = new JsonObject();
            tj.addProperty("type", track.type.toUpperCase());
            JsonArray clips = new JsonArray();
            for (EditorClip clip : track.clips) {
                JsonObject cj = serializeClip(clip);
                clips.add(cj);
            }
            tj.add("clips", clips);
            tracks.add(tj);
        }
        timeline.add("tracks", tracks);
        root.add("timeline", timeline);

        return GSON.toJson(root);
    }

    private static JsonObject serializeClip(EditorClip clip) {
        JsonObject cj = new JsonObject();
        cj.addProperty("start_time", clip.startTime);
        cj.addProperty("duration", clip.duration);
        cj.addProperty("transition", clip.transition);
        if (clip.transitionDuration != 0.5f) {
            cj.addProperty("transition_duration", clip.transitionDuration);
        }
        cj.addProperty("interpolation", clip.interpolation);
        cj.addProperty("position_mode", clip.positionMode);
        cj.addProperty("loop", clip.loop);
        if (clip.loop) {
            cj.addProperty("loop_count", clip.loopCount);
        }
        JsonArray keyframes = new JsonArray();
        for (EditorKeyframe kf : clip.keyframes) {
            JsonObject kj = new JsonObject();
            kj.addProperty("time", kf.time);
            JsonObject pos = new JsonObject();
            if (kf.position.relative) {
                pos.addProperty("dx", kf.position.x);
                pos.addProperty("dy", kf.position.y);
                pos.addProperty("dz", kf.position.z);
            } else {
                pos.addProperty("x", kf.position.x);
                pos.addProperty("y", kf.position.y);
                pos.addProperty("z", kf.position.z);
            }
            kj.add("position", pos);
            kj.addProperty("yaw", kf.yaw);
            kj.addProperty("pitch", kf.pitch);
            kj.addProperty("roll", kf.roll);
            kj.addProperty("fov", kf.fov);
            kj.addProperty("zoom", kf.zoom);
            kj.addProperty("dof", kf.dof);
            keyframes.add(kj);
        }
        cj.add("keyframes", keyframes);
        return cj;
    }

    private static void addNullableBoolean(JsonObject obj, String key, Boolean value) {
        if (value != null) {
            obj.addProperty(key, value);
        } else {
            obj.add(key, null);
        }
    }

    public static EditorScript deserialize(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        EditorScript script = new EditorScript();

        JsonObject meta = root.getAsJsonObject("meta");
        script.id = meta.get("id").getAsString();
        script.name = meta.get("name").getAsString();
        script.author = meta.get("author").getAsString();
        script.version = meta.get("version").getAsInt();
        script.description = getStringOr(meta, "description", "");
        script.dimension = getStringOrNull(meta, "dimension");

        script.blockKeyboard = getBoolOr(meta, "block_keyboard", true);
        script.blockMouse = getBoolOr(meta, "block_mouse", true);
        script.blockMobAi = getBoolOr(meta, "block_mob_ai", false);
        script.hideHud = getBoolOr(meta, "hide_hud", true);
        script.hideArm = getBoolOr(meta, "hide_arm", true);
        script.suppressBob = getBoolOr(meta, "suppress_bob", true);
        script.hideChat = getBoolOrNull(meta, "hide_chat");
        script.hideScoreboard = getBoolOrNull(meta, "hide_scoreboard");
        script.hideActionBar = getBoolOrNull(meta, "hide_action_bar");
        script.hideTitle = getBoolOrNull(meta, "hide_title");
        script.hideSubtitles = getBoolOrNull(meta, "hide_subtitles");
        script.hideHotbar = getBoolOrNull(meta, "hide_hotbar");
        script.hideCrosshair = getBoolOrNull(meta, "hide_crosshair");
        script.renderPlayerModel = getBoolOr(meta, "render_player_model", true);
        script.pauseWhenGamePaused = getBoolOr(meta, "pause_when_game_paused", true);
        script.interruptible = getBoolOr(meta, "interruptible", true);
        script.skippable = getBoolOr(meta, "skippable", true);
        script.holdAtEnd = getBoolOr(meta, "hold_at_end", false);

        if (meta.has("triggers")) {
            JsonArray triggers = meta.getAsJsonArray("triggers");
            for (JsonElement e : triggers) {
                JsonObject tj = e.getAsJsonObject();
                EditorTrigger t = new EditorTrigger();
                t.id = tj.get("id").getAsString();
                t.type = tj.get("type").getAsString();
                if (tj.has("conditions")) {
                    JsonObject cond = tj.getAsJsonObject("conditions");
                    for (Map.Entry<String, JsonElement> entry : cond.entrySet()) {
                        JsonElement ve = entry.getValue();
                        if (ve.isJsonPrimitive()) {
                            t.conditions.put(entry.getKey(), ve.getAsString());
                        } else if (ve.isJsonObject()) {
                            t.conditions.put(entry.getKey(), GSON.fromJson(ve, Map.class));
                        }
                    }
                }
                t.repeatable = getBoolOr(tj, "repeatable", false);
                t.delay = getFloatOr(tj, "delay", 0f);
                script.triggers.add(t);
            }
        }

        JsonObject timeline = root.getAsJsonObject("timeline");
        script.totalDuration = timeline.get("total_duration").getAsFloat();

        script.tracks.clear();
        JsonArray tracksArr = timeline.getAsJsonArray("tracks");
        for (JsonElement e : tracksArr) {
            JsonObject tj = e.getAsJsonObject();
            EditorTrack track = new EditorTrack(tj.get("type").getAsString().toLowerCase());
            JsonArray clipsArr = tj.getAsJsonArray("clips");
            for (JsonElement ce : clipsArr) {
                JsonObject cj = ce.getAsJsonObject();
                EditorClip clip = new EditorClip();
                clip.startTime = cj.get("start_time").getAsFloat();
                clip.duration = cj.get("duration").getAsFloat();
                clip.transition = getStringOr(cj, "transition", "cut");
                clip.transitionDuration = getFloatOr(cj, "transition_duration", 0.5f);
                clip.interpolation = getStringOr(cj, "interpolation", "linear");
                clip.positionMode = getStringOr(cj, "position_mode", "relative");
                clip.loop = getBoolOr(cj, "loop", false);
                clip.loopCount = getIntOr(cj, "loop_count", -1);

                JsonArray kfs = cj.getAsJsonArray("keyframes");
                for (JsonElement ke : kfs) {
                    JsonObject kj = ke.getAsJsonObject();
                    EditorKeyframe kf = new EditorKeyframe();
                    kf.time = kj.get("time").getAsFloat();
                    JsonObject pos = kj.getAsJsonObject("position");
                    if (pos.has("dx") || pos.has("dy") || pos.has("dz")) {
                        kf.position.x = getFloatOr(pos, "dx", 0);
                        kf.position.y = getFloatOr(pos, "dy", 0);
                        kf.position.z = getFloatOr(pos, "dz", 0);
                        kf.position.relative = true;
                    } else {
                        kf.position.x = getFloatOr(pos, "x", 0);
                        kf.position.y = getFloatOr(pos, "y", 0);
                        kf.position.z = getFloatOr(pos, "z", 0);
                        kf.position.relative = false;
                    }
                    kf.yaw = kj.get("yaw").getAsFloat();
                    kf.pitch = kj.get("pitch").getAsFloat();
                    kf.roll = kj.get("roll").getAsFloat();
                    kf.fov = kj.get("fov").getAsFloat();
                    kf.zoom = getFloatOr(kj, "zoom", 1f);
                    kf.dof = getFloatOr(kj, "dof", 0f);
                    clip.keyframes.add(kf);
                }
                track.clips.add(clip);
            }
            script.tracks.add(track);
        }

        return script;
    }

    private static String getStringOr(JsonObject obj, String key, String def) {
        return obj.has(key) ? obj.get(key).getAsString() : def;
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static boolean getBoolOr(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    private static Boolean getBoolOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : null;
    }

    private static float getFloatOr(JsonObject obj, String key, float def) {
        return obj.has(key) ? obj.get(key).getAsFloat() : def;
    }

    private static int getIntOr(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }
}
