package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIAutoCompleteInput;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIDropdown;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIFloatInput;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

public class ObservationEditor extends TriggerEditor {
    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UIAutoCompleteInput target = new UIAutoCompleteInput(x, y, w, 16, I18n.get("editor.field.target"),
            () -> conditions.has("target") ? conditions.get("target").getAsString() : "",
            v -> { conditions.addProperty("target", v); onDirty.run(); },
            SingleIdEditor.getCandidates("target"));
        widgets.add(target);
        y += 18;

        List<String> rawTypes = List.of("", "block", "entity");
        List<String> displayTypes = List.of(I18n.get("editor.enum.tristate.null"),
                I18n.get("editor.enum.target_type.block"), I18n.get("editor.enum.target_type.entity"));
        UIDropdown dd = new UIDropdown(x, y, w, 16, displayTypes,
            () -> {
                if (!conditions.has("target_type")) return 0;
                String t = conditions.get("target_type").getAsString();
                return "block".equals(t) ? 1 : "entity".equals(t) ? 2 : 0;
            },
            i -> {
                if (i == 0) conditions.remove("target_type");
                else conditions.addProperty("target_type", rawTypes.get(i));
                onDirty.run();
            }).setLabel(I18n.get("editor.field.target_type") + ":");
        widgets.add(dd);
        y += 18;

        UIFloatInput reach = new UIFloatInput(x, y, w, 16, I18n.get("editor.field.reach"),
            () -> conditions.has("reach") ? conditions.get("reach").getAsFloat() : 4.5f,
            1f, 64f, 0.5f,
            v -> { conditions.addProperty("reach", v); onDirty.run(); });
        widgets.add(reach);
        return y + 18;
    }
}
