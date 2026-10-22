package com.immersivecinematics.immersive_cinematics.control;

/**
 * 退出请求的原因（{@code requestExit} 的入参）；决定是否渐出，以及受哪些行为开关门控。
 */
public enum ExitReason {
    /** 强制退出（Ctrl+P）：跳过渐出，立即停用。 */
    FORCE_QUIT,
    /** 系统停止（命令 / 服务端 stop / 世界退出）：渐出退场。 */
    SYSTEM_STOP,
    /** 被其他脚本抢占：实例不可打断（interruptible=false）时拒绝。 */
    INTERRUPTED,
    /** 用户长按跳过键：实例不可跳过（skippable=false）时拒绝。 */
    USER_SKIP,
    /** 播放到结尾：实例 hold_at_end 时拒绝（驻留）。 */
    NATURAL_END
}
