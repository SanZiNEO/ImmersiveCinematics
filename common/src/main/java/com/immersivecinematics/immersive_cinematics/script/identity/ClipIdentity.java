package com.immersivecinematics.immersive_cinematics.script.identity;

import java.util.regex.Pattern;

/**
 * 片段在脚本内的稳定身份。
 *
 * <p>命名空间 = 单个脚本（内联片段与脚本级独立片段共用）；格式固定为
 * {@link #PATTERN}（1~64 位字母 / 数字 / 下划线）。身份只由脚本文本决定，
 * 与对象实例、加载顺序无关——规则绑定、路径端点、分支目标与出口目标都用它引用片段。</p>
 *
 * <p>构造即校验：非法值抛 {@link IllegalArgumentException}，由解析期
 * （{@code ScriptParser}）转成带字段路径的 {@code ScriptParseException}。</p>
 */
public record ClipIdentity(String id) {

    /** 合法片段 id 的正则表达式（脚本级唯一性由校验器判定）。 */
    public static final String PATTERN = "^[A-Za-z0-9_]{1,64}$";

    private static final Pattern VALID = Pattern.compile(PATTERN);

    public ClipIdentity {
        if (id == null) {
            throw new IllegalArgumentException("片段 id 不能为 null");
        }
        if (!VALID.matcher(id).matches()) {
            throw new IllegalArgumentException("片段 id 不合法: " + id + "（需匹配 " + PATTERN + "）");
        }
    }

    /** 校验一个候选 id（{@code null} / 空串 / 越界 / 非法字符 → {@code false}）。 */
    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }

    /** 由已校验字符串构造（等价于 {@code new ClipIdentity(id)}，命名更贴近调用点）。 */
    public static ClipIdentity of(String id) {
        return new ClipIdentity(id);
    }
}
