package com.immersivecinematics.immersive_cinematics.trigger.server.evaluator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.spi.ListenStrategy;
import com.immersivecinematics.immersive_cinematics.spi.TriggerEvalContext;
import com.immersivecinematics.immersive_cinematics.spi.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.continuity.MotionSample;
import com.immersivecinematics.immersive_cinematics.trigger.server.continuity.SegmentSweep;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.EventFactMatch;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.Fact;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactLedger;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactType;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactWindow;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.PlayerFacts;
import com.immersivecinematics.immersive_cinematics.trigger.server.prereq.PrerequisiteRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.shared.GeometryConditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * 触发器条件求值（服务端）：每种触发器类型一个静态方法，输入 = 玩家 + conditions 对象。
 * 纯几何 / id 匹配 / 朝向区间数学在 {@link GeometryConditions}（规则侧客户端共用同一核），
 * 本类只保留「读玩家 / 世界状态」的部分并委托共享核。线程契约：仅服务端主线程。
 */
public class Evaluators {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Trigger");

    /** gamestage 反射失败只提示一次（缺可选依赖是常态，不刷屏）。 */
    private static volatile boolean gamestageReflectionLogged = false;

    /** 区域条件外扩：委托共享几何核（规则侧客户端同一实现）。 */
    public static JsonObject expandConditions(JsonObject c, float buffer) {
        return GeometryConditions.expandConditions(c, buffer);
    }

    /** 取该玩家的事实分片：事件类条件一律读事实层，不再有进程内镜像状态。 */
    private static PlayerFacts factsOf(ServerPlayer player) {
        return FactLedger.INSTANCE.of(player.getUUID());
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

    /**
     * {@code location} 的连续语义版本：先跑点判定（与原路径逐位一致），未命中且本轮可扫掠时，
     * 再判定相邻两次采样之间的线段是否穿过方体 / 球。
     *
     * <p>{@code dimension} 条件仍按<b>当前</b>维度比对（跨维度的那一段已被 {@code PlayerMotion}
     * 的代次切断，不会走到这里）。条件既无 {@code corner1+corner2} 也无 {@code position} 时，
     * 扫掠没有几何目标，返回 false（点判定的 {@code dimension} 分支已在上面命中）。
     */
    public static boolean evaluateLocation(ServerPlayer player, JsonObject c, TriggerEvalContext ctx) {
        if (evaluateLocation(player, c)) return true;
        if (!ctx.canSweep()) return false;
        if (c.has("dimension")) {
            String targetDim = c.get("dimension").getAsString();
            if (!player.level().dimension().location().toString().equals(targetDim)) {
                return false;
            }
        }
        MotionSample from = ctx.from();
        MotionSample to = ctx.to();
        if (c.has("corner1") && c.has("corner2")
                && c.get("corner1").isJsonObject() && c.get("corner2").isJsonObject()) {
            if (SegmentSweep.hitsBox(from, to, c.getAsJsonObject("corner1"), c.getAsJsonObject("corner2"))) {
                return true;
            }
        }
        if (c.has("position") && c.get("position").isJsonObject()) {
            double radius = c.has("radius") && c.get("radius").isJsonPrimitive()
                    && c.get("radius").getAsJsonPrimitive().isNumber()
                    ? c.get("radius").getAsDouble() : 0.0;
            return SegmentSweep.hitsSphere(from, to, c.getAsJsonObject("position"), radius);
        }
        return false;
    }

    public static boolean evaluateAdvancement(ServerPlayer player, JsonObject c) {
        if (!c.has("advancement")) return false;
        Fact last = factsOf(player).lastOf(FactType.ADVANCEMENT, "");
        return EventFactMatch.matches(last, c);
    }

    public static boolean evaluateBiome(ServerPlayer player, JsonObject c) {
        if (!c.has("biome")) return false;
        String pattern = c.get("biome").getAsString();
        Holder<Biome> biome = player.level().getBiome(player.blockPosition());
        return biome.unwrapKey()
                .map(key -> GeometryConditions.matchesId(key.location().toString(), pattern))
                .orElse(false);
    }

    /**
     * entity_kill：读「最近一次击杀」这条事实，再匹配 entity + 场景（不是「最后一条匹配的击杀」）。
     * 只认单值 {@code entity}；击杀一组生物用 {@code all_of} + 多个单值子条件表达。
     */
    public static boolean evaluateEntityKill(ServerPlayer player, JsonObject c) {
        if (!c.has("entity")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ENTITY_KILL, ""), c);
    }

    /**
     * death：玩家死亡时触发（事件驱动，无轮询）。场景条件按死亡时刻的记录判定；
     * 该玩家没有死亡记录（未死过 / 已清理）→ false。
     */
    public static boolean evaluateDeath(ServerPlayer player, JsonObject c) {
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.DEATH, ""), c);
    }

    public static boolean evaluateInteract(ServerPlayer player, JsonObject c) {
        if (!c.has("target")) return false;
        Fact last = factsOf(player).lastOf(FactType.INTERACT_ENTITY, "");
        if (last == null) return false;
        return GeometryConditions.matchesId(last.object(), c.get("target").getAsString());
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
            return EventFactMatch.matches(factsOf(player).lastOf(FactType.DIMENSION_CHANGE, ""), c);
        }
        return true;
    }

    public static boolean evaluateLogin(ServerPlayer player, JsonObject c) {
        return true;
    }

    public static boolean evaluateItemCraft(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ITEM_CRAFT, ""), c);
    }

    public static boolean evaluateItemUse(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ITEM_USE, ""), c);
    }

    public static boolean evaluateItemConsume(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ITEM_CONSUME, ""), c);
    }

    public static boolean evaluateItemRelease(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ITEM_RELEASE, ""), c);
    }

    public static boolean evaluateItemUseInterrupt(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ITEM_USE_INTERRUPT, ""), c);
    }

    public static boolean evaluateItemInstantUse(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ITEM_INSTANT_USE, ""), c);
    }

    public static boolean evaluateXp(ServerPlayer player, JsonObject c) {
        if (c.has("level") && player.experienceLevel < c.get("level").getAsInt()) return false;
        if (c.has("total") && player.totalExperience < c.get("total").getAsInt()) return false;
        return c.has("level") || c.has("total");
    }

    /**
     * item_pickup：匹配最近一次捡起（{@code item} 单值）。捡起一组物品中的任意一件
     * 用 {@code any} + 多个单值子条件表达。
     */
    public static boolean evaluateItemPickup(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ITEM_PICKUP, ""), c);
    }

    public static boolean evaluateItemDrop(ServerPlayer player, JsonObject c) {
        if (!c.has("item")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.ITEM_DROP, ""), c);
    }

    /**
     * 注视检测（轮询，服务端射线）。{@code target} 必填；{@code target_type} 缺省时
     * 方块与实体都查、命中距离近者优先；可选 {@code reach}（默认 4.5 格）。
     */
    public static boolean evaluateObservation(ServerPlayer player, JsonObject c) {
        if (!c.has("target")) return false;
        double reach = c.has("reach") ? c.get("reach").getAsDouble() : 4.5;
        return observeRay(player, c, player.getEyePosition(), player.getLookAngle(), reach);
    }

    /** 弧带子步的角步长（度）：yaw 走最短弧、pitch 线性，每步最多转该角度。 */
    private static final double ARC_STEP_DEG = 5.0;

    /**
     * {@code observation} 的连续语义版本：先跑点判定（与 2 参路径逐位一致），未命中且本轮可扫掠时，
     * 按 {@link SegmentSweep#arcSteps} 把相邻两次采样之间的朝向变化切成 {@code ARC_STEP_DEG} 的弧带，
     * 每个子步重放一次同样的射线判定（眼位 = 位置线性插值 + 当前眼高，朝向 = yaw 最短弧插值 + pitch 线性）。
     *
     * <p>实体目标按<b>求值时刻</b>的位置查询（不做实体轨迹外推）；任一子步命中即 true。
     */
    public static boolean evaluateObservation(ServerPlayer player, JsonObject c, TriggerEvalContext ctx) {
        if (!c.has("target")) return false;
        double reach = c.has("reach") ? c.get("reach").getAsDouble() : 4.5;
        boolean pointHit = observeRay(player, c, player.getEyePosition(), player.getLookAngle(), reach);
        if (pointHit) return true;
        if (!ctx.canSweep()) return false;

        MotionSample from = ctx.from();
        MotionSample to = ctx.to();
        int steps = SegmentSweep.arcSteps(from, to, ARC_STEP_DEG);
        double eyeHeight = player.getEyeHeight();
        double dyaw = Mth.wrapDegrees((double) to.yaw() - (double) from.yaw());
        double dpitch = (double) to.pitch() - (double) from.pitch();

        for (int i = 1; i <= steps; i++) {
            double t = (double) i / (double) steps;
            Vec3 eye = new Vec3(
                    from.x() + (to.x() - from.x()) * t,
                    from.y() + (to.y() - from.y()) * t + eyeHeight,
                    from.z() + (to.z() - from.z()) * t);
            Vec3 look = viewVector(
                    (float) (from.pitch() + dpitch * t),
                    (float) (from.yaw() + dyaw * t));
            if (observeRay(player, c, eye, look, reach)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单次注视射线判定：从 {@code eye} 沿 {@code look} 走 {@code reach} 格，
     * 按 {@code target_type}（block / entity / 自动）匹配目标 id。
     */
    private static boolean observeRay(ServerPlayer player, JsonObject c, Vec3 eye, Vec3 look, double reach) {
        String target = c.get("target").getAsString();
        String targetType = c.has("target_type") ? c.get("target_type").getAsString() : null;
        Vec3 end = eye.add(look.scale(reach));
        AABB searchArea = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);

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

    /**
     * 与 {@code Entity#calculateViewVector(pitch, yaw)} 逐位一致的朝向向量（不修改玩家状态）：
     * {@code (sin(-yawR)·cos(pitchR), -sin(pitchR), cos(-yawR)·cos(pitchR))}，角度单位度。
     */
    private static Vec3 viewVector(float pitch, float yaw) {
        float f = pitch * Mth.DEG_TO_RAD;
        float f1 = -yaw * Mth.DEG_TO_RAD;
        float f2 = Mth.cos(f1);
        float f3 = Mth.sin(f1);
        float f4 = Mth.cos(f);
        float f5 = Mth.sin(f);
        return new Vec3((double) (f3 * f4), (double) (-f5), (double) (f2 * f4));
    }

    public static boolean evaluateBlockInteract(ServerPlayer player, JsonObject c) {
        if (!c.has("target")) return false;
        return EventFactMatch.matches(factsOf(player).lastOf(FactType.INTERACT_BLOCK, ""), c);
    }

    public static boolean evaluateItemOnInteract(ServerPlayer player, JsonObject c) {
        if (!c.has("item") || !c.has("target")) return false;
        PlayerFacts facts = factsOf(player);
        // 方块交互与实体交互共用一个「最近一次交互」语义：取两者中更晚的那条事实
        Fact block = facts.lastOf(FactType.INTERACT_BLOCK, "");
        Fact entity = facts.lastOf(FactType.INTERACT_ENTITY, "");
        Fact latest = block == null ? entity
                : entity == null ? block
                : (entity.tick() > block.tick() ? entity : block);
        return EventFactMatch.matches(latest, c);
    }

    /**
     * inventory：{@code items} 里的每个模式都要在背包里找得到（presence 恒为「全部拥有」，
     * 「拥有其中任意一件」用 {@code any} + 多个单值子条件表达）。
     * {@code change}（increase / decrease）走净变化判定：任一模式的数量发生对应变化即真。
     */
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

        var inventory = player.getInventory();
        int size = inventory.getContainerSize();

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
        var level = player.serverLevel();
        var structureRegistry = level.registryAccess()
                .registry(net.minecraft.core.registries.Registries.STRUCTURE).orElse(null);
        if (structureRegistry == null) return false;

        int radius = c.has("radius") ? c.get("radius").getAsInt() : 0;
        return hasStructureAt(level, structureRegistry, c.get("structure").getAsString(),
                radius, player.blockPosition());
    }

    /**
     * {@code structure} 的连续语义版本：点判定未命中且本轮可扫掠时，沿相邻采样的线段按
     * {@link SegmentSweep#substeps} 子步取样（每格一个子步，封顶 {@link SegmentSweep#SUBSTEP_MAX}），
     * 每个子步用 {@link BlockPos#containing(double, double, double)} 落到方块坐标，再按旧半径循环
     * 查结构（与点判定同一匹配逻辑）。
     *
     * <p>结构查询是相对昂贵的世界查询，因此只在点判定失败后才做；静止（段长 0）时
     * {@code substeps} 退化为 1，等价于对当前方块再查一次（点判定已失败，实际不会命中）。
     */
    public static boolean evaluateStructure(ServerPlayer player, JsonObject c, TriggerEvalContext ctx) {
        if (evaluateStructure(player, c)) return true;
        if (!ctx.canSweep() || !c.has("structure")) return false;

        var level = player.serverLevel();
        var structureRegistry = level.registryAccess()
                .registry(net.minecraft.core.registries.Registries.STRUCTURE).orElse(null);
        if (structureRegistry == null) return false;

        String pattern = c.get("structure").getAsString();
        int radius = c.has("radius") ? c.get("radius").getAsInt() : 0;
        MotionSample from = ctx.from();
        MotionSample to = ctx.to();
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        int steps = SegmentSweep.substeps(Math.sqrt(dx * dx + dy * dy + dz * dz), SegmentSweep.SUBSTEP_MAX);
        return SegmentSweep.anyPoint(from, to, steps, (x, y, z) -> hasStructureAt(
                level, structureRegistry, pattern, radius, BlockPos.containing(x, y, z)));
    }

    /** 以 {@code center} 为中心、按 {@code radius} 半径循环查结构；匹配规则与点判定完全一致。 */
    private static boolean hasStructureAt(ServerLevel level, Registry<Structure> structureRegistry,
                                          String pattern, int radius, BlockPos center) {
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
            // 可选集成：GameStages 未安装 / 反射失败 → 该触发永不生效（特性缺失即正确行为，非错误）
            if (!gamestageReflectionLogged) {
                gamestageReflectionLogged = true;
                LOGGER.info("GameStages 未安装或反射失败，gamestage 触发器不生效");
            }
            return false;
        }
    }

    // ===== 朝向范围 + 多重条件（AND 组合） =====

    /** 数字字段检查（存在且是数字） */
    private static boolean isNumber(JsonObject c, String key) {
        return c != null && c.has(key) && c.get(key).isJsonPrimitive()
                && c.get(key).getAsJsonPrimitive().isNumber();
    }

    /**
     * 朝向范围条件：玩家视线（原版 yaw / pitch，服务端同步的玩家实体值）是否落在
     * (yaw1, pitch1) → (yaw2, pitch2) 内。区间数学（含跨 ±180° 环绕）委托
     * {@link GeometryConditions#facingInRange}（规则侧客户端共用同一核）。
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
     * {@code facing} 的连续语义版本：点判定未命中且本轮可扫掠时，把两次采样之间的朝向变化
     * 近似成 yaw 最短弧 + pitch 线性区间，判定这段转动是否扫过目标朝向区间。
     * 区间数学与点判定同语义（跨 ±180° 环绕、{@code span == 0} = 整圈），委托
     * {@link SegmentSweep#hitsFacing}。
     */
    public static boolean evaluateFacing(ServerPlayer player, JsonObject c, TriggerEvalContext ctx) {
        if (evaluateFacing(player, c)) return true;
        if (!ctx.canSweep()) return false;
        if (!isNumber(c, "yaw1") || !isNumber(c, "pitch1")
                || !isNumber(c, "yaw2") || !isNumber(c, "pitch2")) {
            return false;
        }
        return SegmentSweep.hitsFacing(ctx.from(), ctx.to(),
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
        return evaluateCombination(player, c, true, 1, TriggerEvalContext.disabled(0));
    }

    /** 组合器的连续语义版本：同一内核，把 {@code ctx} 透传给每个轮询子条件。 */
    public static boolean evaluateAllOf(ServerPlayer player, JsonObject c, TriggerEvalContext ctx) {
        return evaluateCombination(player, c, true, 1, ctx);
    }

    /**
     * 任一条件（OR）：conditions.list 内任一子条件满足即为真，命中即短路。
     * 与 {@link #evaluateAllOf} 同构（同一份 list、同一个内核），只有判定从“全真”改为“任一真”；
     * 前置条件是锁存语义，解锁后该子条件持续为真，因此 any 列表里含已解锁前置条件时下一轮即命中。
     */
    public static boolean evaluateAny(ServerPlayer player, JsonObject c) {
        return evaluateCombination(player, c, false, 1, TriggerEvalContext.disabled(0));
    }

    /** 组合器的连续语义版本：同一内核，把 {@code ctx} 透传给每个轮询子条件。 */
    public static boolean evaluateAny(ServerPlayer player, JsonObject c, TriggerEvalContext ctx) {
        return evaluateCombination(player, c, false, 1, ctx);
    }

    /**
     * 组合器统一内核（all_of / any 共用）：按 list 顺序逐子求值，allOf 首个不满足 / any 首个满足即短路。
     * 索引遍历不建迭代器（本方法在轮询路径上，每 tick 求值）。
     *
     * @param allOf {@code true} = 全真才真；{@code false} = 任一真即真
     * @param depth 本组合器所处层数，顶层 = 1（上限 {@link #MAX_COMBINATION_DEPTH}，含顶层）
     * @param ctx   连续语义上下文，原样透传给子条件（事件类子条件按 {@code ctx.windowTicks()} 的窗口语义）
     */
    private static boolean evaluateCombination(ServerPlayer player, JsonObject c, boolean allOf, int depth,
                                               TriggerEvalContext ctx) {
        JsonArray list = combinationList(c);
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) {
            JsonElement el = list.get(i);
            boolean hit = el.isJsonObject() && evaluateCombinationChild(player, el.getAsJsonObject(), depth, ctx);
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
     * 组合器子条件求值（any / all_of 共用）：已注册前置条件优先（锁存语义）→ 触发器类型（轮询类按当前时刻；
     * 事件类按 {@code continuity.window} 窗口语义）；子条件为 {@code all_of} / {@code any} 时递归求值其
     * {@code conditions}（层数 = depth + 1，用尽即不满足）；未知类型、结构不全一律不满足。
     * 每轮所有轮询子条件都用同一时刻的玩家状态求值。
     *
     * @param depth 本子条件所属组合器的层数（顶层组合器 = 1；其子条件为组合器时按 depth + 1 递归）
     * @param ctx   连续语义上下文，原样透传给轮询子条件；{@code windowTicks} 给事件类子条件用
     */
    private static boolean evaluateCombinationChild(ServerPlayer player, JsonObject sub, int depth,
                                                    TriggerEvalContext ctx) {
        if (sub == null || !sub.has("type") || !sub.get("type").isJsonPrimitive()) return false;
        String type = sub.get("type").getAsString();
        if ("all_of".equals(type) || "any".equals(type)) {
            // 嵌套组合器：递归走统一内核；层数用尽即不满足（校验层已拦，这里防绕过校验的 JSON）
            if (depth >= MAX_COMBINATION_DEPTH) return false;
            if (!sub.has("conditions") || !sub.get("conditions").isJsonObject()) return false;
            return evaluateCombination(player, sub.getAsJsonObject("conditions"),
                    "all_of".equals(type), depth + 1, ctx);
        }
        JsonObject subConditions = sub.has("conditions") && sub.get("conditions").isJsonObject()
                ? sub.getAsJsonObject("conditions")
                : new JsonObject();
        if (PrerequisiteRegistry.has(type)) {
            return PrerequisiteRegistry.evaluate(type, player, subConditions);
        }
        TriggerType tt = TriggerRegistry.get(type);
        if (tt == null) return false;
        if (tt.getStrategy() == ListenStrategy.POLLING) {
            return tt.evaluate(player, subConditions, ctx);
        }
        // 事件类子条件：只有触发器声明了 continuity.window > 0 才有窗口语义（window = 0 由校验层拒绝）。
        if (ctx.windowTicks() <= 0) return false;
        if ("dimension_change".equals(type)) {
            // 目标维度是玩家当前状态（与单点求值器同口径），先判当前维度，再查窗口内的切换事实。
            JsonElement dimension = subConditions.get("dimension");
            String pattern = dimension != null && dimension.isJsonPrimitive() ? dimension.getAsString() : "*";
            if (!GeometryConditions.matchesId(player.level().dimension().location().toString(), pattern)) {
                return false;
            }
        }
        return FactWindow.evaluate(factsOf(player), type, subConditions,
                ctx.tick() - ctx.windowTicks(), ctx.tick());
    }

    /**
     * 库存采样基线：保存「上一次采样快照」，不是历史事实。
     *
     * <p>保留在求值器内而不收编到事实层，理由：{@code inventory change=increase/decrease} 判的是
     * <b>两次采样之间的净变化</b>，需要「上一轮扫描到的计数表」这一可原地覆盖的基线；
     * 事实层是「发生了什么」的只追加流水（谁 / 何时 / 哪个对象），没有「同一批 key 的上一次读数」这种
     * 可变当前态，把它写成事实再读回来只是给基线套一层历史语义。故 {@link Evaluators#evaluateInventory}
     * 直接持有该快照，其余事件类状态已全部改成事实查询。
     */
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
