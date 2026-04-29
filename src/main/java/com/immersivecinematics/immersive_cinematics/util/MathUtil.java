package com.immersivecinematics.immersive_cinematics.util;

/**
 * 数学工具类 — 相机系统共用插值函数
 * <p>
 * 提供 lerp（线性插值）和 lerpAngle（角度环绕插值），
 * 供 CameraProperties、CameraTestPlayer、ScriptPlayer 等组件共用。
 */
public final class MathUtil {

    private MathUtil() {} // 禁止实例化

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
}
