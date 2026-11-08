package com.immersivecinematics.immersive_cinematics.script.execution;

/**
 * 片段循环的遍历方式（每次执行内部）：
 * {@link #REPEAT} = 正向 0→duration 重复；{@link #PINGPONG} = 正向 / 反向交替。
 *
 * <p>解析期把字符串归一成枚举，运行期不再比较字符串。{@code null} / 未知值回落调用方给的缺省。</p>
 */
public enum LoopMode {

    REPEAT,
    PINGPONG;

    /** 脚本字段名（{@code repeat} / {@code pingpong}）。 */
    public String id() {
        return this == PINGPONG ? "pingpong" : "repeat";
    }

    /**
     * 按名称取枚举（大小写不敏感）。
     *
     * @param name 脚本字段 {@code loop_mode} 的值（{@code repeat} / {@code pingpong}）
     * @param def  未声明 / 无法识别时的缺省
     */
    public static LoopMode fromName(String name, LoopMode def) {
        if ("pingpong".equalsIgnoreCase(name)) return PINGPONG;
        if ("repeat".equalsIgnoreCase(name)) return REPEAT;
        return def;
    }
}
