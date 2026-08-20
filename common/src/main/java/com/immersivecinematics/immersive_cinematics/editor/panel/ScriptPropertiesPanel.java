package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.immersivecinematics.immersive_cinematics.editor.EditorDefaults;
import com.immersivecinematics.immersive_cinematics.editor.fields.FieldGroup;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 脚本属性面板（0.3.5 第5轮 5A + 字段全覆盖/折叠分组）。
 */
public class ScriptPropertiesPanel extends EditorPanel {

    private static final List<FieldGroup> GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("id", "name", "author", "version", "description", "dimension")),
            new FieldGroup("editor.group.runtime", true,
                    List.of("block_keyboard", "block_mouse", "block_mob_ai", "hide_hud",
                            "render_player_model", "pause_when_game_paused")),
            new FieldGroup("editor.group.hide", false,
                    List.of("hide_arm", "suppress_bob", "hide_chat", "hide_scoreboard",
                            "hide_action_bar", "hide_title", "hide_subtitles", "hide_hotbar",
                            "hide_crosshair", "hide_bossbar", "hide_skip_hud")),
            new FieldGroup("editor.group.playback", false,
                    List.of("interruptible", "skippable", "hold_at_end", "priority", "skip_vote_ratio"))
    );

    @Override
    protected List<FieldGroup> currentGroups() {
        return GROUPS;
    }

    @Override
    protected void buildContent() {
        if (ctx == null || ctx.script == null) return;
        EditorDefaults.fillMetaDefaults(ctx.script);

        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.script_info"), lx, cy, 0);
        cy += 16;
        cy = reflectGroupedFields(ctx.script, lx, cy, GROUPS, false);
        cy += 4;
        addSectionLabel(I18n.get("editor.field.total_duration") + ": " + fmtDuration(ctx.totalDuration), lx, cy, 0);
    }
}
