package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.immersivecinematics.immersive_cinematics.editor.EditorDefaults;
import com.immersivecinematics.immersive_cinematics.editor.fields.FieldGroup;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * Clip 属性面板（0.3.5 第5轮 5A + 字段全覆盖/折叠分组）。
 */
public class ClipPropertiesPanel extends EditorPanel {

    private static final List<FieldGroup> CAMERA_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("start_time", "duration", "transition", "interpolation", "loop")),
            new FieldGroup("editor.group.loop", false,
                    List.of("loop_count", "loop_mode")),
            new FieldGroup("editor.group.path", false,
                    List.of("curve", "dimension", "transition_duration")),
            new FieldGroup("editor.group.breath", false,
                    List.of("cam_breath_enabled", "cam_breath_intensity", "cam_breath_seed",
                            "cam_breath_type", "cam_breath_speed", "cam_breath_trauma", "cam_breath_decay"))
    );

    private static final List<FieldGroup> AUDIO_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("start_time", "duration", "sound", "source", "volume", "pitch", "loop")),
            new FieldGroup("editor.group.fade", false,
                    List.of("fade_in", "fade_out")),
            new FieldGroup("editor.group.space", false,
                    List.of("attenuation", "position_mode", "category"))
    );

    private static final List<FieldGroup> EVENT_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("start_time", "duration", "event_type"))
    );

    private static final List<FieldGroup> MOD_EVENT_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("start_time", "duration", "event_type")),
            new FieldGroup("editor.group.data", false,
                    List.of("data"))
    );

    private static final List<FieldGroup> OVERLAY_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("start_time", "duration", "layer_type", "interpolation")),
            new FieldGroup("editor.group.visual", false,
                    List.of("color", "path", "text", "z_index"))
    );

    private static final List<FieldGroup> EMPTY_GROUPS = List.of();

    @Override
    protected List<FieldGroup> currentGroups() {
        return groupsForTrack(selectedTrackType());
    }

    private static List<FieldGroup> groupsForTrack(String trackType) {
        if (trackType == null) return CAMERA_GROUPS;
        switch (trackType.toUpperCase()) {
            case "CAMERA": return CAMERA_GROUPS;
            case "AUDIO": return AUDIO_GROUPS;
            case "EVENT": return EVENT_GROUPS;
            case "MOD_EVENT": return MOD_EVENT_GROUPS;
            case "OVERLAY": return OVERLAY_GROUPS;
            default: return EMPTY_GROUPS;
        }
    }

    @Override
    protected void buildContent() {
        if (ctx == null || ctx.selectedClip == null) return;
        EditorDefaults.fillClipDefaults(ctx.selectedClip, selectedTrackType());

        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.clip_properties"), lx, cy, 0);
        cy += 16;

        List<FieldGroup> groups = groupsForTrack(selectedTrackType());
        cy = reflectGroupedFields(ctx.selectedClip, lx, cy, groups, false);
    }
}
