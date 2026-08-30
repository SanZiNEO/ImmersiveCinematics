package com.immersivecinematics.immersive_cinematics.script.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本 meta 字段的 Java 元数据（0.3.5 第5轮 5B）。
 * <p>
 * 顺序即编辑器 Script Properties 面板渲染顺序；section 用于 info/runtime 分组。
 */
public final class MetaSchemas {

    private MetaSchemas() {}

    public static Map<String, FieldDef> all() {
        Map<String, FieldDef> map = new LinkedHashMap<>();
        map.put("id", new FieldDef("string", "untitled", true, java.util.Collections.emptyList(), "info"));
        map.put("name", new FieldDef("string", "Untitled", true, java.util.Collections.emptyList(), "info"));
        map.put("author", new FieldDef("string", "", false, java.util.Collections.emptyList(), "info"));
        map.put("version", new FieldDef("int", 3, true, java.util.Collections.emptyList(), "info"));
        map.put("description", new FieldDef("string", "", false, java.util.Collections.emptyList(), "info"));
        map.put("dimension", new FieldDef("string", "", false, java.util.Collections.emptyList(), "info"));

        map.put("block_keyboard", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("block_mouse", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("block_mob_ai", new FieldDef("bool", false, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_hud", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_arm", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("suppress_bob", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("suppress_distortion", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_chat", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_scoreboard", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_action_bar", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_title", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_subtitles", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_hotbar", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_crosshair", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_bossbar", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_skip_hud", new FieldDef("tristate", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("render_player_model", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("pause_when_game_paused", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("interruptible", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("skippable", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hold_at_end", new FieldDef("bool", false, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hud_layers", new FieldDef("object", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("priority", new FieldDef("int", 0, false, java.util.Collections.emptyList(), "runtime"));
        map.put("skip_vote_ratio", new FieldDef("int", null, false, java.util.Collections.emptyList(), "runtime"));

        // 相机区域刷怪（0.3.5 第5.5轮）：脚本级开关，不属于全局 Config
        map.put("camera_mob_spawn", new FieldDef("bool", false, false, java.util.Collections.emptyList(), "camera"));
        map.put("camera_mob_radius", new FieldDef("int", 2, false, java.util.Collections.emptyList(), "camera"));
        map.put("camera_mob_ai", new FieldDef("bool", false, false, java.util.Collections.emptyList(), "camera"));

        return map;
    }
}
