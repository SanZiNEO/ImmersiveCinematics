package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIFloatInput;
import com.immersivecinematics.immersive_cinematics.editor.widget.UITextInput;
import java.util.List;

public class StructureEditor extends TriggerEditor {
    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UITextInput ti = new UITextInput(x, y, w, 16, "structure",
            () -> conditions.has("structure") ? conditions.get("structure").getAsString() : "",
            v -> { conditions.addProperty("structure", v); onDirty.run(); });
        widgets.add(ti);
        y += 18;

        UIFloatInput ri = new UIFloatInput(x, y, w, 16, "radius",
            () -> conditions.has("radius") ? conditions.get("radius").getAsFloat() : 0,
            0, 9999, 1,
            v -> { conditions.addProperty("radius", v); onDirty.run(); });
        widgets.add(ri);
        return y + 18;
    }
}
