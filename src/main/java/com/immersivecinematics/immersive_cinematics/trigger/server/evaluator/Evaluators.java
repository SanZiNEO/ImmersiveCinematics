package com.immersivecinematics.immersive_cinematics.trigger.server.evaluator;

import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Evaluators {

    public static boolean evaluateLocation(ServerPlayer player, JsonObject c) {
        if (c.has("dimension")) {
            String targetDim = c.get("dimension").getAsString();
            if (!player.level().dimension().location().toString().equals(targetDim)) {
                return false;
            }
        }
        if (c.has("position")) {
            JsonObject pos = c.getAsJsonObject("position");
            double px = pos.get("x").getAsDouble();
            double py = pos.get("y").getAsDouble();
            double pz = pos.get("z").getAsDouble();
            double radius = c.has("radius") ? c.get("radius").getAsDouble() : 0.0;
            double dx = player.getX() - px;
            double dy = player.getY() - py;
            double dz = player.getZ() - pz;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
        return true;
    }

    public static boolean evaluateAdvancement(ServerPlayer player, JsonObject c) {
        if (!c.has("advancement")) return false;
        ResourceLocation advId = ResourceLocation.parse(c.get("advancement").getAsString());
        var adv = player.server.getAdvancements().getAdvancement(advId);
        if (adv == null) return false;
        return player.getAdvancements().getOrStartProgress(adv).isDone();
    }

    public static boolean evaluateBiome(ServerPlayer player, JsonObject c) {
        if (!c.has("biome")) return false;
        ResourceLocation biomeId = ResourceLocation.parse(c.get("biome").getAsString());
        Holder<Biome> biome = player.level().getBiome(player.blockPosition());
        return biome.unwrapKey()
                .map(key -> key.location().equals(biomeId))
                .orElse(false);
    }

    public static boolean evaluateEntityKill(ServerPlayer player, JsonObject c) {
        if (!c.has("entity")) return false;
        String killedType = KillTracker.getLastKill(player);
        if (killedType == null) return false;
        return matchesId(killedType, c.get("entity").getAsString());
    }

    public static boolean evaluateInteract(ServerPlayer player, JsonObject c) {
        if (!c.has("target")) return false;
        String lastInteract = InteractTracker.getLastInteraction(player);
        if (lastInteract == null) return false;
        String target = c.get("target").getAsString();
        return target.equals("*") || lastInteract.equals(target);
    }

    public static boolean evaluateDimensionChange(ServerPlayer player, JsonObject c) {
        if (!c.has("dimension")) return false;
        return player.level().dimension().location().toString().equals(c.get("dimension").getAsString());
    }

    public static boolean evaluateDimension(ServerPlayer player, JsonObject c) {
        return evaluateDimensionChange(player, c);
    }

    public static boolean evaluateLogin(ServerPlayer player, JsonObject c) {
        return true;
    }

    public static boolean evaluateItemCraft(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastCrafted = CraftTracker.getLastCrafted(player);
        if (lastCrafted == null) return false;
        return matchesId(lastCrafted, c.get("item").getAsString());
    }

    public static boolean evaluateCustom(ServerPlayer player, JsonObject c) {
        if (!c.has("event_id")) return false;
        return CustomEventTracker.hasFired(player, c.get("event_id").getAsString());
    }

    public static boolean evaluateCommand(ServerPlayer player, JsonObject c) {
        return false;
    }

    private static boolean matchesId(String actual, String pattern) {
        if (pattern.equals("*") || pattern.equals(actual)) return true;
        if (pattern.endsWith(":*")) {
            return actual.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return false;
    }

    public static class KillTracker {
        private static final Map<UUID, String> lastKills = new java.util.HashMap<>();
        public static void record(ServerPlayer player, EntityType<?> type) {
            lastKills.put(player.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        }
        public static String getLastKill(ServerPlayer player) {
            return lastKills.get(player.getUUID());
        }
        public static void clear(UUID uuid) { lastKills.remove(uuid); }
    }

    public static class InteractTracker {
        private static final Map<UUID, String> lastInteractions = new java.util.HashMap<>();
        public static void recordBlock(UUID uuid, BlockState state) {
            lastInteractions.put(uuid, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        }
        public static void recordEntity(UUID uuid, EntityType<?> type) {
            lastInteractions.put(uuid, BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        }
        public static String getLastInteraction(ServerPlayer player) {
            return lastInteractions.get(player.getUUID());
        }
        public static void clear(UUID uuid) { lastInteractions.remove(uuid); }
    }

    public static class CraftTracker {
        private static final Map<UUID, String> lastCrafted = new java.util.HashMap<>();
        public static void record(ServerPlayer player, ItemStack stack) {
            lastCrafted.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static String getLastCrafted(ServerPlayer player) {
            return lastCrafted.get(player.getUUID());
        }
        public static void clear(UUID uuid) { lastCrafted.remove(uuid); }
    }

    public static class CustomEventTracker {
        private static final Map<UUID, Set<String>> firedEvents = new java.util.HashMap<>();
        public static void fire(ServerPlayer player, String eventId) {
            firedEvents.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(eventId);
        }
        public static boolean hasFired(ServerPlayer player, String eventId) {
            Set<String> events = firedEvents.get(player.getUUID());
            return events != null && events.contains(eventId);
        }
        public static void clear(UUID uuid) { firedEvents.remove(uuid); }
    }
}
