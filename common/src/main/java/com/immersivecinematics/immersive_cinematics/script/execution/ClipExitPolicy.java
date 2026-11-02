package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 分支片段出口策略的不可变描述。
 *
 * <p>契约：{@link ClipExitKind#GOTO} 必须带非空 {@code target}，且 {@code entryLocalTime}
 * 有限、{@code >= 0}；其余出口类型必须 {@code target == null} 且 {@code entryLocalTime == 0}。
 * 违规抛 {@link IllegalArgumentException}，让错误在构造点暴露而不是运行期静默降级。</p>
 */
public record ClipExitPolicy(ClipExitKind kind, Clip target, double entryLocalTime) {

    /** 默认出口：结束分支回主线。 */
    public static final ClipExitPolicy CONTINUE = new ClipExitPolicy(ClipExitKind.CONTINUE, null, 0.0);

    public ClipExitPolicy {
        if (kind == null) {
            throw new IllegalArgumentException("出口类型不能为 null");
        }
        if (kind == ClipExitKind.GOTO) {
            if (target == null) {
                throw new IllegalArgumentException("GOTO 必须有目标片段");
            }
            if (!Double.isFinite(entryLocalTime) || entryLocalTime < 0.0) {
                throw new IllegalArgumentException(
                        "GOTO 入口局部时间必须是有限非负数：" + entryLocalTime);
            }
        } else {
            if (target != null) {
                throw new IllegalArgumentException(kind + " 不能带目标片段");
            }
            if (entryLocalTime != 0.0) {
                throw new IllegalArgumentException(kind + " 的入口局部时间必须为 0：" + entryLocalTime);
            }
        }
    }

    /** 结束分支并请求脚本结束。 */
    public static ClipExitPolicy endScript() {
        return new ClipExitPolicy(ClipExitKind.END_SCRIPT, null, 0.0);
    }

    /** 结束当前分支并跳到 {@code target} 的 {@code entryLocalTime} 处。 */
    public static ClipExitPolicy gotoClip(Clip target, double entryLocalTime) {
        return new ClipExitPolicy(ClipExitKind.GOTO, target, entryLocalTime);
    }
}
