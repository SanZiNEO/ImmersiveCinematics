package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 曲率算子：{@code kappa = |P' × P''| / |P'|³}，世界单位倒数。
 * {@code |P'| < 1e-9} 时返回 0（尖点 / 驻点不产生除零）。
 */
public final class Curvature {

    private static final double MIN_SPEED = 1e-9;

    private Curvature() {
    }

    /** u 处曲率；一阶导接近零返回 0。 */
    public static double kappa(PathExpression expression, double u) {
        if (expression == null) {
            throw new IllegalArgumentException("expression 不能为 null");
        }
        Vec3 first = expression.derivative(u);
        double speed = PathMath.length(first);
        if (speed < MIN_SPEED) {
            return 0.0;
        }
        Vec3 second = expression.secondDerivative(u);
        return PathMath.length(PathMath.cross(first, second)) / (speed * speed * speed);
    }
}
