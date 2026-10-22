package com.immersivecinematics.immersive_cinematics.camera.cover;

/**
 * 合成模式：Modifier 的通道值如何并入 Base 状态（合成语义唯一定义点）。
 *
 * 单一模式作用于该 Modifier 的整个掩码；需要不同模式的通道拆成多个 Modifier。
 * 通道缺省模式见 {@link CameraChannels#defaultMode(int)}。
 * 数值契约：不做 clamp / normalize / 角度环绕——结果原样交给消费侧。
 */
public enum ComposeMode {

    /** 直写该通道（必须显式声明，不作通道缺省）。 */
    REPLACE,

    /** 相加（增量）。 */
    ADD,

    /** 相乘（倍率）。 */
    MULTIPLY;

    /**
     * 合成一个通道值。
     *
     * @param base  当前值（Base 状态或前序 Modifier 的合成结果）
     * @param value 本 Modifier 写入的值（{@code REPLACE} = 绝对值、{@code ADD} = 增量、{@code MULTIPLY} = 倍率）
     * @return 合成后的值
     */
    public double apply(double base, double value) {
        return switch (this) {
            case REPLACE -> value;
            case ADD -> base + value;
            case MULTIPLY -> base * value;
        };
    }
}
