package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;

import java.util.List;

/**
 * 速度曲线引擎 — 将线性时间进度映射为弧长进度 s
 * <p>
 * 核心公式链：
 * <pre>
 * t ──[Hermite 样条/线性]──→ v(t) ──[积分+归一化]──→ s(t) ∈ [0,1]
 * </pre>
 * <p>
 * 其中 s(t) 是归一化弧长进度，直接用于属性插值：
 * <ul>
 *   <li>贝塞尔路径：s → ArcLengthLUT → cubicBezier (匀速)</li>
 *   <li>其他属性：floatLerp(from, to, s)</li>
 * </ul>
 * <p>
 * 两种速度曲线：
 * <ul>
 *   <li>{@link InterpolationType#LINEAR} — 速度线性变化，解析积分</li>
 *   <li>{@link InterpolationType#SMOOTH} — Hermite 样条，C¹ 连续，curve_bias 可控</li>
 * </ul>
 */
public final class SpeedCurve {

    /** curve_bias 的切线缩放常数 */
    private static final float TANGENT_SCALE = 1.0f;

    private SpeedCurve() {}

    /**
     * 计算指定关键帧段的归一化弧长进度 s
     *
     * @param t             段内线性时间进度 [0, 1]
     * @param segmentIndex  段索引（在 keyframes 列表中的 from 索引）
     * @param keyframes     当前片段的所有关键帧
     * @param interpolation 速度曲线类型
     * @return 弧长进度 s ∈ [0, 1]
     */
    public static float computeProgress(float t, int segmentIndex,
                                        List<CameraKeyframe> keyframes,
                                        InterpolationType interpolation) {
        CameraKeyframe from = keyframes.get(segmentIndex);
        CameraKeyframe to = keyframes.get(segmentIndex + 1);
        float s0 = from.getSpeed();
        float s1 = to.getSpeed();

        if (interpolation == InterpolationType.LINEAR) {
            return integrateLinear(t, s0, s1);
        }

        // SMOOTH — Hermite 样条
        float p0 = computeTangent(segmentIndex, keyframes);
        float p1 = computeTangent(segmentIndex + 1, keyframes);
        return integrateHermite(t, s0, s1, p0, p1);
    }

    /**
     * 计算指定关键帧处的 Hermite 切线
     * <p>
     * 算法：
     * <ol>
     *   <li>计算默认切线（Catmull-Rom 风格，边界使用前向/后向差分）</li>
     *   <li>应用 curve_bias 偏移：p = p_default + curveBias * offset</li>
     * </ol>
     *
     * @param index     关键帧索引
     * @param keyframes 所有关键帧
     * @return Hermite 切线值
     */
    public static float computeTangent(int index, List<CameraKeyframe> keyframes) {
        int n = keyframes.size();
        float speed = keyframes.get(index).getSpeed();
        float curveBias = keyframes.get(index).getCurveBias();

        float defaultTangent;
        float offsetDir;

        if (n == 1) {
            return 0f;
        } else if (index == 0) {
            // 第一个关键帧：前向差分
            float speedNext = keyframes.get(1).getSpeed();
            defaultTangent = speedNext - speed;
            offsetDir = speedNext - speed;
        } else if (index == n - 1) {
            // 最后一个关键帧：后向差分
            float speedPrev = keyframes.get(n - 2).getSpeed();
            defaultTangent = speed - speedPrev;
            offsetDir = speed - speedPrev;
        } else {
            // 内部关键帧：Catmull-Rom 默认切线
            float speedPrev = keyframes.get(index - 1).getSpeed();
            float speedNext = keyframes.get(index + 1).getSpeed();
            defaultTangent = (speedNext - speedPrev) / 2f;
            offsetDir = speedNext - speed;
        }

        return defaultTangent + curveBias * offsetDir * TANGENT_SCALE;
    }

    /**
     * 线性速度曲线积分 — 归一化弧长进度
     * <p>
     * v(t) = s0 + (s1 - s0) * t
     * ∫₀ᵗ v(τ)dτ = s0*t + (s1-s0)*t²/2
     * 归一化：除以 ∫₀¹ v(τ)dτ = (s0 + s1) / 2
     */
    static float integrateLinear(float t, float s0, float s1) {
        float integral = s0 * t + (s1 - s0) * t * t / 2f;
        float total = (s0 + s1) / 2f;
        if (total == 0f) return t >= 1f ? 1f : 0f;
        return Math.max(0f, Math.min(1f, integral / total));
    }

    /**
     * Hermite 样条速度曲线积分 — 归一化弧长进度
     * <p>
     * v(t) = h₀₀(t)·s₀ + h₁₀(t)·p₀ + h₀₁(t)·s₁ + h₁₁(t)·p₁
     * ∫₀ᵗ v(τ)dτ = s₀·∫h₀₀ + p₀·∫h₁₀ + s₁·∫h₀₁ + p₁·∫h₁₁
     * 归一化：除以 ∫₀¹ v(τ)dτ
     */
    static float integrateHermite(float t, float s0, float s1, float p0, float p1) {
        float integral = s0 * MathUtil.ih00(t) + p0 * MathUtil.ih10(t)
                       + s1 * MathUtil.ih01(t) + p1 * MathUtil.ih11(t);
        float total = s0 * MathUtil.ih00(1f) + p0 * MathUtil.ih10(1f)
                    + s1 * MathUtil.ih01(1f) + p1 * MathUtil.ih11(1f);
        if (total == 0f) return t >= 1f ? 1f : 0f;
        return Math.max(0f, Math.min(1f, integral / total));
    }
}
