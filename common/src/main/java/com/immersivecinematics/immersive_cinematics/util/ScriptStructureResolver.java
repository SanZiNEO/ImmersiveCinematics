package com.immersivecinematics.immersive_cinematics.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.script.PositionData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 脚本推送前的服务端结构/方块基准替换。
 * <p>
 * 遍历脚本关键帧，把"客户端无法解析的来源"就地替换为坐标：
 * <ul>
 *   <li>{@code look_at_target_structure} → {@code look_at_target_x/y/z}</li>
 *   <li>{@code position.relative_origin}（结构 id）→ {@code "coordinate"} + {@code relative_origin_x/y/z}</li>
 *   <li>{@code position.relative_origin}（{@code block:id[:radius]} 或 {@code {type:"block",...}}）→ 同上</li>
 * </ul>
 * 定位失败保留原字段（客户端该端无目标，片段按空处理）。脚本文件本身不被修改，只替换推送内容。
 * <p>
 * 两个服务端推送路径共用：{@code /icinematics play}（命令执行者位置为基准）与触发器播放
 * （{@code StartPlaybackAction}，触发者位置为基准）。单人服不受影响：客户端
 * {@code CameraTrackPlayer} 仍保留兜底解析。
 */
public final class ScriptStructureResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptStructureResolver");

    private ScriptStructureResolver() {}

    /**
     * 解析脚本 JSON 中的结构/方块来源，返回替换后的 JSON（无替换或解析失败时原样返回）。
     *
     * @param json   脚本原始 JSON（不被修改）
     * @param level  服务端世界（用于定位结构/方块）
     * @param origin 定位圆心（一般为执行者/触发者位置）
     * @return 替换后的 JSON；未发生替换或异常时返回入参
     */
    public static String resolveTargets(String json, ServerLevel level, Vec3 origin) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray tracks = root.getAsJsonObject("timeline").getAsJsonArray("tracks");
            if (tracks == null) return json;
            Map<String, Vec3> posCache = new HashMap<>();
            boolean changed = false;
            for (JsonElement te : tracks) {
                if (!te.isJsonObject()) continue;
                JsonArray clips = te.getAsJsonObject().getAsJsonArray("clips");
                if (clips == null) continue;
                for (JsonElement ce : clips) {
                    if (!ce.isJsonObject()) continue;
                    JsonArray kfs = ce.getAsJsonObject().getAsJsonArray("keyframes");
                    if (kfs == null) continue;
                    for (JsonElement ke : kfs) {
                        if (!ke.isJsonObject()) continue;
                        JsonObject kf = ke.getAsJsonObject();
                        if (kf.has("look_at_target_structure")) {
                            changed |= replaceStructureTarget(kf, "look_at_target_structure",
                                    "look_at_target_x", "look_at_target_y", "look_at_target_z",
                                    posCache, level, origin);
                        }
                        if (kf.has("position") && kf.get("position").isJsonObject()) {
                            JsonObject pos = kf.getAsJsonObject("position");
                            if (pos.has("relative_origin")) {
                                JsonElement ro = pos.get("relative_origin");
                                if (ro.isJsonObject() || (ro.isJsonPrimitive() && ro.getAsString().startsWith("block:"))) {
                                    // 方块基准：服务端定位 → 替换为 coordinate + 方块中心坐标
                                    changed |= replaceRelativeOriginBlock(pos, level, origin);
                                } else if (ro.isJsonPrimitive()) {
                                    // 结构 id / coordinate：走原结构替换路径
                                    changed |= replaceStructureTarget(pos, "relative_origin",
                                            "relative_origin_x", "relative_origin_y", "relative_origin_z",
                                            posCache, level, origin);
                                }
                            }
                        }
                    }
                }
            }
            return changed ? new Gson().toJson(root) : json;
        } catch (Exception e) {
            LOGGER.warn("结构坐标替换失败（原脚本照常推送）: {}", e.getMessage());
            return json;
        }
    }

    /**
     * 把对象内的结构字段替换为结构中心坐标：
     * sourceField（结构 id）→ 解析成功：写入 targetX/Y/Z 并移除 sourceField；
     * 解析失败（或 sourceField 非结构 id，如 "coordinate"）：保留原字段。
     *
     * @return 是否发生了替换
     */
    private static boolean replaceStructureTarget(JsonObject obj, String sourceField,
                                                  String targetX, String targetY, String targetZ,
                                                  Map<String, Vec3> posCache, ServerLevel level, Vec3 origin) {
        String structureId = obj.get(sourceField).getAsString();
        if ("coordinate".equals(structureId) || structureId.isEmpty()) return false;
        Vec3 pos = posCache.containsKey(structureId) ? posCache.get(structureId) : locateStructure(level, origin, structureId);
        posCache.put(structureId, pos);
        if (pos != null) {
            obj.addProperty(targetX, (float) pos.x);
            obj.addProperty(targetY, (float) pos.y);
            obj.addProperty(targetZ, (float) pos.z);
            obj.remove(sourceField);
            return true;
        }
        LOGGER.debug("结构 '{}' 定位失败，脚本保留 structure 字段（客户端该端无目标，片段按空处理）", structureId);
        return false;
    }

    /**
     * 服务端方块基准替换：relative_origin 的 block 写法（字符串 "block:id[:radius]" 或结构化对象
     * {type:"block",block,radius}）→ 定位服务端最近匹配方块，替换为 "coordinate" + 方块中心坐标。
     * 定位失败保留原字段（客户端该端无目标，片段按空处理）。
     */
    private static boolean replaceRelativeOriginBlock(JsonObject pos, ServerLevel level, Vec3 origin) {
        JsonElement ro = pos.get("relative_origin");
        String blockId;
        int radius;
        if (ro.isJsonPrimitive()) {
            String[] parsed = PositionData.parseBlockOriginString(ro.getAsString());
            blockId = parsed[0];
            radius = Integer.parseInt(parsed[1]);
        } else {
            JsonObject o = ro.getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            if (!"block".equals(type)) return false;
            blockId = o.get("block").getAsString();
            radius = o.has("radius") ? o.get("radius").getAsInt() : PositionData.DEFAULT_BLOCK_RADIUS;
        }
        Vec3 p = locateBlock(level, origin, blockId, radius);
        if (p != null) {
            pos.addProperty("relative_origin_x", (float) p.x);
            pos.addProperty("relative_origin_y", (float) p.y);
            pos.addProperty("relative_origin_z", (float) p.z);
            pos.addProperty("relative_origin", "coordinate");
            return true;
        }
        LOGGER.debug("方块基准 '{}' 定位失败，脚本保留 block 字段（客户端该端无目标，片段按空处理）", blockId);
        return false;
    }

    /** 服务端方块定位：以 origin 为中心搜索最近匹配方块，返回方块中心坐标 */
    private static Vec3 locateBlock(ServerLevel level, Vec3 origin, String blockId, int radius) {
        BlockPos found = BlockLocator.findNearest(level, blockId, BlockPos.containing(origin), radius);
        if (found != null) {
            return new Vec3(found.getX() + 0.5, found.getY() + 0.5, found.getZ() + 0.5);
        }
        return null;
    }

    /** 服务端结构定位：以 origin 为中心做附近搜寻（3 区块），返回结构 bounding box 中心 */
    private static Vec3 locateStructure(ServerLevel level, Vec3 origin, String structureId) {
        return StructureLocator.locateCenter(level, structureId, BlockPos.containing(origin), 3);
    }
}
