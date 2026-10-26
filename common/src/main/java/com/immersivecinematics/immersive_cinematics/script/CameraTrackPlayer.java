package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.source.CameraKeyframeEvaluator;
import com.immersivecinematics.immersive_cinematics.camera.source.EntityTargetResolver;
import com.immersivecinematics.immersive_cinematics.camera.source.LaneSnapshotCollector;
import com.immersivecinematics.immersive_cinematics.camera.source.PointSourceSnapshots;
import com.immersivecinematics.immersive_cinematics.camera.source.WorldPointLocator;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionSpec;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * CAMERA 轨道播放器：逐帧编排数据源 / 时间推进 / 片段选取 / 写入分发 / 生命周期。
 * 写入契约：本轨顶层六参数经 {@link CameraManager#setScriptState} 进全局写入缓冲
 * （导演 Base 层的当前值来源）；重叠窗口里层级在下的 clip 只产 lane 快照、不写全局。
 * 求值与过渡合成归 {@link CameraKeyframeEvaluator}，lane 捕获归 {@link LaneSnapshotCollector}；
 * 仅客户端主线程逐渲染帧调用，帧内路径不新建集合。
 */
public class CameraTrackPlayer implements TrackPlayer {

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;
    private final CameraManager cameraManager;

    /** 目标解析与锁定：selector → 实体 + 按 {@code role + selector} 维护的锁与切换平滑状态 */
    private final EntityTargetResolver entityResolver = new EntityTargetResolver();
    /** 点源快照表（时态 = 快照的选择器点源）：本轨一份，seek / 停止 / 替换清空，loop 折返不清（延续） */
    private final PointSourceSnapshots snapshots = new PointSourceSnapshots();
    /** 关键帧求值（六参数 / 插值 / 过渡合成） */
    private final CameraKeyframeEvaluator evaluator;
    /** 本轨本帧的 lane 快照捕获与持有 */
    private final LaneSnapshotCollector laneCollector;

    /** 本轨正在驱动的过渡对（相邻片段下标 i / i + 1）；-1 = 无——打断续接的尾巴据此在窗口外继续驱动。 */
    private int transitionPair = -1;

    /**
     * 本帧活跃的 CAMERA 片段（按轨道顺序；morph / 过渡窗口 = prev + next）。
     * 每帧开头清空，供租约对账保留「片段仍活跃但本帧不产画面」的租约。
     */
    private final List<Clip> frameActiveClips = new ArrayList<>(2);

    public CameraTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, Vec3 originPos, CameraManager cameraManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.trackIndex = trackIndex;
        this.cameraManager = cameraManager;
        WorldPointLocator pointLocator = new WorldPointLocator();
        entityResolver.attachSnapshots(snapshots);
        this.evaluator = new CameraKeyframeEvaluator(entityResolver, pointLocator, originPos);
        this.laneCollector = new LaneSnapshotCollector(entityResolver, evaluator);
    }

    /** 动态数据源：replaceScript 后自动用新数据，无需重建轨道 */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
    }

    /**
     * 本轨本帧的画面 lane 快照列表（按轨道内 clip 顺序，最后一个是顶层相机）。
     *
     * @return 空列表 = 本轨本帧不产出画面（片段间隙 / 目标不可用 / 编辑器直控）
     */
    public List<CameraLane> getLaneSnapshots() {
        return laneCollector.getLaneSnapshots();
    }

    /**
     * 本帧活跃的 CAMERA 片段（按轨道顺序；morph / 过渡窗口 = prev + next）。只读，供租约对账使用。
     *
     * @return 本轨本帧活跃片段；空列表 = 本轨无活跃片段
     */
    public List<Clip> frameActiveClips() {
        return frameActiveClips;
    }

    /**
     * 片段在本轨 {@code clips()} 里的下标。
     * {@link Clip} 未重写 {@code equals}，这里按对象身份比较（与租约身份同一口径）；找不到 = {@code -1}。
     */
    public int indexOfClip(Clip clip) {
        return clips().indexOf(clip);
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return evaluator.isActiveAt(clips(), globalTime);
    }

    @Override
    public void onRenderFrame(float globalTime) {
        // 本帧无写入 → lane 快照为空；有写入时由顶层路径与逐 clip 捕获填值
        laneCollector.clear();
        frameActiveClips.clear();
        List<Clip> clips = clips();
        if (clips.isEmpty()) return;

        // 编辑器拖拽直控期间，相机由编辑器直驱（setCameraDirect），跳过轨道写入
        if (cameraManager.isPreviewDirectControl()) return;

        // 过渡层驱动：窗口内 → 最新的一对（后开始者 = 打断者接管）；窗口外若层仍在过渡（打断后时长重算）→ 继续同一对
        int pair = transitionPairAt(clips, globalTime);
        if (pair < 0 && transitionPair >= 0 && transitionPair + 1 < clips.size() && evaluator.isTransitionActive()) {
            pair = transitionPair;
        }
        if (pair >= 0) {
            Clip prev = clips.get(pair);
            Clip next = clips.get(pair + 1);
            // 租约对账口径：过渡两侧都算活跃（即使本帧不产画面，也不释放它们的租约）
            frameActiveClips.add(prev);
            frameActiveClips.add(next);
            // 进入的片段目标不可用 → 整个转场按空处理
            if (!evaluator.isClipUsable(next)) {
                evaluator.warnClipUnusableOnce();
                return;
            }
            // 两 clip 混合成一份相机状态 → 本轨本帧只产出一份 lane；合成参数取进入的片段（转场结束后留在屏上的是它）
            CameraState morphState = evaluator.renderMorph(prev, next, globalTime);
            if (morphState != null) {
                // 转场两侧任一声明 suppress = 本帧跳过 Modifier 链（两侧都活跃）
                writeGlobal(morphState, prev.getSuppressModifiers() || next.getSuppressModifiers());
                laneCollector.add(new CameraLane(morphState, next, globalTime - next.getStartTime()));
                transitionPair = pair;
                return;
            }
        }
        transitionPair = -1;

        // 顶层活跃片段（轨道顺序最后者；后面的 clip 覆盖前面）驱动相机
        List<Clip> active = evaluator.findActiveClips(clips, globalTime);
        if (active.isEmpty()) return;
        // 可用性检查之前登记：目标暂时不可用的片段仍算活跃，租约保持到片段真正结束
        frameActiveClips.addAll(active);
        Clip topClip = active.get(active.size() - 1);
        // 目标不可用（结构/实体找不到）= 该片段按空处理（不写相机 → 玩家视角，与片段间隙同语义）
        if (!evaluator.isClipUsable(topClip)) {
            evaluator.warnClipUnusableOnce();
            return;
        }

        // 顶层 clip：正常求值（写全局相机状态，与顶层 lane 同源同值）
        CameraLane topLane = evaluator.renderSingle(globalTime, topClip, globalTime - topClip.getStartTime(),
                false, CameraKeyframeEvaluator.TOP_LANE);
        if (topLane != null) writeGlobal(topLane.state(), topClip.getSuppressModifiers());
        // 重叠窗口内层级在下的活跃 clip：先捕获（列表顺序 = 绘制顺序，后面的在上）
        laneCollector.captureLowerLanes(globalTime, active, topClip);
        if (topLane != null) laneCollector.add(topLane);
    }

    /** 顶层写入：本轨本帧的六参数写入全局相机状态（写入缓冲 + 链上的当前值 + 该片段的 Modifier 抑制标志）。 */
    private void writeGlobal(CameraState state, boolean suppressModifiers) {
        cameraManager.setScriptState(state, suppressModifiers);
    }

    /**
     * 本帧过渡窗口覆盖的最新一对相邻 morph 片段下标（无 = -1）。
     *
     * 窗口判据 = {@link TransitionSpec#covers}（与活跃判定 / Modifier 活跃窗延长同一口径）；倒序扫描 =
     * 后开始的过渡优先（进行中的旧过渡被新过渡打断：blend 以当前混合中间态为新 A 侧续接，
     * snap 立即切到新目标）。
     */
    private static int transitionPairAt(List<Clip> clips, float globalTime) {
        for (int i = clips.size() - 2; i >= 0; i--) {
            Clip prev = clips.get(i);
            if (!prev.isMorph() || prev.isEffectivelyInfinite()) {
                continue;
            }
            TransitionSpec spec = prev.getTransitionSpec();
            if (spec != null && spec.covers(globalTime, prev.getWindowEnd())) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onStop() {
        laneCollector.clear();
        frameActiveClips.clear();
        entityResolver.clear();
        snapshots.clear();
        evaluator.resetTransitionState();
        transitionPair = -1;
    }

    /** 编辑器 seek：点源快照重取（seek = 硬定位）；本轨运动模型实例归位（下一次求值以当帧输入为初值，dt = 0）；过渡作废。 */
    @Override
    public void onSeek() {
        snapshots.clear();
        evaluator.resetMotionState();
        evaluator.resetTransitionState();
        transitionPair = -1;
    }

    /** 数据替换后复位派生状态：lane 快照 / 目标锁 / 点源快照 / 过渡（都按脚本数据重建）。 */
    @Override
    public void onScriptReplaced() {
        laneCollector.clear();
        frameActiveClips.clear();
        entityResolver.clear();
        snapshots.clear();
        evaluator.resetTransitionState();
        transitionPair = -1;
    }

    /** 宏观循环折返：过渡归位（结算记录 / 打断留底按新一圈重新开始）；点源快照不重取（折返 = 状态延续）。 */
    @Override
    public void onLoopRestart() {
        evaluator.resetTransitionState();
        transitionPair = -1;
    }
}
