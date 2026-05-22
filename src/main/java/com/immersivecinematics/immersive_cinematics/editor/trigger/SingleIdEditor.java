package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIAutoCompleteInput;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

public class SingleIdEditor extends TriggerEditor {
    private final String fieldKey;

    public SingleIdEditor(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    @Override
    public int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty) {
        List<String> candidates = getCandidates(fieldKey);
        UIAutoCompleteInput ti = new UIAutoCompleteInput(x, y, w, 16, fieldKey,
            () -> conditions.has(fieldKey) ? conditions.get(fieldKey).getAsString() : "",
            v -> { conditions.addProperty(fieldKey, v); onDirty.run(); },
            candidates);
        widgets.add(ti);
        return y + 18;
    }

    private static List<String> getCandidates(String field) {
        return switch (field) {
            case "item" -> rlKeys(BuiltInRegistries.ITEM.keySet());
            case "target" -> {
                List<String> list = new ArrayList<>();
                list.addAll(rlKeys(BuiltInRegistries.BLOCK.keySet()));
                list.addAll(rlKeys(BuiltInRegistries.ENTITY_TYPE.keySet()));
                yield list;
            }
            case "advancement" -> getAdvancementIds();
            default -> {
                if ("biome".equals(field)) yield dynamicRegistryKeys(Registries.BIOME);
                if ("dimension".equals(field)) yield dynamicRegistryKeys(Registries.DIMENSION);
                yield List.of();
            }
        };
    }

    private static List<String> advancementIds;
    private static List<String> getAdvancementIds() {
        if (advancementIds != null) return advancementIds;
        try {
            var conn = Minecraft.getInstance().getConnection();
            if (conn == null) return List.of();
            var adv = conn.getAdvancements();
            var field = adv.getClass().getDeclaredField("advancements");
            field.setAccessible(true);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) field.get(adv);
            advancementIds = map.keySet().stream()
                    .map(Object::toString)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            advancementIds = List.of();
        }
        return advancementIds;
    }

    private static List<String> rlKeys(java.util.Set<net.minecraft.resources.ResourceLocation> keySet) {
        return keySet.stream().map(net.minecraft.resources.ResourceLocation::toString).sorted().collect(Collectors.toList());
    }

    private static List<String> dynamicRegistryKeys(net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>> registryKey) {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return List.of();
            var reg = level.registryAccess().registry(registryKey).orElse(null);
            if (reg == null) return List.of();
            return reg.keySet().stream()
                    .map(net.minecraft.resources.ResourceLocation::toString)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }
}
