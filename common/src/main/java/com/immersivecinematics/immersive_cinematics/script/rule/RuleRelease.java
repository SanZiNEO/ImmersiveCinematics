package com.immersivecinematics.immersive_cinematics.script.rule;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 一条动作释放条件（{@code schedule} 解析后的不可变形态）。
 *
 * <p>字段按 {@link RuleReleaseKind} 取用（其余字段恒为「空」哨兵值），构造即校验：
 * <ul>
 *   <li>{@link RuleReleaseKind#CLIP_START} / {@link RuleReleaseKind#CLIP_COMPLETE}：
 *       {@code clip} 非 null，{@code at == 0}，{@code source == null}，{@code fact == null}；</li>
 *   <li>{@link RuleReleaseKind#LOCAL_TIME}：{@code clip} 非 null，{@code at} 有限且 {@code >= 0}；</li>
 *   <li>{@link RuleReleaseKind#DELAY}：{@code clip == null}，{@code at} 有限且 {@code >= 0}（秒）；</li>
 *   <li>{@link RuleReleaseKind#FACT}：{@code source} 非 null，{@code fact} 非空字符串。</li>
 * </ul>
 */
public record RuleRelease(RuleReleaseKind kind, Clip clip, double at, RuleBinding source, String fact) {

    public RuleRelease {
        if (kind == null) {
            throw new IllegalArgumentException("释放条件类型不能为 null");
        }
        switch (kind) {
            case CLIP_START, CLIP_COMPLETE -> {
                requireClip(kind, clip);
                requireZero(kind, at);
                requireNoSource(kind, source, fact);
            }
            case LOCAL_TIME -> {
                requireClip(kind, clip);
                requireNonNegative(kind, at);
                requireNoSource(kind, source, fact);
            }
            case DELAY -> {
                if (clip != null) {
                    throw new IllegalArgumentException(kind + " 不能带目标片段");
                }
                requireNonNegative(kind, at);
                requireNoSource(kind, source, fact);
            }
            case FACT -> {
                if (clip != null) {
                    throw new IllegalArgumentException(kind + " 不能带目标片段");
                }
                requireZero(kind, at);
                if (source == null) {
                    throw new IllegalArgumentException(kind + " 必须有来源绑定");
                }
                if (fact == null || fact.isEmpty()) {
                    throw new IllegalArgumentException(kind + " 必须有非空事实名");
                }
            }
        }
    }

    /** 目标片段开始。 */
    public static RuleRelease clipStart(Clip clip) {
        return new RuleRelease(RuleReleaseKind.CLIP_START, clip, 0.0, null, null);
    }

    /** 目标片段完成。 */
    public static RuleRelease clipComplete(Clip clip) {
        return new RuleRelease(RuleReleaseKind.CLIP_COMPLETE, clip, 0.0, null, null);
    }

    /** 目标片段执行到达局部时间 {@code at}（秒）。 */
    public static RuleRelease localTime(Clip clip, double at) {
        return new RuleRelease(RuleReleaseKind.LOCAL_TIME, clip, at, null, null);
    }

    /** 判定成功后经过真实秒数 {@code seconds}。 */
    public static RuleRelease delay(double seconds) {
        return new RuleRelease(RuleReleaseKind.DELAY, null, seconds, null, null);
    }

    /** 另一绑定的最近一次激活事实。 */
    public static RuleRelease fact(RuleBinding source, String fact) {
        return new RuleRelease(RuleReleaseKind.FACT, null, 0.0, source, fact);
    }

    private static void requireClip(RuleReleaseKind kind, Clip clip) {
        if (clip == null) {
            throw new IllegalArgumentException(kind + " 必须有目标片段");
        }
    }

    private static void requireZero(RuleReleaseKind kind, double at) {
        if (at != 0.0) {
            throw new IllegalArgumentException(kind + " 的时间参数必须为 0：" + at);
        }
    }

    private static void requireNonNegative(RuleReleaseKind kind, double at) {
        if (!Double.isFinite(at) || at < 0.0) {
            throw new IllegalArgumentException(kind + " 的时间参数必须是有限非负数：" + at);
        }
    }

    private static void requireNoSource(RuleReleaseKind kind, RuleBinding source, String fact) {
        if (source != null || fact != null) {
            throw new IllegalArgumentException(kind + " 不能带来源绑定 / 事实名");
        }
    }
}
