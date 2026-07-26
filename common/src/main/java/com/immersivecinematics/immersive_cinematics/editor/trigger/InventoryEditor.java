package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.List;

public class InventoryEditor extends TriggerEditor {
    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UITextInput ti = new UITextInput(x, y, w, 16, "items",
            () -> {
                if (!conditions.has("items")) return "";
                JsonArray arr = conditions.getAsJsonArray("items");
                StringBuilder sb = new StringBuilder();
                for (JsonElement je : arr) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(je.getAsString());
                }
                return sb.toString();
            },
            v -> {
                JsonArray arr = new JsonArray();
                for (String s : v.split(",")) arr.add(s.trim());
                conditions.add("items", arr);
                onDirty.run();
            });
        widgets.add(ti);
        y += 18;

        List<String> modes = List.of("and", "or");
        UIDropdown md = new UIDropdown(x, y, w, 16, modes,
            () -> conditions.has("mode") ? ("and".equals(conditions.get("mode").getAsString()) ? 0 : 1) : 0,
            i -> { conditions.addProperty("mode", modes.get(i)); onDirty.run(); });
        widgets.add(md);
        y += 18;

        List<String> changes = List.of("none", "increase", "decrease");
        UIDropdown cd = new UIDropdown(x, y, w, 16, changes,
            () -> {
                if (!conditions.has("change")) return 0;
                String c = conditions.get("change").getAsString();
                return "increase".equals(c) ? 1 : "decrease".equals(c) ? 2 : 0;
            },
            i -> {
                if (i == 0) conditions.remove("change");
                else conditions.addProperty("change", changes.get(i));
                onDirty.run();
            });
        widgets.add(cd);
        return y + 18;
    }
}
