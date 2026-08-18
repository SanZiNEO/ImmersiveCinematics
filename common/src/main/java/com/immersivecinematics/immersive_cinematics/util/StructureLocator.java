package com.immersivecinematics.immersive_cinematics.util;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 结构定位工具 — 返回结构的几何中心（bounding box 中心）
 * <p>
 * 找结构用<b>触发器同款附近搜寻</b>（structureManager.getAllStructuresAt 按 chunk 步进扫描
 * 玩家附近已加载区域的 STRUCTURE_REFERENCES）——look_at/相对基准的场景基本都是"玩家在结构附近"，
 * 附近搜寻天然命中最近的已加载结构。
 * <p>
 * 不采用原版 {@code findNearestMapStructure}（/locate 同源）：它是网格环序 + 环内固定顺序命中，
 * 可能选到更远的未加载区域结构（相机飞到远处），且会强制加载 chunk。
 * <p>
 * 命中后取 {@link StructureStart#getBoundingBox()} 的几何中心作为注视/相对基准。
 * 服务端播放推送（CinematicCommand）与客户端编辑器预览（CameraTrackPlayer）共用此实现。
 */
public final class StructureLocator {

    private StructureLocator() {}

    /**
     * 以 center 为圆心、radius 区块半径内按 chunk 步进扫描，找匹配的结构并返回其 bounding box 中心。
     * 只在已加载区域查找：玩家不在结构附近时找不到（返回 null，调用方按空片段处理）。
     *
     * @param level       服务端世界（客户端编辑器预览通过 singleplayer server 获取）
     * @param structureId 结构 id（精确匹配，如 {@code "minecraft:village_plains"}）
     * @param center      搜索圆心（世界坐标，一般为玩家位置）
     * @param radius      搜索半径（区块，建议 3 = 48 格；玩家在结构附近时必中）
     * @return 结构中心（含 +0.5 块中心偏移）；未找到/解析失败返回 null
     */
    @Nullable
    public static Vec3 locateCenter(ServerLevel level, String structureId, BlockPos center, int radius) {
        try {
            ResourceLocation id = new ResourceLocation(structureId);
            Structure structure = level.registryAccess()
                    .registryOrThrow(Registries.STRUCTURE).get(id);
            if (structure == null) return null;

            // 触发器同款附近搜寻：以 center 为圆心按 chunk（16 格）步进扫描 STRUCTURE_REFERENCES
            int step = 16;
            int range = Math.max(step, radius * step);
            for (int dx = -range; dx <= range; dx += step) {
                for (int dz = -range; dz <= range; dz += step) {
                    BlockPos scan = center.offset(dx, 0, dz);
                    Map<Structure, LongSet> refs = level.structureManager().getAllStructuresAt(scan);
                    if (!refs.containsKey(structure)) continue;

                    // 命中：从 references 拿 StructureStart → 结构几何中心
                    for (StructureStart start : level.structureManager()
                            .startsForStructure(SectionPos.of(scan), structure)) {
                        if (start != null && start.isValid()) {
                            BlockPos c = start.getBoundingBox().getCenter();
                            return new Vec3(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            // 定位失败 → null（调用方按"结构未找到"处理，片段空）；真实扫描异常要可见
            org.slf4j.LoggerFactory.getLogger("ImmersiveCinematics/StructureLocator")
                    .warn("结构定位异常 '{}': {}", structureId, e.getMessage());
            return null;
        }
    }
}
