package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

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

    /** 诊断：look_at 目标位置一次性日志（播放期间只打印 1 次） */
    private boolean lookAtLoggedOnce;
    /** 结构定位失败提示只打一次 */
    private boolean lookAtWarnOnce;
    /** 片段目标不可用（按空片段处理）提示只打一次 */
    private boolean clipUnusableWarnOnce;

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
                    // 进入的片段目标不可用 → 整个转场按空处理
                    if (!isClipUsable(next)) {
                        warnClipUnusableOnce();
                        return;
                    }
                    float weight = (globalTime - morphStart) / prev.getTransitionDuration();
                    renderMorph(prev, next, weight, globalTime);
                    return;
                }
            }
        }

        Clip primaryClip = findActiveClip(globalTime);
        if (primaryClip == null) return;
        // 目标不可用（结构/实体找不到）= 该片段按空处理（不写相机 → 玩家视角，与片段间隙同语义）
        if (!isClipUsable(primaryClip)) {
            warnClipUnusableOnce();
            return;
        }

        float clipLocalTime = globalTime - primaryClip.getStartTime();
        renderSingle(globalTime, primaryClip, clipLocalTime);
    }

    /** 片段目标不可用提示只打一次（debug 级：作者排查可见，不打扰玩家） */
    private void warnClipUnusableOnce() {
        if (!clipUnusableWarnOnce) {
            clipUnusableWarnOnce = true;
            LOGGER.debug("片段目标不可用（结构/实体未找到），该片段按空处理（玩家视角）");
        }
    }

    /**
     * 片段目标可用性：look_at/follow 的实体、结构目标、position.relative_origin 结构/方块基准
     * 任一不可解析 → 片段不可用，按空片段处理（不写相机，玩家视角）。
     * 找不到就找不到——不引入任何替代值/回退逻辑。
     */
    private boolean isClipUsable(Clip clip) {
        for (Keyframe kf : clip.getKeyframes()) {
            String lookAt = kf.getString("look_at", "none");
            if ("entity".equals(lookAt)) {
                if (resolveEntity(kf.getString("look_at_selector", "@p"), lastWorldPos) == null) return false;
            } else if ("coordinate".equals(lookAt)) {
                String sid = kf.getString("look_at_target_structure", "");
                if (!sid.isEmpty() && resolveStructurePos(sid) == null) return false;
                // 相对目标对象的实体基准不存在 → 该端无目标，片段按空处理
                Object targetObj = kf.getObject("look_at_target");
                if (targetObj instanceof Map<?, ?> m) {
                    Object relTo = m.get("relative_to");
                    if (relTo != null && !"coordinate".equals(relTo)
                            && resolveEntity(String.valueOf(relTo), lastWorldPos) == null) {
                        return false;
                    }
                }
            }
            if ("entity".equals(kf.getString("follow", "none"))) {
                if (resolveEntity(kf.getString("follow_selector", "@p"), lastWorldPos) == null) return false;
            }
            // 朝向基准（yaw_base/pitch_base）：entity 实体缺失 / line 端点缺失 → 空片段
            String yawBase = kf.getString("yaw_base", "world");
            String pitchBase = kf.getString("pitch_base", "world");
            if ("entity".equals(yawBase) || "entity".equals(pitchBase)) {
                if (resolveEntity(kf.getString("yaw_base_selector", "@p"), lastWorldPos) == null) return false;
            } else if ("line".equals(yawBase) || "line".equals(pitchBase)) {
                if (resolveEntity(kf.getString("yaw_base_from", ""), lastWorldPos) == null
                        || resolveEntity(kf.getString("yaw_base_to", ""), lastWorldPos) == null) return false;
            }
            PositionData pd = kf.getPosition();
            if (pd != null && pd.isRelative()) {
                String sid = pd.getOriginStructure();
                if (sid != null && !sid.isEmpty() && resolveStructurePos(sid) == null) return false;
                if (pd.isOriginBlock()) {
                    if (resolveBlockPos(pd.getOriginBlockId(), pd.getOriginBlockRadius()) == null) return false;
                }
            }
        }
        return true;
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
        float zoom = blendZoom(
                prevFrom != null ? KeyframeInterpolator.interpolateZoom(prevFrom, prevTo, prevS) : 1f,
                nextFrom != null ? KeyframeInterpolator.interpolateZoom(nextFrom, nextTo, nextS) : 1f,
                weight);

        // ====== Breath disturbance (v2: 按 cam_breath_type 分派, 确定性) ======
        if (prevClip.getBool("cam_breath_enabled", false)) {
            float[] jitter = BreathDisturbance.fromClip(prevClip).compute(globalTime);
            yaw += jitter[0];
            pitch += jitter[1];
            roll += jitter[2];
        }
        // ====== End breath ======

        cameraManager.getPath().setPositionDirect(pos);
        cameraManager.getProperties().setAllDirect(yaw, pitch, roll, fov, zoom);
        lastWorldPos = pos;
    }

    /**
     * 关键帧世界坐标求值：
     * follow=entity → 实体渲染帧插值位置 + position 偏移（动态，每帧重算）
     * 普通关键帧    → position 对象自描述：absolute = 世界坐标；relative = 相对基准 + 偏移
     *                （基准默认玩家激活位置 originPos，可用 relative_origin 指定坐标/结构中心）
     * 注意：实体/结构目标不可用已在 isClipUsable 前置拦截（该片段按空处理），此处分支为防御。
     */
    private Vec3 evalKeyframeWorldPos(Keyframe kf, Clip clip) {
        PositionData pd = kf.getPosition();
        // 基准空间坐标系偏移（fwd/up/right）：基准 = follow 的实体 或 玩家（实时朝向，三维旋转）
        if (pd != null && pd.isFacingRelative()) {
            return evalFacingOffset(kf, pd);
        }
        if ("entity".equals(kf.getString("follow", "none"))) {
            Entity target = resolveEntity(kf.getString("follow_selector", "@p"), lastWorldPos);
            if (target != null) {
                Vec3 off = pd != null ? pd.toVec3() : Vec3.ZERO;
                return entityPosInterp(target).add(off);
            }
            return lastWorldPos;
        }
        Vec3 p = pd != null ? pd.toVec3() : Vec3.ZERO;
        if (pd == null || !pd.isRelative()) return p;
        // 相对基准：relative_origin = "coordinate"（固定坐标）/ 结构 id（结构中心）/ 默认玩家激活位置
        return resolveRelativeBase(pd).add(p);
    }

    /**
     * 基准空间坐标系求值：基准点 = 玩家/实体眼睛高度；三轴按基准实时朝向（yaw+pitch）旋转。
     * - fwd/right 始终随朝向水平旋转（前/后 & 左/右）
     * - up 轴由 up_axis 控制："view"=随俯仰全三维（默认）；"world"=保持世界竖直
     * 基准 = follow 实体（其实时视线）或 玩家（实时视线；鼠标未锁则能转着跟，被锁则静止）。
     */
    private Vec3 evalFacingOffset(Keyframe kf, PositionData pd) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        Entity base = null;
        if ("entity".equals(kf.getString("follow", "none"))) {
            base = resolveEntity(kf.getString("follow_selector", "@p"), lastWorldPos);
        } else if (mc.player != null) {
            base = mc.player;
        }
        if (base == null) {
            LOGGER.warn("基准空间偏移：基准实体/玩家不可用（防御路径，按当前视点处理）");
            return lastWorldPos;
        }
        Vec3 basePos = entityPosInterp(base);
        double eyeY = base instanceof net.minecraft.world.entity.LivingEntity le
                ? basePos.y + le.getEyeHeight()
                : basePos.y + 2.0;
        Vec3 origin = new Vec3(basePos.x, eyeY, basePos.z);

        float yawRad = (float) Math.toRadians(base.getYRot());
        float pitchRad = (float) Math.toRadians(base.getXRot());
        boolean viewUp = "view".equals(pd.getUpAxis());
        // 正交基（对齐 MC Entity.calculateViewVector / Camera up-left-look；参考 ShoulderSurfing 本地偏移→世界）：
        //   look  = ( -sinY·cosP, -sinP, cosY·cosP )   ← pitch 的 Y 分量带负号（俯视时 look 向下）
        //   right = ( -cosY, 0, -sinY )                 ← 与 look 恒正交
        //   up    = right × look（view 模式；pitch=0 时恒 (0,1,0)，稳定不翻转）
        Vec3 fwdVec;
        Vec3 upVec;
        Vec3 rightVec = new Vec3(-Math.cos(yawRad), 0, -Math.sin(yawRad));
        if (viewUp) {
            fwdVec = new Vec3(
                    -Math.sin(yawRad) * Math.cos(pitchRad),
                    -Math.sin(pitchRad),
                    Math.cos(yawRad) * Math.cos(pitchRad));
            upVec = rightVec.cross(fwdVec).normalize();
        } else {
            // up 保持世界竖直：只水平转（fwd 不带俯仰）
            fwdVec = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
            upVec = new Vec3(0, 1, 0);
        }
        return origin
                .add(fwdVec.scale(pd.getFwd()))
                .add(rightVec.scale(pd.getRight()))
                .add(upVec.scale(pd.getUp()));
    }

    /**
     * 相对基准求值：coordinate → 固定坐标；结构 id → 结构中心；默认玩家激活位置。
     * 结构基准不可用已在 isClipUsable 前置拦截（该片段按空处理），此处为防御。
     */
    private Vec3 resolveRelativeBase(PositionData pd) {
        if (pd.isOriginCoordinate()) {
            return new Vec3(pd.getOriginX(), pd.getOriginY(), pd.getOriginZ());
        }
        String structureId = pd.getOriginStructure();
        if (structureId != null && !structureId.isEmpty()) {
            Vec3 structurePos = resolveStructurePos(structureId);
            if (structurePos != null) return structurePos;
            LOGGER.debug("相对基准结构 '{}' 未找到（防御路径）", structureId);
        }
        if (pd.isOriginBlock()) {
            Vec3 blockPos = resolveBlockPos(pd.getOriginBlockId(), pd.getOriginBlockRadius());
            if (blockPos != null) return blockPos;
            LOGGER.warn("相对基准方块 '{}' 未找到（半径 {}，防御路径，片段按空处理）",
                    pd.getOriginBlockId(), pd.getOriginBlockRadius());
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

    /** 方块基准缓存条目 */
    private static class BlockPosCache {
        Vec3 pos;
        long resolvedAt;
    }

    /** 方块基准缓存（blockId:radius → 块中心坐标；方块静态：成功永久缓存，失败 2 秒重试） */
    private final java.util.Map<String, BlockPosCache> blockCache = new java.util.HashMap<>();

    /**
     * 结构坐标解析（客户端）：单人/集成服务器（含编辑器预览）直接访问服务端 level，
     * 用原版 findNearestMapStructure（/locate 同源）定位结构中心。
     * 结构是静态目标：解析成功的结果永久缓存（整场播放直接复用，无需刷新——与实体目标不同）；
     * 失败结果 2 秒后重试（结构可能随后被生成/加载）。
     * 多人服务器（无服务端访问）或找不到返回 null——调用方（isClipUsable）按空片段处理，不引入替代值。
     * 注：服务端 /icinematics play 推送前已把 look_at_target_structure 替换为坐标，此路径主要为编辑器预览兜底。
     */
    private Vec3 resolveStructurePos(String structureId) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        long now = System.currentTimeMillis();
        StructurePosCache cached = structureCache.get(structureId);
        if (cached != null && (cached.pos != null || now - cached.resolvedAt < 2000)) {
            return cached.pos;
        }
        Vec3 result = null;
        try {
            net.minecraft.server.MinecraftServer singleplayer = mc.getSingleplayerServer();
            if (singleplayer == null) {
                LOGGER.debug("多人服务器无法解析结构 '{}'（服务端 play 推送会替换为坐标；编辑器预览仅限单人）", structureId);
            } else {
                net.minecraft.server.level.ServerLevel serverLevel = singleplayer.getLevel(mc.level.dimension());
                if (serverLevel != null) {
                    result = com.immersivecinematics.immersive_cinematics.util.StructureLocator.locateCenter(
                            serverLevel, structureId,
                            net.minecraft.core.BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 3);
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
     * 方块基准定位（客户端）：单人/集成服务器直接访问服务端 level，玩家附近搜索最近匹配方块，返回方块中心坐标。
     * 方块静态：成功永久缓存，失败 2 秒后重试（与结构基准同语义）。
     * 多人（无服务端访问）或找不到返回 null——调用方 isClipUsable 按空片段处理，不引入替代值。
     * 注：服务端 /icinematics play 推送前已把 block relative_origin 替换为坐标，此路径主要为编辑器预览兜底。
     */
    private Vec3 resolveBlockPos(String blockId, int radius) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        long now = System.currentTimeMillis();
        String key = blockId + ":" + radius;
        BlockPosCache cached = blockCache.get(key);
        if (cached != null && (cached.pos != null || now - cached.resolvedAt < 2000)) {
            return cached.pos;
        }
        net.minecraft.core.BlockPos result = null;
        try {
            net.minecraft.server.MinecraftServer singleplayer = mc.getSingleplayerServer();
            if (singleplayer == null) {
                LOGGER.debug("多人服务器无法解析方块基准 '{}'（服务端 play 推送会替换为坐标；编辑器预览仅限单人）", blockId);
            } else {
                net.minecraft.server.level.ServerLevel serverLevel = singleplayer.getLevel(mc.level.dimension());
                if (serverLevel != null) {
                    result = com.immersivecinematics.immersive_cinematics.util.BlockLocator.findNearest(
                            serverLevel, blockId,
                            net.minecraft.core.BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()),
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

    /**
     * 关键帧 look_at 目标点求值：
     * entity     → 实体正中心（渲染帧插值位置 + 半高，动态）
     * coordinate → 固定坐标点（与结构互斥：指定结构后只解析结构）
     * none       → 由该关键帧 yaw/pitch 决定的 100 格方向远点（看向它 = 保持该朝向）
     * 返回 null 表示该端无注视目标（实体消失 / 结构定位失败），该段按 look_at=none 处理（关键帧角度）。
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
                // 结构目标与坐标互斥：指定了结构就只用结构。定位失败返回 null（该端无注视目标），
                // 整个片段已被 isClipUsable 拦截按空处理，此处为防御。
                Vec3 structurePos = resolveStructurePos(structureId);
                if (structurePos != null) return structurePos;
                // 定位失败只提示一次（debug 级：作者排查可见，不打扰玩家）
                if (!lookAtWarnOnce) {
                    lookAtWarnOnce = true;
                    LOGGER.debug("结构 '{}' 未找到（该端无注视目标）", structureId);
                }
                return null;
            }
            // 相对目标对象（优先级高于散字段绝对坐标）：绝对点 / 触发点偏移 / 相对实体偏移 / 相对坐标点+偏移
            Object targetObj = kf.getObject("look_at_target");
            if (targetObj instanceof Map<?, ?> m) {
                Vec3 target = evalLookTargetObject(m, pos);
                if (target != null) return target;
                // 对象解析失败（相对实体找不到/基准缺失）→ 该端无注视目标（isClipUsable 已前置拦截，此处防御）
                return null;
            }
            return new Vec3(
                    kf.getFloat("look_at_target_x", 0),
                    kf.getFloat("look_at_target_y", 64),
                    kf.getFloat("look_at_target_z", 0));
        }
        // none：关键帧朝向的 100 格远点（MC 视线方向 forwards = (-sin yaw·cos pitch, -sin pitch, cos yaw·cos pitch)）
        double yawRad = Math.toRadians(kf.getYaw());
        double pitchRad = Math.toRadians(kf.getPitch());
        double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fy = -Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * Math.cos(pitchRad);
        return pos.add(fx * 100, fy * 100, fz * 100);
    }

    /**
     * look_at_target 对象目标点求值（四种模式）：
     * <ul>
     *   <li>{x,y,z}                                → 世界绝对坐标点</li>
     *   <li>{dx,dy,dz}                             → 相对触发点（脚本激活时玩家位置）偏移</li>
     *   <li>{relative_to:&lt;selector&gt;, dx..}    → 相对实体位置偏移（每帧求值，动态）</li>
     *   <li>{relative_to:"coordinate", relative_x/y/z, dx..} → 相对固定坐标点 + 偏移</li>
     * </ul>
     * 返回 null = 该端无注视目标（相对实体找不到等；isClipUsable 已前置拦截，此处防御）。
     */
    private Vec3 evalLookTargetObject(Map<?, ?> m, Vec3 pos) {
        Float x = numOrNull(m.get("x"));
        Float y = numOrNull(m.get("y"));
        Float z = numOrNull(m.get("z"));
        if (x != null && y != null && z != null) {
            return new Vec3(x, y, z);
        }
        float dx = numOrDefault(m.get("dx"));
        float dy = numOrDefault(m.get("dy"));
        float dz = numOrDefault(m.get("dz"));
        Object relTo = m.get("relative_to");
        if (relTo == null) {
            // 相对触发点偏移
            return originPos.add(dx, dy, dz);
        }
        if ("coordinate".equals(relTo)) {
            double rx = numOrDefault(m.get("relative_x"));
            double ry = numOrDefault(m.get("relative_y"));
            double rz = numOrDefault(m.get("relative_z"));
            return new Vec3(rx + dx, ry + dy, rz + dz);
        }
        // 相对实体 selector（每帧求实体位置 + 偏移）
        Entity target = resolveEntity(String.valueOf(relTo), pos);
        return target != null ? entityPosInterp(target).add(dx, dy, dz) : null;
    }

    private static Float numOrNull(Object o) {
        return o instanceof Number n ? n.floatValue() : null;
    }

    private static float numOrDefault(Object o) {
        return o instanceof Number n ? n.floatValue() : 0f;
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
     * 单段朝向求值：任一端 look_at != none 时用目标点插值模型（看向插值目标点）。
     * 两端目标齐全才用目标点；目标缺失（evalLookTarget 返回 null）时该段按 look_at=none 处理
     * ——用两端关键帧自身的 yaw/pitch 角度插值（yawBase/pitchBase）。目标不可用已由
     * isClipUsable 前置拦截（片段按空处理），此处为防御。返回 [yaw, pitch]。
     */
    private float[] segmentYawPitch(Keyframe from, Keyframe to, float s, Clip clip, Vec3 segPos,
                                    float yawBase, float pitchBase) {
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
                    // 诊断：look_at 目标位置，播放期间只打印 1 次
                    if (!lookAtLoggedOnce) {
                        lookAtLoggedOnce = true;
                        LOGGER.info("LOOK_AT_ONCE: cam=({}, {}, {}) target=({}, {}, {}) yaw={} pitch={}",
                                String.format("%.2f", segPos.x), String.format("%.2f", segPos.y), String.format("%.2f", segPos.z),
                                String.format("%.2f", target.x), String.format("%.2f", target.y), String.format("%.2f", target.z),
                                String.format("%.2f", yaw), String.format("%.2f", pitch));
                    }
                    return new float[]{yaw, pitch};
                }
            }
        }
        // 目标缺失 / 两端都 look_at=none：关键帧自身角度 + 朝向基准（yaw_base/pitch_base，world/entity/line）
        // 做法：两端关键帧各自算"最终世界角度 = 基准 + 偏移"，再做角度插值——
        // 基准切换（world↔entity）自然过渡，实体转头时 finalYaw 每帧跟实体视线。
        if (from != null && to != null) {
            float yawA = finalYaw(from);
            float pitchA = finalPitch(from);
            float yawB = finalYaw(to);
            float pitchB = finalPitch(to);
            return new float[]{
                    blendAngle(yawA, yawB, s),
                    blendFloat(pitchA, pitchB, s)
            };
        }
        // 单端防御：退回调用方传入的插值角
        return new float[]{yawBase, pitchBase};
    }

    /** 单关键帧最终世界 yaw = 基准方向 + 偏移（look_at=none 语义） */
    private float finalYaw(Keyframe kf) {
        return yawBaseOf(kf) + kf.getYaw();
    }

    /** 单关键帧最终世界 pitch = 基准俯仰 + 偏移 */
    private float finalPitch(Keyframe kf) {
        return pitchBaseOf(kf) + kf.getPitch();
    }

    /**
     * 关键帧 yaw 基准方向：yaw_base = world（0，现状）| entity（实体视线水平角 getYRot）| line（from→to 连线水平角）。
     * 实体缺失/line 端点缺失时 isClipUsable 已前置拦截为空片段；此处防御回退 0（=world）。
     */
    private float yawBaseOf(Keyframe kf) {
        String base = kf.getString("yaw_base", "world");
        if ("entity".equals(base)) {
            Entity e = resolveEntity(kf.getString("yaw_base_selector", "@p"), lastWorldPos);
            return e != null ? e.getYRot() : 0f;
        }
        if ("line".equals(base)) {
            float[] dir = lineDir(kf);
            if (dir != null) return dir[0];
        }
        return 0f;
    }

    /** 关键帧 pitch 基准俯仰：pitch_base = world（0）| entity（实体视线俯仰 getXRot）| line（连线垂直角） */
    private float pitchBaseOf(Keyframe kf) {
        String base = kf.getString("pitch_base", "world");
        if ("entity".equals(base)) {
            Entity e = resolveEntity(kf.getString("yaw_base_selector", "@p"), lastWorldPos);
            return e != null ? e.getXRot() : 0f;
        }
        if ("line".equals(base)) {
            float[] dir = lineDir(kf);
            if (dir != null) return dir[1];
        }
        return 0f;
    }

    /**
     * line 基准方向：yaw_base_from → yaw_base_to 两点连线方向（水平 yaw + 垂直 pitch）。
     * 两端点至少一个缺失（实体找不到）返回 null。
     */
    private float[] lineDir(Keyframe kf) {
        Entity a = resolveEntity(kf.getString("yaw_base_from", ""), lastWorldPos);
        Entity b = resolveEntity(kf.getString("yaw_base_to", ""), lastWorldPos);
        if (a == null || b == null) return null;
        Vec3 from = entityPosInterp(a);
        Vec3 to = entityPosInterp(b);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4 && Math.abs(dy) < 1.0E-4) return null;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
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
        // ====== Breath disturbance (v2: 按 cam_breath_type 分派, 确定性) ======
        if (clip.getBool("cam_breath_enabled", false)) {
            float[] jitter = BreathDisturbance.fromClip(clip).compute(globalTime);
            yaw += jitter[0];
            pitch += jitter[1];
            roll += jitter[2];
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

    private static float blendZoom(float a, float b, float weight) {
        if (a <= 0f || b <= 0f) return blendFloat(a, b, weight);
        return (float) Math.exp(Math.log(a) * (1f - weight) + Math.log(b) * weight);
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
                LOGGER.warn("无效的实体 UUID selector '{}': {}", selector, ex.getMessage());
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
