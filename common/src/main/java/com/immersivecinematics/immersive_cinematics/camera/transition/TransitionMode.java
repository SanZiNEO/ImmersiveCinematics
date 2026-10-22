package com.immersivecinematics.immersive_cinematics.camera.transition;

/**
 * 过渡通道模式 — 单个六参数通道在 A→B 过渡里的处置方式（脚本字段 {@code transition_params.<通道>.mode}）。
 *
 * {@link #BLEND}：按权重在 A / B 之间混合（缺省，= 线性斜坡）。{@link #HOLD}：过渡期间保持 A 侧取值，
 * 过渡窗口结束后随片段切换为 B 侧。{@link #CUT}：不做过渡——片段交界时刻之前取 A 侧、之后取 B 侧。
 * 窗口口径：以片段交界时刻为中心、以该通道时长为宽度；{@link #CUT} 不占窗口。
 */
public enum TransitionMode {

    /** 按权重在 A / B 之间混合（缺省）。 */
    BLEND,

    /** 过渡期间保持 A 侧取值（本通道不参与过渡）。 */
    HOLD,

    /** 不做过渡：交界之前取 A 侧、之后取 B 侧。 */
    CUT;

    /** 脚本用名（与 JSON 值一致）。 */
    public String scriptName() {
        return switch (this) {
            case BLEND -> "blend";
            case HOLD -> "hold";
            case CUT -> "cut";
        };
    }

    /**
     * 按脚本用名解析。
     *
     * @param name 脚本用名（{@code blend} / {@code hold} / {@code cut}）
     * @return 模式；未知 / {@code null} = {@code null}
     */
    public static TransitionMode byName(String name) {
        if (name == null) {
            return null;
        }
        for (TransitionMode mode : values()) {
            if (mode.scriptName().equals(name)) {
                return mode;
            }
        }
        return null;
    }
}
