package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.spi.FieldDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本 meta 字段的 Java 元数据。
 *
 * 顺序即编辑器 Script Properties 面板渲染顺序；section 用于 info/runtime 分组。
 */
public final class MetaSchemas {

    private MetaSchemas() {}

    public static Map<String, FieldDef> all() {
        Map<String, FieldDef> map = new LinkedHashMap<>();
        map.put("id", new FieldDef("string", "untitled", true, java.util.Collections.emptyList(), "info"));
        map.put("name", new FieldDef("string", "Untitled", true, java.util.Collections.emptyList(), "info"));
        map.put("author", new FieldDef("string", "", false, java.util.Collections.emptyList(), "info"));
        map.put("description", new FieldDef("string", "", false, java.util.Collections.emptyList(), "info"));
        map.put("dimension", new FieldDef("string", "", false, java.util.Collections.emptyList(), "info"));
        // 编辑基准分辨率 {w, h}（像素，正整数）：分辨率转义系数 k 的分母；缺省 null = 1920×1080
        map.put("base_resolution", new FieldDef("object", null, false, java.util.Collections.emptyList(), "info"));
        map.put("listener", new FieldDef("enum", "player", false, java.util.List.of("player", "camera"), "runtime"));

        map.put("block_keyboard", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("block_mouse", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("block_mob_ai", new FieldDef("bool", false, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_hud", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_arm", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("suppress_bob", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("suppress_distortion", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_chat", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_scoreboard", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_action_bar", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_title", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_subtitles", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_hotbar", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_crosshair", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_bossbar", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hide_skip_hud", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("render_player_model", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("pause_when_game_paused", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("interruptible", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        // 跨脚本播放关系（三态，缺省 parallel = 现状）；同脚本再触发的替换语义仍看上面的 interruptible
        map.put("play_mode", new FieldDef("enum", "parallel", false,
                java.util.List.of("parallel", "exclusive", "interruptible"), "runtime"));
        map.put("skippable", new FieldDef("bool", true, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hold_at_end", new FieldDef("bool", false, false, java.util.Collections.emptyList(), "runtime"));
        map.put("macro_loop", new FieldDef("bool", false, false, java.util.Collections.emptyList(), "runtime"));
        map.put("macro_loop_count", new FieldDef("int", -1, false, java.util.Collections.emptyList(), "runtime"));
        map.put("macro_loop_mode", new FieldDef("enum", "repeat", false, java.util.List.of("repeat", "pingpong"), "runtime"));
        // 画面层统一入口（H-5）：15 个规范层名 + 已知别名 + 动态层名；值 = 显示（true 显示 / false 隐藏）
        map.put("screen_layers", new FieldDef("object", null, false, java.util.Collections.emptyList(), "runtime"));
        // 接管模式：normal / allowlist；缺省由 ScriptParser#resolveScreenLayerMode 归一（新字段路径 → allowlist）
        map.put("screen_layer_mode", new FieldDef("enum", null, false,
                java.util.List.of("normal", "allowlist"), "runtime"));
        // 以下两项为 deprecated 兼容入口：hud_layers 解析期归一到 screen_layers（极性翻转），hard_hide_hud 归一为模式别名
        map.put("hud_layers", new FieldDef("object", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("hard_hide_hud", new FieldDef("bool", null, false, java.util.Collections.emptyList(), "runtime"));
        map.put("priority", new FieldDef("int", 0, false, java.util.Collections.emptyList(), "runtime"));
        // 跳过投票比例：整数百分比 10~100，非法值解析期报错（不允许 0）
        map.put("skip_vote_ratio", new FieldDef("int", null, false, java.util.Collections.emptyList(), "runtime"));

        return map;
    }
}
