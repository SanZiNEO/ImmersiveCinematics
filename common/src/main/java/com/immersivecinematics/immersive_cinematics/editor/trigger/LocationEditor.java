package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;

public class LocationEditor extends TriggerEditor {
    private static final List<String> SUB_MODES = List.of("point+radius", "box");

    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UIAutoCompleteInput dim = new UIAutoCompleteInput(x, y, w, 16, "dimension",
            () -> conditions.has("dimension") ? conditions.get("dimension").getAsString() : "",
            v -> { conditions.addProperty("dimension", v); onDirty.run(); },
            getDimensionCandidates());
        widgets.add(dim);
        y += 18;

        boolean isBox = conditions.has("corner1");
        UIDropdown mm = new UIDropdown(x, y, w, 16, SUB_MODES,
            () -> isBox ? 1 : 0,
            i -> {
                if (i == 0) {
                    conditions.remove("corner1");
                    conditions.remove("corner2");
                    if (!conditions.has("position")) {
                        JsonObject p = new JsonObject();
                        p.addProperty("x", 0); p.addProperty("y", 0); p.addProperty("z", 0);
                        conditions.add("position", p);
                    }
                    if (!conditions.has("radius")) conditions.addProperty("radius", 0);
                } else {
                    conditions.remove("position");
                    conditions.remove("radius");
                    if (!conditions.has("corner1")) {
                        JsonObject c1 = new JsonObject();
                        c1.addProperty("x", 0); c1.addProperty("y", 0); c1.addProperty("z", 0);
                        conditions.add("corner1", c1);
                    }
                    if (!conditions.has("corner2")) {
                        JsonObject c2 = new JsonObject();
                        c2.addProperty("x", 0); c2.addProperty("y", 0); c2.addProperty("z", 0);
                        conditions.add("corner2", c2);
                    }
                }
                onDirty.run();
            });
        widgets.add(mm);
        y += 18;

        if (isBox) {
            y = addPositionFields(widgets, x, y, w, "corner1", onDirty);
            y = addPositionFields(widgets, x, y, w, "corner2", onDirty);
        } else {
            y = addPositionFields(widgets, x, y, w, "position", onDirty);
            UIFloatInput rad = new UIFloatInput(x, y, w, 16, "radius",
                () -> conditions.has("radius") ? conditions.get("radius").getAsFloat() : 0,
                0, 9999, 1,
                v -> { conditions.addProperty("radius", v); onDirty.run(); });
            widgets.add(rad);
            y += 18;
        }
        return y;
    }

    private int addPositionFields(List<UIComponent> widgets, int x, int y, int w,
                                   String key, Runnable onDirty) {
        JsonObject pos = conditions.has(key) ? conditions.getAsJsonObject(key) : new JsonObject();
        if (!conditions.has(key)) conditions.add(key, pos);
        for (String axis : new String[]{"x", "y", "z"}) {
            UIFloatInput fi = new UIFloatInput(x, y, w, 16, key + "." + axis,
                () -> pos.has(axis) ? pos.get(axis).getAsFloat() : 0,
                -99999, 99999, 1,
                v -> { pos.addProperty(axis, v); onDirty.run(); });
            widgets.add(fi);
            y += 18;
        }
        return y;
    }

    private static List<String> getDimensionCandidates() {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return List.of();
            var reg = level.registryAccess().registry(Registries.DIMENSION).orElse(null);
            if (reg == null) return List.of();
            List<String> list = new ArrayList<>();
            for (var key : reg.keySet()) list.add(key.toString());
            java.util.Collections.sort(list);
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
