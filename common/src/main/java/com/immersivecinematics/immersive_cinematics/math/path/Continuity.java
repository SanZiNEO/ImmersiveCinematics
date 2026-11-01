package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 段间几何连续性算子：给定相邻两段的路径表达式，量出端点位置差、切向夹角、曲率差，
 * 据此判定实际达到的 G0 / G1 / G2 阶次。
 *
 * <p>三项量都是「接缝处两侧」的差，不是整条曲线的误差：
 * <ul>
 *   <li>G0：{@code |P_left(end) - P_right(start)|}，容差 {@link #POSITION_TOLERANCE}（世界单位）。</li>
 *   <li>G1：{@code acos(P'_left·P'_right / (|P'_left||P'_right|))}，容差 {@link #TANGENT_TOLERANCE}（rad）。
 *       角度落在 {@code [0, π]}，反向切线给 π，因此「同向」已包含在角度里；任一端模长
 *       {@code < 1e-12}（驻点 / 尖点，方向无定义）记 {@code +Inf}，直接判不满足。</li>
 *   <li>G2：{@code |κ_left - κ_right| / max(|κ_left|, |κ_right|)}，容差 {@link #CURVATURE_TOLERANCE}
 *       （相对）；两端曲率绝对值都 {@code < 1e-9} 时改用绝对差
 *       {@link #CURVATURE_ABSOLUTE_TOLERANCE}，避免「0 / 0」把微小曲率放大成巨大相对误差。</li>
 * </ul>
 *
 * <p>判定是「从 G2 往下退」：三项全过 = G2；位置与切向过 = G1；只有位置过 = G0；
 * 位置也不过 = {@code achieved == null}（{@link Report#satisfied()} 返 {@code false}）。
 * 声明高于实际时只降级不修改几何，调用方据此决定告警或插入过渡段。
 */
public final class Continuity {

    /** G0 位置容差（世界单位）。 */
    public static final double POSITION_TOLERANCE = 1e-9;

    /** G1 切向夹角容差（rad）。 */
    public static final double TANGENT_TOLERANCE = 1e-6;

    /** G2 曲率相对容差。 */
    public static final double CURVATURE_TOLERANCE = 1e-6;

    /** 两端曲率都接近零时的 G2 绝对容差。 */
    public static final double CURVATURE_ABSOLUTE_TOLERANCE = 1e-9;

    /** 切线模长低于此值时方向无定义。 */
    private static final double MIN_TANGENT = 1e-12;

    /** 曲率绝对值低于此值按「κ ≈ 0」处理。 */
    private static final double NEAR_ZERO_CURVATURE = 1e-9;

    private Continuity() {
    }

    /** 几何连续性阶次；{@code ordinal()} 递增即阶次递增，可直接比较。 */
    public enum Order {
        G0,
        G1,
        G2
    }

    /**
     * 一次接缝的校验结果。
     *
     * @param declared       声明的阶次
     * @param achieved       实际达到的阶次；位置都不连续时为 {@code null}
     * @param positionGap    端点位置距离（世界单位）
     * @param tangentAngle   切向夹角（rad）；某端切线退化时为 {@code +Inf}
     * @param curvatureError 曲率差（κ≈0 时为绝对差，否则相对差）
     */
    public record Report(Order declared, Order achieved, double positionGap, double tangentAngle,
                         double curvatureError) {

        public Report {
            if (declared == null) {
                throw new IllegalArgumentException("declared 不能为 null");
            }
        }

        /** 实际达到的阶次是否不低于声明；{@code achieved == null} 时返 {@code false}。 */
        public boolean satisfied() {
            return achieved != null && achieved.ordinal() >= declared.ordinal();
        }
    }

    /**
     * 校验 {@code left} 末端与 {@code right} 首端的接缝。
     *
     * @param left     前一段表达式，取 {@code domain().max()} 处求值
     * @param right    后一段表达式，取 {@code domain().min()} 处求值
     * @param declared 声明的最低连续性
     * @return 三项实测误差与降级后的实际阶次
     * @throws IllegalArgumentException 任一参数为 null
     */
    public static Report check(PathExpression left, PathExpression right, Order declared) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("相邻段不能为 null: left=" + left + ", right=" + right);
        }
        if (declared == null) {
            throw new IllegalArgumentException("declared 不能为 null");
        }
        double leftU = left.domain().max();
        double rightU = right.domain().min();

        double positionGap = left.position(leftU).distanceTo(right.position(rightU));

        Vec3 leftTangent = left.derivative(leftU);
        Vec3 rightTangent = right.derivative(rightU);
        double leftSpeed = PathMath.length(leftTangent);
        double rightSpeed = PathMath.length(rightTangent);
        double tangentAngle = leftSpeed < MIN_TANGENT || rightSpeed < MIN_TANGENT
                ? Double.POSITIVE_INFINITY
                : angle(leftTangent, rightTangent);

        double leftKappa = Curvature.kappa(left, leftU);
        double rightKappa = Curvature.kappa(right, rightU);
        double curvatureError;
        if (Math.abs(leftKappa) < NEAR_ZERO_CURVATURE && Math.abs(rightKappa) < NEAR_ZERO_CURVATURE) {
            curvatureError = Math.abs(leftKappa - rightKappa);
        } else {
            curvatureError = Math.abs(leftKappa - rightKappa) / Math.max(Math.abs(leftKappa), Math.abs(rightKappa));
        }

        Order achieved = null;
        boolean positionContinuous = positionGap <= POSITION_TOLERANCE;
        boolean tangentContinuous = positionContinuous && tangentAngle <= TANGENT_TOLERANCE;
        if (tangentContinuous && curvatureError <= CURVATURE_TOLERANCE) {
            achieved = Order.G2;
        } else if (tangentContinuous) {
            achieved = Order.G1;
        } else if (positionContinuous) {
            achieved = Order.G0;
        }
        return new Report(declared, achieved, positionGap, tangentAngle, curvatureError);
    }

    /** 两个非零切向量的夹角（rad），落在 {@code [0, π]}；余弦先夹取再 acos。 */
    private static double angle(Vec3 a, Vec3 b) {
        double denominator = PathMath.length(a) * PathMath.length(b);
        double cosine = a.dot(b) / denominator;
        if (cosine > 1.0) {
            cosine = 1.0;
        } else if (cosine < -1.0) {
            cosine = -1.0;
        }
        return Math.acos(cosine);
    }
}
