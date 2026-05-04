package com.immersivecinematics.immersive_cinematics.trigger.server.evaluator;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
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
        if (c.has("corner1") && c.has("corner2")) {
            JsonObject c1 = c.getAsJsonObject("corner1");
            JsonObject c2 = c.getAsJsonObject("corner2");
            double minX = Math.min(c1.get("x").getAsDouble(), c2.get("x").getAsDouble());
            double maxX = Math.max(c1.get("x").getAsDouble(), c2.get("x").getAsDouble());
            double minY = Math.min(c1.get("y").getAsDouble(), c2.get("y").getAsDouble());
            double maxY = Math.max(c1.get("y").getAsDouble(), c2.get("y").getAsDouble());
            double minZ = Math.min(c1.get("z").getAsDouble(), c2.get("z").getAsDouble());
            double maxZ = Math.max(c1.get("z").getAsDouble(), c2.get("z").getAsDouble());
            double px = player.getX(), py = player.getY(), pz = player.getZ();
            if (px >= minX && px <= maxX && py >= minY && py <= maxY && pz >= minZ && pz <= maxZ) {
                return true;
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
        return c.has("dimension");
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
        String pattern = c.get("biome").getAsString();
        Holder<Biome> biome = player.level().getBiome(player.blockPosition());
        return biome.unwrapKey()
                .map(key -> matchesId(key.location().toString(), pattern))
                .orElse(false);
    }

    public static boolean evaluateEntityKill(ServerPlayer player, JsonObject c) {
        if (!c.has("entity")) return false;
        var entity = c.get("entity");

        if (!entity.isJsonArray()) {
            String killedType = KillTracker.getLastKill(player);
            if (killedType == null) return false;
            return matchesId(killedType, entity.getAsString());
        }

        String mode = c.has("mode") ? c.get("mode").getAsString() : "or";

        if ("and".equals(mode)) {
            Set<String> allKills = KillTracker.getAllKills(player);
            if (allKills.isEmpty()) return false;
            for (var elem : entity.getAsJsonArray()) {
                String pattern = elem.getAsString();
                boolean matched = false;
                for (String k : allKills) {
                    if (matchesId(k, pattern)) { matched = true; break; }
                }
                if (!matched) return false;
            }
            return true;
        }

        String killedType = KillTracker.getLastKill(player);
        if (killedType == null) return false;
        for (var elem : entity.getAsJsonArray()) {
            if (matchesId(killedType, elem.getAsString())) return true;
        }
        return false;
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
        return matchesId(
                player.level().dimension().location().toString(),
                c.get("dimension").getAsString());
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

    public static boolean evaluateItemUse(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastUsed = UseItemTracker.getLastUsed(player);
        if (lastUsed == null) return false;
        return matchesId(lastUsed, c.get("item").getAsString());
    }

    public static boolean evaluateInventory(ServerPlayer player, JsonObject c) {
        if (!c.has("items") || !c.get("items").isJsonArray()) return false;
        var items = c.getAsJsonArray("items");
        if (items.size() == 0) return false;

        java.util.Set<String> patterns = new java.util.HashSet<>();
        for (var elem : items) {
            patterns.add(elem.getAsString());
        }

        if (c.has("change")) {
            String change = c.get("change").getAsString();
            Map<String, Integer> snapshot = InventoryTracker.getSnapshot(player);
            Map<String, Integer> current = scanInventoryCounts(player);
            InventoryTracker.setSnapshot(player, current);

            if ("increase".equals(change)) {
                for (String p : patterns) {
                    int prev = snapshot.getOrDefault(p, 0);
                    int now = current.getOrDefault(p, 0);
                    if (now > prev) return true;
                }
            } else if ("decrease".equals(change)) {
                for (String p : patterns) {
                    int prev = snapshot.getOrDefault(p, 0);
                    int now = current.getOrDefault(p, 0);
                    if (now < prev) return true;
                }
            }
            return false;
        }

        String mode = c.has("mode") ? c.get("mode").getAsString() : "and";
        var inventory = player.getInventory();
        int size = inventory.getContainerSize();

        if ("or".equals(mode)) {
            for (int i = 0; i < size; i++) {
                var stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    for (String p : patterns) {
                        if (matchesId(id, p)) return true;
                    }
                }
            }
            return false;
        }

        java.util.Set<String> remaining = new java.util.HashSet<>(patterns);
        for (int i = 0; i < size; i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                remaining.removeIf(pattern -> matchesId(id, pattern));
                if (remaining.isEmpty()) return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> scanInventoryCounts(ServerPlayer player) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    public static boolean evaluateCustom(ServerPlayer player, JsonObject c) {
        if (!c.has("event_id")) return false;
        return CustomEventTracker.hasFired(player, c.get("event_id").getAsString());
    }

    public static boolean evaluateCommand(ServerPlayer player, JsonObject c) {
        return false;
    }

    public static boolean evaluateStructure(ServerPlayer player, JsonObject c) {
        if (!c.has("structure")) return false;
        String pattern = c.get("structure").getAsString();
        var level = player.serverLevel();
        var structureRegistry = level.registryAccess()
                .registry(net.minecraft.core.registries.Registries.STRUCTURE).orElse(null);
        if (structureRegistry == null) return false;

        int radius = c.has("radius") ? c.get("radius").getAsInt() : 0;
        BlockPos center = player.blockPosition();

        if (radius > 0) {
            for (int dx = -radius; dx <= radius; dx += 8) {
                for (int dz = -radius; dz <= radius; dz += 8) {
                    var structures = level.structureManager().getAllStructuresAt(center.offset(dx, 0, dz));
                    for (var structure : structures.keySet()) {
                        ResourceLocation id = structureRegistry.getKey(structure);
                        if (id != null && matchesId(id.toString(), pattern)) return true;
                    }
                }
            }
        } else {
            var structures = level.structureManager().getAllStructuresAt(center);
            for (var structure : structures.keySet()) {
                ResourceLocation id = structureRegistry.getKey(structure);
                if (id != null && matchesId(id.toString(), pattern)) return true;
            }
        }
        return false;
    }

    public static boolean evaluateGamestage(ServerPlayer player, JsonObject c) {
        if (!c.has("stage")) return false;
        String stage = c.get("stage").getAsString();
        try {
            Class<?> helper = Class.forName("net.darkhax.gamestages.GameStageHelper");
            var hasStage = helper.getMethod("hasStage", net.minecraft.world.entity.player.Player.class, String.class);
            return (boolean) hasStage.invoke(null, player, stage);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean matchesId(String actual, String pattern) {
        if (pattern.equals("*") || pattern.equals(actual)) return true;
        if (pattern.endsWith(":*")) {
            return actual.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        if (!pattern.contains(":")) {
            return actual.contains(pattern);
        }
        return false;
    }

    public static class KillTracker {
        private static final Map<UUID, String> lastKills = new java.util.HashMap<>();
        private static final Map<UUID, Set<String>> allKills = new java.util.HashMap<>();
        public static void record(ServerPlayer player, EntityType<?> type) {
            UUID uuid = player.getUUID();
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            lastKills.put(uuid, id);
            allKills.computeIfAbsent(uuid, k -> new HashSet<>()).add(id);
        }
        public static String getLastKill(ServerPlayer player) {
            return lastKills.get(player.getUUID());
        }
        public static Set<String> getAllKills(ServerPlayer player) {
            return allKills.getOrDefault(player.getUUID(), java.util.Collections.emptySet());
        }
        public static void clear(UUID uuid) {
            lastKills.remove(uuid);
            allKills.remove(uuid);
        }
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

    public static class UseItemTracker {
        private static final Map<UUID, String> lastUsed = new java.util.HashMap<>();
        public static void record(ServerPlayer player, ItemStack stack) {
            lastUsed.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static String getLastUsed(ServerPlayer player) {
            return lastUsed.get(player.getUUID());
        }
        public static void clear(UUID uuid) { lastUsed.remove(uuid); }
    }

    public static class InventoryTracker {
        private static final Map<UUID, Map<String, Integer>> snapshots = new java.util.HashMap<>();
        public static Map<String, Integer> getSnapshot(ServerPlayer player) {
            return snapshots.getOrDefault(player.getUUID(), java.util.Collections.emptyMap());
        }
        public static void setSnapshot(ServerPlayer player, Map<String, Integer> snapshot) {
            snapshots.put(player.getUUID(), snapshot);
        }
        public static void clear(UUID uuid) { snapshots.remove(uuid); }
    }
}
