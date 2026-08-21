package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.TrackType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各轨道类型的 Java 字段元数据（0.3.5 第5轮 5B）。
 * <p>
 * 数据源从 schema.json 迁移到此处；顺序即编辑器渲染顺序。
 */
public final class TrackSchemas {

    private TrackSchemas() {}

    public static Map<TrackType, TrackTypeSchema> all() {
        Map<TrackType, TrackTypeSchema> map = new LinkedHashMap<>();
        map.put(TrackType.CAMERA, camera());
        map.put(TrackType.LETTERBOX, letterbox());
        map.put(TrackType.AUDIO, audio());
        map.put(TrackType.EVENT, event());
        map.put(TrackType.MOD_EVENT, modEvent());
        map.put(TrackType.OVERLAY, overlay());
        return map;
    }

    private static TrackTypeSchema camera() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("transition", new FieldDef("enum", "cut", false, List.of("cut", "morph")));
        clips.put("transition_duration", new FieldDef("float", 0.5f));
        clips.put("interpolation", new FieldDef("enum", "linear", false, List.of("linear", "smooth")));
        clips.put("curve", new FieldDef("bezier_curve", null));
        clips.put("dimension", new FieldDef("string", ""));
        clips.put("loop", new FieldDef("bool", false));
        clips.put("loop_count", new FieldDef("int", -1));
        clips.put("loop_mode", new FieldDef("enum", "repeat", false, List.of("repeat", "pingpong")));
        clips.put("cam_breath_enabled", new FieldDef("bool", false));
        clips.put("cam_breath_intensity", new FieldDef("float", 0.05f));
        clips.put("cam_breath_seed", new FieldDef("int", 0));
        clips.put("cam_breath_type", new FieldDef("enum", "perlin", false,
                List.of("perlin", "perlin_axis", "sine", "trauma")));
        clips.put("cam_breath_speed", new FieldDef("float", 1.0f));
        clips.put("cam_breath_trauma", new FieldDef("float", 1.0f));
        clips.put("cam_breath_decay", new FieldDef("float", 0.5f));
        clips.put("orient", new FieldDef("enum", "manual", false, List.of("manual", "look_at", "tangent")));
        clips.put("yaw_offset", new FieldDef("float", 0f));
        clips.put("pitch_offset", new FieldDef("float", 0f));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("position", new FieldDef("position", null, true));
        kfs.put("position_mode", new FieldDef("enum", "relative", false, List.of("relative", "absolute")));
        kfs.put("follow", new FieldDef("enum", "none", false, List.of("none", "entity")));
        kfs.put("follow_selector", new FieldDef("string", "@p"));
        kfs.put("look_at", new FieldDef("enum", "none", false, List.of("none", "coordinate", "entity")));
        kfs.put("look_at_selector", new FieldDef("string", "@p"));
        kfs.put("look_at_target_x", new FieldDef("float", null));
        kfs.put("look_at_target_y", new FieldDef("float", null));
        kfs.put("look_at_target_z", new FieldDef("float", null));
        kfs.put("look_at_target_structure", new FieldDef("string", ""));
        kfs.put("look_at_target", new FieldDef("map", null));
        kfs.put("yaw_base", new FieldDef("enum", "world", false, List.of("world", "entity", "line")));
        kfs.put("pitch_base", new FieldDef("enum", "world", false, List.of("world", "entity", "line")));
        kfs.put("yaw_base_selector", new FieldDef("string", "@p"));
        kfs.put("yaw_base_from", new FieldDef("string", ""));
        kfs.put("yaw_base_to", new FieldDef("string", ""));
        kfs.put("yaw", new FieldDef("float", 0));
        kfs.put("pitch", new FieldDef("float", 0));
        kfs.put("roll", new FieldDef("float", 0));
        kfs.put("fov", new FieldDef("float", 70));
        kfs.put("zoom", new FieldDef("float", 1.0f));
        kfs.put("orient", new FieldDef("enum", "manual", false, List.of("manual", "look_at", "tangent")));
        kfs.put("yaw_offset", new FieldDef("float", 0f));
        kfs.put("pitch_offset", new FieldDef("float", 0f));

        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema letterbox() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("aspect_ratio", new FieldDef("float", 2.35f));
        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema audio() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("sound", new FieldDef("string", null, true));
        clips.put("source", new FieldDef("enum", "file", false, List.of("file", "minecraft")));
        clips.put("volume", new FieldDef("float", 1.0f));
        clips.put("pitch", new FieldDef("float", 1.0f));
        clips.put("loop", new FieldDef("bool", false));
        clips.put("fade_in", new FieldDef("float", 0));
        clips.put("fade_out", new FieldDef("float", 0));
        clips.put("attenuation", new FieldDef("enum", "linear", false, List.of("none", "linear", "inverse")));
        clips.put("position_mode", new FieldDef("enum", "relative", false, List.of("relative", "absolute")));
        clips.put("category", new FieldDef("enum", "music", false, List.of("music", "ambient")));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("volume", new FieldDef("float", 1.0f));
        kfs.put("x", new FieldDef("float", 0));
        kfs.put("y", new FieldDef("float", 0));
        kfs.put("z", new FieldDef("float", 0));

        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema event() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("event_type", new FieldDef("string", "command"));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("event_type", new FieldDef("string", "command"));
        kfs.put("command", new FieldDef("string", ""));
        kfs.put("position", new FieldDef("map", null));

        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema modEvent() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("event_type", new FieldDef("string", null, true));
        clips.put("data", new FieldDef("map", null));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("event_type", new FieldDef("string", "mod_event"));
        kfs.put("data", new FieldDef("map", null));

        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema overlay() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("layer_type", new FieldDef("enum", "fade", true, List.of("fade", "image", "subtitle", "pip")));
        clips.put("color", new FieldDef("string", "#000000"));
        clips.put("path", new FieldDef("string", ""));
        clips.put("text", new FieldDef("string", ""));
        clips.put("z_index", new FieldDef("int", 20));
        clips.put("interpolation", new FieldDef("enum", "linear", false, List.of("linear", "smooth")));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("opacity", new FieldDef("float", 0.0f));
        kfs.put("x", new FieldDef("float", 0));
        kfs.put("y", new FieldDef("float", 0));
        kfs.put("font_scale", new FieldDef("float", 1));
        kfs.put("scale_x", new FieldDef("float", 1));
        kfs.put("scale_y", new FieldDef("float", 1));

        return new TrackTypeSchema(clips, kfs);
    }

}
