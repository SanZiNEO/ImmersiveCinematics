package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.immersivecinematics.immersive_cinematics.editor.EditorDefaults;
import com.immersivecinematics.immersive_cinematics.editor.Scale;
import com.immersivecinematics.immersive_cinematics.script.SchemaLoader;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import net.minecraft.client.resources.language.I18n;

import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Clip 属性面板（0.3.5 第5轮 5A）。
 */
public class ClipPropertiesPanel extends EditorPanel {

    @Override
    protected void buildContent() {
        if (ctx == null || ctx.selectedClip == null) return;
        EditorDefaults.fillClipDefaults(ctx.selectedClip, selectedTrackType());

        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.clip_properties"), lx, cy, 0);
        cy += (int)(16 * Scale.sy);

        String trackType = selectedTrackType();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("start_time");
        keys.add("duration");
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getClipFields(TrackType.valueOf(trackType.toUpperCase())).entrySet()) {
            if (!"start_time".equals(e.getKey()) && !"duration".equals(e.getKey()) && !"keyframes".equals(e.getKey())) {
                keys.add(e.getKey());
            }
        }
        if (ctx.selectedClip.has("cam_breath_type")) {
            if (!"trauma".equals(ctx.selectedClip.get("cam_breath_type").getAsString())) {
                keys.remove("cam_breath_trauma");
                keys.remove("cam_breath_decay");
            }
        }
        reflectObject(ctx.selectedClip, lx, cy, keys.toArray(new String[0]), false);
    }
}
