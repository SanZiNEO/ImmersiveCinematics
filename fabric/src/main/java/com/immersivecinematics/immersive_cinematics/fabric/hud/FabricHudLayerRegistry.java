package com.immersivecinematics.immersive_cinematics.fabric.hud;

import com.immersivecinematics.immersive_cinematics.api.HudLayerRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric HUD 层注册表：本平台自有层的分类表，供 {@link FabricHudVisibility#isHidden} 查询。
 *
 * <p>第三方注册请用 {@link HudLayerRegistry}（对外稳定面）。Fabric 没有 Forge 的 overlay 注册表，
 * 也没有 hard 模式：注册只影响普通层过滤（{@code FabricHudVisibility.isHidden} 与固定分类），
 * 分类本身仅作记录，不参与任何整帧接管。</p>
 */
public final class FabricHudLayerRegistry {

    private static final Map<String, String> LAYER_CATEGORIES = new ConcurrentHashMap<>();

    private FabricHudLayerRegistry() {}

    /**
     * 内部实现；第三方请用 {@link HudLayerRegistry}。
     * 本表只放 Fabric 侧自有层，第三方注册一律落门面表。
     */
    public static void register(String layerId, String category) {
        LAYER_CATEGORIES.put(layerId, category);
    }

    /**
     * 查询分类：本类自有表 → {@link HudLayerRegistry}（第三方，按层 ID 解析成资源 ID 后查）→ 原始层 ID。
     *
     * <p>中间一档是跨加载器契约：第三方用门面注册时给的是 {@link ResourceLocation}，Fabric 侧只有字符串，
     * 因此这里按资源 ID 语法解析；不是合法资源 ID 的层名（纯字符串）直接落到最后一档。</p>
     */
    public static String categoryOf(String layerId) {
        String category = LAYER_CATEGORIES.get(layerId);
        if (category != null) return category;
        ResourceLocation overlayId = ResourceLocation.tryParse(layerId);
        if (overlayId != null) {
            String external = HudLayerRegistry.categoryOf(overlayId);
            if (external != null) return external;
        }
        return layerId;
    }
}
