package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.world.phys.Vec3;

/**
 * 数学工具类 — 相机系统共用插值函数
 * <p>
 * 提供 lerp（线性插值）、lerpAngle（角度环绕插值）、
 * 多种插值曲线（smooth/easeIn/easeOut/easeInOut）、
 * 三次贝塞尔曲线、smoothstep 混合、NaN/Infinity 防护，
 * 供 CameraProperties、ScriptPlayer、KeyframeInterpolator 等组件共用。
 */
public final class MathUtil {

    private MathUtil() {} // 禁止实例化

    // ========== 基础插值 ==========

    /**
     * 线性插值
     *
     * @param from 起始值
     * @param to   目标值
     * @param t    插值进度 [0, 1]
     * @return 插值结果
     */
    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * 角度环绕插值（处理 -180° ~ 180° 的环绕跳变）
     * <p>
     * 例如：从 170° 到 -170°，正确路径经过 180°（仅 20° 差），
     * 而非绕远路经过 0°（340° 差）。
     *
     * @param from 起始角度（度）
     * @param to   目标角度（度）
     * @param t    插值进度 [0, 1]
     * @return 插值结果（度）
     */
    public static float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return from + diff * t;
    }

    // ========== 插值曲线 ==========

    /**
     * 平滑插值曲线（Hermite 插值：3t² - 2t³）
     * <p>
     * 缓入缓出效果，适合大多数相机运动。
     *
     * @param t 线性进度 [0, 1]
     * @return 平滑后的进度 [0, 1]
     */
    public static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    /**
     * 缓入曲线（二次：t²）
     * <p>
     * 起步慢，结束快。适合需要从静止加速的镜头运动。
     *
     * @param t 线性进度 [0, 1]
     * @return 缓入后的进度 [0, 1]
     */
    public static float easeIn(float t) {
        return t * t;
    }

    /**
     * 缓出曲线（二次：1 - (1-t)²）
     * <p>
     * 起步快，结束慢。适合需要减速停止的镜头运动。
     *
     * @param t 线性进度 [0, 1]
     * @return 缓出后的进度 [0, 1]
     */
    public static float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    /**
     * 缓入缓出曲线（两段二次曲线）
     * <p>
     * 两端慢，中间快。前半段使用 easeIn，后半段使用 easeOut。
     * 与 smooth（Hermite）的区别：easeInOut 在中点处速度更高，
     * 产生更明显的"快-慢"对比效果。
     *
     * @param t 线性进度 [0, 1]
     * @return 缓入缓出后的进度 [0, 1]
     */
    public static float easeInOut(float t) {
        if (t < 0.5f) {
            return 2f * t * t;
        } else {
            float t2 = 2f * t - 1f;
            return 0.5f + 0.5f * (1f - (1f - t2) * (1f - t2));
        }
    }

    // ========== 贝塞尔曲线 ==========

    /**
     * 三次贝塞尔曲线插值
     * <p>
     * B(t) = (1-t)³P0 + 3(1-t)²tP1 + 3(1-t)t²P2 + t³P3
     * <p>
     * P0=起始关键帧位置，P3=结束关键帧位置，
     * P1/P2=curve.control_points。
     * <p>
     * 特殊情况：
     * <ul>
     *   <li>P1 == P2（重叠）：控制点重合为圆心，P0→P3 做正圆弧运动</li>
     *   <li>P1、P2 在一条线上：镜头做椭圆弧线运动</li>
     * </ul>
     *
     * @param p0 起始点
     * @param p1 控制点1
     * @param p2 控制点2
     * @param p3 终止点
     * @param t  进度 [0, 1]
     * @return 贝塞尔曲线上的点
     */
    public static Vec3 cubicBezier(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        float u = 1f - t;
        float uu = u * u;
        float uuu = uu * u;
        float tt = t * t;
        float ttt = tt * t;

        // B(t) = (1-t)³P0 + 3(1-t)²tP1 + 3(1-t)t²P2 + t³P3
        double x = uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x;
        double y = uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y;
        double z = uuu * p0.z + 3 * uu * t * p1.z + 3 * u * tt * p2.z + ttt * p3.z;

        return new Vec3(x, y, z);
    }

    // ========== Crossfade 混合 ==========

    /**
     * Smoothstep 混合函数
     * <p>
     * 在 edge0 和 edge1 之间对 x 进行平滑插值，
     * 结果被限制在 [0, 1] 范围内。
     * <p>
     * 公式：t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
     *       result = t² * (3 - 2t)
     * <p>
     * 用途：crossfade 过渡区间内的平滑混合权重计算。
     * <p>
     * 边界情况：当 edge0 == edge1 时，x >= edge0 返回 1，否则返回 0，
     * 避免 0/0 产生 NaN。
     *
     * @param edge0 下边界
     * @param edge1 上边界
     * @param x     输入值
     * @return 平滑插值结果 [0, 1]
     */
    public static float smoothstep(float edge0, float edge1, float x) {
        if (edge0 == edge1) {
            return (x >= edge0) ? 1f : 0f;
        }
        float t = (x - edge0) / (edge1 - edge0);
        t = Math.max(0f, Math.min(1f, t)); // clamp [0, 1]
        return t * t * (3f - 2f * t);
    }

    // ========== 厄米（Hermite）基函数 — 速度曲线引擎 ==========

    /**
     * 厄米基函数 h₀₀(t) = 2t³ - 3t² + 1
     * <p>
     * 边界值：h₀₀(0)=1, h₀₀(1)=0, h'₀₀(0)=0, h'₀₀(1)=0
     */
    public static float h00(float t) {
        return 2f * t * t * t - 3f * t * t + 1f;
    }

    /**
     * 厄米基函数 h₁₀(t) = t³ - 2t² + t
     * <p>
     * 边界值：h'₁₀(0)=1, h'₁₀(1)=0
     */
    public static float h10(float t) {
        return t * t * t - 2f * t * t + t;
    }

    /**
     * 厄米基函数 h₀₁(t) = -2t³ + 3t²
     * <p>
     * 边界值：h₀₁(0)=0, h₀₁(1)=1, h'₀₁(0)=0, h'₀₁(1)=0
     */
    public static float h01(float t) {
        return -2f * t * t * t + 3f * t * t;
    }

    /**
     * 厄米基函数 h₁₁(t) = t³ - t²
     * <p>
     * 边界值：h'₁₁(0)=0, h'₁₁(1)=1
     */
    public static float h11(float t) {
        return t * t * t - t * t;
    }

    /**
     * 厄米基函数不定积分 ∫h₀₀(t)dt = t⁴/2 - t³ + t
     * <p>
     * 从 0 到 t 的定积分：∫₀ᵗ h₀₀(τ)dτ
     */
    public static float ih00(float t) {
        return t * t * t * t / 2f - t * t * t + t;
    }

    /**
     * 厄米基函数不定积分 ∫h₁₀(t)dt = t⁴/4 - 2t³/3 + t²/2
     * <p>
     * 从 0 到 t 的定积分：∫₀ᵗ h₁₀(τ)dτ
     */
    public static float ih10(float t) {
        return t * t * t * t / 4f - 2f * t * t * t / 3f + t * t / 2f;
    }

    /**
     * 厄米基函数不定积分 ∫h₀₁(t)dt = -t⁴/2 + t³
     * <p>
     * 从 0 到 t 的定积分：∫₀ᵗ h₀₁(τ)dτ
     */
    public static float ih01(float t) {
        return -t * t * t * t / 2f + t * t * t;
    }

    /**
     * 厄米基函数不定积分 ∫h₁₁(t)dt = t⁴/4 - t³/3
     * <p>
     * 从 0 到 t 的定积分：∫₀ᵗ h₁₁(τ)dτ
     */
    public static float ih11(float t) {
        return t * t * t * t / 4f - t * t * t / 3f;
    }

    // ========== NaN/Infinity 防护 ==========

    /**
     * 浮点数 NaN/Infinity 防护
     * <p>
     * 如果 value 为 NaN 或 Infinity，返回 fallback 值。
     * 防止脚本数据异常导致相机位置/角度变为无效值。
     *
     * @param value   待检查的值
     * @param fallback 备用值
     * @return value（如果合法），否则 fallback
     */
    public static float sanitizeFloat(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    /**
     * Vec3 NaN/Infinity 防护
     * <p>
     * 如果 value 的任一分量为 NaN 或 Infinity，返回 fallback 值。
     * 防止脚本数据异常导致相机位置变为无效值。
     *
     * @param value   待检查的向量
     * @param fallback 备用向量
     * @return value（如果所有分量合法），否则 fallback
     */
    public static Vec3 sanitizeVec3(Vec3 value, Vec3 fallback) {
        if (value == null) return fallback;
        if (Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)) {
            return value;
        }
        return fallback;
    }
}
