package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.immersivecinematics.immersive_cinematics.editor.EditorDefaults;
import com.immersivecinematics.immersive_cinematics.editor.Scale;
import com.immersivecinematics.immersive_cinematics.editor.widget.UILabel;
import com.immersivecinematics.immersive_cinematics.script.SchemaLoader;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import net.minecraft.client.resources.language.I18n;

import java.util.Map;

/**
 * 脚本属性面板（0.3.5 第5轮 5A）。
 */
public class ScriptPropertiesPanel extends EditorPanel {

    @Override
    protected void buildContent() {
        if (ctx == null || ctx.script == null) return;
        EditorDefaults.fillMetaDefaults(ctx.script);

        int cy = y + 6;
        int lx = x + 6;

        int sectionGap = (int)(16 * Scale.sy);
        int smallGap = (int)(4 * Scale.sy);
        addSectionLabel(I18n.get("editor.section.script_info"), lx, cy, 0); cy += sectionGap;
        cy = buildMetaFields(lx, cy, "info");
        cy += smallGap;
        addSectionLabel(I18n.get("editor.section.runtime"), lx, cy, 0); cy += sectionGap;
        cy = buildMetaFields(lx, cy, "runtime");
        cy += 4;
        addSectionLabel(I18n.get("editor.section.duration"), lx, cy, 0); cy += (int)(16 * Scale.sy);
        addSectionLabel(I18n.get("editor.field.total_duration") + ": " + fmtDuration(ctx.totalDuration), lx, cy, 0);
    }

    private int buildMetaFields(int lx, int cy, String section) {
        for (Map.Entry<String, FieldDef> e : SchemaLoader.getMetaFields().entrySet()) {
            if (!section.equals(e.getValue().section())) continue;
            String key = e.getKey();
            if ("tristate".equals(e.getValue().type())) {
                cy = reflectTristate(key, lx, cy, 0, ctx.script);
            } else if ("int".equals(e.getValue().type()) && e.getValue().defaultValue() == null) {
                cy = reflectOptionalInt(key, lx, cy, 0, ctx.script);
            } else if (ctx.script.has(key)) {
                cy = reflectField(key, ctx.script.get(key), lx, cy, 0, ctx.script, null, false);
            }
        }
        return cy;
    }
}
