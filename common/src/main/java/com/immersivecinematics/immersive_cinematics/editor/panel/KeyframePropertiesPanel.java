package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorDefaults;
import com.immersivecinematics.immersive_cinematics.editor.Scale;
import com.immersivecinematics.immersive_cinematics.script.SchemaLoader;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import net.minecraft.client.resources.language.I18n;

import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 关键帧属性面板（0.3.5 第5轮 5A）。
 */
public class KeyframePropertiesPanel extends EditorPanel {

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
        cy += (int)(16 * Scale.sy);

        String kfTrackType = selectedTrackType();
        LinkedHashSet<String> kfKeys = new LinkedHashSet<>();
        kfKeys.add("time");
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getKeyframeFields(TrackType.valueOf(kfTrackType.toUpperCase())).entrySet()) {
            if (!"time".equals(e.getKey())) kfKeys.add(e.getKey());
        }

        if ("CAMERA".equals(kfTrackType)) {
            String lookAt = ctx.selectedKeyframe.has("look_at")
                    ? ctx.selectedKeyframe.get("look_at").getAsString() : "none";
            String follow = ctx.selectedKeyframe.has("follow")
                    ? ctx.selectedKeyframe.get("follow").getAsString() : "none";
            if (!"entity".equals(lookAt)) kfKeys.remove("look_at_selector");
            if (!"coordinate".equals(lookAt)) {
                kfKeys.remove("look_at_target_x");
                kfKeys.remove("look_at_target_y");
                kfKeys.remove("look_at_target_z");
                kfKeys.remove("look_at_target_structure");
            } else {
                String structureId = ctx.selectedKeyframe.has("look_at_target_structure")
                        ? ctx.selectedKeyframe.get("look_at_target_structure").getAsString() : "";
                if (!structureId.isEmpty()) {
                    kfKeys.remove("look_at_target_x");
                    kfKeys.remove("look_at_target_y");
                    kfKeys.remove("look_at_target_z");
                }
            }
            if (!"none".equals(lookAt)) {
                kfKeys.remove("yaw");
                kfKeys.remove("pitch");
            }
            if (!"entity".equals(follow)) kfKeys.remove("follow_selector");
        }

        reflectObject(ctx.selectedKeyframe, lx, cy, kfKeys.toArray(new String[0]), true);
    }
}
