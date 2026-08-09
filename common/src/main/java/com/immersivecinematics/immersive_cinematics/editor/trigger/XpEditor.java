package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIFloatInput;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

public class XpEditor extends TriggerEditor {
    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UIFloatInput level = new UIFloatInput(x, y, w, 16, I18n.get("editor.field.level"),
            () -> conditions.has("level") ? conditions.get("level").getAsFloat() : 0f,
            0f, 10000f, 1f,
            v -> { conditions.addProperty("level", v.intValue()); onDirty.run(); });
        widgets.add(level);
        y += 18;

        UIFloatInput total = new UIFloatInput(x, y, w, 16, I18n.get("editor.field.total"),
            () -> conditions.has("total") ? conditions.get("total").getAsFloat() : 0f,
            0f, 20000000f, 1f,
            v -> { conditions.addProperty("total", v.intValue()); onDirty.run(); });
        widgets.add(total);
        return y + 18;
    }
}
