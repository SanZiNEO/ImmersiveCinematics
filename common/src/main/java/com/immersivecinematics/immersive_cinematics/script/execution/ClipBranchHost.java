package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 分支运行期与播放器之间的回调面（由播放器实现；探针用 fake）。
 *
 * <p>正常完成路径：{@link #onBranchStarted} → 策略解析（GOTO 可能再触发一次
 * {@link #onBranchStarted}）。失败路径只有 {@link #onBranchFailed} + 账本快照恢复，
 * 绝不混入正常完成回调。</p>
 */
public interface ClipBranchHost {

    /** 当前播放头读数（秒）。 */
    double now();

    /** 把播放头移到 {@code time}（失败回滚时回到 launch 快照的播放头）。 */
    void seekTo(double time);

    /** 请求脚本整体结束。 */
    void requestScriptEnd();

    /** 分支入口已生效：{@code execution} 是 {@code clip} 的分支入口记录。 */
    void onBranchStarted(Clip clip, ClipExecution execution);

    /** 分支失败：账本已恢复到 launch 快照，播放头已 seek 回快照位置。 */
    void onBranchFailed(Clip clip, String reason);
}
