package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.resources.language.I18n;
public class InventoryEditor extends TriggerEditor {
    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UITextInput ti = new UITextInput(x, y, w, 16, I18n.get("editor.field.items"),
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

        List<String> rawModes = List.of("and", "or");
        List<String> displayModes = rawModes.stream().map(m -> I18n.get("editor.trigger.mode." + m)).collect(Collectors.toList());
        UIDropdown md = new UIDropdown(x, y, w, 16, displayModes,
            () -> conditions.has("mode") ? ("and".equals(conditions.get("mode").getAsString()) ? 0 : 1) : 0,
            i -> { conditions.addProperty("mode", rawModes.get(i)); onDirty.run(); });
        widgets.add(md);
        y += 18;

        List<String> rawChanges = List.of("none", "increase", "decrease");
        List<String> displayChanges = rawChanges.stream().map(c -> I18n.get("editor.trigger.change." + c)).collect(Collectors.toList());
        UIDropdown cd = new UIDropdown(x, y, w, 16, displayChanges,
            () -> {
                if (!conditions.has("change")) return 0;
                String c = conditions.get("change").getAsString();
                return "increase".equals(c) ? 1 : "decrease".equals(c) ? 2 : 0;
            },
            i -> {
                if (i == 0) conditions.remove("change");
                else conditions.addProperty("change", rawChanges.get(i));
                onDirty.run();
            });
        widgets.add(cd);
        return y + 18;
    }
}
