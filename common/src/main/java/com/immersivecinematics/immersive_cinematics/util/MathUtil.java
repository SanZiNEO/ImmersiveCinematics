package com.immersivecinematics.immersive_cinematics.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数学工具类 — 相机系统共用的插值函数。
 *
 * {@code lerp} = 线性插值（{@code t} 不夹取）；{@code sanitizeFloatLogged} = NaN / Infinity 防护 + 限频告警。
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

    // ========== NaN/Infinity 防护 ==========

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
}
