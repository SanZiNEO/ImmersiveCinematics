package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIAutoCompleteInput;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIFloatInput;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;

public class StructureEditor extends TriggerEditor {
    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        UIAutoCompleteInput ti = new UIAutoCompleteInput(x, y, w, 16, "structure",
            () -> conditions.has("structure") ? conditions.get("structure").getAsString() : "",
            v -> { conditions.addProperty("structure", v); onDirty.run(); },
            getStructureCandidates());
        widgets.add(ti);
        y += 18;

        UIFloatInput ri = new UIFloatInput(x, y, w, 16, "radius",
            () -> conditions.has("radius") ? conditions.get("radius").getAsFloat() : 0,
            0, 9999, 1,
            v -> { conditions.addProperty("radius", v); onDirty.run(); });
        widgets.add(ri);
        return y + 18;
    }

    private static List<String> getStructureCandidates() {
        try {
            var level = Minecraft.getInstance().level;
            if (level != null) {
                var reg = level.registryAccess().registry(Registries.STRUCTURE).orElse(null);
                if (reg != null) {
                    return reg.keySet().stream()
                            .map(net.minecraft.resources.ResourceLocation::toString)
                            .sorted()
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception ignored) {}
        return List.of("minecraft:village_plains", "minecraft:fortress",
                "minecraft:stronghold", "minecraft:mineshaft",
                "minecraft:ancient_city");
    }
}
