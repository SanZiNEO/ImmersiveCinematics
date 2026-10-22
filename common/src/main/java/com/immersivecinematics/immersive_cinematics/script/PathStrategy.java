package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 路径策略：解耦 KeyframeInterpolator 与具体路径插值算法。
 * 实现按 {@code curve.type} 名称在 {@link PathStrategies} 注册表登记，运行时按名取用。
 */
@FunctionalInterface
public interface PathStrategy {

    /**
     * 计算路径上指定参数位置的点。
     *
     * @param from  起始关键帧位置
     * @param to    终止关键帧位置
     * @param t     归一化参数 [0, 1]（速度驱动模型下 = 弧长进度）
     * @param curve 贝塞尔控制点（可为 null）
     * @return 插值后的位置
     */
    Vec3 interpolate(Vec3 from, Vec3 to, float t, BezierCurve curve);

    /**
     * 计算路径上指定参数位置的切线方向（未归一化）。
     * 默认实现为直线方向；曲线策略应覆写为真实导数。
     *
     * @param from  起始关键帧位置
     * @param to    终止关键帧位置
     * @param t     归一化参数 [0, 1]
     * @param curve 贝塞尔控制点（可为 null）
     * @return 切线向量（可为零向量，调用方自行兜底）
     */
    default Vec3 tangent(Vec3 from, Vec3 to, float t, BezierCurve curve) {
        return to.subtract(from);
    }
}
