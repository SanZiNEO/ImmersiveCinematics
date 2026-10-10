package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.script.PositionData;
import com.immersivecinematics.immersive_cinematics.util.BlockLocator;
import com.immersivecinematics.immersive_cinematics.util.StructureLocator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 世界点定位（IO）：把脚本里的静态目标（结构 id / 方块 id + 半径）解析成世界坐标。
 *
 * 坐标空间与单位：返回值为世界空间方块坐标；方块目标取方块中心（整数坐标 + 0.5）。
 * 数值契约：不可解析（多人服务器无服务端访问 / 附近找不到 / 异常）返回 {@code null}，不引入替代值——
 * 调用方（片段可用性判定）按空片段处理。
 * 缓存契约：两类目标各一级缓存（键 = 结构 id / {@code blockId:radius}）；成功结果永久缓存
 * （静态目标，整场播放复用），失败结果 2 秒后可重试（目标可能随后被生成 / 加载）。
 * 线程与频率：仅客户端主线程，位于每帧求值路径；缓存由本实例持有，生命周期 = 所属轨道播放器。
 * 平台事实：单人 / 集成服务器（含编辑器预览）直连服务端 level；多人服务器无服务端访问——
 * 服务端 {@code /icinematics play} 推送前已把这两类目标替换为坐标，此路径主要为编辑器预览兜底。
 */
public final class WorldPointLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/WorldPointLocator");

    /** 结构坐标缓存条目 */
    private static class StructurePosCache {
        Vec3 pos;
        long resolvedAt;
    }

    /** 结构坐标缓存（structure id → 解析结果） */
    private final Map<String, StructurePosCache> structureCache = new HashMap<>();

    /** 方块基准缓存条目 */
    private static class BlockPosCache {
        Vec3 pos;
        long resolvedAt;
    }

    /** 方块基准缓存（blockId:radius → 块中心坐标） */
    private final Map<String, BlockPosCache> blockCache = new HashMap<>();

    /**
     * 结构中心定位：单人 / 集成服务器直连服务端 level，在玩家附近 3 区块已加载区域内搜寻结构
     * （{@link StructureLocator#locateCenter}，与触发器同源）。
     *
     * @param structureId 结构 id（非空，调用方已判空）
     * @return 结构 bounding box 中心（世界坐标）；不可解析返回 {@code null}
     */
    public Vec3 resolveStructurePos(String structureId) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        StructurePosCache cached = structureCache.get(structureId);
        if (cached != null && (cached.pos != null || now - cached.resolvedAt < 2000)) {
            return cached.pos;
        }
        Vec3 result = null;
        try {
            MinecraftServer singleplayer = mc.getSingleplayerServer();
            if (singleplayer == null) {
                LOGGER.debug("多人服务器无法解析结构 '{}'（服务端 play 推送会替换为坐标；编辑器预览仅限单人）", structureId);
            } else {
                ServerLevel serverLevel = singleplayer.getLevel(mc.level.dimension());
                if (serverLevel != null) {
                    result = StructureLocator.locateCenter(
                            serverLevel, structureId,
                            BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 3);
                    if (result == null) {
                        LOGGER.debug("结构 '{}' 在附近（3 区块内已加载区域）未找到", structureId);
                    }
                }
            }
        } catch (Exception e) {
            // 结构定位失败会直接表现为"片段按空处理"，作者难察觉，升级为 WARN 可见（2 秒缓存不刷屏）
            LOGGER.warn("结构坐标解析失败 '{}': {}", structureId, e.getMessage());
        }
        StructurePosCache entry = new StructurePosCache();
        entry.pos = result;
        entry.resolvedAt = now;
        structureCache.put(structureId, entry);
        return result;
    }

    /**
     * 方块基准定位：单人 / 集成服务器直连服务端 level，在玩家附近搜寻最近匹配方块
     * （{@link BlockLocator#findNearest}）。
     *
     * @param blockId 方块 id
     * @param radius  搜索半径（方块），实际取 {@code max(默认半径, radius)}
     * @return 方块中心（整数坐标 + 0.5，世界坐标）；不可解析返回 {@code null}
     */
    public Vec3 resolveBlockPos(String blockId, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        long now = System.currentTimeMillis();
        String key = blockId + ":" + radius;
        BlockPosCache cached = blockCache.get(key);
        if (cached != null && (cached.pos != null || now - cached.resolvedAt < 2000)) {
            return cached.pos;
        }
        BlockPos result = null;
        try {
            MinecraftServer singleplayer = mc.getSingleplayerServer();
            if (singleplayer == null) {
                LOGGER.debug("多人服务器无法解析方块基准 '{}'（服务端 play 推送会替换为坐标；编辑器预览仅限单人）", blockId);
            } else {
                ServerLevel serverLevel = singleplayer.getLevel(mc.level.dimension());
                if (serverLevel != null) {
                    result = BlockLocator.findNearest(
                            serverLevel, blockId,
                            BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                            Math.max(PositionData.DEFAULT_BLOCK_RADIUS, radius));
                }
            }
        } catch (Exception e) {
            // 方块定位失败会表现为"片段按空处理"，作者难察觉，升级为 WARN 可见
            LOGGER.warn("方块基准定位失败 '{}': {}", blockId, e.getMessage());
        }
        Vec3 center = result != null
                ? new Vec3(result.getX() + 0.5, result.getY() + 0.5, result.getZ() + 0.5)
                : null;
        BlockPosCache entry = new BlockPosCache();
        entry.pos = center;
        entry.resolvedAt = now;
        blockCache.put(key, entry);
        return center;
    }
}
