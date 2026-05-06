package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.List;

public class EntityKillEditor extends TriggerEditor {
    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UITextInput ti = new UITextInput(x, y, w, 16, "entity",
            () -> {
                if (!conditions.has("entity")) return "";
                JsonElement e = conditions.get("entity");
                if (e.isJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonElement je : e.getAsJsonArray()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(je.getAsString());
                    }
                    return sb.toString();
                }
                return e.getAsString();
            },
            v -> {
                if (v.contains(",")) {
                    JsonArray arr = new JsonArray();
                    for (String s : v.split(",")) arr.add(s.trim());
                    conditions.add("entity", arr);
                } else {
                    conditions.addProperty("entity", v);
                }
                onDirty.run();
            });
        widgets.add(ti);
        y += 18;

        List<String> modes = List.of("or", "and");
        UIDropdown dd = new UIDropdown(x, y, w, 16, modes,
            () -> conditions.has("mode") ? ("or".equals(conditions.get("mode").getAsString()) ? 0 : 1) : 0,
            i -> { conditions.addProperty("mode", modes.get(i)); onDirty.run(); });
        widgets.add(dd);
        return y + 18;
    }
}
