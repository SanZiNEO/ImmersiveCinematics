package com.immersivecinematics.immersive_cinematics.camera.rule;

import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;

/**
 * 规则动作工件账本：{@code camera_set} / {@code overlay_set} / {@code adjust_set} 的实例级工件在触发帧
 * {@link #attachArtifact 接入}、片段活跃窗末 / 账本重建 / 停播时回收（同类再次触发 = 覆盖）。
 *
 * <p>每播放实例一份。工件回收时刻 = 本批动作所属片段的活跃窗末（{@link #setWindowEnd} 由动作执行方在
 * 接入前写入）；帧驱动每帧调 {@link #expireArtifacts(double)} 到期回收。</p>
 *
 * <p>分配契约：槽位数组 + 窗末平行数组在触发帧按需扩容，回收路径零分配。线程契约：仅客户端主线程。</p>
 */
public final class RuleArtifactLedger {

    /** 动作作用对象（本实例）；未接线（探针）时为 {@code null}。 */
    private PlaybackInstance owner;

    /** 已接入的实例级工件（同类至多一件；槽位数组 + 窗末平行数组）。 */
    private RuleArtifact[] artifactSlots = new RuleArtifact[0];

    /** 各工件的回收时刻（时间轴读数秒；到达即回收）。 */
    private double[] artifactWindowEnd = new double[0];

    private int artifactCount = 0;

    /** 本批动作的注册窗末（{@link #attachArtifact} 记进工件）。 */
    private double actionWindowEnd = Double.MAX_VALUE;

    /** 接线动作作用对象（{@code PlaybackInstance} 构造时调用一次）。 */
    public void attachOwner(PlaybackInstance owner) {
        this.owner = owner;
    }

    /** 本批动作的注册窗末（= 触发动作的片段活跃窗末；接入前由动作执行方写入）。 */
    public void setWindowEnd(double windowEnd) {
        this.actionWindowEnd = windowEnd;
    }

    /**
     * 接入一件规则动作工件（{@code camera_set} / {@code overlay_set} / {@code adjust_set} 触发时）。
     * 窗末 = 本批动作所属片段的活跃窗末（{@link #setWindowEnd}），{@link #expireArtifacts} 到期回收；
     * 同类工件先回收旧的（一实例同类至多一件 = 触发即覆盖）。
     */
    public void attachArtifact(RuleArtifact artifact) {
        if (artifact == null) return;
        for (int i = artifactCount - 1; i >= 0; i--) {
            if (artifactSlots[i].kind() == artifact.kind()) {
                artifactSlots[i].release();
                removeArtifact(i);
            }
        }
        if (artifactCount >= artifactSlots.length) growArtifacts();
        artifactSlots[artifactCount] = artifact;
        artifactWindowEnd[artifactCount] = actionWindowEnd;
        artifactCount++;
        artifact.attach();
    }

    /** 片段窗末到期回收（{@code timeline} 已达回收时刻即 {@link RuleArtifact#release()}）。 */
    public void expireArtifacts(double timeline) {
        for (int i = artifactCount - 1; i >= 0; i--) {
            if (timeline >= artifactWindowEnd[i]) {
                artifactSlots[i].release();
                removeArtifact(i);
            }
        }
    }

    /** 回收全部工件（账本重建 / 停播）。 */
    public void releaseArtifacts() {
        for (int i = 0; i < artifactCount; i++) {
            artifactSlots[i].release();
            artifactSlots[i] = null;
        }
        artifactCount = 0;
    }

    /** 账本复位（{@code stop} 时调用：不留残件）。 */
    public void reset() {
        releaseArtifacts();
    }

    /** 摘除一个槽位（尾元素填坑，保序无关：同类覆盖与到期回收都不依赖顺序）。 */
    private void removeArtifact(int index) {
        int last = artifactCount - 1;
        artifactSlots[index] = artifactSlots[last];
        artifactWindowEnd[index] = artifactWindowEnd[last];
        artifactSlots[last] = null;
        artifactCount = last;
    }

    /** 扩容工件槽位（只在触发帧发生）。 */
    private void growArtifacts() {
        int size = Math.max(2, artifactSlots.length * 2);
        RuleArtifact[] slots = new RuleArtifact[size];
        double[] ends = new double[size];
        System.arraycopy(artifactSlots, 0, slots, 0, artifactCount);
        System.arraycopy(artifactWindowEnd, 0, ends, 0, artifactCount);
        artifactSlots = slots;
        artifactWindowEnd = ends;
    }
}
