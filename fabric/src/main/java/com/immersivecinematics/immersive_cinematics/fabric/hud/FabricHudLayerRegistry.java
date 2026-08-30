package com.immersivecinematics.immersive_cinematics.fabric.hud;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric HUD 层注册表：模组把自己的 HUD 层 ID 注册到某个白名单分类，
 * 脚本通过 {@code hide_hud} / {@code hud_layers} 控制该分类。
 */
public final class FabricHudLayerRegistry {

    private static final Map<String, String> LAYER_CATEGORIES = new ConcurrentHashMap<>();

    private FabricHudLayerRegistry() {}

    /** 注册一个 HUD 层 ID 到指定分类 */
    public static void register(String layerId, String category) {
        LAYER_CATEGORIES.put(layerId, category);
    }

    /** 查询分类；未注册时返回 layerId 本身，作为动态层 ID */
    public static String categoryOf(String layerId) {
        String category = LAYER_CATEGORIES.get(layerId);
        return category != null ? category : layerId;
    }
}
