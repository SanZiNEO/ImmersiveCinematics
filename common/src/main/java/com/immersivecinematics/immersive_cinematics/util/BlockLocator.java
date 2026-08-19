package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 方块搜索基准 — 玩家附近搜索最近匹配方块（如传送门框架 obsidian），作为相对坐标 / 注视目标的基准点。
 * <p>
 * 静态目标：搜索成功的坐标由调用方缓存（与结构基准同语义：成功永久、失败短重试）。
 * xz 平面按格遍历 + y 范围限制（玩家 y ± {@link #Y_RANGE}），返回与中心最近距离的匹配方块。
 */
public final class BlockLocator {

    /** y 搜索范围（玩家 y ± 此值，格） */
    private static final int Y_RANGE = 12;

    private BlockLocator() {}

    /**
     * 在 center 附近（xz 半径 radius 格、y ± Y_RANGE）找最近的 blockId 匹配方块。
     *
     * @param level   服务端世界（客户端编辑器预览通过 singleplayer server 获取）
     * @param blockId 方块 id（如 {@code "minecraft:obsidian"}）
     * @param center  搜索圆心（世界坐标，一般为玩家位置）
     * @param radius  xz 搜索半径（格，如 16）
     * @return 匹配方块坐标；未找到 / id 非法返回 null
     */
    public static BlockPos findNearest(ServerLevel level, String blockId, BlockPos center, int radius) {
        ResourceLocation id;
        try {
            id = new ResourceLocation(blockId);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("ImmersiveCinematics/BlockLocator")
                    .warn("无效的方块 id '{}': {}", blockId, e.getMessage());
            return null;
        }
        Block target = BuiltInRegistries.BLOCK.get(id);
        if (target == null || target == Blocks.AIR) return null;

        int minY = Math.max(level.getMinBuildHeight(), center.getY() - Y_RANGE);
        int maxY = Math.min(level.getMaxBuildHeight(), center.getY() + Y_RANGE);
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    cur.set(center.getX() + dx, y, center.getZ() + dz);
                    if (level.getBlockState(cur).is(target)) {
                        double distSq = cur.distSqr(center);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = cur.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }
}
