package com.immersivecinematics.immersive_cinematics.forge.hud;

import com.immersivecinematics.immersive_cinematics.api.HudLayerRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Forge HUD 层注册表：内置原版 overlay 的分类表 + 世界效果层豁免名单，供 normal / hard 两条消费链查询。
 *
 * <p>第三方注册请用 {@link HudLayerRegistry}（对外稳定面）：本类只保留「内置表 → 门面表 → overlay ID」
 * 的解析优先级与 {@link #isWorldOverlay}，不再作为注册入口。</p>
 */
public final class ForgeHudLayerRegistry {

    private static final Map<ResourceLocation, String> LAYER_CATEGORIES = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> WORLD_OVERLAYS = new CopyOnWriteArraySet<>();

    static {
        // 原版世界效果/非 HUD overlay：总控隐藏时不拦截
        world(VanillaGuiOverlay.VIGNETTE);
        world(VanillaGuiOverlay.SPYGLASS);
        world(VanillaGuiOverlay.HELMET);
        world(VanillaGuiOverlay.FROSTBITE);
        world(VanillaGuiOverlay.PORTAL);
        world(VanillaGuiOverlay.SLEEP_FADE);
        world(VanillaGuiOverlay.POTION_ICONS);
        world(VanillaGuiOverlay.DEBUG_TEXT);
        world(VanillaGuiOverlay.FPS_GRAPH);

        // vanilla HUD 默认分类
        reg(VanillaGuiOverlay.HOTBAR, "hotbar");
        reg(VanillaGuiOverlay.CROSSHAIR, "crosshair");
        reg(VanillaGuiOverlay.BOSS_EVENT_PROGRESS, "bossbar");
        reg(VanillaGuiOverlay.PLAYER_HEALTH, "health");
        reg(VanillaGuiOverlay.ARMOR_LEVEL, "health");
        reg(VanillaGuiOverlay.FOOD_LEVEL, "health");
        reg(VanillaGuiOverlay.AIR_LEVEL, "health");
        reg(VanillaGuiOverlay.MOUNT_HEALTH, "health");
        reg(VanillaGuiOverlay.JUMP_BAR, "status");
        reg(VanillaGuiOverlay.EXPERIENCE_BAR, "status");
        reg(VanillaGuiOverlay.ITEM_NAME, "status");
        reg(VanillaGuiOverlay.RECORD_OVERLAY, "action_bar");
        reg(VanillaGuiOverlay.TITLE_TEXT, "title");
        reg(VanillaGuiOverlay.SUBTITLES, "subtitles");
        reg(VanillaGuiOverlay.SCOREBOARD, "scoreboard");
        reg(VanillaGuiOverlay.CHAT_PANEL, "chat");
        reg(VanillaGuiOverlay.PLAYER_LIST, "scoreboard");
    }

    private ForgeHudLayerRegistry() {}

    /**
     * 内部实现；第三方请用 {@link HudLayerRegistry}。
     * 原版 overlay 的内置分类由静态块直接写本类表，不经这里——本方法只是门面的转调点。
     */
    public static void register(ResourceLocation overlayId, String category) {
        HudLayerRegistry.register(overlayId, category);
    }

    /**
     * 查询 overlay 的分类：本类内置表 → {@link HudLayerRegistry}（第三方）→ overlay 自身的资源 ID。
     * 最后一档是未注册 overlay 的动态层名，脚本可直接把它写进 {@code hud_layers}。
     */
    public static String categoryOf(ResourceLocation overlayId) {
        String category = LAYER_CATEGORIES.get(overlayId);
        if (category != null) return category;
        String external = HudLayerRegistry.categoryOf(overlayId);
        return external != null ? external : overlayId.toString();
    }

    /** 是否为原版世界效果 overlay（总控隐藏时不拦截） */
    public static boolean isWorldOverlay(ResourceLocation overlayId) {
        return WORLD_OVERLAYS.contains(overlayId);
    }

    private static void reg(VanillaGuiOverlay overlay, String category) {
        LAYER_CATEGORIES.put(overlay.id(), category);
    }

    private static void world(VanillaGuiOverlay overlay) {
        WORLD_OVERLAYS.add(overlay.id());
    }
}
