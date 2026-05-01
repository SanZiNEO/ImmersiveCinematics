package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 路径策略接口 — 解耦 KeyframeInterpolator 与具体路径插值算法
 * <p>
 * 当前实现：{@link BezierPathStrategy}（三次贝塞尔曲线）
 * <p>
 * 未来可扩展：
 * <ul>
 *   <li>CatmullRomPathStrategy — Catmull-Rom 样条</li>
 *   <li>LinearPathStrategy — 线性插值</li>
 *   <li>CustomPathStrategy — 用户自定义路径</li>
 * </ul>
 * <p>
 * 注册方式：在 {@link PathStrategies} 注册表中按 curve.type 名称注册。
 */
@FunctionalInterface
public interface PathStrategy {

    /**
     * 计算路径上指定参数位置的点
     *
     * @param from  起始关键帧位置
     * @param to    终止关键帧位置
     * @param t     归一化参数 [0, 1]
     * @param curve 贝塞尔控制点（可为 null）
     * @return 插值后的位置
     */
    Vec3 interpolate(Vec3 from, Vec3 to, float t, BezierCurve curve);
}
