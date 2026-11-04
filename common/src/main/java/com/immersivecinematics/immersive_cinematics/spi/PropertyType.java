package com.immersivecinematics.immersive_cinematics.spi;

/**
 * 采样属性的值类型。
 *
 * <p>类型决定 JSON 取值转换（见 {@link ObjectCodec}）与缺省插值（见 {@link Interp#infer}）。
 * {@link #EVENT_RECORD} 只用于 {@link PropertyMode#EVENT} 的事件对象。</p>
 */
public enum PropertyType {
    /** 有限浮点数（不接受字符串数字）。 */
    FLOAT,
    /** 有限整数。 */
    INT,
    /** 布尔。 */
    BOOL,
    /** 自由字符串。 */
    STRING,
    /** 枚举字符串（合法值由 schema 的 enumValues 校验）。 */
    ENUM,
    /** 选择器/实体引用字符串。 */
    REF,
    /** 浮点数或 {@code null}（{@code null} = 不锁/不启用）。 */
    NULLABLE_FLOAT,
    /**
     * 结构化 value-only 值（曲线 / LUT 模型 / 渐变 / modifier / transition_params）：
     * 不可关键帧、不插值；JSON 值递归转为不可变 {@code Map}/{@code List}/{@code String}/
     * {@code Boolean}/{@code Float}/{@code null}，拒绝 NaN/Infinity。
     */
    STRUCT,
    /** 离散事件记录对象。 */
    EVENT_RECORD;

    /**
     * 解析脚本名（{@code float} / {@code nullable_float} / {@code event_record} …）。
     *
     * @param name 脚本中的类型字符串
     * @return 对应类型；未知或 {@code null} 返回 {@code null}
     */
    public static PropertyType fromString(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "float" -> FLOAT;
            case "int" -> INT;
            case "bool" -> BOOL;
            case "string" -> STRING;
            case "enum" -> ENUM;
            case "ref" -> REF;
            case "nullable_float" -> NULLABLE_FLOAT;
            case "struct" -> STRUCT;
            case "event_record" -> EVENT_RECORD;
            default -> null;
        };
    }
}
