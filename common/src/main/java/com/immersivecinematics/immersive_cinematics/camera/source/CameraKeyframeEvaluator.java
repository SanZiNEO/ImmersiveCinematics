package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.script.BezierPathStrategy;
import com.immersivecinematics.immersive_cinematics.script.BreathDisturbance;
import com.immersivecinematics.immersive_cinematics.script.CameraLane;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.KeyframeInterpolator;
import com.immersivecinematics.immersive_cinematics.script.PathStrategies;
import com.immersivecinematics.immersive_cinematics.script.PathStrategy;
import com.immersivecinematics.immersive_cinematics.script.PositionData;
import com.immersivecinematics.immersive_cinematics.script.TangentOrientation;
import com.immersivecinematics.immersive_cinematics.util.TimeInterpolation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 相机关键帧求值：把脚本数据（片段 / 关键帧 / 目标源）算成六参数快照与 lane 快照（纯计算，不写全局相机）。
 *
 * 单位与坐标空间：位置为世界空间方块坐标；yaw / pitch / roll 为度——yaw = 身体朝向、pitch = 视线，
 * MC 视线方向 = {@code (-sin yaw·cos pitch, -sin pitch, cos yaw·cos pitch)}；时间参数为秒。
 * 数值契约：连线水平分量 < {@link #LINE_HORIZONTAL_EPSILON}（零长度 / 纯垂直线）时水平角未定义 →
 * 拒绝该朝向输入（回退基准 0 = world，限频告警）；目标不可解析 → 该段按空处理（返回 null / 基准 0），不引入替代值。
 * 状态所有权：{@link #lastWorldPos} 与基准坐标系（frameOrigin 起）是跨帧求值状态，仅本实例持有；
 * 重叠窗口的捕获求值经 {@link #snapshotFrameState()} / {@link #restoreFrameState()} 留底与回写，
 * 目标锁状态由 {@link EntityTargetResolver} 自行留底。
 * 分配 / 线程契约：仅客户端主线程，位于每帧求值路径；帧内路径不新建集合、不装箱。
 */
public final class CameraKeyframeEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraKeyframeEvaluator");

    /**
     * 连线方向退化阈值：水平分量 {@code sqrt(dx² + dz²)} 小于该值即「没有水平分量」——
     * 零长度线（水平 ≈ 0 且 |dy| ≈ 0）与纯垂直线（水平 ≈ 0、|dy| > 0）都落在这一侧，两者的水平角
     * 都未定义，一律拒绝该朝向输入（{@link #lineDir} / {@link #buildFrame}）；纯水平线不在这一侧。
     */
    private static final double LINE_HORIZONTAL_EPSILON = 1.0E-4;

    /** 退化连线告警限频窗口（毫秒） */
    private static final long DEGENERATE_LINE_WARN_INTERVAL_MS = 2000L;
    /** 上次退化连线告警时刻（毫秒） */
    private long lastDegenerateLineWarnAt;

    /** 脚本激活位置（触发点）：相对触发点偏移与相对基准的默认落点 */
    private final Vec3 originPos;
    /** 目标解析与锁定：selector → 实体 + 按 {@code role + selector} 维护的锁与切换平滑状态 */
    private final EntityTargetResolver entityResolver;
    /** 独立 Bezier 路径策略实例（弧长 LUT 缓存随本实例 GC） */
    private final PathStrategy bezierStrategy = new BezierPathStrategy();

    /** 上一帧最终世界坐标（实体目标消失时停在原地、以及作为 @e 就近基准） */
    private Vec3 lastWorldPos;

    /**
     * 本帧基准坐标系（由基准点 + 基准朝向建立），供其它通道复用：
     * 注视点偏移可以按基准坐标系表达（{@code look_at_target.space = "facing"}）。
     * 基准坐标系是"这一帧的基准"，只建一套，不按通道各建一套。
     */
    private Vec3 frameOrigin;
    private Vec3 frameFwd;
    private Vec3 frameRight;
    private Vec3 frameUp;
    private boolean frameValid;

    /** 捕获求值隔离基线：{@link #snapshotFrameState()} 留底，{@link #restoreFrameState()} 回写 */
    private Vec3 baselineOrigin;
    private Vec3 baselineFwd;
    private Vec3 baselineRight;
    private Vec3 baselineUp;
    private boolean baselineValid;

    /** 诊断：look_at 目标位置一次性日志（播放期间只打印 1 次） */
    private boolean lookAtLoggedOnce;
    /** 结构定位失败提示只打一次 */
    private boolean lookAtWarnOnce;
    /** 片段目标不可用（按空片段处理）提示只打一次 */
    private boolean clipUnusableWarnOnce;

    public CameraKeyframeEvaluator(EntityTargetResolver entityResolver, WorldPointLocator pointLocator,
                                   Vec3 originPos) {
        this.entityResolver = entityResolver;
        this.originPos = originPos;
        this.lastWorldPos = originPos;
        // 世界上下文共用同一实例：位置侧点源（结构 / 方块 / 玩家激活位置）由 EntityTargetResolver 解析
        entityResolver.attachWorldContext(pointLocator, originPos);
    }

    /**
     * 本轨在 globalTime 是否有画面：有活跃片段（含无限片段），或落在某 morph 转场窗口
     * {@code [end−t/2, end+t/2)} 内即为真。
     *
     * @param clips 本轨片段（轨道顺序）
     */
    public boolean isActiveAt(List<Clip> clips, float globalTime) {
        Clip c = findActiveClip(clips, globalTime);
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

    /**
     * 片段目标可用性：look_at/follow 的实体、结构、方块目标、position.relative_origin 结构/方块基准
     * 任一不可解析 → 片段不可用，按空片段处理（不写相机，玩家视角）。
     * 点源（位置基准 / 注视点 / 连线端点 / 基准朝向目标）与求值路径走同一解析，判据不分叉。
     * 找不到就找不到——不引入任何替代值/回退逻辑。
     */
    public boolean isClipUsable(Clip clip) {
        for (Keyframe kf : clip.getKeyframes()) {
            String lookAt = kf.getString("look_at", "none");
            if ("entity".equals(lookAt)) {
                if (evalPointSource(lookAtEntitySource(kf), lastWorldPos, "look_at", kf) == null) return false;
            } else if ("block".equals(lookAt)) {
                if (evalPointSource(lookAtBlockSource(kf), lastWorldPos, "look_at", kf) == null) return false;
            } else if ("coordinate".equals(lookAt)) {
                String sid = kf.getString("look_at_target_structure", "");
                if (!sid.isEmpty()
                        && evalPointSource(PointSource.structure(sid), lastWorldPos, "look_at", kf) == null) {
                    return false;
                }
                // 相对目标对象的实体基准不存在 → 该端无目标，片段按空处理
                Object targetObj = kf.getObject("look_at_target");
                if (targetObj instanceof Map<?, ?> m) {
                    Object relTo = m.get("relative_to");
                    if (relTo != null && !"coordinate".equals(relTo)
                            && entityResolver.resolveEntity(
                                    String.valueOf(relTo), lastWorldPos, "look_at_target", kf) == null) {
                        return false;
                    }
                }
            }
            if ("entity".equals(kf.getString("follow", "none"))) {
                if (entityResolver.resolveEntity(
                        kf.getString("follow_selector", "@p"), lastWorldPos, "follow", kf) == null) return false;
            }
            // 朝向基准（yaw_base/pitch_base）：entity 实体缺失 / line 端点缺失 → 空片段
            String yawBase = kf.getString("yaw_base", "world");
            String pitchBase = kf.getString("pitch_base", "world");
            if ("entity".equals(yawBase) || "entity".equals(pitchBase)) {
                if (entityResolver.resolveEntity(
                        kf.getString("yaw_base_selector", "@p"), lastWorldPos, "yaw_base", kf) == null) return false;
            } else if ("line".equals(yawBase) || "line".equals(pitchBase)) {
                if (evalPointField(kf, "yaw_base_from", PointSource.EntityPoint.FOOT) == null
                        || evalPointField(kf, "yaw_base_to", PointSource.EntityPoint.FOOT) == null) return false;
            }
            PositionData pd = kf.getPosition();
            if (pd != null && pd.isRelative()) {
                // 基准坐标系偏移：显式指定的基准点不可解析 → 该段无基准，按空片段处理
                if (pd.isOriginSelector()
                        && evalPointSource(PointSource.of(pd, PointSource.EntityPoint.FOOT),
                                lastWorldPos, "facing_origin", kf) == null) {
                    return false;
                }
                // 基准朝向目标不可解析 → 基准朝向不存在，同样按空片段处理
                PointSource facingTarget = PointSource.parse(kf, "facing_target", pd.getFacingTarget(),
                        PointSource.EntityPoint.CENTER);
                if (facingTarget != null
                        && evalPointSource(facingTarget, lastWorldPos, "facing_target", kf) == null) {
                    return false;
                }
                String sid = pd.getOriginStructure();
                if (sid != null && !sid.isEmpty()
                        && evalPointSource(PointSource.structure(sid), lastWorldPos, "facing_origin", kf) == null) {
                    return false;
                }
                if (pd.isOriginBlock()
                        && evalPointSource(PointSource.of(pd, PointSource.EntityPoint.FOOT),
                                lastWorldPos, "facing_origin", kf) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 片段目标不可用（按空片段处理）提示只打一次（debug 级：作者排查可见，不打扰玩家）。
     * 由编排层（顶层片段）与 lane 捕获层（重叠下层片段）在 {@link #isClipUsable(Clip)} 判定为假时调用。
     */
    public void warnClipUnusableOnce() {
        if (!clipUnusableWarnOnce) {
            clipUnusableWarnOnce = true;
            LOGGER.debug("片段目标不可用（结构/实体未找到），该片段按空处理（玩家视角）");
        }
    }

    /**
     * 单 clip 求值。
     *
     * @param capture {@code true} = 不推进跨帧求值状态（{@link #lastWorldPos} 不变；全局相机写入由编排层跳过）
     * @return 该 clip 本帧的 lane 快照；无插值结果（关键帧为空）时 {@code null}
     */
    public CameraLane renderSingle(float globalTime, Clip clip, float clipLocalTime, boolean capture) {
        KeyframeInterpolator.InterpolationResult result =
                KeyframeInterpolator.computeInterpolation(clipLocalTime, clip);
        if (result == null) return null;

        float s = result.adjustedT;
        CameraState state = writeAttributes(result.from, result.to, s, clip, globalTime, capture);
        return state != null ? new CameraLane(state, clip, clipLocalTime) : null;
    }

    /**
     * B 模型 morph 交叉求值（两 clip 混合成一份相机状态），并提交 {@link #lastWorldPos}。
     *
     * @return 混合后的六参数快照；两端都无插值结果时 {@code null}
     */
    public CameraState renderMorph(Clip prevClip, Clip nextClip, float weight, float globalTime) {
        // B 模型：双轨各自按自身时间插值（prev 走 [dur−t/2, dur)，next 走 [0, t)），再按 weight 交叉
        float prevLocal = globalTime - prevClip.getStartTime();
        float nextLocal = globalTime - nextClip.getStartTime();
        KeyframeInterpolator.InterpolationResult prevResult =
                KeyframeInterpolator.computeInterpolation(prevLocal, prevClip);
        KeyframeInterpolator.InterpolationResult nextResult =
                KeyframeInterpolator.computeInterpolation(nextLocal, nextClip);

        if (prevResult == null && nextResult == null) return null;

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

        lastWorldPos = pos;
        // 画面 lane 快照：本轨本帧顶层求值出的六参数（全局相机写入由编排层执行）
        return new CameraState(pos, yaw, pitch, roll, fov, zoom);
    }

    /** 捕获求值隔离：把本帧基准坐标系留底（同一时刻只保留最近一次留底）。 */
    public void snapshotFrameState() {
        baselineOrigin = frameOrigin;
        baselineFwd = frameFwd;
        baselineRight = frameRight;
        baselineUp = frameUp;
        baselineValid = frameValid;
    }

    /** 捕获求值隔离：把本帧基准坐标系恢复为最近一次 {@link #snapshotFrameState()} 的留底。 */
    public void restoreFrameState() {
        frameOrigin = baselineOrigin;
        frameFwd = baselineFwd;
        frameRight = baselineRight;
        frameUp = baselineUp;
        frameValid = baselineValid;
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
            String selector = kf.getString("follow_selector", "@p");
            Entity target = entityResolver.resolveEntity(selector, lastWorldPos, "follow", kf);
            if (target != null) {
                Vec3 off = pd != null ? pd.toVec3() : Vec3.ZERO;
                return entityResolver.smoothTargetPoint("follow", selector, "pos",
                        TimeInterpolation.entityPosition(target).add(off),
                        entityResolver.selectorPolicy(kf, "follow").switchSmooth(), kf);
            }
            return lastWorldPos;
        }
        Vec3 p = pd != null ? pd.toVec3() : Vec3.ZERO;
        if (pd == null || !pd.isRelative()) return p;
        // 相对基准：relative_origin = "coordinate"（固定坐标）/ 结构 id（结构中心）/ 默认玩家激活位置
        return resolveRelativeBase(kf, pd).add(p);
    }

    /**
     * 基准空间坐标系求值：基准点 = 点源（facing_origin 指定的实体/坐标/结构/方块；
     * 未指定时回落 follow 实体 / 玩家）；三轴由基准朝向建立。
     * - fwd/right 始终随朝向水平旋转（前/后 & 左/右）
     * - up 轴由 up_axis 控制："view"=随俯仰全三维（默认）；"world"=保持世界竖直
     */
    private Vec3 evalFacingOffset(Keyframe kf, PositionData pd) {
        Vec3[] frame = evalFacingFrame(kf, pd);
        if (frame == null) {
            LOGGER.warn("基准空间偏移：基准点/基准朝向不可用（防御路径，按当前视点处理）");
            return lastWorldPos;
        }
        // 记录本帧基准坐标系，供注视点等其它通道按基准系表达偏移
        frameOrigin = frame[0];
        frameFwd = frame[1];
        frameRight = frame[2];
        frameUp = frame[3];
        frameValid = true;
        return frameOrigin
                .add(frameFwd.scale(pd.getFwd()))
                .add(frameRight.scale(pd.getRight()))
                .add(frameUp.scale(pd.getUp()));
    }

    /**
     * 建立本关键帧的基准坐标系 = 基准点 + 基准朝向。
     *
     * @return {@code [原点, fwd, right, up]}；不可用或方向退化时返回 null
     */
    private Vec3[] evalFacingFrame(Keyframe kf, PositionData pd) {
        Minecraft mc = Minecraft.getInstance();
        // 基准点来源（统一点源解析）：形态见 camera/source/PointSource
        Vec3 baseVec = evalFacingBasePoint(kf, pd);
        if (baseVec == null) return null;
        // 朝向所属实体：显式实体基准 → 该实体；坐标 / 结构 / 方块 / 玩家激活位置基准 → follow 实体 / 玩家
        Entity orient = pd.isOriginSelector()
                ? entityResolver.resolveEntity(pd.getOriginSelector(), lastWorldPos, "facing_origin", kf)
                : evalFacingOrient(kf, mc);
        if (pd.isOriginSelector() && orient == null) return null;

        // 基准朝向：facing_target 非空 → 基准点 → 该目标的连线方向（含俯仰的完整三维方向）
        // 取值来源 = position 的基准朝向字段（与解析侧同一份；配套坐标读关键帧 facing_target_x/y/z）
        PointSource facingTarget = PointSource.parse(kf, "facing_target", pd.getFacingTarget(),
                PointSource.EntityPoint.CENTER);
        if (facingTarget != null) {
            Vec3 to = evalPointSource(facingTarget, lastWorldPos, "facing_target", kf);
            return to != null ? buildFrame(baseVec, to.subtract(baseVec), pd) : null;
        }

        if (orient == null) return null;

        // 实体朝向必须按渲染 partialTick 插值：raw getYRot()/getXRot() 会在 20Hz tick 间跳变。
        float yawRad = (float) Math.toRadians(TimeInterpolation.entityBodyYaw(orient));
        float pitchRad = (float) Math.toRadians(TimeInterpolation.entityPitch(orient));
        float sinY = (float) Math.sin(yawRad);
        float cosY = (float) Math.cos(yawRad);
        float sinP = (float) Math.sin(pitchRad);
        float cosP = (float) Math.cos(pitchRad);
        return buildFrame(baseVec, new Vec3(-sinY * cosP, -sinP, cosY * cosP), pd);
    }

    /**
     * 点源求值（相机侧口径）：锚点取该调用点的 {@code selector_anchor}（回落通用字段 → 缺省 {@code camera}）。
     *
     * @param source    点源规格（{@link PointSource}）；{@code null} = 无点源
     * @param cameraPos 实体形态的解析原点
     * @param role      调用点名（策略 / 锚点 / 锁键，见 {@code selector/SelectorSchema}）
     */
    private Vec3 evalPointSource(PointSource source, Vec3 cameraPos, String role, Keyframe kf) {
        return entityResolver.resolvePointSource(source, cameraPos, role, kf,
                entityResolver.selectorAnchor(kf, role));
    }

    /** 点源字段求值：字段名 = 调用点名（{@code yaw_base_from} / {@code yaw_base_to}），解析原点 = 当前视点。 */
    private Vec3 evalPointField(Keyframe kf, String field, PointSource.EntityPoint point) {
        return evalPointSource(PointSource.parse(kf, field, kf.getString(field, ""), point), lastWorldPos,
                field, kf);
    }

    /**
     * 基准坐标系的基准点：{@code facing_origin} 点源（坐标 / 结构 / 方块 / 实体选择器 / 玩家激活位置）。
     * 实体形态的基准点按 {@code base} 通道平滑；坐标 / 结构 / 方块 / 玩家激活位置不平滑（静态点）。
     */
    private Vec3 evalFacingBasePoint(Keyframe kf, PositionData pd) {
        PointSource source = PointSource.of(pd, PointSource.EntityPoint.FOOT);
        Vec3 raw = evalPointSource(source, lastWorldPos, "facing_origin", kf);
        return raw == null || source.kind() != PointSource.Kind.SELECTOR ? raw : smoothFacingBase(kf, pd, raw);
    }

    /** 基准点实体形态的 {@code base} 通道平滑（锁句柄 = 点源选择器 / follow 选择器）。 */
    private Vec3 smoothFacingBase(Keyframe kf, PositionData pd, Vec3 raw) {
        return entityResolver.smoothTargetPoint("facing_origin", frameHandle(kf, pd), "base", raw,
                entityResolver.selectorPolicy(kf, "facing_origin").switchSmooth(), kf);
    }

    /** 点源的锁定/平滑句柄（基准点用哪个 selector 作为锁的键） */
    private static String frameHandle(Keyframe kf, PositionData pd) {
        return pd.isOriginSelector() ? pd.getOriginSelector() : kf.getString("follow_selector", "@p");
    }

    /**
     * 由基准点 + 前轴方向（任意三维向量）构造基准坐标系。
     * 前轴由方向决定（实体朝向 / 两点连线），因此 up 是否跟随俯仰用方向向量本身判断：
     * 非水平方向 + up_axis=view → 跟随俯仰（全三维）；否则 up 保持世界竖直。
     * 前轴没有水平分量（零长度 / 纯垂直）时水平角未定义：拒绝该朝向输入，返回 null，并限频告警。
     * 与旧实现等价：水平朝向 + up_axis=world → up 世界竖直；带俯仰 + up_axis=view → up 与 fwd/right 正交。
     */
    private Vec3[] buildFrame(Vec3 base, Vec3 dir, PositionData pd) {
        double dx = dir.x, dy = dir.y, dz = dir.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < LINE_HORIZONTAL_EPSILON) {
            warnDegenerateLine();
            return null;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float sinY = (float) Math.sin(Math.toRadians(yaw));
        float cosY = (float) Math.cos(Math.toRadians(yaw));
        Vec3 rightVec = new Vec3(-cosY, 0, -sinY);
        Vec3 fwdVec;
        Vec3 upVec;
        boolean followPitch = "view".equals(pd.getUpAxis()) && Math.abs(dy) > 1.0E-4;
        if (followPitch) {
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
            float sinP = (float) Math.sin(Math.toRadians(pitch));
            float cosP = (float) Math.cos(Math.toRadians(pitch));
            fwdVec = new Vec3(-sinY * cosP, -sinP, cosY * cosP);
            upVec = new Vec3(-sinY * sinP, cosP, cosY * sinP);
        } else {
            // up 保持世界竖直：fwd/right 只水平转
            fwdVec = new Vec3(-sinY, 0, cosY);
            upVec = new Vec3(0, 1, 0);
        }
        return new Vec3[]{base, fwdVec, rightVec, upVec};
    }

    /**
     * 把基准坐标系里的偏移（fwd/up/right）转成世界偏移。
     * 本帧没有建立基准坐标系（未用 fwd/up/right 摆位）时返回 null，调用方按世界轴处理。
     */
    private Vec3 frameOffsetToWorld(float fwd, float up, float right) {
        if (!frameValid) return null;
        return frameFwd.scale(fwd).add(frameRight.scale(right)).add(frameUp.scale(up));
    }

    /** 基准朝向所属实体（坐标 / 结构 / 方块 / 玩家激活位置基准点）：follow 实体（{@code follow=entity} 时）/ 玩家。 */
    private Entity evalFacingOrient(Keyframe kf, Minecraft mc) {
        if ("entity".equals(kf.getString("follow", "none"))) {
            return entityResolver.resolveEntity(kf.getString("follow_selector", "@p"), lastWorldPos, "follow", kf);
        }
        return mc.player;
    }

    /**
     * 相对基准求值（dx/dy/dz）：位置侧点源（坐标 / 结构 / 方块 / 玩家激活位置，见 {@link PointSource}）；
     * 点源不可解析 → 回落玩家激活位置。结构 / 方块不可用已在 isClipUsable 前置拦截（该片段按空处理），此处为防御。
     */
    private Vec3 resolveRelativeBase(Keyframe kf, PositionData pd) {
        Vec3 base = evalPointSource(PointSource.of(pd, PointSource.EntityPoint.FOOT), lastWorldPos,
                "facing_origin", kf);
        if (base != null) return base;
        warnRelativeBaseMiss(pd);
        return originPos;
    }

    /** 相对基准点源不可解析提示（防御路径）：结构 / 方块各自一句，与前置拦截同口径。 */
    private void warnRelativeBaseMiss(PositionData pd) {
        String structureId = pd.getOriginStructure();
        if (structureId != null && !structureId.isEmpty()) {
            LOGGER.debug("相对基准结构 '{}' 未找到（防御路径）", structureId);
        } else if (pd.isOriginBlock()) {
            LOGGER.warn("相对基准方块 '{}' 未找到（半径 {}，防御路径，片段按空处理）",
                    pd.getOriginBlockId(), pd.getOriginBlockRadius());
        }
    }

    /**
     * look_at 实体点源：选择器 + 部位百分比取点（{@code look_at_part}，缺省包围盒中心）。
     * {@link #isClipUsable} 与 {@link #evalLookTarget} 共用同一构造（判据与求值同源）。
     */
    private static PointSource lookAtEntitySource(Keyframe kf) {
        return PointSource.selector(kf.getString("look_at_selector", "@p"), lookAtPart(kf));
    }

    /**
     * look_at 方块点源：{@code look_at_target_block} 的 {@code block:<方块 id>[:<半径>]} 字符串，
     * 解析与求值走 {@link PointSource} 统一路径（取点 = 方块中心）。
     */
    private static PointSource lookAtBlockSource(Keyframe kf) {
        return PointSource.parse(kf, "look_at_target_block", kf.getString("look_at_target_block", ""),
                PointSource.EntityPoint.CENTER);
    }

    /**
     * 部位百分比取点：{@code look_at_part} 的 {@code {x,y,z}} 三元组（每轴 0 ~ 100，缺省分量 50 = 中心；
     * 越界钳制，见 {@link PointSource.EntityPoint}）。缺字段 / 非对象 = 包围盒中心。
     */
    private static PointSource.EntityPoint lookAtPart(Keyframe kf) {
        Object part = kf.getObject("look_at_part");
        if (!(part instanceof Map<?, ?> m)) return PointSource.EntityPoint.CENTER;
        return new PointSource.EntityPoint(percentOrDefault(m.get("x")), percentOrDefault(m.get("y")),
                percentOrDefault(m.get("z")));
    }

    /** 部位百分比分量：非数字 = 50（中心）。 */
    private static double percentOrDefault(Object o) {
        return o instanceof Number n ? n.doubleValue() : 50.0;
    }

    /**
     * 关键帧 look_at 目标点求值：
     * entity     → 实体包围盒内的取点（{@code look_at_part} 部位百分比，缺省包围盒中心）
     * block      → 就近搜索的方块中心（{@code look_at_target_block}，点源 BLOCK 形态）
     * coordinate → 固定坐标点（与结构互斥：指定结构后只解析结构）
     * none       → 由该关键帧 yaw/pitch 决定的 100 格方向远点（看向它 = 保持该朝向）
     * 返回 null 表示该端无注视目标（实体消失 / 方块与结构定位失败），该段按 look_at=none 处理（关键帧角度）。
     */
    private Vec3 evalLookTarget(Keyframe kf, Clip clip, Vec3 pos) {
        String lookAt = kf.getString("look_at", "none");
        if ("entity".equals(lookAt)) {
            String selector = kf.getString("look_at_selector", "@p");
            Vec3 raw = evalPointSource(lookAtEntitySource(kf), pos, "look_at", kf);
            if (raw == null) return null;
            return entityResolver.smoothTargetPoint("look_at", selector, "point", raw,
                    entityResolver.selectorPolicy(kf, "look_at").switchSmooth(), kf);
        }
        if ("block".equals(lookAt)) {
            // 定位失败 = 该端无注视目标（isClipUsable 已前置拦截按空处理，此处为防御）
            return evalPointSource(lookAtBlockSource(kf), pos, "look_at", kf);
        }
        if ("coordinate".equals(lookAt)) {
            String structureId = kf.getString("look_at_target_structure", "");
            if (!structureId.isEmpty()) {
                // 结构目标与坐标互斥：指定了结构就只用结构。定位失败返回 null（该端无注视目标），
                // 整个片段已被 isClipUsable 拦截按空处理，此处为防御。
                Vec3 structurePos = evalPointSource(PointSource.structure(structureId), pos, "look_at", kf);
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
                Vec3 target = evalLookTargetObject(m, kf, pos);
                if (target != null) return target;
                // 对象解析失败（相对实体找不到/基准缺失）→ 该端无注视目标（isClipUsable 已前置拦截，此处防御）
                return null;
            }
            return evalPointSource(PointSource.coordinate(
                    kf.getFloat("look_at_target_x", 0), kf.getFloat("look_at_target_y", 64),
                    kf.getFloat("look_at_target_z", 0)), pos, "look_at", kf);
        }
        // none：关键帧朝向的 100 格远点（MC 视线方向 forwards =
        // (-sin yaw·cos pitch, -sin pitch, cos yaw·cos pitch)）
        double yawRad = Math.toRadians(kf.getYaw());
        double pitchRad = Math.toRadians(kf.getPitch());
        double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fy = -Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * Math.cos(pitchRad);
        return pos.add(fx * 100, fy * 100, fz * 100);
    }

    /**
     * look_at_target 对象目标点求值（四种模式）：
     * {x,y,z} → 世界绝对坐标点；{dx,dy,dz} → 相对触发点（脚本激活时玩家位置）偏移；
     * {relative_to:selector, dx..} → 相对实体位置偏移（每帧求值，动态）；
     * {relative_to:"coordinate", relative_x/y/z, dx..} → 相对固定坐标点 + 偏移。
     * 返回 null = 该端无注视目标（相对实体找不到等；isClipUsable 已前置拦截，此处防御）。
     */
    private Vec3 evalLookTargetObject(Map<?, ?> m, Keyframe kf, Vec3 pos) {
        Float x = numOrNull(m.get("x"));
        Float y = numOrNull(m.get("y"));
        Float z = numOrNull(m.get("z"));
        if (x != null && y != null && z != null) {
            return evalPointSource(PointSource.coordinate(x, y, z), pos, "look_at", kf);
        }
        float dx = numOrDefault(m.get("dx"));
        float dy = numOrDefault(m.get("dy"));
        float dz = numOrDefault(m.get("dz"));
        // 偏移表达空间：缺省 = 世界轴；space = "facing" 时按本帧基准坐标系表达（fwd/up/right）
        boolean facingSpace = "facing".equals(String.valueOf(m.get("space")));
        Vec3 offset = null;
        if (facingSpace) {
            offset = frameOffsetToWorld(numOrDefault(m.get("fwd")), numOrDefault(m.get("up")),
                    numOrDefault(m.get("right")));
            // 本帧没有基准坐标系 → 退回世界轴偏移（有什么放什么）
            if (offset == null) offset = new Vec3(dx, dy, dz);
        }
        Object relTo = m.get("relative_to");
        if (relTo == null) {
            // 相对触发点偏移
            return offset != null ? originPos.add(offset) : originPos.add(dx, dy, dz);
        }
        if ("coordinate".equals(relTo)) {
            double rx = numOrDefault(m.get("relative_x"));
            double ry = numOrDefault(m.get("relative_y"));
            double rz = numOrDefault(m.get("relative_z"));
            return offset != null
                    ? new Vec3(rx, ry, rz).add(offset)
                    : new Vec3(rx + dx, ry + dy, rz + dz);
        }
        // 相对实体 selector（每帧求实体位置 + 偏移）
        String selector = String.valueOf(relTo);
        Entity target = entityResolver.resolveEntity(selector, pos, "look_at_target", kf);
        if (target == null) return null;
        Vec3 raw = offset != null
                ? TimeInterpolation.entityPosition(target).add(offset)
                : TimeInterpolation.entityPosition(target).add(dx, dy, dz);
        return entityResolver.smoothTargetPoint("look_at_target", selector, "point", raw,
                entityResolver.selectorPolicy(kf, "look_at_target").switchSmooth(), kf);
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
        // 1) look_at 优先（关键帧级）：段内任一端有 look_at 就用目标点插值
        if (from != null && to != null) {
            boolean anyLook = !"none".equals(from.getString("look_at", "none"))
                    || !"none".equals(to.getString("look_at", "none"));
            if (anyLook) {
                Vec3 t0 = evalLookTarget(from, clip, segPos);
                Vec3 t1 = evalLookTarget(to, clip, segPos);
                if (t0 != null && t1 != null) {
                    Vec3 target = new Vec3(t0.x + (t1.x - t0.x) * s, t0.y + (t1.y - t0.y) * s,
                            t0.z + (t1.z - t0.z) * s);
                    double dx = target.x - segPos.x;
                    double dy = target.y - segPos.y;
                    double dz = target.z - segPos.z;
                    float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    // 诊断：look_at 目标位置，播放期间只打印 1 次
                    if (!lookAtLoggedOnce) {
                        lookAtLoggedOnce = true;
                        LOGGER.info("LOOK_AT_ONCE: cam=({}, {}, {}) target=({}, {}, {}) yaw={} pitch={}",
                                String.format("%.2f", segPos.x), String.format("%.2f", segPos.y),
                                String.format("%.2f", segPos.z), String.format("%.2f", target.x),
                                String.format("%.2f", target.y), String.format("%.2f", target.z),
                                String.format("%.2f", yaw), String.format("%.2f", pitch));
                    }
                    return new float[]{yaw, pitch};
                }
            }
        }
        // 2) 片段级 tangent：仅在无 look_at 的段生效
        if (from != null && to != null && isTangentOrientation(clip)) {
            Vec3 p0 = evalKeyframeWorldPos(from, clip);
            Vec3 p3 = evalKeyframeWorldPos(to, clip);
            boolean anyFollow = "entity".equals(from.getString("follow", "none"))
                    || "entity".equals(to.getString("follow", "none"));
            PathStrategy strategy = anyFollow ? PathStrategies.get("linear") : bezierStrategy;
            return TangentOrientation.compute(p0, p3, s,
                    anyFollow ? null : clip.getCurve(), strategy,
                    clip.getFloat("yaw_offset", 0f), clip.getFloat("pitch_offset", 0f));
        }
        // 3) 手写角度：两端关键帧各自算"最终世界角度 = 基准 + 偏移"，再做角度插值
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

    // ===== 切线朝向辅助 =====

    private boolean isTangentOrientation(Clip clip) {
        return "tangent".equals(clip.getString("orient", "manual"));
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
     * 关键帧 yaw 基准方向：yaw_base = world（0，现状）| entity（实体身体朝向水平角，LivingEntity 取 yBodyRot）
     * | line（from→to 连线水平角）。
     * 实体缺失/line 端点缺失时 isClipUsable 已前置拦截为空片段；此处防御回退 0（=world）。
     */
    private float yawBaseOf(Keyframe kf) {
        String base = kf.getString("yaw_base", "world");
        if ("entity".equals(base)) {
            Entity e = entityResolver.resolveEntity(
                    kf.getString("yaw_base_selector", "@p"), lastWorldPos, "yaw_base", kf);
            return e != null ? TimeInterpolation.entityBodyYaw(e) : 0f;
        }
        if ("line".equals(base)) {
            float[] dir = lineDir(kf);
            if (dir != null) return dir[0];
        }
        return 0f;
    }

    /** 关键帧 pitch 基准俯仰：pitch_base = world（0）| entity（实体视线俯仰，即 xRot）| line（连线垂直角） */
    private float pitchBaseOf(Keyframe kf) {
        String base = kf.getString("pitch_base", "world");
        if ("entity".equals(base)) {
            Entity e = entityResolver.resolveEntity(
                    kf.getString("yaw_base_selector", "@p"), lastWorldPos, "yaw_base", kf);
            return e != null ? TimeInterpolation.entityPitch(e) : 0f;
        }
        if ("line".equals(base)) {
            float[] dir = lineDir(kf);
            if (dir != null) return dir[1];
        }
        return 0f;
    }

    /**
     * line 基准方向：{@code yaw_base_from} → {@code yaw_base_to} 两点连线方向（水平 yaw + 垂直 pitch）。
     * 两端点各是点源（见 {@link PointSource}），取点 = 实体脚底；任一端无点源或不可解析返回 null。
     * 水平分量小于 {@link #LINE_HORIZONTAL_EPSILON}（零长度线 / 纯垂直线）时水平角未定义，
     * 拒绝该朝向输入：返回 null，调用方（{@link #yawBaseOf} / {@link #pitchBaseOf}）回退基准 0 = world。
     * 纯水平线（水平分量达标、|dy| ≈ 0）合法：水平角有定义、pitch = 0。
     */
    private float[] lineDir(Keyframe kf) {
        Vec3 from = evalPointField(kf, "yaw_base_from", PointSource.EntityPoint.FOOT);
        Vec3 to = evalPointField(kf, "yaw_base_to", PointSource.EntityPoint.FOOT);
        if (from == null || to == null) return null;
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < LINE_HORIZONTAL_EPSILON) {
            warnDegenerateLine();
            return null;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    /**
     * 退化连线告警（限频）：零长度线与纯垂直线的水平角都未定义，该朝向输入被拒——
     * {@code yaw_base = line} 回退基准 0 = world，基准空间偏移按当前视点处理。2 秒内只打一条。
     */
    private void warnDegenerateLine() {
        long now = System.currentTimeMillis();
        if (now - lastDegenerateLineWarnAt < DEGENERATE_LINE_WARN_INTERVAL_MS) return;
        lastDegenerateLineWarnAt = now;
        LOGGER.warn("基准朝向退化（零长度或纯垂直：水平分量 < {}），水平角未定义，该朝向输入被拒（回退 world / 按当前视点处理）",
                LINE_HORIZONTAL_EPSILON);
    }

    /**
     * 单段求值出相机六参数，并按 {@code capture} 决定是否提交 {@link #lastWorldPos}。
     *
     * @param capture {@code true} = 不推进 {@link #lastWorldPos}（全局相机写入由编排层按同一标志跳过）
     * @return 本段求值出的六参数快照
     */
    private CameraState writeAttributes(Keyframe from, Keyframe to, float s, Clip clip, float globalTime,
                                        boolean capture) {
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

        if (!capture) {
            lastWorldPos = pos;
        }
        // 画面 lane 快照：本 clip 本帧求值出的六参数（capture=true 时不写全局相机状态、不推进跨帧求值状态）
        return new CameraState(pos, yaw, pitch, roll, fov, zoom);
    }

    /**
     * 收集 globalTime 时刻的全部活跃片段（按轨道顺序；轨道内后面的 clip 在上层）。
     *
     * 活跃窗口 = {@code [startTime, windowEnd)}；永不结束的片段
     * （{@link Clip#isEffectivelyInfinite()}）自 {@code startTime} 起活跃，并按"终点"语义处理：
     * 收集到它即停止，其后的内容不播放。
     *
     * @param clips 本轨片段（轨道顺序）
     */
    public List<Clip> findActiveClips(List<Clip> clips, float globalTime) {
        List<Clip> active = new ArrayList<>(2);
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            if (clip.isEffectivelyInfinite()) {
                if (globalTime >= clip.getStartTime()) {
                    active.add(clip);
                    break; // 终点：永不结束的片段之后的内容不播放
                }
                continue;
            }
            if (globalTime >= clip.getStartTime() && globalTime < clip.getWindowEnd()) {
                active.add(clip);
            }
        }
        return active;
    }

    /**
     * 驱动相机的片段 = 顶层活跃片段 = 轨道顺序最后的活跃片段（后面的 clip 覆盖前面）。
     * 无活跃片段返回 null（不写相机 → 玩家视角）。
     */
    private Clip findActiveClip(List<Clip> clips, float globalTime) {
        List<Clip> active = findActiveClips(clips, globalTime);
        if (active.isEmpty()) return null;
        Clip top = active.get(active.size() - 1);
        return top;
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
}
