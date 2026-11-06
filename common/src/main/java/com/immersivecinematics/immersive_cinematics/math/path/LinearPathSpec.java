package com.immersivecinematics.immersive_cinematics.math.path;

import com.immersivecinematics.immersive_cinematics.spi.PathSpec;
import net.minecraft.world.phys.Vec3;

/**
 * 线性路径：{@code position(u) = lerp(from, to, normalize(u))}，导数恒为 {@code (to-from)/span}。
 * 定义域 {@link PathDomain#UNIT}，锚点 {@link PathAnchor#ENDPOINTS}；零长时位置恒为 {@code from}、导数为零向量。
 */
public final class LinearPathSpec implements PathSpec {

    /** 无状态单例。 */
    public static final LinearPathSpec INSTANCE = new LinearPathSpec();

    private LinearPathSpec() {
    }

    @Override
    public String type() {
        return "linear";
    }

    @Override
    public PathDomain domain() {
        return PathDomain.UNIT;
    }

    @Override
    public PathAnchor anchor() {
        return PathAnchor.ENDPOINTS;
    }

    @Override
    public PathExpression bind(Vec3 from, Vec3 to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("端点不能为 null: from=" + from + ", to=" + to);
        }
        return new LinearExpression(from, to);
    }

    private static final class LinearExpression implements PathExpression {

        private final Vec3 from;
        private final Vec3 to;
        private final Vec3 delta;
        private final double invSpan;
        private final boolean degenerate;

        LinearExpression(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
            this.delta = to.subtract(from);
            this.invSpan = 1.0 / PathDomain.UNIT.span();
            this.degenerate = delta.x == 0.0 && delta.y == 0.0 && delta.z == 0.0;
        }

        @Override
        public String type() {
            return "linear";
        }

        @Override
        public PathDomain domain() {
            return PathDomain.UNIT;
        }

        @Override
        public Vec3 position(double u) {
            if (degenerate) {
                return from;
            }
            return PathMath.lerp(from, to, PathDomain.UNIT.normalize(u));
        }

        @Override
        public Vec3 derivative(double u) {
            if (degenerate) {
                return Vec3.ZERO;
            }
            return delta.scale(invSpan);
        }

        /** 导数恒定，模长 = {@code |to-from| / span}；零长返回 0。 */
        @Override
        public double speed(double u) {
            if (degenerate) {
                return 0.0;
            }
            return Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z) * invSpan;
        }
    }
}
