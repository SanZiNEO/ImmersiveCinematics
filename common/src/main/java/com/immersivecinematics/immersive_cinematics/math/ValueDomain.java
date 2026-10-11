package com.immersivecinematics.immersive_cinematics.math;

/**
 * 值域语义 — 决定通道数与环绕规则（定义 / 实例分离：域属定义）。
 *
 * 通道数：SCALAR / ANGLE = 1、VECTOR = 3、COLOR = 4；v1 输入 / 输出同域。
 * 单位：ANGLE = 度；差值 / 混合取最短路径（wrap 到 (-180, 180]），绝对输出不强制归一；
 * VECTOR / COLOR 逐分量独立状态。{@link #key()} 为 JSON 定义对象的 {@code domain} 结构键。
 */
public enum ValueDomain {

    SCALAR(1),
    ANGLE(1),
    VECTOR(3),
    COLOR(4);

    private final int channels;

    ValueDomain(int channels) {
        this.channels = channels;
    }

    /** 通道数（定长 double 通道：SCALAR / ANGLE = 1、VECTOR = 3、COLOR = 4）。 */
    public int channels() {
        return channels;
    }

    /** JSON 结构键名（小写）。 */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 按 JSON 结构键名解析；未知 → null。 */
    public static ValueDomain byKey(String key) {
        for (ValueDomain domain : ValueDomain.values()) {
            if (domain.key().equals(key)) {
                return domain;
            }
        }
        return null;
    }
}
