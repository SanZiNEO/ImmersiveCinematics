package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.world.phys.Vec3;

/**
 * 贝塞尔路径策略 — 弧长参数化的三次贝塞尔曲线插值
 * <p>
 * 在速度驱动模型下，输入的 t 是弧长进度 s ∈ [0,1]。
 * 使用 ArcLengthLUT 将 s 映射为贝塞尔参数 t_bezier，确保匀速运动。
 * <p>
 * 当 curve 为 null 或无效时，退化为线性插值（线性路径天然匀速，无需弧长参数化）。
 */
public class BezierPathStrategy implements PathStrategy {

    @Override
    public Vec3 interpolate(Vec3 from, Vec3 to, float s, BezierCurve curve) {
        if (curve != null && curve.isValid()) {
            Vec3 p1 = curve.getP1();
            Vec3 p2 = curve.getP2();

            // 弧长参数化：将匀速弧长进度 s 映射为贝塞尔参数 t_bezier
            ArcLengthLUT lut = new ArcLengthLUT(from, p1, p2, to);
            float tBezier = lut.lookupT(s);

            return MathUtil.cubicBezier(from, p1, p2, to, tBezier);
        } else {
            // 线性路径天然匀速，无需弧长参数化
            return from.lerp(to, s);
        }
    }
}
