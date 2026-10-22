package com.immersivecinematics.immersive_cinematics.control;

/**
 * 播放实例的结束原因，由客户端判定后随完成包上报服务端。
 *
 * 服务端据此区分「用户跳过」与其他结束：只有 {@link #SKIPPED} 触发 skip 事件，其余只走完成收尾。
 */
public enum CompletionReason {
    /** 强制退出（Ctrl+P）：立即停用，不走渐出。 */
    FORCE_QUIT,
    /** 系统 / 服务端主动停止：渐出退场。 */
    STOPPED,
    /** 被其他脚本抢占：要求实例可打断（interruptible）。 */
    INTERRUPTED,
    /** 用户长按跳过键：要求实例可跳过（skippable）。 */
    SKIPPED,
    /** 正常播放到结尾（含作者声明的 exit 动作）。 */
    FINISHED
}
