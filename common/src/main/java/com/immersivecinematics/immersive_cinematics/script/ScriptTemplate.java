package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;

import java.util.Map;

/**
 * 新建脚本的默认骨架（空脚本模板）。
 * <p>
 * meta 默认值取自 {@link SchemaLoader#getMetaFields()}（tristate 字段无默认值则不写入，
 * 与运行时 {@code optNullableBool} 语义一致）；轨道列表跟随 {@link TrackType} 枚举，
 * 每条轨道带唯一 id 与空 clips 数组。生成的 JSON 与手写脚本同构，
 * 经 {@link ScriptParser} / {@link ScriptValidator} 校验后可正常播放。
 */
public final class ScriptTemplate {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 新建脚本的默认总时长（秒） */
    public static final float DEFAULT_TOTAL_DURATION = 10f;

    private ScriptTemplate() {}

    /** 默认脚本 JSON 文本（WebUI {@code script.new} 使用） */
    public static String newScriptJson() {
        return GSON.toJson(build());
    }

    /** 默认脚本 JSON 对象 */
    public static JsonObject build() {
        JsonObject root = new JsonObject();

        JsonObject meta = new JsonObject();
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getMetaFields().entrySet()) {
            Object def = e.getValue().defaultValue();
            if (def instanceof Boolean b) meta.addProperty(e.getKey(), b);
            else if (def instanceof Number n) meta.addProperty(e.getKey(), n.floatValue());
            else if (def instanceof String s) meta.addProperty(e.getKey(), s);
        }
        root.add("meta", meta);

        JsonObject timeline = new JsonObject();
        timeline.addProperty("total_duration", DEFAULT_TOTAL_DURATION);
        JsonArray tracks = new JsonArray();
        for (TrackType t : TrackType.values()) {
            JsonObject track = new JsonObject();
            track.addProperty("type", t.name());
            track.addProperty("id", generateTrackId(tracks, t.name()));
            track.add("clips", new JsonArray());
            tracks.add(track);
        }
        timeline.add("tracks", tracks);
        root.add("timeline", timeline);

        return root;
    }

    /** 生成唯一轨道 id：{type小写}_{n}（n 从 1 递增直到无冲突；同类型多条轨道通过 id 区分） */
    private static String generateTrackId(JsonArray tracks, String type) {
        String base = type.toLowerCase();
        int n = 1;
        while (true) {
            String id = base + "_" + n;
            boolean taken = false;
            for (JsonElement te : tracks) {
                if (id.equals(te.getAsJsonObject().get("id").getAsString())) {
                    taken = true;
                    break;
                }
            }
            if (!taken) return id;
            n++;
        }
    }
}
