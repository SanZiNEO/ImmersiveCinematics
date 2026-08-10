package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 结构定位工具 — 返回结构的几何中心（bounding box 中心）
 * <p>
 * 原版 {@code findNearestMapStructure}（/locate 同源）返回的是结构<b>生成锚点</b>
 * （placement 的 locatePos），不是结构中心。本类在锚点基础上进一步取该 chunk 的
 * {@code STRUCTURE_STARTS} 数据拿到 {@link StructureStart}，用
 * {@link StructureStart#getBoundingBox()} 的几何中心作为注视/相对基准。
 * <p>
 * 服务端播放推送（CinematicCommand）与客户端编辑器预览（CameraTrackPlayer）共用此实现。
 */
public final class StructureLocator {

    private StructureLocator() {}

    /**
     * 以 center 为圆心搜索最近的结构（原版 /locate 同范围），返回结构 bounding box 中心。
     *
     * @param level       服务端世界（客户端编辑器预览通过 singleplayer server 获取）
     * @param structureId 结构 id（如 {@code "minecraft:village"}）
     * @param center      搜索圆心（世界坐标）
     * @param radius      搜索半径（区块）
     * @return 结构中心（含 +0.5 块中心偏移）；未找到/解析失败返回 null
     */
    @Nullable
    public static Vec3 locateCenter(ServerLevel level, String structureId, BlockPos center, int radius) {
        try {
            ResourceLocation id = new ResourceLocation(structureId);
            net.minecraft.core.Registry<Structure> reg = level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);

            // 定位结构锚点：支持结构 tag（如 minecraft:village）与单结构（如 minecraft:village_plains）。
            // ⚠️ 不能用 TagKey 直接查单结构——registry.getTag() 对该 id 返回 empty，定位必然失败；
            // 原版 /locate 对单结构构造的是单元素 HolderSet（ChunkGenerator.findNearestMapStructure 的 HolderSet 重载）。
            TagKey<Structure> tag = TagKey.create(net.minecraft.core.registries.Registries.STRUCTURE, id);
            BlockPos locatePos;
            if (reg.getTag(tag).isPresent()) {
                locatePos = level.findNearestMapStructure(tag, center, radius, false);
            } else {
                net.minecraft.core.Holder.Reference<Structure> holder = reg.getHolderOrThrow(
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.STRUCTURE, id));
                com.mojang.datafixers.util.Pair<BlockPos, net.minecraft.core.Holder<Structure>> pair =
                        level.getChunkSource().getGenerator()
                                .findNearestMapStructure(level, net.minecraft.core.HolderSet.direct(holder), center, radius, false);
                locatePos = pair != null ? pair.getFirst() : null;
            }
            if (locatePos == null) return null;

            Structure structure = reg.get(id);
            if (structure == null) return null;

            // 锚点所在 chunk 的 STRUCTURE_STARTS 数据 → StructureStart（getLocatePos 保证锚点在结构 chunk 内）
            ChunkPos cp = new ChunkPos(locatePos);
            ChunkAccess chunk = level.getChunk(cp.x, cp.z, ChunkStatus.STRUCTURE_STARTS);
            StructureStart start = level.structureManager()
                    .getStartForStructure(SectionPos.bottomOf(chunk), structure, chunk);
            if (start == null || !start.isValid()) return null;

            BlockPos c = start.getBoundingBox().getCenter();
            return new Vec3(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
        } catch (Exception e) {
            return null;
        }
    }
}
