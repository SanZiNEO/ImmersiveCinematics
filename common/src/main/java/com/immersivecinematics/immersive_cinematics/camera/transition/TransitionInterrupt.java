package com.immersivecinematics.immersive_cinematics.camera.transition;

/**
 * 过渡打断规则 — 过渡进行中遇到新的过渡起点（片段交界变化）时的处置
 * （脚本字段 {@code transition_interrupt}）。
 *
 * {@link #BLEND}（缺省）：不跳变——以当前混合中间态为新的 A 侧、向新目标续接，时长重新起算。
 * {@link #SNAP}：立即结束当前过渡、输出 = 新目标（= 过渡时长 0 的特例）。
 * 链式 A→B→C = 打断 {@link #BLEND} 的自然延伸；同一 / 更早的交界结算后不再重开。
 */
public enum TransitionInterrupt {

    /** 从当前混合中间态平滑转新目标（缺省）。 */
    BLEND,

    /** 打断即硬切到新目标。 */
    SNAP;

    /** 脚本用名（与 JSON 值一致）。 */
    public String scriptName() {
        return switch (this) {
            case BLEND -> "blend";
            case SNAP -> "snap";
        };
    }

    /**
     * 按脚本用名解析。
     *
     * @param name 脚本用名（{@code blend} / {@code snap}）
     * @return 规则；未知 / {@code null} = {@code null}
     */
    public static TransitionInterrupt byName(String name) {
        if (name == null) {
            return null;
        }
        for (TransitionInterrupt interrupt : values()) {
            if (interrupt.scriptName().equals(name)) {
                return interrupt;
            }
        }
        return null;
    }
}
