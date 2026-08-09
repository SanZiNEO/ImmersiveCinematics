package com.immersivecinematics.immersive_cinematics.trigger.server.evaluator;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Evaluators {

    public static JsonObject expandConditions(JsonObject c, float buffer) {
        if (c == null || buffer <= 0f) return null;
        if (!c.has("corner1") || !c.has("corner2")) {
            if (c.has("position") && c.has("radius")) {
                JsonObject expanded = c.deepCopy();
                expanded.addProperty("radius", c.get("radius").getAsFloat() + buffer);
                return expanded;
            }
            return null;
        }
        JsonObject result = c.deepCopy();
        JsonObject c1 = result.getAsJsonObject("corner1");
        JsonObject c2 = result.getAsJsonObject("corner2");
        if (c1.has("x")) c1.addProperty("x", c1.get("x").getAsDouble() - buffer);
        if (c1.has("y")) c1.addProperty("y", c1.get("y").getAsDouble() - buffer);
        if (c1.has("z")) c1.addProperty("z", c1.get("z").getAsDouble() - buffer);
        if (c2.has("x")) c2.addProperty("x", c2.get("x").getAsDouble() + buffer);
        if (c2.has("y")) c2.addProperty("y", c2.get("y").getAsDouble() + buffer);
        if (c2.has("z")) c2.addProperty("z", c2.get("z").getAsDouble() + buffer);
        return result;
    }

    public static boolean evaluateLocation(ServerPlayer player, JsonObject c) {
        if (c.has("dimension")) {
            String targetDim = c.get("dimension").getAsString();
            if (!player.level().dimension().location().toString().equals(targetDim)) {
                return false;
            }
        }
        if (c.has("corner1") && c.has("corner2")) {
            if (inBox(player.getX(), player.getY(), player.getZ(),
                    c.getAsJsonObject("corner1"), c.getAsJsonObject("corner2"))) {
                return true;
            }
        }
        if (c.has("position")) {
            double radius = c.has("radius") ? c.get("radius").getAsDouble() : 0.0;
            return inRadius(player.getX(), player.getY(), player.getZ(),
                    c.getAsJsonObject("position"), radius);
        }
        return c.has("dimension");
    }

    public static boolean evaluateAdvancement(ServerPlayer player, JsonObject c) {
        if (!c.has("advancement")) return false;
        String last = AdvancementTracker.getLastAdvancement(player);
        return last != null && matchesId(last, c.get("advancement").getAsString());
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
            KillTracker.KillRecord rec = KillTracker.getLastKill(player);
            if (rec == null) return false;
            return matchesId(rec.entityId(), entity.getAsString()) && matchesScene(c, rec);
        }

        String mode = c.has("mode") ? c.get("mode").getAsString() : "or";

        if ("and".equals(mode)) {
            Set<KillTracker.KillRecord> allKills = KillTracker.getAllKills(player);
            if (allKills.isEmpty()) return false;
            for (var elem : entity.getAsJsonArray()) {
                String pattern = elem.getAsString();
                boolean matched = false;
                for (KillTracker.KillRecord rec : allKills) {
                    if (matchesId(rec.entityId(), pattern)) { matched = true; break; }
                }
                if (!matched) return false;
            }
            // 场景条件按最近一次击杀记录判定（击杀时刻的位置，非玩家当前位置）
            KillTracker.KillRecord last = KillTracker.getLastKill(player);
            return last != null && matchesScene(c, last);
        }

        KillTracker.KillRecord rec = KillTracker.getLastKill(player);
        if (rec == null) return false;
        for (var elem : entity.getAsJsonArray()) {
            if (matchesId(rec.entityId(), elem.getAsString()) && matchesScene(c, rec)) return true;
        }
        return false;
    }

    /**
     * entity_kill 场景条件：dimension / biome / position+radius / corner1+corner2，
     * 全部按击杀时刻的记录（KillRecord）判定，而非玩家当前位置。
     */
    private static boolean matchesScene(JsonObject c, KillTracker.KillRecord rec) {
        if (c.has("dimension") && !matchesId(rec.dimension(), c.get("dimension").getAsString())) return false;
        if (c.has("biome") && !matchesId(rec.biome(), c.get("biome").getAsString())) return false;
        if (c.has("position")) {
            double radius = c.has("radius") ? c.get("radius").getAsDouble() : 0.0;
            if (!inRadius(rec.x(), rec.y(), rec.z(), c.getAsJsonObject("position"), radius)) return false;
        }
        if (c.has("corner1") && c.has("corner2")) {
            if (!inBox(rec.x(), rec.y(), rec.z(), c.getAsJsonObject("corner1"), c.getAsJsonObject("corner2"))) return false;
        }
        return true;
    }

    private static boolean inRadius(double x, double y, double z, JsonObject pos, double radius) {
        double px = pos.get("x").getAsDouble();
        double py = pos.get("y").getAsDouble();
        double pz = pos.get("z").getAsDouble();
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private static boolean inBox(double x, double y, double z, JsonObject c1, JsonObject c2) {
        double minX = Math.min(c1.get("x").getAsDouble(), c2.get("x").getAsDouble());
        double maxX = Math.max(c1.get("x").getAsDouble(), c2.get("x").getAsDouble());
        double minY = Math.min(c1.get("y").getAsDouble(), c2.get("y").getAsDouble());
        double maxY = Math.max(c1.get("y").getAsDouble(), c2.get("y").getAsDouble());
        double minZ = Math.min(c1.get("z").getAsDouble(), c2.get("z").getAsDouble());
        double maxZ = Math.max(c1.get("z").getAsDouble(), c2.get("z").getAsDouble());
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
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

    public static boolean evaluateItemConsume(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastConsumed = UseItemTracker.getLastConsumed(player);
        if (lastConsumed == null) return false;
        return matchesId(lastConsumed, c.get("item").getAsString());
    }

    public static boolean evaluateItemRelease(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastReleased = UseItemTracker.getLastReleased(player);
        if (lastReleased == null) return false;
        return matchesId(lastReleased, c.get("item").getAsString());
    }

    public static boolean evaluateItemUseInterrupt(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastInterrupted = UseItemTracker.getLastInterrupted(player);
        if (lastInterrupted == null) return false;
        return matchesId(lastInterrupted, c.get("item").getAsString());
    }

    public static boolean evaluateItemInstantUse(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastInstantUsed = UseItemTracker.getLastInstantUsed(player);
        if (lastInstantUsed == null) return false;
        return matchesId(lastInstantUsed, c.get("item").getAsString());
    }

    public static boolean evaluateXp(ServerPlayer player, JsonObject c) {
        if (c.has("level") && player.experienceLevel < c.get("level").getAsInt()) return false;
        if (c.has("total") && player.totalExperience < c.get("total").getAsInt()) return false;
        return c.has("level") || c.has("total");
    }

    public static boolean evaluateItemPickup(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastPickedUp = PickupDropTracker.getLastPickedUp(player);
        if (lastPickedUp == null) return false;
        return matchesId(lastPickedUp, c.get("item").getAsString());
    }

    public static boolean evaluateItemDrop(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastDropped = PickupDropTracker.getLastDropped(player);
        if (lastDropped == null) return false;
        return matchesId(lastDropped, c.get("item").getAsString());
    }

    /**
     * 注视检测（轮询，服务端射线）。{@code target} 必填；{@code target_type} 缺省时
     * 方块与实体都查、命中距离近者优先；可选 {@code reach}（默认 4.5 格）。
     */
    public static boolean evaluateObservation(ServerPlayer player, JsonObject c) {
        if (!c.has("target")) return false;
        String target = c.get("target").getAsString();
        String targetType = c.has("target_type") ? c.get("target_type").getAsString() : null;
        double reach = c.has("reach") ? c.get("reach").getAsDouble() : 4.5;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(reach));
        AABB searchArea = player.getBoundingBox().expandTowards(player.getLookAngle().scale(reach)).inflate(1.0);

        if ("block".equals(targetType)) {
            BlockHitResult blockHit = player.level().clip(
                    new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (blockHit.getType() == HitResult.Type.MISS) return false;
            return matchesId(BuiltInRegistries.BLOCK.getKey(
                    player.level().getBlockState(blockHit.getBlockPos()).getBlock()).toString(), target);
        }
        if ("entity".equals(targetType)) {
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                    player.level(), player, eye, end, searchArea, e -> !e.isSpectator() && e.isPickable(), 0.3f);
            if (entityHit == null) return false;
            return matchesId(BuiltInRegistries.ENTITY_TYPE.getKey(entityHit.getEntity().getType()).toString(), target);
        }
        BlockHitResult blockHit = player.level().clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player.level(), player, eye, end, searchArea, e -> !e.isSpectator() && e.isPickable(), 0.3f);
        if (entityHit != null && (blockHit.getType() == HitResult.Type.MISS
                || entityHit.getLocation().distanceToSqr(eye) <= blockHit.getLocation().distanceToSqr(eye))) {
            return matchesId(BuiltInRegistries.ENTITY_TYPE.getKey(entityHit.getEntity().getType()).toString(), target);
        }
        if (blockHit.getType() != HitResult.Type.MISS) {
            return matchesId(BuiltInRegistries.BLOCK.getKey(
                    player.level().getBlockState(blockHit.getBlockPos()).getBlock()).toString(), target);
        }
        return false;
    }

    public static boolean evaluateBlockInteract(ServerPlayer player, JsonObject c) {
        if (!c.has("target")) return false;
        String lastInteract = InteractTracker.getLastInteraction(player);
        if (lastInteract == null) return false;
        return matchesId(lastInteract, c.get("target").getAsString());
    }

    public static boolean evaluateItemOnInteract(ServerPlayer player, JsonObject c) {
        if (!c.has("item") || !c.has("target")) return false;
        String lastItem = InteractTracker.getLastInteractionItem(player);
        if (lastItem == null) return false;
        String itemPattern = c.get("item").getAsString();
        if (!matchesId(lastItem, itemPattern)) return false;
        String lastTarget = InteractTracker.getLastInteraction(player);
        if (lastTarget == null) return false;
        String targetPattern = c.get("target").getAsString();
        if (!matchesId(lastTarget, targetPattern)) return false;
        if (c.has("target_type")
                && !c.get("target_type").getAsString().equals(InteractTracker.getLastInteractionType(player))) {
            return false;
        }
        return true;
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

            if (snapshot.isEmpty()) return false;

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
        /** 击杀记录：实体 id + 击杀时刻的维度/群系/坐标（场景条件用） */
        public record KillRecord(String entityId, String dimension, String biome, double x, double y, double z) {}

        private static final Map<UUID, KillRecord> lastKills = new java.util.HashMap<>();
        private static final Map<UUID, Set<KillRecord>> allKills = new java.util.HashMap<>();
        public static void record(ServerPlayer player, EntityType<?> type, ServerLevel level,
                                  double x, double y, double z) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            String dimension = level.dimension().location().toString();
            String biome = level.getBiome(BlockPos.containing(x, y, z)).unwrapKey()
                    .map(key -> key.location().toString()).orElse("");
            KillRecord rec = new KillRecord(id, dimension, biome, x, y, z);
            lastKills.put(player.getUUID(), rec);
            allKills.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(rec);
        }
        public static KillRecord getLastKill(ServerPlayer player) {
            return lastKills.get(player.getUUID());
        }
        public static Set<KillRecord> getAllKills(ServerPlayer player) {
            return allKills.getOrDefault(player.getUUID(), java.util.Collections.emptySet());
        }
        public static void clear(UUID uuid) {
            lastKills.remove(uuid);
            allKills.remove(uuid);
        }
    }

    public static class AdvancementTracker {
        private static final Map<UUID, String> lastAdvancements = new java.util.HashMap<>();
        public static void record(ServerPlayer player, String advancementId) {
            lastAdvancements.put(player.getUUID(), advancementId);
        }
        public static String getLastAdvancement(ServerPlayer player) {
            return lastAdvancements.get(player.getUUID());
        }
        public static void clear(UUID uuid) { lastAdvancements.remove(uuid); }
    }

    public static class InteractTracker {
        private static final Map<UUID, String> lastInteractions = new java.util.HashMap<>();
        private static final Map<UUID, String> lastInteractionItems = new java.util.HashMap<>();
        private static final Map<UUID, String> lastInteractionTypes = new java.util.HashMap<>();
        public static void recordBlock(UUID uuid, BlockState state) {
            lastInteractions.put(uuid, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            lastInteractionTypes.put(uuid, "block");
        }
        public static void recordEntity(UUID uuid, EntityType<?> type) {
            lastInteractions.put(uuid, BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
            lastInteractionTypes.put(uuid, "entity");
        }
        public static void recordInteractionItem(UUID uuid, ItemStack stack) {
            lastInteractionItems.put(uuid, stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static String getLastInteraction(ServerPlayer player) {
            return lastInteractions.get(player.getUUID());
        }
        public static String getLastInteractionItem(ServerPlayer player) {
            return lastInteractionItems.get(player.getUUID());
        }
        public static String getLastInteractionType(ServerPlayer player) {
            return lastInteractionTypes.get(player.getUUID());
        }
        public static void clear(UUID uuid) {
            lastInteractions.remove(uuid);
            lastInteractionItems.remove(uuid);
            lastInteractionTypes.remove(uuid);
        }
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

    public static class PickupDropTracker {
        private static final Map<UUID, String> lastPickedUp = new java.util.HashMap<>();
        private static final Map<UUID, String> lastDropped = new java.util.HashMap<>();
        public static void recordPickup(ServerPlayer player, ItemStack stack) {
            lastPickedUp.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static void recordDrop(ServerPlayer player, ItemStack stack) {
            lastDropped.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static String getLastPickedUp(ServerPlayer player) {
            return lastPickedUp.get(player.getUUID());
        }
        public static String getLastDropped(ServerPlayer player) {
            return lastDropped.get(player.getUUID());
        }
        public static void clear(UUID uuid) {
            lastPickedUp.remove(uuid);
            lastDropped.remove(uuid);
        }
    }

    public static class UseItemTracker {
        private static final Map<UUID, String> lastUsed = new java.util.HashMap<>();
        private static final Map<UUID, String> lastConsumed = new java.util.HashMap<>();
        private static final Map<UUID, String> lastReleased = new java.util.HashMap<>();
        private static final Map<UUID, String> lastInterrupted = new java.util.HashMap<>();
        private static final Map<UUID, String> lastInstantUsed = new java.util.HashMap<>();
        public static void recordUsed(ServerPlayer player, ItemStack stack) {
            lastUsed.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static void recordConsumed(ServerPlayer player, ItemStack stack) {
            lastConsumed.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static void recordReleased(ServerPlayer player, ItemStack stack) {
            lastReleased.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static void recordInterrupted(ServerPlayer player, ItemStack stack) {
            lastInterrupted.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static void recordInstantUse(ServerPlayer player, ItemStack stack) {
            lastInstantUsed.put(player.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        public static String getLastUsed(ServerPlayer player) {
            return lastUsed.get(player.getUUID());
        }
        public static String getLastConsumed(ServerPlayer player) {
            return lastConsumed.get(player.getUUID());
        }
        public static String getLastReleased(ServerPlayer player) {
            return lastReleased.get(player.getUUID());
        }
        public static String getLastInterrupted(ServerPlayer player) {
            return lastInterrupted.get(player.getUUID());
        }
        public static String getLastInstantUsed(ServerPlayer player) {
            return lastInstantUsed.get(player.getUUID());
        }
        public static void clear(UUID uuid) {
            lastUsed.remove(uuid);
            lastConsumed.remove(uuid);
            lastReleased.remove(uuid);
            lastInterrupted.remove(uuid);
            lastInstantUsed.remove(uuid);
        }
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
