package com.immersivecinematics.immersive_cinematics.forge.hud;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Forge HUD 层注册表：第三方模组可把自己的 {@link ResourceLocation} overlay 注册到某个白名单分类，
 * 脚本通过 {@code hide_hud} / {@code hud_layers} 控制该分类。
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

    /** 注册一个 overlay 到指定分类（分类名可配合脚本 hide_* 或 hud_layers 使用） */
    public static void register(ResourceLocation overlayId, String category) {
        LAYER_CATEGORIES.put(overlayId, category);
    }

    /** 查询 overlay 的分类；未注册时返回 overlay 自身的资源 ID 字符串（作为动态层 ID） */
    public static String categoryOf(ResourceLocation overlayId) {
        String category = LAYER_CATEGORIES.get(overlayId);
        return category != null ? category : overlayId.toString();
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
