package com.immersivecinematics.immersive_cinematics.script.object.position;

import net.minecraft.world.phys.Vec3;

/**
 * 路径切线：在某执行局部时间给出位置导数方向（世界轴分量）。
 *
 * <p>只负责"采样表达空间的导数"——点源 / 参考系变换由调用方负责（{@link PositionEvaluator}
 * 给出世界位置与参考系，切线在采样表达空间内计算）。实现见 {@link SampledPathTangent}。</p>
 */
public interface PathTangent {

    /**
     * 该时刻的切线方向（未归一化，长度 = 速度量级）。
     *
     * @param localTime 执行局部时间
     * @return 方向；不可用 = {@code null}
     */
    Vec3 direction(double localTime);
}
