package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.world.phys.Vec3;

/**
 * 贝塞尔路径策略 — 三次贝塞尔曲线插值
 * <p>
 * 使用 BezierCurve 的 P1/P2 控制点与 from/to 端点构成三次贝塞尔曲线。
 * 当 curve 为 null 或无效时，退化为线性插值。
 */
public class BezierPathStrategy implements PathStrategy {

    @Override
    public Vec3 interpolate(Vec3 from, Vec3 to, float t, BezierCurve curve) {
        if (curve != null && curve.isValid()) {
            Vec3 p1 = curve.getP1();
            Vec3 p2 = curve.getP2();
            return MathUtil.cubicBezier(from, p1, p2, to, t);
        } else {
            return from.lerp(to, t);
        }
    }
}
