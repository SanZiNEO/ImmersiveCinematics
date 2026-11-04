package com.immersivecinematics.immersive_cinematics.spi;

import java.util.Set;

/**
 * 关键帧之间的插值方式。
 *
 * <p>缺省推导见 {@link #infer(PropertyType, String, String)}；显式声明覆盖推导。
 * validator 拒绝不兼容组合（例如 {@code bool} + {@code linear}）；运行时只按已声明的插值求值。</p>
 */
public enum Interp {
    /** 不插值：value-only / 事件属性。 */
    NONE,
    /** 线性：{@code a + (b - a) * s}。 */
    LINEAR,
    /** 角度最短路径；仅 float / nullable_float。 */
    ANGLE,
    /** 几何插值：{@code exp(log a + (log b - log a) * s)}；仅 float（{@code zoom}）。 */
    GEOMETRIC,
    /** 阶跃：{@code s < 1 ? a : b}；枚举/字符串/布尔/引用/可空数值缺省。 */
    STEP;

    /** 缺省按角度插值的路径名。 */
    private static final Set<String> ANGLE_PATHS = Set.of("yaw", "roll", "yaw_lock", "pitch_lock");

    /**
     * 按 schema 文档 §3.4 推导缺省插值方式。
     *
     * <ul>
     *   <li>{@code float} 且 path 是 {@code yaw}/{@code roll}/{@code yaw_lock}/{@code pitch_lock}
     *       或 {@code unit=degree} → {@link #ANGLE}；</li>
     *   <li>{@code float} 且 path 是 {@code zoom} → {@link #GEOMETRIC}；</li>
     *   <li>其他 {@code float} → {@link #LINEAR}；</li>
     *   <li>{@code int}/{@code bool}/{@code string}/{@code enum}/{@code ref}/{@code nullable_float}
     *       → {@link #STEP}；</li>
     *   <li>{@code event_record} → {@link #NONE}。</li>
     * </ul>
     *
     * @param type 值类型
     * @param path 属性路径
     * @param unit schema 显示单位（只认 {@code degree}）
     * @return 缺省插值方式；{@code type == null} 返回 {@link #NONE}
     */
    public static Interp infer(PropertyType type, String path, String unit) {
        if (type == null) {
            return NONE;
        }
        if (type == PropertyType.FLOAT) {
            if ("degree".equals(unit)) {
                return ANGLE;
            }
            if (path != null && ANGLE_PATHS.contains(path)) {
                return ANGLE;
            }
            if ("zoom".equals(path)) {
                return GEOMETRIC;
            }
            return LINEAR;
        }
        if (type == PropertyType.EVENT_RECORD || type == PropertyType.STRUCT) {
            return NONE;
        }
        return STEP;
    }

    /**
     * 本插值方式是否适用于给定值类型。
     *
     * <p>{@link #NONE} 的合法性由形态决定（{@code mode=value} / {@code mode=event}），由调用方保证，
     * 因此这里对任意类型都返回 {@code true}。</p>
     *
     * @param type 值类型
     * @return 是否兼容
     */
    public boolean accepts(PropertyType type) {
        if (type == null) {
            return false;
        }
        return switch (this) {
            case ANGLE -> type == PropertyType.FLOAT || type == PropertyType.NULLABLE_FLOAT;
            case GEOMETRIC -> type == PropertyType.FLOAT;
            case LINEAR -> type == PropertyType.FLOAT || type == PropertyType.NULLABLE_FLOAT;
            case STEP -> type != PropertyType.EVENT_RECORD && type != PropertyType.STRUCT;
            case NONE -> true;
        };
    }
}
