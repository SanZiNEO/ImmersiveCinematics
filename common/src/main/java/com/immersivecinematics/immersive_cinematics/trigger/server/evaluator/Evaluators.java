package com.immersivecinematics.immersive_cinematics.trigger.server.evaluator;

import com.immersivecinematics.immersive_cinematics.trigger.server.ListenStrategy;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.prereq.PrerequisiteRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.shared.GeometryConditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

/**
 * 触发器条件求值（服务端）：每种触发器类型一个静态方法，输入 = 玩家 + conditions 对象。
 *
 * 纯几何 / id 匹配 / 朝向区间数学已下沉到 {@link GeometryConditions}（0.3.6 C6-2，规则侧客户端共用同一核），
 * 本类只保留「读玩家 / 世界状态」的部分并委托共享核。线程契约：仅服务端主线程。
 */
public class Evaluators {

    /** 区域条件外扩：委托判定源共享几何（{@link GeometryConditions#expandConditions}，C6-2 下沉）。 */
    public static JsonObject expandConditions(JsonObject c, float buffer) {
        return GeometryConditions.expandConditions(c, buffer);
    }

    public static boolean evaluateLocation(ServerPlayer player, JsonObject c) {
        if (c.has("dimension")) {
            String targetDim = c.get("dimension").getAsString();
            if (!player.level().dimension().location().toString().equals(targetDim)) {
                return false;
            }
        }
        if (c.has("corner1") && c.has("corner2")
                && c.get("corner1").isJsonObject() && c.get("corner2").isJsonObject()) {
            if (GeometryConditions.inBox(player.getX(), player.getY(), player.getZ(),
                    c.getAsJsonObject("corner1"), c.getAsJsonObject("corner2"))) {
                return true;
            }
        }
        if (c.has("position")) {
            if (!c.get("position").isJsonObject()) return false;
            double radius = c.has("radius") && c.get("radius").isJsonPrimitive()
                    && c.get("radius").getAsJsonPrimitive().isNumber()
                    ? c.get("radius").getAsDouble() : 0.0;
            return GeometryConditions.inRadius(player.getX(), player.getY(), player.getZ(),
                    c.getAsJsonObject("position"), radius);
        }
        return c.has("dimension");
    }

    public static boolean evaluateAdvancement(ServerPlayer player, JsonObject c) {
        if (!c.has("advancement")) return false;
        String last = AdvancementTracker.getLastAdvancement(player);
        return last != null && GeometryConditions.matchesId(last, c.get("advancement").getAsString());
    }

    public static boolean evaluateBiome(ServerPlayer player, JsonObject c) {
        if (!c.has("biome")) return false;
        String pattern = c.get("biome").getAsString();
        Holder<Biome> biome = player.level().getBiome(player.blockPosition());
        return biome.unwrapKey()
                .map(key -> GeometryConditions.matchesId(key.location().toString(), pattern))
                .orElse(false);
    }

    public static boolean evaluateEntityKill(ServerPlayer player, JsonObject c) {
        if (!c.has("entity")) return false;
        var entity = c.get("entity");

        if (!entity.isJsonArray()) {
            KillTracker.KillRecord rec = KillTracker.getLastKill(player);
            if (rec == null) return false;
            return GeometryConditions.matchesId(rec.entityId(), entity.getAsString())
                    && matchesScene(c, rec.dimension(), rec.biome(), rec.x(), rec.y(), rec.z());
        }

        String mode = c.has("mode") ? c.get("mode").getAsString() : "or";

        if ("and".equals(mode)) {
            Set<KillTracker.KillRecord> allKills = KillTracker.getAllKills(player);
            if (allKills.isEmpty()) return false;
            for (var elem : entity.getAsJsonArray()) {
                String pattern = elem.getAsString();
                boolean matched = false;
                for (KillTracker.KillRecord rec : allKills) {
                    if (GeometryConditions.matchesId(rec.entityId(), pattern)) { matched = true; break; }
                }
                if (!matched) return false;
            }
            // 场景条件按最近一次击杀记录判定（击杀时刻的位置，非玩家当前位置）
            KillTracker.KillRecord last = KillTracker.getLastKill(player);
            return last != null && matchesScene(c, last.dimension(), last.biome(), last.x(), last.y(), last.z());
        }

        KillTracker.KillRecord rec = KillTracker.getLastKill(player);
        if (rec == null) return false;
        for (var elem : entity.getAsJsonArray()) {
            if (GeometryConditions.matchesId(rec.entityId(), elem.getAsString())
                    && matchesScene(c, rec.dimension(), rec.biome(), rec.x(), rec.y(), rec.z())) return true;
        }
        return false;
    }

    /**
     * death：玩家死亡时触发（事件驱动，无轮询）。场景条件按死亡时刻的记录判定，与
     * {@link #matchesScene} 同口径；该玩家没有死亡记录（未死过 / 已清理）→ false。
     */
    public static boolean evaluateDeath(ServerPlayer player, JsonObject c) {
        DeathTracker.DeathRecord rec = DeathTracker.getLastDeath(player.getUUID());
        if (rec == null) return false;
        return matchesScene(c, rec.dimension(), rec.biome(), rec.x(), rec.y(), rec.z());
    }

    /**
     * 事件时刻场景条件：dimension / biome / position+radius / corner1+corner2，按事件发生时刻记录下来的
     * 维度/群系/坐标判定（entity_kill 传击杀记录、death 传死亡记录），而不是玩家当前位置；无字段 = 恒真。
     */
    private static boolean matchesScene(JsonObject c, String dimension, String biome,
                                       double x, double y, double z) {
        if (c.has("dimension") && !GeometryConditions.matchesId(dimension, c.get("dimension").getAsString())) return false;
        if (c.has("biome") && !GeometryConditions.matchesId(biome, c.get("biome").getAsString())) return false;
        if (c.has("position")) {
            if (!c.get("position").isJsonObject()) return false;
            double radius = c.has("radius") && c.get("radius").isJsonPrimitive()
                    && c.get("radius").getAsJsonPrimitive().isNumber()
                    ? c.get("radius").getAsDouble() : 0.0;
            if (!GeometryConditions.inRadius(x, y, z, c.getAsJsonObject("position"), radius)) return false;
        }
        if (c.has("corner1") && c.has("corner2")
                && c.get("corner1").isJsonObject() && c.get("corner2").isJsonObject()) {
            if (!GeometryConditions.inBox(x, y, z, c.getAsJsonObject("corner1"), c.getAsJsonObject("corner2"))) return false;
        }
        return true;
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
        // 目标维度匹配（现有）
        if (!GeometryConditions.matchesId(player.level().dimension().location().toString(),
                c.get("dimension").getAsString())) {
            return false;
        }
        // 可选来源维度过滤：from_dimension 不写 = 不限制来源（旧脚本零迁移）
        if (c.has("from_dimension")) {
            String from = DimensionTracker.getLastFrom(player);
            if (from == null) return false;
            if (!GeometryConditions.matchesId(from, c.get("from_dimension").getAsString())) return false;
        }
        return true;
    }

    public static boolean evaluateLogin(ServerPlayer player, JsonObject c) {
        return true;
    }

    public static boolean evaluateItemCraft(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastCrafted = CraftTracker.getLastCrafted(player);
        if (lastCrafted == null) return false;
        return GeometryConditions.matchesId(lastCrafted, c.get("item").getAsString());
    }

    public static boolean evaluateItemUse(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastUsed = UseItemTracker.getLastUsed(player);
        if (lastUsed == null) return false;
        return GeometryConditions.matchesId(lastUsed, c.get("item").getAsString());
    }

    public static boolean evaluateItemConsume(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastConsumed = UseItemTracker.getLastConsumed(player);
        if (lastConsumed == null) return false;
        return GeometryConditions.matchesId(lastConsumed, c.get("item").getAsString());
    }

    public static boolean evaluateItemRelease(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastReleased = UseItemTracker.getLastReleased(player);
        if (lastReleased == null) return false;
        return GeometryConditions.matchesId(lastReleased, c.get("item").getAsString());
    }

    public static boolean evaluateItemUseInterrupt(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastInterrupted = UseItemTracker.getLastInterrupted(player);
        if (lastInterrupted == null) return false;
        return GeometryConditions.matchesId(lastInterrupted, c.get("item").getAsString());
    }

    public static boolean evaluateItemInstantUse(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastInstantUsed = UseItemTracker.getLastInstantUsed(player);
        if (lastInstantUsed == null) return false;
        return GeometryConditions.matchesId(lastInstantUsed, c.get("item").getAsString());
    }

    public static boolean evaluateXp(ServerPlayer player, JsonObject c) {
        if (c.has("level") && player.experienceLevel < c.get("level").getAsInt()) return false;
        if (c.has("total") && player.totalExperience < c.get("total").getAsInt()) return false;
        return c.has("level") || c.has("total");
    }

    public static boolean evaluateItemPickup(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        JsonElement item = c.get("item");

        // 数组模式：item 为数组 + mode（"or" 默认 = 捡起任一触发；"and" = 全部捡过才触发，基于本会话捡起历史）
        if (item.isJsonArray()) {
            String mode = c.has("mode") ? c.get("mode").getAsString() : "or";
            if ("and".equals(mode)) {
                Set<String> picked = PickupDropTracker.getPickedUpSet(player);
                if (picked == null || picked.isEmpty()) return false;
                for (JsonElement elem : item.getAsJsonArray()) {
                    boolean found = false;
                    for (String id : picked) {
                        if (GeometryConditions.matchesId(id, elem.getAsString())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) return false;
                }
                return true;
            }
            String lastPickedUp = PickupDropTracker.getLastPickedUp(player);
            if (lastPickedUp == null) return false;
            for (JsonElement elem : item.getAsJsonArray()) {
                if (GeometryConditions.matchesId(lastPickedUp, elem.getAsString())) return true;
            }
            return false;
        }

        // 单物品模式：item 为字符串，匹配最近一次捡起
        String lastPickedUp = PickupDropTracker.getLastPickedUp(player);
        if (lastPickedUp == null) return false;
        return GeometryConditions.matchesId(lastPickedUp, item.getAsString());
    }

    public static boolean evaluateItemDrop(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        String lastDropped = PickupDropTracker.getLastDropped(player);
        if (lastDropped == null) return false;
        return GeometryConditions.matchesId(lastDropped, c.get("item").getAsString());
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
            return GeometryConditions.matchesId(BuiltInRegistries.BLOCK.getKey(
                    player.level().getBlockState(blockHit.getBlockPos()).getBlock()).toString(), target);
        }
        if ("entity".equals(targetType)) {
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                    player.level(), player, eye, end, searchArea, e -> !e.isSpectator() && e.isPickable(), 0.3f);
            if (entityHit == null) return false;
            return GeometryConditions.matchesId(BuiltInRegistries.ENTITY_TYPE.getKey(entityHit.getEntity().getType()).toString(), target);
        }
        BlockHitResult blockHit = player.level().clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player.level(), player, eye, end, searchArea, e -> !e.isSpectator() && e.isPickable(), 0.3f);
        if (entityHit != null && (blockHit.getType() == HitResult.Type.MISS
                || entityHit.getLocation().distanceToSqr(eye) <= blockHit.getLocation().distanceToSqr(eye))) {
            return GeometryConditions.matchesId(BuiltInRegistries.ENTITY_TYPE.getKey(entityHit.getEntity().getType()).toString(), target);
        }
        if (blockHit.getType() != HitResult.Type.MISS) {
            return GeometryConditions.matchesId(BuiltInRegistries.BLOCK.getKey(
                    player.level().getBlockState(blockHit.getBlockPos()).getBlock()).toString(), target);
        }
        return false;
    }

    public static boolean evaluateBlockInteract(ServerPlayer player, JsonObject c) {
        if (!c.has("target")) return false;
        String lastInteract = InteractTracker.getLastInteraction(player);
        if (lastInteract == null) return false;
        return GeometryConditions.matchesId(lastInteract, c.get("target").getAsString());
    }

    public static boolean evaluateItemOnInteract(ServerPlayer player, JsonObject c) {
        if (!c.has("item") || !c.has("target")) return false;
        String lastItem = InteractTracker.getLastInteractionItem(player);
        if (lastItem == null) return false;
        String itemPattern = c.get("item").getAsString();
        if (!GeometryConditions.matchesId(lastItem, itemPattern)) return false;
        String lastTarget = InteractTracker.getLastInteraction(player);
        if (lastTarget == null) return false;
        String targetPattern = c.get("target").getAsString();
        if (!GeometryConditions.matchesId(lastTarget, targetPattern)) return false;
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
                        if (GeometryConditions.matchesId(id, p)) return true;
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
                remaining.removeIf(pattern -> GeometryConditions.matchesId(id, pattern));
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
                        if (id != null && GeometryConditions.matchesId(id.toString(), pattern)) return true;
                    }
                }
            }
        } else {
            var structures = level.structureManager().getAllStructuresAt(center);
            for (var structure : structures.keySet()) {
                ResourceLocation id = structureRegistry.getKey(structure);
                if (id != null && GeometryConditions.matchesId(id.toString(), pattern)) return true;
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
            // 可选集成：Gamestage 模组未安装 / 反射失败 → 该触发永不生效（特性缺失即正确行为，非错误，不刷屏）
            return false;
        }
    }

    // ===== 0.3.6：朝向范围 + 多重条件（AND 组合） =====

    /** 数字字段检查（存在且是数字） */
    private static boolean isNumber(JsonObject c, String key) {
        return c != null && c.has(key) && c.get(key).isJsonPrimitive()
                && c.get(key).getAsJsonPrimitive().isNumber();
    }

    /**
     * 朝向范围条件：玩家视线（原版 yaw / pitch，服务端同步的玩家实体值）是否落在
     * (yaw1, pitch1) → (yaw2, pitch2) 内。区间数学（含跨 ±180° 环绕）委托
     * {@link GeometryConditions#facingInRange}（C6-2 下沉，规则侧客户端共用同一核）。
     * 只读玩家实体自身的朝向，与相机脚本 / 模组相机无关。
     */
    public static boolean evaluateFacing(ServerPlayer player, JsonObject c) {
        if (!isNumber(c, "yaw1") || !isNumber(c, "pitch1")
                || !isNumber(c, "yaw2") || !isNumber(c, "pitch2")) {
            return false;
        }
        return GeometryConditions.facingInRange(player.getYRot(), player.getXRot(),
                c.get("yaw1").getAsFloat(), c.get("pitch1").getAsFloat(),
                c.get("yaw2").getAsFloat(), c.get("pitch2").getAsFloat());
    }

    /**
     * 组合器嵌套深度上限（含顶层组合器自身为第 1 层）：求值侧与校验侧（{@code ScriptValidator}）共用。
     * 超过上限的嵌套运行时按「不满足」处理（防御——正常路径校验层已拦）。
     */
    public static final int MAX_COMBINATION_DEPTH = 8;

    /**
     * 多重条件（AND）：conditions.list 内每个子条件全部满足才为真；每轮用同一时刻的玩家状态重算全部
     * 子条件（无记忆、无等待、首个不满足短路）。子条件可为轮询类触发器、前置条件（锁存）或另一个组合器。
     */
    public static boolean evaluateAllOf(ServerPlayer player, JsonObject c) {
        return evaluateCombination(player, c, true, 1);
    }

    /**
     * 任一条件（OR）：conditions.list 内**任一**子条件满足即为真，命中即短路。
     * 与 {@link #evaluateAllOf} 同构（同一份 list、同一个内核），只有判定从“全真”改为“任一真”；
     * 前置条件是锁存语义，解锁后该子条件持续为真，因此 any 列表里含已解锁前置条件时下一轮即命中。
     */
    public static boolean evaluateAny(ServerPlayer player, JsonObject c) {
        return evaluateCombination(player, c, false, 1);
    }

    /**
     * 组合器统一内核（all_of / any 共用）：按 list 顺序逐子求值，allOf 首个不满足 / any 首个满足即短路。
     * 索引遍历不建迭代器（本方法在轮询路径上，每 tick 求值）。
     *
     * @param allOf {@code true} = 全真才真；{@code false} = 任一真即真
     * @param depth 本组合器所处层数，顶层 = 1（上限 {@link #MAX_COMBINATION_DEPTH}，含顶层）
     */
    private static boolean evaluateCombination(ServerPlayer player, JsonObject c, boolean allOf, int depth) {
        JsonArray list = combinationList(c);
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) {
            JsonElement el = list.get(i);
            boolean hit = el.isJsonObject() && evaluateCombinationChild(player, el.getAsJsonObject(), depth);
            if (allOf && !hit) return false;
            if (!allOf && hit) return true;
        }
        return allOf;
    }

    /** 组合器（any / all_of）的 list 提取：缺失 / 非数组 / 空数组 → null（视为不满足） */
    private static JsonArray combinationList(JsonObject c) {
        if (c == null || !c.has("list") || !c.get("list").isJsonArray()) return null;
        JsonArray list = c.getAsJsonArray("list");
        return list.isEmpty() ? null : list;
    }

    /**
     * 组合器子条件求值（any / all_of 共用）：已注册前置条件优先（锁存语义）→ 触发器类型（须 POLLING，瞬时语义）；
     * 子条件为 {@code all_of} / {@code any} 时递归求值其 {@code conditions}（层数 = depth + 1，用尽即不满足）；
     * 事件类触发器、未知类型、结构不全一律不满足。每轮所有轮询子条件都用同一时刻的玩家状态求值。
     *
     * @param depth 本子条件所属组合器的层数（顶层组合器 = 1；其子条件为组合器时按 depth + 1 递归）
     */
    private static boolean evaluateCombinationChild(ServerPlayer player, JsonObject sub, int depth) {
        if (sub == null || !sub.has("type") || !sub.get("type").isJsonPrimitive()) return false;
        String type = sub.get("type").getAsString();
        if ("all_of".equals(type) || "any".equals(type)) {
            // 嵌套组合器：递归走统一内核；层数用尽即不满足（校验层已拦，这里防绕过校验的 JSON）
            if (depth >= MAX_COMBINATION_DEPTH) return false;
            if (!sub.has("conditions") || !sub.get("conditions").isJsonObject()) return false;
            return evaluateCombination(player, sub.getAsJsonObject("conditions"),
                    "all_of".equals(type), depth + 1);
        }
        JsonObject subConditions = sub.has("conditions") && sub.get("conditions").isJsonObject()
                ? sub.getAsJsonObject("conditions")
                : new JsonObject();
        if (PrerequisiteRegistry.has(type)) {
            return PrerequisiteRegistry.evaluate(type, player, subConditions);
        }
        TriggerType tt = TriggerRegistry.get(type);
        if (tt == null || tt.getStrategy() != ListenStrategy.POLLING) return false;
        return tt.evaluate(player, subConditions);
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

    /**
     * death 触发器记录：每位玩家单槽（最近一次死亡），内容 = 死亡时刻的维度 / 群系 / 坐标。
     * <p>与 {@link KillTracker} 并列而不复用：KillRecord 带实体 id 且维护 allKills 集合（entity_kill 的
     * and 模式语义），死亡没有"被杀的实体"，混用会污染该集合。维度 / 群系为 id 字符串，坐标为世界坐标。
     */
    public static class DeathTracker {
        /** 死亡记录：死亡时刻的维度 / 群系 / 坐标（场景条件用，与 KillRecord 的对应字段同形） */
        public record DeathRecord(String dimension, String biome, double x, double y, double z) {}

        private static final Map<UUID, DeathRecord> lastDeaths = new java.util.HashMap<>();

        /** 记录一次死亡（uuid = 死者）；同一玩家再次死亡覆盖单槽 */
        public static void record(UUID uuid, String dimension, String biome, double x, double y, double z) {
            lastDeaths.put(uuid, new DeathRecord(dimension, biome, x, y, z));
        }

        /** 最近一次死亡记录；无记录（未死过 / 已 clear）→ null */
        public static DeathRecord getLastDeath(UUID uuid) {
            return lastDeaths.get(uuid);
        }

        public static void clear(UUID uuid) {
            lastDeaths.remove(uuid);
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

    public static class DimensionTracker {
        private static final Map<UUID, String> lastFrom = new java.util.HashMap<>();
        /** 记录玩家最近一次维度切换的来源维度 id */
        public static void record(UUID uuid, String fromDimensionId) {
            lastFrom.put(uuid, fromDimensionId);
        }
        /** 玩家最近一次维度切换的来源维度（dimension_change 触发时同步写入，轮询型共用时可能过期） */
        public static String getLastFrom(ServerPlayer player) {
            return lastFrom.get(player.getUUID());
        }
        public static void clear(UUID uuid) {
            lastFrom.remove(uuid);
        }
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
        /** 捡起历史（and 模式用）：玩家本会话捡起过的全部物品 id 集合 */
        private static final Map<UUID, Set<String>> pickedUpSet = new java.util.HashMap<>();
        public static void recordPickup(ServerPlayer player, ItemStack stack) {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            lastPickedUp.put(player.getUUID(), id);
            pickedUpSet.computeIfAbsent(player.getUUID(), k -> new java.util.HashSet<>()).add(id);
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
        /** 本会话捡起历史集合（可能为 null：从未捡起过） */
        public static Set<String> getPickedUpSet(ServerPlayer player) {
            return pickedUpSet.get(player.getUUID());
        }
        public static void clear(UUID uuid) {
            lastPickedUp.remove(uuid);
            lastDropped.remove(uuid);
            pickedUpSet.remove(uuid);
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
