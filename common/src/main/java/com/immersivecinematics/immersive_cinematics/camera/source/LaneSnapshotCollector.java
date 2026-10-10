package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.script.CameraLane;
import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本轨本帧画面 lane 快照的捕获与持有：顶层片段的快照由编排层求值后交给本类，重叠窗口内层级在下的
 * 活跃片段由本类逐 clip 捕获（与顶层同一条求值链，只捕获不写）。
 *
 * 列表契约：按轨道内 clip 顺序排列——后面的在上（与 lane 合成的绘制顺序一致：先画的在下，后画的盖在上面）；
 * 最后一个元素 = 顶层相机（写入全局的那一份状态）。空列表 = 本轨本帧不产出画面
 * （片段间隙 / 目标不可用 / 编辑器直控）。morph 过渡窗口是旧模型（两 clip 混合成一份相机状态），
 * 本轨本帧仍只产出一份 lane（合成参数取进入的片段 next）。
 * 捕获隔离：捕获前经 {@link EntityTargetResolver#snapshotLockState()} 留底，逐 clip
 * {@link EntityTargetResolver#restoreLockState()} 回写，并留底 / 回写 {@link CameraKeyframeEvaluator}
 * 的基准坐标系——每个 clip 都从同一份状态出发（等价「单独求值」），捕获求值对扫描 / 切换 / 平滑的改动一律丢弃。
 * 线程契约：仅客户端主线程，顺序求值，无并发共享问题。
 */
public final class LaneSnapshotCollector {

    private final EntityTargetResolver entityResolver;
    private final CameraKeyframeEvaluator evaluator;

    private final List<CameraLane> laneSnapshots = new ArrayList<>(2);
    private final List<CameraLane> laneSnapshotsView = Collections.unmodifiableList(laneSnapshots);

    public LaneSnapshotCollector(EntityTargetResolver entityResolver, CameraKeyframeEvaluator evaluator) {
        this.entityResolver = entityResolver;
        this.evaluator = evaluator;
    }

    /** 只读视图（避免每次取用时包装）。 */
    public List<CameraLane> getLaneSnapshots() {
        return laneSnapshotsView;
    }

    public void clear() {
        laneSnapshots.clear();
    }

    public void add(CameraLane lane) {
        laneSnapshots.add(lane);
    }

    /**
     * 重叠窗口内层级在下的活跃 clip 的 lane 快照：顺序求值、逐 clip 捕获。
     *
     * 与顶层 clip 走同一条求值链（位置 / 朝向 / 注视 / 呼吸），只是"只捕获不写"：不写全局相机状态
     * （全局仍由顶层 clip 决定）、不推进跨帧求值状态（{@code lastWorldPos}），本帧基准坐标系与
     * 目标锁状态在捕获前后整体留底 / 回写——每个 clip 都从同一份状态出发（等价"单独求值"）。
     *
     * @param active  本帧全部活跃 clip（按轨道顺序，最后一个 = 顶层）
     * @param topClip 顶层活跃 clip（已由正常路径求值，这里跳过）
     */
    public void captureLowerLanes(float globalTime, List<Clip> active, Clip topClip) {
        if (active.size() < 2) return;  // 无重叠：本轨本帧只有顶层一份快照（单 clip 行为不变）

        // 捕获求值的"隔离基线"：目标锁状态深拷贝留底（捕获求值只在这份基线之上进行，事后丢弃）
        entityResolver.snapshotLockState();
        evaluator.snapshotFrameState();
        try {
            for (int i = 0; i < active.size() - 1; i++) {
                Clip clip = active.get(i);
                if (clip == topClip) continue;  // 防御：顶层只走正常路径
                // 每个 clip 都从同一份目标锁状态出发（不受兄弟 clip 捕获求值影响）
                entityResolver.restoreLockState();
                // 目标不可用 = 该 clip 本帧无画面（与顶层 clip 同语义：不产出 lane）
                if (!evaluator.isClipUsable(clip)) {
                    evaluator.warnClipUnusableOnce();
                    continue;
                }
                CameraLane snapshot = evaluator.renderSingle(globalTime, clip, globalTime - clip.getStartTime(), true);
                if (snapshot != null) laneSnapshots.add(snapshot);
            }
        } finally {
            entityResolver.restoreLockState();
            evaluator.restoreFrameState();
        }
    }
}
