package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.transition.CameraTransitionLayer;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionFrame;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionSpec;
import com.immersivecinematics.immersive_cinematics.script.BreathDisturbance;
import com.immersivecinematics.immersive_cinematics.script.CameraLane;
import com.immersivecinematics.immersive_cinematics.script.CameraLaneComposite;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipTimeSource;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.script.path.PathBindingSource;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 相机求值：把脚本数据（片段 / 对象轨道）算成六参数快照与 lane 快照（纯计算，不写全局相机）。
 *
 * 单位与坐标空间：位置 = 世界空间方块坐标；yaw / pitch / roll = 度（yaw = 身体朝向、pitch = 视线）；
 * 时间 = 秒；MC 视线方向 = {@code (-sin yaw·cos pitch, -sin pitch, cos yaw·cos pitch)}。
 * 数值契约：目标不可解析 → 片段按空处理（不写相机，玩家视角），不引入替代值 / 回退逻辑。
 * 求值路径：对象装配（{@link ObjectCameraEvaluator}）产出位置 / 朝向基准与合成参数，本类再叠该片段
 * 自己的运动模型（{@link LaneMotionStepper}）与 breath（{@link BreathDisturbance}）。
 * 状态所有权：{@link #lastWorldPos} 仅本实例持有；目标锁由 {@link EntityTargetResolver} 留底。
 * 过渡状态由 {@link CameraTransitionLayer} 持有（本轨一份）。
 * 分配 / 线程契约：仅客户端主线程，位于每帧求值路径；帧内路径不新建集合、不装箱。
 */
public final class CameraKeyframeEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraKeyframeEvaluator");

    /**
     * 顶层 lane 的层号：本轨本帧写入全局相机状态的那条 lane（{@link #renderSingle} / {@link #renderMorph} 的
     * 运动模型槽索引）；层级在下的 lane 依次为 1、2……（重叠窗口的捕获求值）。
     */
    public static final int TOP_LANE = 0;

    /** 上一帧最终世界坐标（实体目标消失时停在原地、以及作为 @e 就近基准） */
    private Vec3 lastWorldPos;

    /** 本轨 per-lane 运动模型实例槽（六通道；定义来自本帧生效片段，缺省 null = 该通道不接模型）。 */
    private final LaneMotionStepper motion = new LaneMotionStepper();

    /**
     * morph 专用运动模型实例槽（每侧一份）：A 侧 = {@code prevClip}、B 侧 = {@code nextClip}。
     * 与 {@link #motion} 分开持有，是为了让两侧各自按自己的片段定义推进（同一帧两侧各 step 一次，
     * 不共享状态）；{@link #motion} 仍归单片段 / lane 捕获使用。
     */
    private final LaneMotionStepper motionA = new LaneMotionStepper();
    private final LaneMotionStepper motionB = new LaneMotionStepper();

    /** morph 本帧 next 侧的合成参数（{@code null} = 本帧无产出）；供 lane 快照读取。 */
    private CameraLaneComposite morphComposite;

    /** 本轨的过渡层实例（过渡流的唯一持有者：进度 / 曲线 / 分参数 / 打断状态只属于本轨）。 */
    private final CameraTransitionLayer transitionLayer = new CameraTransitionLayer();

    /** 过渡层输入 / 输出载体（A 侧 / B 侧 / 输出；跨帧复用，过渡求值零分配）。 */
    private final TransitionFrame morphFrom = new TransitionFrame();
    private final TransitionFrame morphTo = new TransitionFrame();
    private final TransitionFrame morphOut = new TransitionFrame();

    /** 运动模型 step 的六参数缓冲区（0~2 = 位置 x/y/z，3~7 = yaw/pitch/roll/fov/zoom）；仅求值路径使用。 */
    private final double[] motionValues = new double[LaneMotionStepper.VALUES];

    /** 片段目标不可用（按空片段处理）提示只打一次 */
    private boolean clipUnusableWarnOnce;

    /** 执行账本的只读时间来源（活跃窗 / 执行局部时间 / 有限窗口末端）。 */
    private final ClipTimeSource timeSource;

    /** 新格式对象装配：采样缓存（按片段身份复用缓冲 / value 视图）+ 单片段求值器。 */
    private final ClipRuntimeCache objectCache = new ClipRuntimeCache(TrackTypeId.CAMERA);
    private final ObjectCameraEvaluator objectEvaluator;

    public CameraKeyframeEvaluator(EntityTargetResolver entityResolver, WorldPointLocator pointLocator,
                                   Vec3 originPos, ClipTimeSource timeSource) {
        this.lastWorldPos = originPos;
        this.timeSource = timeSource;
        // 世界上下文共用同一实例：位置侧点源（结构 / 方块 / 玩家激活位置）由 EntityTargetResolver 解析
        entityResolver.attachWorldContext(pointLocator, originPos);
        // 新格式：选择器服务与实体解析同源（锁 / 平滑 / 捕获留底），方块 / 结构定位器共用缓存
        ObjectWorldContext objectWorld = new ObjectWorldContext(entityResolver.service(), pointLocator, originPos);
        this.objectEvaluator = new ObjectCameraEvaluator(objectCache, objectWorld, originPos);
    }

    /**
     * 本轨在 globalTime 是否有画面：有活跃片段（含无限片段），或落在某过渡窗口内即为真。
     *
     * 过渡窗口判据 = {@link TransitionSpec#covers}（与轨道驱动 / Modifier 活跃窗延长同一口径）。
     *
     * @param clips 本轨片段（轨道顺序）
     */
    public boolean isActiveAt(List<Clip> clips, double globalTime) {
        Clip c = findActiveClip(clips, globalTime);
        if (c != null) return true;
        for (int i = 0; i < clips.size() - 1; i++) {
            Clip prev = clips.get(i);
            if (!prev.isMorph() || prev.isEffectivelyInfinite()) continue;
            TransitionSpec spec = prev.getTransitionSpec();
            if (spec != null && spec.covers(globalTime, timeSource.finiteWindowEnd(prev))) return true;
        }
        return false;
    }

    /**
     * 片段目标可用性：新格式对象装配判定（只检查 {@code [0, duration]} 内的对象，静态对象直接可用，
     * 超界关键帧不参与）→ 任一目标不可解析 = 片段不可用（不写相机，玩家视角）。
     * 找不到就找不到，不引入替代值 / 回退。
     */
    public boolean isClipUsable(Clip clip) {
        return objectEvaluator.isClipUsable(clip);
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
     * @param capture  {@code true} = 不推进跨帧求值状态（{@link #lastWorldPos} 不变；全局相机写入由编排层跳过）
     * @param laneRank 该 clip 所在 lane 的层号（{@link #TOP_LANE} = 顶层；捕获路径按距顶层层级传入）
     * @return 该 clip 本帧的 lane 快照；对象求值无结果时 {@code null}
     */
    public CameraLane renderSingle(double globalTime, Clip clip, double clipLocalTime, boolean capture,
                                   int laneRank) {
        // 同一 SampledClip 装配六参数 + 合成参数，再叠本片段自己的 motion / breath
        CameraState base = objectEvaluator.evaluate(clip, clipLocalTime, capture, laneRank);
        if (base == null) return null;
        CameraState state = applyClipEffects(clip, clipLocalTime, globalTime, laneRank, motion, base);
        // 跨帧求值状态 = 本帧最终位置（含运动模型输出）
        if (!capture) lastWorldPos = state.position();
        return new CameraLane(state, clip, clipLocalTime, objectEvaluator.composite());
    }

    /**
     * B 模型 morph 交叉求值（两 clip 各按自身时间求值 → 过渡层合成一份相机状态），并提交 {@link #lastWorldPos}。
     *
     * <p>求值次序：每侧先求完整状态（位置 / 朝向 + 本段 motion + 本段 breath），过渡层只混合两份快照；
     * 混合后不再跑 motion / breath。每侧各持一份 {@link LaneMotionStepper}（A = prev、B = next），
     * 一帧内每侧只 step 一次。</p>
     *
     * 过渡声明（分参数 / 曲线 / 打断）取自 {@code prevClip}；缺省 = 全六通道同权重线性斜坡（= 现状）。
     *
     * @return 混合后的六参数快照；两端都无结果、或过渡本帧不产出时 {@code null}
     */
    public CameraState renderMorph(Clip prevClip, Clip nextClip, double globalTime) {
        // B 模型：双轨各自按本次执行局部时间求值（prev 走 [dur−t/2, dur)，next 走 [0, t)），再交过渡层按声明合成
        double prevLocal = timeSource.executionLocalTime(prevClip, globalTime);
        double nextLocal = timeSource.executionLocalTime(nextClip, globalTime);
        return renderMorphFullState(prevClip, nextClip, globalTime, prevLocal, nextLocal);
    }

    /**
     * morph：每侧完整状态 → 过渡层只混合两份快照。
     *
     * 运动模型按侧推进（{@link #motionA} / {@link #motionB}，时间基准 = {@code globalTime}，dt 单调）；
     * breath 时间基准走 {@link #breathTime}（= 该侧局部时间）。混合后不再叠加 motion / breath——
     * 两侧效果已各自落在快照里。
     */
    private CameraState renderMorphFullState(Clip prevClip, Clip nextClip, double globalTime,
                                             double prevLocal, double nextLocal) {
        CameraState prevState = sideState(prevClip, prevLocal, globalTime, motionA);
        CameraState nextState = sideState(nextClip, nextLocal, globalTime, motionB);
        // lane 合成参数取 next 侧本帧求值结果
        CameraLaneComposite nextComposite = objectEvaluator.composite();
        if (prevState == null && nextState == null) {
            morphComposite = null;
            return null;
        }
        writeFrame(morphFrom, prevState);
        writeFrame(morphTo, nextState);

        // 过渡层：进度 / 曲线 / 分参数 / 打断（缺省 = 全六通道同权重线性斜坡）；未产出 = 本帧按常规路径处理
        if (!transitionLayer.blendInto(globalTime, timeSource.finiteWindowEnd(prevClip),
                prevClip.getTransitionSpec(), morphFrom, morphTo, morphOut)) {
            morphComposite = null;
            return null;
        }
        Vec3 pos = new Vec3(morphOut.px, morphOut.py, morphOut.pz);
        lastWorldPos = pos;
        morphComposite = nextComposite;
        // 画面 lane 快照：混合后的六参数（全局相机写入由编排层执行）
        return new CameraState(pos, morphOut.yaw, morphOut.pitch, morphOut.roll, morphOut.fov, morphOut.zoom);
    }

    /**
     * morph 的一侧完整状态：位置 / 朝向（新格式对象装配）+ 本段 motion + 本段 breath。
     * 与 {@link #renderSingle} 共用 {@link #applyClipEffects}。
     *
     * @param stepper 该侧专用的运动模型槽（一帧一次 step）
     * @return {@code null} = 该侧本帧无结果
     */
    private CameraState sideState(Clip clip, double localTime, double globalTime, LaneMotionStepper stepper) {
        CameraState base = objectEvaluator.evaluate(clip, localTime, true, TOP_LANE);
        return base != null
                ? applyClipEffects(clip, localTime, globalTime, TOP_LANE, stepper, base)
                : null;
    }

    /**
     * 单侧快照写入过渡载体；{@code null}（该侧本帧无结果）= 既有缺省（位置沿用上一帧最终坐标、
     * 角度 0、fov 70、zoom 1）。
     */
    private void writeFrame(TransitionFrame out, CameraState state) {
        if (state == null) {
            out.set(lastWorldPos.x, lastWorldPos.y, lastWorldPos.z, 0f, 0f, 0f, 70f, 1f);
            return;
        }
        Vec3 pos = state.position();
        out.set(pos.x, pos.y, pos.z, state.yaw(), state.pitch(), state.roll(), state.fov(), state.zoom());
    }

    /**
     * 施加本片段自己的效果：motion（六通道模型 step，定义取本片段）+ breath（{@code cam_breath_*}）。
     * 单片段路径与 morph 每侧共用同一口径 = 本侧完整状态（位置 / 朝向 + 本段 motion + 本段 breath）。
     *
     * @param localTime 该片段自己的执行局部时间（breath 相位）
     * @param globalTime 脚本全局时间（motion 的 t：dt 单调，跨循环不出负值）
     * @param stepper   本 lane / 本侧的模型实例槽（同一帧只 step 一次）
     * @param base      位置 / 朝向基准快照（不含 motion / breath）
     */
    private CameraState applyClipEffects(Clip clip, double localTime, double globalTime,
                                         int laneRank, LaneMotionStepper stepper, CameraState base) {
        Vec3 basePos = base.position();
        float yaw = base.yaw();
        float pitch = base.pitch();
        float roll = base.roll();
        float fov = base.fov();
        float zoom = base.zoom();

        motionValues[0] = basePos.x;
        motionValues[1] = basePos.y;
        motionValues[2] = basePos.z;
        motionValues[3] = yaw;
        motionValues[4] = pitch;
        motionValues[5] = roll;
        motionValues[6] = fov;
        motionValues[7] = zoom;
        int applied = stepper.step(laneRank, clip, globalTime, motionValues);
        Vec3 pos = basePos;
        if ((applied & LaneMotionStepper.CH_POSITION) != 0) {
            pos = new Vec3(motionValues[0], motionValues[1], motionValues[2]);
        }
        if (applied != 0) {
            yaw = (float) motionValues[3];
            pitch = (float) motionValues[4];
            roll = (float) motionValues[5];
            fov = (float) motionValues[6];
            zoom = (float) motionValues[7];
        }

        if (clip.getBool("cam_breath_enabled", false)) {
            float[] jitter = BreathDisturbance.fromClip(clip).compute(breathTime(localTime));
            yaw += jitter[0];
            pitch += jitter[1];
            roll += jitter[2];
        }
        return new CameraState(pos, yaw, pitch, roll, fov, zoom);
    }

    /**
     * breath 的时间基准：各侧用自己的执行局部时间（每侧相位独立，morph 两侧不共用全局相位）。
     */
    private static double breathTime(double localTime) {
        return localTime;
    }

    /** morph 本帧 next 侧的合成参数（{@code null} = 本帧无产出）。 */
    public CameraLaneComposite morphComposite() {
        return morphComposite;
    }

    /**
     * seek（{@code editor.seek} / {@code setTime}）：全部 lane 的运动模型实例归位——下一次求值以当帧输入为
     * 初值、dt = 0，从 seek 点重新推进；跨帧求值状态（{@link #lastWorldPos} / 目标锁）不在归位范围。
     */
    public void resetMotionState() {
        motion.reset();
        motionA.reset();
        motionB.reset();
    }

    /** 本轨的过渡层当前是否在过渡中（未走完 / 未结算）——驱动方据此在窗口外继续喂同一对片段。 */
    public boolean isTransitionActive() {
        return transitionLayer.isActive();
    }

    /** 过渡归位（停播 / seek / 脚本替换）：清空过渡状态（进度 / 曲线 / 打断留底），下一次求值重新开始。 */
    public void resetTransitionState() {
        transitionLayer.reset();
    }

    /**
     * 路径绑定缓存归位（脚本替换 / 停播）：丢弃新格式对象装配的采样缓存与逐片段运行态
     * （切线 / 冻结桶 / 片段键），下一次求值按当时的 spec 与端点重新绑定。
     */
    public void resetPaths() {
        objectCache.clear();
        objectEvaluator.resetRuntimeState();
    }

    /**
     * 脚本替换钩子：丢弃新格式对象装配的缓存（采样缓冲 / value 视图 / 切线 / 冻结桶）。
     * 停播与替换共用同一清理口径。
     */
    public void onScriptReplaced() {
        resetPaths();
    }

    /**
     * I-4 接线：替换脚本级路径绑定来源（{@code null} = 不启用）。切换时丢弃已缓存的绑定与端点采样器
     * （{@link ObjectCameraEvaluator#setPathBindingSource} 内部新建求值器）。
     */
    public void setPathBindingSource(PathBindingSource source) {
        objectEvaluator.setPathBindingSource(source);
    }

    /** seek：新格式冻结桶重取（冻结的是坐标值，硬定位后重新捕获）。 */
    public void resetFreezes() {
        objectEvaluator.resetFreezes();
    }

    /**
     * 收集 {@code globalTime} 时刻的全部活跃片段（按轨道顺序；轨道内后面的 clip 在上层）。
     *
     * 活跃窗由执行账本给出（{@link ClipTimeSource#isClipActive}）：有限 = {@code [windowStart, windowEnd)}，
     * 无限执行自起点起永续。无限片段不截断同轨后续片段——后续是否活跃同样由账本逐条判定。
     *
     * @param clips 本轨片段（轨道顺序）
     */
    public List<Clip> findActiveClips(List<Clip> clips, double globalTime) {
        List<Clip> active = new ArrayList<>(2);
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            if (timeSource.isClipActive(clip, globalTime)) {
                active.add(clip);
            }
        }
        return active;
    }

    /**
     * 驱动相机的片段 = 顶层活跃片段 = 轨道顺序最后的活跃片段（后面的 clip 覆盖前面）。
     * 无活跃片段返回 null（不写相机 → 玩家视角）。
     */
    private Clip findActiveClip(List<Clip> clips, double globalTime) {
        List<Clip> active = findActiveClips(clips, globalTime);
        if (active.isEmpty()) return null;
        Clip top = active.get(active.size() - 1);
        return top;
    }
}
