package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.source.CameraKeyframeEvaluator;
import com.immersivecinematics.immersive_cinematics.camera.source.EntityTargetResolver;
import com.immersivecinematics.immersive_cinematics.camera.source.LaneSnapshotCollector;
import com.immersivecinematics.immersive_cinematics.camera.source.WorldPointLocator;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * CAMERA 轨道播放器：TrackPlayer 接口实现 + 逐帧编排（数据源 / 时间推进 / 片段选取 / 写入分发 / 生命周期）。
 *
 * 职责边界：关键帧求值（六参数 / 插值 / 混合）归 {@link CameraKeyframeEvaluator}，重叠窗口的 lane 捕获归
 * {@link LaneSnapshotCollector}，结构 / 方块静态目标定位归 {@link WorldPointLocator}。
 * 写入契约：本轨本帧的顶层相机状态（六参数）由本类写入全局 {@link CameraManager} 写入缓冲；
 * 重叠窗口层级在下的 clip 只产 lane 快照、不写全局。
 * 线程契约：仅客户端主线程，逐渲染帧调用；帧内路径不新建集合。
 */
public class CameraTrackPlayer implements TrackPlayer {

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;
    private final CameraManager cameraManager;

    /** 目标解析与锁定：selector → 实体 + 按 {@code role + selector} 维护的锁与切换平滑状态 */
    private final EntityTargetResolver entityResolver = new EntityTargetResolver();
    /** 关键帧求值（六参数 / 插值 / 混合） */
    private final CameraKeyframeEvaluator evaluator;
    /** 本轨本帧的 lane 快照捕获与持有 */
    private final LaneSnapshotCollector laneCollector;

    public CameraTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, Vec3 originPos, CameraManager cameraManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.trackIndex = trackIndex;
        this.cameraManager = cameraManager;
        WorldPointLocator pointLocator = new WorldPointLocator();
        this.evaluator = new CameraKeyframeEvaluator(entityResolver, pointLocator, originPos);
        this.laneCollector = new LaneSnapshotCollector(entityResolver, evaluator);
    }

    /** 组 A：动态数据源（replaceScript 后自动用新数据，零重建） */
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

    @Override
    public boolean isActiveAt(float globalTime) {
        return evaluator.isActiveAt(clips(), globalTime);
    }

    @Override
    public void onRenderFrame(float globalTime) {
        // 本帧无写入 → lane 快照为空；有写入时由顶层路径与逐 clip 捕获填值
        laneCollector.clear();
        List<Clip> clips = clips();
        if (clips.isEmpty()) return;

        // 编辑器拖拽直控期间，相机由编辑器直驱（setCameraDirect），跳过轨道写入
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
                    if (!evaluator.isClipUsable(next)) {
                        evaluator.warnClipUnusableOnce();
                        return;
                    }
                    float weight = (globalTime - morphStart) / prev.getTransitionDuration();
                    // morph = 旧模型：两 clip 混合成一份相机状态 → 本轨本帧只产出一份 lane
                    // 合成参数取进入的片段（转场结束后留在屏上的是它），不做参数混合
                    CameraState morphState = evaluator.renderMorph(prev, next, weight, globalTime);
                    if (morphState != null) {
                        writeGlobal(morphState);
                        laneCollector.add(new CameraLane(morphState, next, globalTime - next.getStartTime()));
                    }
                    return;
                }
            }
        }

        // 顶层活跃片段（轨道顺序最后者；后面的 clip 覆盖前面）驱动相机
        List<Clip> active = evaluator.findActiveClips(clips, globalTime);
        if (active.isEmpty()) return;
        Clip topClip = active.get(active.size() - 1);
        // 目标不可用（结构/实体找不到）= 该片段按空处理（不写相机 → 玩家视角，与片段间隙同语义）
        if (!evaluator.isClipUsable(topClip)) {
            evaluator.warnClipUnusableOnce();
            return;
        }

        // 顶层 clip：正常求值（写全局相机状态，与顶层 lane 同源同值）
        CameraLane topLane = evaluator.renderSingle(globalTime, topClip, globalTime - topClip.getStartTime(), false);
        if (topLane != null) writeGlobal(topLane.state());
        // 重叠窗口内层级在下的活跃 clip：先捕获（列表顺序 = 绘制顺序，后面的在上）
        laneCollector.captureLowerLanes(globalTime, active, topClip);
        if (topLane != null) laneCollector.add(topLane);
    }

    /** 顶层写入：本轨本帧的六参数写入全局相机状态（写入缓冲 + 统一快照来源）。 */
    private void writeGlobal(CameraState state) {
        cameraManager.getPath().setPositionDirect(state.position());
        cameraManager.getProperties().setAllDirect(state.yaw(), state.pitch(), state.roll(), state.fov(), state.zoom());
    }

    @Override
    public void onStop() {
        laneCollector.clear();
        entityResolver.clear();
    }

    /** 组 A：数据替换后复位派生状态——lane 快照 / 目标锁（两者都按脚本数据重建）。 */
    @Override
    public void onScriptReplaced() {
        laneCollector.clear();
        entityResolver.clear();
    }
}
