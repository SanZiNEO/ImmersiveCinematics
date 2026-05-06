package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UITextInput;
import java.util.List;

public class SingleIdEditor extends TriggerEditor {
    private final String fieldKey;

    public SingleIdEditor(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UITextInput ti = new UITextInput(x, y, w, 16, fieldKey,
            () -> conditions.has(fieldKey) ? conditions.get(fieldKey).getAsString() : "",
            v -> { conditions.addProperty(fieldKey, v); onDirty.run(); });
        widgets.add(ti);
        return y + 18;
    }
}
