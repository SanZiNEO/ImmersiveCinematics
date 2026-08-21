package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorDefaults;
import com.immersivecinematics.immersive_cinematics.editor.fields.FieldGroup;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 关键帧属性面板（0.3.5 第5轮 5A + 字段全覆盖/折叠分组）。
 */
public class KeyframePropertiesPanel extends EditorPanel {

    private static final List<FieldGroup> CAMERA_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("time", "position", "position_mode")),
            new FieldGroup("editor.group.orientation", false,
                    List.of("yaw", "pitch", "roll", "yaw_base", "pitch_base",
                            "yaw_base_selector", "yaw_base_from", "yaw_base_to",
                            "orient", "yaw_offset", "pitch_offset")),
            new FieldGroup("editor.group.lookat", false,
                    List.of("look_at", "look_at_selector", "look_at_target_x",
                            "look_at_target_y", "look_at_target_z", "look_at_target_structure", "look_at_target")),
            new FieldGroup("editor.group.follow", false,
                    List.of("follow", "follow_selector")),
            new FieldGroup("editor.group.optics", false,
                    List.of("fov", "zoom"))
    );

    private static final List<FieldGroup> AUDIO_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("time", "volume")),
            new FieldGroup("editor.group.space", false,
                    List.of("x", "y", "z"))
    );

    private static final List<FieldGroup> EVENT_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("time", "event_type", "command", "position"))
    );

    private static final List<FieldGroup> MOD_EVENT_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("time", "event_type")),
            new FieldGroup("editor.group.data", false,
                    List.of("data"))
    );

    private static final List<FieldGroup> OVERLAY_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("time", "opacity")),
            new FieldGroup("editor.group.visual", false,
                    List.of("x", "y", "font_scale", "scale_x", "scale_y"))
    );

    private static final List<FieldGroup> LETTERBOX_GROUPS = List.of(
            new FieldGroup("editor.group.basic", true,
                    List.of("time", "aspect_ratio"))
    );

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
            case "LETTERBOX": return LETTERBOX_GROUPS;
            default: return CAMERA_GROUPS;
        }
    }

    @Override
    protected void buildContent() {
        if (ctx == null || ctx.selectedKeyframe == null) return;
        EditorDefaults.fillKeyframeDefaults(ctx.selectedKeyframe, selectedTrackType());

        if (ctx.selectedKeyframe.has("position")) {
            String mode = ctx.selectedKeyframe.has("position_mode")
                    ? ctx.selectedKeyframe.get("position_mode").getAsString() : "relative";
            JsonObject pos = ctx.selectedKeyframe.getAsJsonObject("position");
            if ("absolute".equals(mode) && pos.has("dx") && !pos.has("x")) {
                pos.addProperty("x", pos.get("dx").getAsFloat());
                pos.addProperty("y", pos.get("dy").getAsFloat());
                pos.addProperty("z", pos.get("dz").getAsFloat());
                pos.remove("dx"); pos.remove("dy"); pos.remove("dz");
            } else if ("relative".equals(mode) && pos.has("x") && !pos.has("dx")) {
                pos.addProperty("dx", pos.get("x").getAsFloat());
                pos.addProperty("dy", pos.get("y").getAsFloat());
                pos.addProperty("dz", pos.get("z").getAsFloat());
                pos.remove("x"); pos.remove("y"); pos.remove("z");
            }
        }

        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.keyframe_properties"), lx, cy, 0);
        cy += 16;

        List<FieldGroup> groups = groupsForTrack(selectedTrackType());
        cy = reflectGroupedFields(ctx.selectedKeyframe, lx, cy, groups, true);
    }
}
