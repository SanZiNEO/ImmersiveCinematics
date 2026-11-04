package com.immersivecinematics.immersive_cinematics.spi;

/**
 * 采样属性的三种形态。
 *
 * <ul>
 *   <li>{@link #VALUE}：只有静态值，不接受 keyframes（{@code orient}、{@code layer_type} 等）。</li>
 *   <li>{@link #ANIMATED}：静态值 + 可选 keyframes，可独立加帧的连续量。</li>
 *   <li>{@link #EVENT}：离散事件记录，必须有 keyframes，值为 {@code event_record}，不插值。</li>
 * </ul>
 */
public enum PropertyMode {
    VALUE,
    ANIMATED,
    EVENT;

    /**
     * 解析脚本名（{@code value} / {@code animated} / {@code event}）。
     *
     * @param name 脚本中的形态字符串
     * @return 对应形态；未知或 {@code null} 返回 {@code null}
     */
    public static PropertyMode fromString(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "value" -> VALUE;
            case "animated" -> ANIMATED;
            case "event" -> EVENT;
            default -> null;
        };
    }
}
