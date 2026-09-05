package com.immersivecinematics.immersive_cinematics.webui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WebUI 注册表/自动补全数据源。
 *
 * <p>第一版采用“即时查询”：前端请求时从 MC 注册表读取并过滤。
 * 数据源与旧 Java 编辑器 {@code SingleIdEditor} / {@code LocationEditor} 等保持一致，
 * 走 Mojang official mapping 的公共 API，Forge / Fabric 通用。</p>
 */
public final class WebRegistryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private static List<String> advancementIds;

    private WebRegistryService() {
    }

    /** 按 kind 查询候选值；query 为空返回全量（受 limit 限制）。 */
    public static List<String> query(String kind, String query, int limit) {
        String k = kind == null ? "" : kind.toLowerCase();
        String q = query == null ? "" : query.trim().toLowerCase();
        int max = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        List<String> all = switch (k) {
            case "item" -> rlKeys(BuiltInRegistries.ITEM.keySet());
            case "block" -> rlKeys(BuiltInRegistries.BLOCK.keySet());
            case "entity", "entity_type" -> rlKeys(BuiltInRegistries.ENTITY_TYPE.keySet());
            case "sound" -> rlKeys(BuiltInRegistries.SOUND_EVENT.keySet());
            case "target" -> targetKeys();
            case "biome" -> dynamicRegistryKeys(Registries.BIOME);
            case "dimension", "from_dimension" -> dynamicRegistryKeys(Registries.DIMENSION);
            case "structure" -> dynamicRegistryKeys(Registries.STRUCTURE);
            case "advancement" -> advancementIds();
            case "stage", "gamestage" -> List.of();
            default -> List.of();
        };

        if (all.isEmpty()) return all;

        List<String> matched = new ArrayList<>();
        for (String id : all) {
            if (q.isEmpty() || id.toLowerCase().contains(q)) {
                matched.add(id);
                if (matched.size() >= max) break;
            }
        }
        return matched;
    }

    /** 小表全量获取（biome/dimension/structure/advancement 等），由前端决定是否缓存。 */
    public static List<String> getAll(String kind) {
        return query(kind, "", 500);
    }

    // ── 内部数据源 ──────────────────────────────────────────────

    private static List<String> targetKeys() {
        List<String> list = new ArrayList<>();
        list.addAll(rlKeys(BuiltInRegistries.BLOCK.keySet()));
        list.addAll(rlKeys(BuiltInRegistries.ENTITY_TYPE.keySet()));
        Collections.sort(list);
        return list;
    }

    private static List<String> rlKeys(Set<ResourceLocation> keys) {
        return keys.stream()
                .map(ResourceLocation::toString)
                .sorted()
                .collect(Collectors.toList());
    }

    private static List<String> dynamicRegistryKeys(ResourceKey<? extends Registry<?>> registryKey) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return List.of();
            var reg = mc.level.registryAccess().registry(registryKey).orElse(null);
            if (reg == null) return List.of();
            return reg.keySet().stream()
                    .map(ResourceLocation::toString)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> advancementIds() {
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
}
