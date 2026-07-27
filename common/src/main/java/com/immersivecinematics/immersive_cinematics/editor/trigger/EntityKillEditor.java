package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;

public class EntityKillEditor extends TriggerEditor {
    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UIAutoCompleteInput ti = new UIAutoCompleteInput(x, y, w, 16, I18n.get("editor.field.entity"),
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
            },
            getEntityCandidates());
        widgets.add(ti);
        y += 18;

        List<String> rawModes = List.of("or", "and");
        List<String> displayModes = rawModes.stream().map(m -> I18n.get("editor.trigger.mode." + m)).collect(Collectors.toList());
        UIDropdown dd = new UIDropdown(x, y, w, 16, displayModes,
            () -> conditions.has("mode") ? ("or".equals(conditions.get("mode").getAsString()) ? 0 : 1) : 0,
            i -> { conditions.addProperty("mode", rawModes.get(i)); onDirty.run(); });
        widgets.add(dd);
        return y + 18;
    }

    private static List<String> getEntityCandidates() {
        List<String> list = new ArrayList<>();
        for (var key : BuiltInRegistries.ENTITY_TYPE.keySet())
            list.add(key.toString());
        java.util.Collections.sort(list);
        return list;
    }
}
