package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数学工具类 — 相机系统共用的插值函数。
 *
 * {@code lerp} = 线性插值（{@code t} 不夹取）；{@code lerpAngle} = 度制角度环绕插值（走最短弧）；
 * {@code cubicBezier} = 三次贝塞尔曲线；{@code sanitize*} = NaN / Infinity 防护（非有限值回落缺省值）。
 * 曲线 / 缓动 / 动态响应模型（linear / smoothstep / ease_* / spring / chain / …）见 {@code math} 包。
 */
public final class MathUtil {

    private MathUtil() {}

    // ========== 基础插值 ==========

    /**
     * 线性插值 {@code from + (to - from) * t}。
     *
     * @param t 插值进度，不夹取（0 ~ 1 之外为外推）
     */
    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * 角度环绕插值（度）：差值先归一到 -180° ~ 180°，故走最短弧
     * （170° → -170° 经 180°，不是绕经 0°）；返回值不归一化。
     *
     * @param t 插值进度，不夹取
     */
    public static float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return from + diff * t;
    }

    // ========== 贝塞尔曲线 ==========

    /**
     * 三次贝塞尔曲线插值：B(t) = (1-t)³P0 + 3(1-t)²tP1 + 3(1-t)t²P2 + t³P3。
     * P0 / P3 = 起始 / 终止关键帧位置，P1 / P2 = {@code curve.control_points}。
     * 控制点几何：P1 == P2 时为正圆弧（重合点为圆心）；P1、P2 共线时为椭圆弧。
     *
     * @param t 进度 0 ~ 1
     */
    public static Vec3 cubicBezier(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        float u = 1f - t;
        float uu = u * u;
        float uuu = uu * u;
        float tt = t * t;
        float ttt = tt * t;

        double x = uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x;
        double y = uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y;
        double z = uuu * p0.z + 3 * uu * t * p1.z + 3 * u * tt * p2.z + ttt * p3.z;

        return new Vec3(x, y, z);
    }

    // ========== NaN/Infinity 防护 ==========

    /**
     * 浮点数 NaN / Infinity 防护：非有限值回落 {@code fallback}，不告警。
     *
     * @param fallback 该字段的缺省值
     */
    public static float sanitizeFloat(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/MathUtil");

    /** 已告警过的字段位置（同一位置只告警一次，避免逐帧采样路径刷屏）；上限防异常脚本撑爆内存。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final int WARN_LIMIT = 256;

    /**
     * 浮点数 NaN / Infinity 防护 + 限频告警：非有限值回落 {@code fallback}（= 该字段缺省值），
     * 同一 {@code what} 只告警一次，告警条目上限 {@link #WARN_LIMIT}。
     * 用于脚本数据入口：JSON 的 {@code NaN} / {@code Infinity} 字面量或 {@code 1e999} 溢出数会解析成
     * 非有限 float，不拦就经采样直达 shader，而 {@code clamp(NaN, 0, 1)} 在主流驱动取 0 = 整屏变黑。
     *
     * @param what 告警用的位置标识（如 {@code keyframe.gamma_r}）
     */
    public static float sanitizeFloatLogged(float value, float fallback, String what) {
        if (Float.isFinite(value)) {
            return value;
        }
        if (WARNED.size() < WARN_LIMIT && WARNED.add(what)) {
            LOGGER.warn("[脚本参数] {} 非有限值（{}）→ 回落缺省 {}（同一位置只告警一次）", what, value, fallback);
        }
        return fallback;
    }

    /**
     * Vec3 NaN / Infinity 防护：{@code null} 或任一分量非有限时回落 {@code fallback}。
     *
     * @param fallback 该字段的缺省向量
     */
    public static Vec3 sanitizeVec3(Vec3 value, Vec3 fallback) {
        if (value == null) return fallback;
        if (Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)) {
            return value;
        }
        return fallback;
    }
}
