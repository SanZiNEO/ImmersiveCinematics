package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;

import java.util.Locale;

/**
 * 事实结果：播放结束原因与其它可区分结果。
 *
 * <p>{@link #of(CompletionReason)} 与客户端上报的五种退出原因一一映射；
 * {@link #SUCCEEDED} / {@link #FAILED} 留给非播放类事实（如动作结果）。
 */
public enum FactResult {
    NONE,
    FINISHED,
    STOPPED,
    INTERRUPTED,
    SKIPPED,
    FORCE_QUIT,
    SUCCEEDED,
    FAILED;

    /** SNBT 中的稳定字符串键（小写）；{@link #byId(String)} 大小写不敏感。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 五种播放退出原因的一一映射；{@code null} 视为 {@link #NONE}。 */
    public static FactResult of(CompletionReason reason) {
        if (reason == null) {
            return NONE;
        }
        return switch (reason) {
            case FINISHED -> FINISHED;
            case STOPPED -> STOPPED;
            case INTERRUPTED -> INTERRUPTED;
            case SKIPPED -> SKIPPED;
            case FORCE_QUIT -> FORCE_QUIT;
        };
    }

    /** 按字符串查结果（大小写不敏感）；未知或空返回 {@link #NONE}。 */
    public static FactResult byId(String id) {
        if (id == null || id.isEmpty()) {
            return NONE;
        }
        for (FactResult result : values()) {
            if (result.name().equalsIgnoreCase(id)) {
                return result;
            }
        }
        return NONE;
    }
}
