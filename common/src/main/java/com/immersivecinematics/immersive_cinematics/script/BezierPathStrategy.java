package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 贝塞尔路径策略 — 弧长参数化的三次贝塞尔曲线插值
 * <p>
 * 在速度驱动模型下，输入的 t 是弧长进度 s ∈ [0,1]。
 * 使用 ArcLengthLUT 将 s 映射为贝塞尔参数 t_bezier，确保匀速运动。
 * <p>
 * 缓存 ArcLengthLUT 避免每帧重建。
 */
public class BezierPathStrategy implements PathStrategy {

    private final Map<LutKey, ArcLengthLUT> lutCache = new HashMap<>();

    @Override
    public Vec3 interpolate(Vec3 from, Vec3 to, float s, BezierCurve curve) {
        if (curve != null && curve.isValid()) {
            // 控制点世界化：相对控制点（dx/dy/dz）= 段起点 from + 偏移；绝对控制点（x/y/z）= 原值
            Vec3 p1 = curve.resolveP1(from);
            Vec3 p2 = curve.resolveP2(from);

            ArcLengthLUT lut = lutCache.computeIfAbsent(
                    new LutKey(from, p1, p2, to),
                    k -> new ArcLengthLUT(from, p1, p2, to)
            );

            float tBezier = lut.lookupT(s);
            return MathUtil.cubicBezier(from, p1, p2, to, tBezier);
        } else {
            return from.lerp(to, s);
        }
    }

    @Override
    public Vec3 tangent(Vec3 from, Vec3 to, float s, BezierCurve curve) {
        if (curve != null && curve.isValid()) {
            Vec3 p1 = curve.resolveP1(from);
            Vec3 p2 = curve.resolveP2(from);

            ArcLengthLUT lut = lutCache.computeIfAbsent(
                    new LutKey(from, p1, p2, to),
                    k -> new ArcLengthLUT(from, p1, p2, to)
            );

            // B'(u) = 3(1-u)^2(P1-P0) + 6(1-u)u(P2-P1) + 3u^2(P3-P2)
            float u = lut.lookupT(s);
            double a = 3.0 * (1.0 - u) * (1.0 - u);
            double b = 6.0 * (1.0 - u) * u;
            double c = 3.0 * u * u;
            return p1.subtract(from).scale(a)
                    .add(p2.subtract(p1).scale(b))
                    .add(to.subtract(p2).scale(c));
        }
        return to.subtract(from);
    }

    private record LutKey(Vec3 from, Vec3 p1, Vec3 p2, Vec3 to) {
        private LutKey {
            Objects.requireNonNull(from);
            Objects.requireNonNull(p1);
            Objects.requireNonNull(p2);
            Objects.requireNonNull(to);
        }
    }
}
