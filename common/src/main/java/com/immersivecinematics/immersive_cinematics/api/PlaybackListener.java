package com.immersivecinematics.immersive_cinematics.api;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;

/**
 * 播放生命周期监听器（对外稳定面）：脚本真正开始 / 结束时回调。
 *
 * <p><b>线程契约</b>：仅客户端主线程（tick / 渲染线程）。跨线程调用方须自行 marshal 到主线程后再注册与使用。
 *
 * <p><b>触发口径</b>：
 * <ul>
 *   <li>{@link #onStarted} = 实例真正开始播放时（{@code PlaybackLifecycle.startScriptInternal} 成功启动后），
 *       排队等待 / 被拒绝不触发。</li>
 *   <li>{@link #onFinished} = 实例停用时（{@code PlaybackLedger.reportScriptFinished}，逐实例一次）。</li>
 *   <li><b>编辑器播放实例不触发</b>：编辑器预览不是游戏播放，与账本口径一致（{@code isEditorDriven()} 门控）。</li>
 * </ul>
 *
 * <p><b>异常隔离</b>：回调抛出的异常由内部逐个隔离（限频记错误日志），<b>不影响其它监听器，也不影响播放</b>。
 * 实现仍应尽快返回：回调运行在客户端主线程，阻塞会拖慢整条播放链路。
 *
 * <p>注册入口：{@link PlaybackApi#addListener(PlaybackListener)} / {@link PlaybackApi#removeListener(PlaybackListener)}。
 */
public interface PlaybackListener {

    /**
     * 脚本开始播放。
     *
     * @param scriptId   脚本 id
     * @param instanceId 播放实例 id；匿名实例为空串
     */
    default void onStarted(String scriptId, String instanceId) {}

    /**
     * 脚本结束播放。
     *
     * @param scriptId   脚本 id
     * @param instanceId 播放实例 id；匿名实例为空串
     * @param reason     结束原因（自然结束 / 跳过 / 打断 / 停止 / 强制退出）
     */
    default void onFinished(String scriptId, String instanceId, CompletionReason reason) {}
}
