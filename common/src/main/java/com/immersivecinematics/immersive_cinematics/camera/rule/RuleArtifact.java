package com.immersivecinematics.immersive_cinematics.camera.rule;

/**
 * 规则动作建出的实例级工件：{@code camera_set} 的覆盖链 Modifier /
 * {@code overlay_set} 的临时层 / {@code adjust_set} 的规则调整层——三类动作的共同形态：
 * 触发那一刻 {@link #attach()}，片段活跃窗末（{@code RuleEngine} 逐帧到期回收）/
 * 规则引擎重建（脚本替换）/ 停播时 {@link #release()}。
 *
 * 一实例同类至多一件：同类工件再次触发 = 覆盖（引擎先回收旧件，见
 * {@link RuleEngine#attachArtifact}）。
 * 分配契约：工件在触发帧建出（非稳态路径），帧内只做 {@link #kind()} 比较与到期回收（零分配）。
 * 线程契约：仅客户端主线程。
 */
public interface RuleArtifact {

    /** 工件种类（同类覆盖的判据；一个动作一种）。 */
    Kind kind();

    /** 接入（触发时；由 {@link RuleEngine#attachArtifact} 调用）。 */
    void attach();

    /** 回收（片段活跃窗末 / 引擎重建 / 停播；幂等——可能被覆盖路径与到期路径各调一次）。 */
    void release();

    /** 工件种类。 */
    enum Kind {
        /** {@code camera_set}：覆盖链 Modifier。 */
        CAMERA,
        /** {@code overlay_set}：覆盖层临时层。 */
        OVERLAY,
        /** {@code adjust_set}：master 规则调整层。 */
        ADJUST
    }
}
