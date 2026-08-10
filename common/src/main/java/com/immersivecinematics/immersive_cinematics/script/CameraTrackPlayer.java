package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

public class CameraTrackPlayer implements TrackPlayer {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ImmersiveCinematics/CameraTrackPlayer");

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;
    private final Vec3 originPos;
    private final CameraManager cameraManager;

    /** 独立 Bezier 路径策略实例，脚本结束时随 TrackPlayer 一起 GC，LUT 缓存自动释放 */
    private final PathStrategy bezierStrategy = new BezierPathStrategy();

    private int lastClipIndex = 0;

    /** 诊断：look_at 日志节流 */
    private long lastLookAtLog;

    /** 上一帧最终世界坐标（实体目标消失时停在原地、以及作为 @e 就近基准） */
    private Vec3 lastWorldPos;

    /** 非玩家目标解析缓存（selector → 实体+时间；1 秒有效，避免每帧全量遍历实体列表） */
    private static class CachedTarget {
        Entity entity;
        long resolvedAt;
    }
    private final java.util.Map<String, CachedTarget> targetCache = new java.util.HashMap<>();

    public CameraTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, Vec3 originPos, CameraManager cameraManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.trackIndex = trackIndex;
        this.originPos = originPos;
        this.cameraManager = cameraManager;
        this.lastWorldPos = originPos;
    }

    /** 组 A：动态数据源（replaceScript 后自动用新数据，零重建） */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
    }


    @Override
    public boolean isActiveAt(float globalTime) {
        List<Clip> clips = clips();
        Clip c = findActiveClip(globalTime);
        if (c != null) return true;
        for (int i = 0; i < clips.size() - 1; i++) {
            Clip prev = clips.get(i);
            if (prev.isMorph() && prev.getTransitionDuration() > 0f && !prev.isEffectivelyInfinite()) {
                // B 模型：转场区以片段边界为中心 [end−t/2, end+t/2)
                float prevEnd = prev.getWindowEnd();
                float half = prev.getTransitionDuration() / 2f;
                if (globalTime >= prevEnd - half && globalTime < prevEnd + half) return true;
            }
        }
        return false;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        List<Clip> clips = clips();
        if (clips.isEmpty()) return;

        // 组 7：编辑器拖拽直控期间，相机由编辑器直驱（previewSetCamera），跳过轨道写入
        if (cameraManager.isPreviewDirectControl()) return;

        // B 模型 morph：转场区 [A_end−t/2, A_end+t/2) 内双轨各自插值交叉（A 尾部真实走完、B 头部真实进入）
        for (int i = 0; i < clips.size() - 1; i++) {
            Clip prev = clips.get(i);
            Clip next = clips.get(i + 1);
            if (prev.isMorph() && prev.getTransitionDuration() > 0f && !prev.isEffectivelyInfinite()) {
                float prevEnd = prev.getWindowEnd();
                float half = prev.getTransitionDuration() / 2f;
                float morphStart = prevEnd - half;
                float morphEnd = prevEnd + half;
                if (globalTime >= morphStart && globalTime < morphEnd) {
                    float weight = (globalTime - morphStart) / prev.getTransitionDuration();
                    renderMorph(prev, next, weight, globalTime);
                    return;
                }
            }
        }

        Clip primaryClip = findActiveClip(globalTime);
        if (primaryClip == null) return;

        float clipLocalTime = globalTime - primaryClip.getStartTime();
        renderSingle(globalTime, primaryClip, clipLocalTime);
    }

    private void renderSingle(float globalTime, Clip clip, float clipLocalTime) {
        KeyframeInterpolator.InterpolationResult result =
                KeyframeInterpolator.computeInterpolation(clipLocalTime, clip);
        if (result == null) return;

        float s = result.adjustedT;
        writeAttributes(result.from, result.to, s, clip, globalTime);
    }

    private void renderMorph(Clip prevClip, Clip nextClip, float weight, float globalTime) {
        // B 模型：双轨各自按自身时间插值（prev 走 [dur−t/2, dur)，next 走 [0, t)），再按 weight 交叉
        float prevLocal = globalTime - prevClip.getStartTime();
        float nextLocal = globalTime - nextClip.getStartTime();
        KeyframeInterpolator.InterpolationResult prevResult =
                KeyframeInterpolator.computeInterpolation(prevLocal, prevClip);
        KeyframeInterpolator.InterpolationResult nextResult =
                KeyframeInterpolator.computeInterpolation(nextLocal, nextClip);

        if (prevResult == null && nextResult == null) return;

        float prevS = prevResult != null ? prevResult.adjustedT : 0f;
        float nextS = nextResult != null ? nextResult.adjustedT : 0f;

        Keyframe prevFrom = prevResult != null ? prevResult.from : null;
        Keyframe prevTo = prevResult != null ? prevResult.to : null;
        Keyframe nextFrom = nextResult != null ? nextResult.from : null;
        Keyframe nextTo = nextResult != null ? nextResult.to : null;

        float invWeight = 1f - weight;

        Vec3 prevPos = prevFrom != null
                ? interpolateWorldPosition(prevFrom, prevTo, prevS, prevClip)
                : lastWorldPos;

        Vec3 nextPos = nextFrom != null
                ? interpolateWorldPosition(nextFrom, nextTo, nextS, nextClip)
                : lastWorldPos;

        Vec3 pos = new Vec3(
                prevPos.x * invWeight + nextPos.x * weight,
                prevPos.y * invWeight + nextPos.y * weight,
                prevPos.z * invWeight + nextPos.z * weight
        );

        float prevYawBase = prevFrom != null ? KeyframeInterpolator.interpolateYaw(prevFrom, prevTo, prevS) : 0f;
        float prevPitchBase = prevFrom != null ? KeyframeInterpolator.interpolatePitch(prevFrom, prevTo, prevS) : 0f;
        float nextYawBase = nextFrom != null ? KeyframeInterpolator.interpolateYaw(nextFrom, nextTo, nextS) : 0f;
        float nextPitchBase = nextFrom != null ? KeyframeInterpolator.interpolatePitch(nextFrom, nextTo, nextS) : 0f;
        float[] prevYp = segmentYawPitch(prevFrom, prevTo, prevS, prevClip, prevPos, prevYawBase, prevPitchBase);
        float[] nextYp = segmentYawPitch(nextFrom, nextTo, nextS, nextClip, nextPos, nextYawBase, nextPitchBase);
        float yaw = blendAngle(prevYp[0], nextYp[0], weight);
        float pitch = blendFloat(prevYp[1], nextYp[1], weight);
        float roll = blendAngle(
                prevFrom != null ? KeyframeInterpolator.interpolateRoll(prevFrom, prevTo, prevS) : 0f,
                nextFrom != null ? KeyframeInterpolator.interpolateRoll(nextFrom, nextTo, nextS) : 0f,
                weight);
        float fov = blendFloat(
                prevFrom != null ? KeyframeInterpolator.interpolateFov(prevFrom, prevTo, prevS) : 70f,
                nextFrom != null ? KeyframeInterpolator.interpolateFov(nextFrom, nextTo, nextS) : 70f,
                weight);
        float zoom = blendFloat(
                prevFrom != null ? KeyframeInterpolator.interpolateZoom(prevFrom, prevTo, prevS) : 1f,
                nextFrom != null ? KeyframeInterpolator.interpolateZoom(nextFrom, nextTo, nextS) : 1f,
                weight);

        // ====== Breath disturbance ======
        if (prevClip.getBool("cam_breath_enabled", false)) {
            float intensity = prevClip.getFloat("cam_breath_intensity", 0.05f);
            int seed = prevClip.getInt("cam_breath_seed", 0);
            long timeSeed = (long)(globalTime * 100) + seed;
            Random rng = new Random(timeSeed);
            float yawJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            float pitchJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            float rollJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            yaw += yawJitter;
            pitch += pitchJitter;
            roll += rollJitter;
        }
        // ====== End breath ======

        cameraManager.getPath().setPositionDirect(pos);
        cameraManager.getProperties().setAllDirect(yaw, pitch, roll, fov, zoom);
        lastWorldPos = pos;
    }

    /**
     * 关键帧世界坐标求值：
     * follow=entity → 实体渲染帧插值位置 + position 偏移（动态，每帧重算；实体消失时停在上一帧位置）
     * 普通关键帧    → position 对象自描述：absolute = 世界坐标；relative = 相对基准 + 偏移
     *                （基准默认玩家激活位置 originPos，可用 relative_origin 指定坐标/结构中心）
     */
    private Vec3 evalKeyframeWorldPos(Keyframe kf, Clip clip) {
        if ("entity".equals(kf.getString("follow", "none"))) {
            Entity target = resolveEntity(kf.getString("follow_selector", "@p"), lastWorldPos);
            if (target != null) {
                PositionData pd = kf.getPosition();
                Vec3 off = pd != null ? pd.toVec3() : Vec3.ZERO;
                return entityPosInterp(target).add(off);
            }
            return lastWorldPos;
        }
        PositionData pd = kf.getPosition();
        Vec3 p = pd != null ? pd.toVec3() : Vec3.ZERO;
        if (pd == null || !pd.isRelative()) return p;
        // 相对基准：relative_origin = "coordinate"（固定坐标）/ 结构 id（结构中心）/ 默认玩家激活位置
        return resolveRelativeBase(pd).add(p);
    }

    /** 相对基准求值：coordinate → 固定坐标；结构 id → 结构中心（找不到回退玩家位置 + warn）；默认玩家激活位置 */
    private Vec3 resolveRelativeBase(PositionData pd) {
        if (pd.isOriginCoordinate()) {
            return new Vec3(pd.getOriginX(), pd.getOriginY(), pd.getOriginZ());
        }
        String structureId = pd.getOriginStructure();
        if (structureId != null && !structureId.isEmpty()) {
            Vec3 structurePos = resolveStructurePos(structureId);
            if (structurePos != null) return structurePos;
            LOGGER.warn("相对基准结构 '{}' 未找到，回退玩家激活位置", structureId);
        }
        return originPos;
    }

    /** 结构坐标缓存条目 */
    private static class StructurePosCache {
        Vec3 pos;
        long resolvedAt;
    }

    /** 结构坐标缓存（structure id → 解析结果，1 秒有效） */
    private final java.util.Map<String, StructurePosCache> structureCache = new java.util.HashMap<>();

    /**
     * 结构坐标解析（客户端）：单人/集成服务器（含编辑器预览）直接访问服务端 level，
     * 用原版 findNearestMapStructure（/locate 同源）定位结构中心。结果 1 秒缓存。
     * 多人服务器（无服务端访问）或找不到返回 null（调用方回退 xyz）。
     * 注：服务端 /icinematics play 推送前已把 look_at_target_structure 替换为坐标，此路径主要为编辑器预览兜底。
     */
    private Vec3 resolveStructurePos(String structureId) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        long now = System.currentTimeMillis();
        StructurePosCache cached = structureCache.get(structureId);
        if (cached != null && now - cached.resolvedAt < 1000) {
            return cached.pos;
        }
        Vec3 result = null;
        try {
            net.minecraft.server.MinecraftServer singleplayer = mc.getSingleplayerServer();
            if (singleplayer == null) {
                LOGGER.warn("多人服务器无法解析结构 '{}'（服务端 play 推送会替换为坐标；编辑器预览仅限单人）", structureId);
            } else {
                net.minecraft.server.level.ServerLevel serverLevel = singleplayer.getLevel(mc.level.dimension());
                if (serverLevel != null) {
                    result = com.immersivecinematics.immersive_cinematics.util.StructureLocator.locateCenter(
                            serverLevel, structureId,
                            net.minecraft.core.BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 100);
                    if (result == null) {
                        LOGGER.warn("结构 '{}' 在搜索半径内未找到（原版 /locate 同范围）", structureId);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("结构坐标解析失败 '{}': {}", structureId, e.getMessage());
        }
        StructurePosCache entry = new StructurePosCache();
        entry.pos = result;
        entry.resolvedAt = now;
        structureCache.put(structureId, entry);
        return result;
    }

    /**
     * 关键帧 look_at 目标点求值：
     * entity     → 实体正中心（渲染帧插值位置 + 半高，动态）
     * coordinate → 固定坐标点
     * none       → 由该关键帧 yaw/pitch 决定的 100 格方向远点（看向它 = 保持该朝向）
     * 返回 null 表示该端无法求值（实体消失），调用方回退角度插值。
     */
    private Vec3 evalLookTarget(Keyframe kf, Clip clip, Vec3 pos) {
        String lookAt = kf.getString("look_at", "none");
        if ("entity".equals(lookAt)) {
            Entity target = resolveEntity(kf.getString("look_at_selector", "@p"), pos);
            return target != null
                    ? entityPosInterp(target).add(0, target.getBbHeight() / 2.0, 0)
                    : null;
        }
        if ("coordinate".equals(lookAt)) {
            String structureId = kf.getString("look_at_target_structure", "");
            if (!structureId.isEmpty()) {
                Vec3 structurePos = resolveStructurePos(structureId);
                if (structurePos != null) return structurePos;
                LOGGER.warn("结构 '{}' 未在附近已加载区块中找到，回退 look_at_target_xyz", structureId);
            }
            return new Vec3(
                    kf.getFloat("look_at_target_x", 0),
                    kf.getFloat("look_at_target_y", 64),
                    kf.getFloat("look_at_target_z", 0));
        }
        // none：关键帧朝向的 100 格远点（MC 视线方向 forwards = (-sin yaw·cos pitch, sin pitch, cos yaw·cos pitch)）
        double yawRad = Math.toRadians(kf.getYaw());
        double pitchRad = Math.toRadians(kf.getPitch());
        double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fy = Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * Math.cos(pitchRad);
        return pos.add(fx * 100, fy * 100, fz * 100);
    }

    /**
     * 世界坐标空间插值：两端关键帧各自求值成世界坐标后按路径策略插值。
     * 任一端为 follow（动态目标）时强制 linear（曲线控制点对动态实体无意义）。
     * 由此 follow↔普通、换实体、换偏移的过渡天然平滑（两端都是世界坐标）。
     */
    private Vec3 interpolateWorldPosition(Keyframe from, Keyframe to, float s, Clip clip) {
        Vec3 p0 = evalKeyframeWorldPos(from, clip);
        Vec3 p3 = evalKeyframeWorldPos(to, clip);
        boolean anyFollow = "entity".equals(from.getString("follow", "none"))
                || "entity".equals(to.getString("follow", "none"));
        PathStrategy strategy = anyFollow ? PathStrategies.get("linear") : bezierStrategy;
        return strategy.interpolate(p0, p3, s, anyFollow ? null : clip.getCurve());
    }

    /**
     * 单段朝向求值：任一端 look_at != none 时用目标点插值模型（看向插值目标点），
     * 否则回退角度插值。返回 [yaw, pitch]。
     */
    private float[] segmentYawPitch(Keyframe from, Keyframe to, float s, Clip clip, Vec3 segPos,
                                    float yawFallback, float pitchFallback) {
        if (from != null && to != null) {
            boolean anyLook = !"none".equals(from.getString("look_at", "none"))
                    || !"none".equals(to.getString("look_at", "none"));
            if (anyLook) {
                Vec3 t0 = evalLookTarget(from, clip, segPos);
                Vec3 t1 = evalLookTarget(to, clip, segPos);
                if (t0 != null && t1 != null) {
                    Vec3 target = new Vec3(t0.x + (t1.x - t0.x) * s, t0.y + (t1.y - t0.y) * s, t0.z + (t1.z - t0.z) * s);
                    double dx = target.x - segPos.x;
                    double dy = target.y - segPos.y;
                    double dz = target.z - segPos.z;
                    float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    // 诊断：look_at 计算链路（相机位置 / 目标点 / 算出的角度），1 秒节流
                    long now = System.currentTimeMillis();
                    if (now - lastLookAtLog >= 1000) {
                        lastLookAtLog = now;
                        LOGGER.info("LOOK_AT: cam=({}, {}, {}) target=({}, {}, {}) yaw={} pitch={}",
                                String.format("%.2f", segPos.x), String.format("%.2f", segPos.y), String.format("%.2f", segPos.z),
                                String.format("%.2f", target.x), String.format("%.2f", target.y), String.format("%.2f", target.z),
                                String.format("%.2f", yaw), String.format("%.2f", pitch));
                    }
                    return new float[]{yaw, pitch};
                }
            }
        }
        return new float[]{yawFallback, pitchFallback};
    }

    private void writeAttributes(Keyframe from, Keyframe to, float s, Clip clip, float globalTime) {
        Vec3 pos = interpolateWorldPosition(from, to, s, clip);
        float yawBase = KeyframeInterpolator.interpolateYaw(from, to, s);
        float pitchBase = KeyframeInterpolator.interpolatePitch(from, to, s);
        float roll = KeyframeInterpolator.interpolateRoll(from, to, s);
        float fov = KeyframeInterpolator.interpolateFov(from, to, s);
        float zoom = KeyframeInterpolator.interpolateZoom(from, to, s);

        // ====== look_at 目标点插值模型 ======
        // 关键帧 look_at 定义"目标点"（entity=实体正中心、coordinate=固定点、none=由该关键帧 yaw/pitch 决定的方向远点）。
        // 目标点在关键帧间插值后相机看向插值点——look_at 切换/开关天然平滑；两端都 none 时保持角度插值（零回归）。
        float[] yp = segmentYawPitch(from, to, s, clip, pos, yawBase, pitchBase);
        float yaw = yp[0];
        float pitch = yp[1];
        // ====== End look_at ======
        // ====== Breath disturbance ======
        if (clip.getBool("cam_breath_enabled", false)) {
            float intensity = clip.getFloat("cam_breath_intensity", 0.05f);
            int seed = clip.getInt("cam_breath_seed", 0);
            long timeSeed = (long)(globalTime * 100) + seed;
            Random rng = new Random(timeSeed);
            float yawJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            float pitchJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            float rollJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            yaw += yawJitter;
            pitch += pitchJitter;
            roll += rollJitter;
        }
        // ====== End breath ======

        cameraManager.getPath().setPositionDirect(pos);
        cameraManager.getProperties().setAllDirect(yaw, pitch, roll, fov, zoom);
        lastWorldPos = pos;
    }

    @Override
    public void onStop() {
        lastClipIndex = 0;
        // bezierStrategy 随 TrackPlayer 实例一起被 GC，其 LUT 缓存自动释放
    }

    /** 组 A：数据替换后复位 clip 索引状态 */
    @Override
    public void onScriptReplaced() {
        lastClipIndex = 0;
        targetCache.clear();
    }

    private Clip findActiveClip(float globalTime) {
        List<Clip> clips = clips();
        if (clips.isEmpty()) return null;

        Clip result = null;
        int resultIndex = -1;
        int startIdx = Math.max(0, Math.min(lastClipIndex, clips.size() - 1));

        for (int i = startIdx; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            float clipEnd = clip.getWindowEnd();

            if (clip.isEffectivelyInfinite()) {
                if (globalTime >= clip.getStartTime()) {
                    result = clip;
                    resultIndex = i;
                }
                continue;
            }

            if (globalTime >= clip.getStartTime() && globalTime < clipEnd) {
                lastClipIndex = i;
                return clip;
            }
        }

        for (int i = 0; i < startIdx; i++) {
            Clip clip = clips.get(i);
            float clipEnd = clip.getWindowEnd();

            if (clip.isEffectivelyInfinite()) {
                if (globalTime >= clip.getStartTime()) {
                    result = clip;
                    resultIndex = i;
                }
                continue;
            }

            if (globalTime >= clip.getStartTime() && globalTime < clipEnd) {
                lastClipIndex = i;
                return clip;
            }
        }

        if (result != null) {
            lastClipIndex = resultIndex;
            return result;
        }
        return null;
    }

    private static float blendFloat(float a, float b, float weight) {
        return a * (1f - weight) + b * weight;
    }

    private static float blendAngle(float a, float b, float weight) {
        float diff = ((b - a) % 360f + 540f) % 360f - 180f;
        return a + diff * weight;
    }

    private static Vec3 blendVec3(Vec3 a, Vec3 b, float weight) {
        float inv = 1f - weight;
        return new Vec3(
                a.x * inv + b.x * weight,
                a.y * inv + b.y * weight,
                a.z * inv + b.z * weight
        );
    }

    /** 非玩家目标缓存（范围内唯一目标不会频繁切换，避免每帧全量遍历实体列表） */
    private Entity cachedTarget;
    private long cachedTargetResolvedAt;

    /**
     * 解析目标实体（原版 EntitySelectorParser 语义的子集，就近优先）：
     * @p / @s        = 玩家（原版 @p ORDER_NEAREST limit 1 的等价简化）
     * @e             = 范围内按离 origin 最近取 1 个活实体
     * @e[type=…]     = 按实体类型过滤后就近取 1（如 minecraft:sheep / 模组 boss id）
     * @e[name=…]     = 按自定义名过滤后就近取 1
     * uuid:xxxxxxxx  = UUID 直绑（唯一确定，不排序）
     * 解析失败或无匹配返回 null：follow 停在上一帧位置、look_at 不生效
     */
    private net.minecraft.world.entity.Entity resolveEntity(String selector, Vec3 origin) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if ("@p".equals(selector) || "@s".equals(selector)) {
            return mc.player;
        }

        // 非玩家目标：按 selector 分键缓存 1 秒（目标仍存活时复用，避免每帧全量遍历）
        long now = System.currentTimeMillis();
        CachedTarget cached = targetCache.get(selector);
        if (cached != null && cached.entity.isAlive() && now - cached.resolvedAt < 1000) {
            return cached.entity;
        }

        net.minecraft.world.entity.Entity found = null;
        if (selector.startsWith("uuid:")) {
            try {
                java.util.UUID uuid = java.util.UUID.fromString(selector.substring(5));
                for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                    if (uuid.equals(e.getUUID())) {
                        found = e;
                        break;
                    }
                }
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("无效的实体 UUID selector: {}", selector);
            }
        } else if ("@e".equals(selector) || selector.startsWith("@e[")) {
            String typeId = null;
            String name = null;
            if (selector.startsWith("@e[")) {
                String inner = selector.substring(3, selector.length() - 1);
                for (String kv : inner.split(",")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String key = kv.substring(0, eq).trim();
                    String val = kv.substring(eq + 1).trim();
                    if ("type".equals(key)) typeId = val;
                    else if ("name".equals(key)) name = val;
                    // 未知选项忽略（容错，不崩溃）
                }
            }
            final String fType = typeId;
            final String fName = name;
            double bestDist = Double.MAX_VALUE;
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (!e.isAlive()) continue;
                if (fType != null && !fType.equals(net.minecraft.world.entity.EntityType.getKey(e.getType()).toString())) continue;
                if (fName != null && (e.getCustomName() == null || !fName.equals(e.getCustomName().getString()))) continue;
                double dist = e.distanceToSqr(origin);
                if (dist < bestDist) {
                    bestDist = dist;
                    found = e;
                }
            }
        } else {
            LOGGER.warn("不支持的实体 selector: {}（支持 @p/@s/@e/@e[type=…,name=…]/uuid:xxx）", selector);
        }

        CachedTarget entry = new CachedTarget();
        entry.entity = found;
        entry.resolvedAt = now;
        targetCache.put(selector, entry);
        return found;
    }

    /** 实体渲染帧插值位置（上一 tick → 当前 tick 按渲染 partialTick 插值，消除 20Hz 步进卡顿） */
    private static Vec3 entityPosInterp(net.minecraft.world.entity.Entity e) {
        float pt = net.minecraft.client.Minecraft.getInstance().getFrameTime();
        return new Vec3(
                net.minecraft.util.Mth.lerp(pt, e.xo, e.getX()),
                net.minecraft.util.Mth.lerp(pt, e.yo, e.getY()),
                net.minecraft.util.Mth.lerp(pt, e.zo, e.getZ())
        );
    }
}
