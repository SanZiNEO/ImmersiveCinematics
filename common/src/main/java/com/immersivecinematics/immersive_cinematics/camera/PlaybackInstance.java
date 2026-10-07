package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;

/**
 * 播放实例 — 一次脚本播放的独立载体（并行播放模型的第一步：播放 = 实例）。
 *
 * <h2>形态</h2>
 * <ul>
 *   <li><b>一个实例 = 一个 {@link ScriptPlayer}</b>：时间轴、轨道状态、音频实例、玩家移动控制全部随实例走，
 *       实例之间不共享可变状态（见 {@code plans/0.3.6/parallel-playback.md} §1、§3.1）。</li>
 *   <li><b>运行时行为快照</b>：{@link #behavior()} 是启动时从脚本 meta 取下的
 *       {@link ScriptMeta.RuntimeBehavior} 快照，{@link #replaceScript}（编辑器增量替换）时随新脚本刷新。
 *       生命周期判定（{@link #isSkippable()} / {@link #isInterruptible()} / {@link #isHoldAtEnd()} /
 *       {@link #isPauseWhenGamePaused()}）一律读本实例，不再读全局 {@code CinematicController}
 *       ——跳过 / 打断 / 末尾保持 / 暂停联动因此按实例独立。</li>
 *   <li><b>生命周期状态</b>：退场渐出中（{@link #isStopping()}）与退出原因（{@link #exitReason()}）
 *       原先分散在 {@code CameraManager} 的 {@code stopping} / {@code pendingCompletionReason} 字段，随实例走。</li>
 * </ul>
 *
 * <h2>本版本的约束</h2>
 * {@code CameraManager} 仍只允许一个活跃实例（{@code pendingScript} / 播放队列语义不变）；
 * 实例列表化是并行放开（§7 步骤 2+）的落点，本步只做载体切换、行为与现状一致。
 *
 * <p>生命周期驱动方法（{@code start} / {@code replaceScript} / {@code stop} / {@code markStopping} /
 * {@code setExitReason}）为包内可见：实例只能由 {@code CameraManager} 创建与驱动。
 */
public final class PlaybackInstance {

    private final ScriptPlayer player = new ScriptPlayer();

    /** 启动时快照的运行时行为（脚本 meta 的那一份；与 {@link ScriptPlayer} 持有的为同一对象） */
    private ScriptMeta.RuntimeBehavior behavior;

    /** 退场渐出中（等价改造前 {@code CameraManager.stopping}） */
    private boolean stopping = false;

    /** 退出原因：由 {@code requestExit} 置入、{@code deactivateNow} 消费（等价改造前 {@code pendingCompletionReason}） */
    private CompletionReason exitReason = CompletionReason.FINISHED;

    /** 本实例的播放器（时间轴与轨道状态）。 */
    public ScriptPlayer player() {
        return player;
    }

    /** 本实例正在播放的脚本；未开始或已停止时为 null。 */
    public CinematicScript script() {
        return player.getScript();
    }

    /** 本实例的脚本 id（无脚本时与 {@link ScriptPlayer#getScriptId()} 一致，为 {@code "<none>"}）。 */
    public String scriptId() {
        return player.getScriptId();
    }

    /** 启动时快照的运行时行为；未开始或已停止时为 null（判定方法自带默认值）。 */
    public ScriptMeta.RuntimeBehavior behavior() {
        return behavior;
    }

    // ========== 生命周期驱动（CameraManager） ==========

    /**
     * 开始播放：启动本实例的播放器，并快照脚本的运行时行为。
     *
     * @param script       已解析的脚本对象
     * @param preExecuteAt 预执行首帧的期望 elapsed 时间（预览模式 = 播放头，游戏内 = 0）
     */
    void start(CinematicScript script, float preExecuteAt) {
        this.player.start(script, preExecuteAt);
        ScriptMeta meta = script.getMeta();
        this.behavior = meta != null ? meta.getBehavior() : null;
    }

    /** 编辑器增量替换脚本数据（常驻实例零重启）：播放数据与行为快照一起刷新。 */
    void replaceScript(CinematicScript newScript) {
        if (newScript == null) return;
        this.player.replaceScript(newScript);
        ScriptMeta meta = newScript.getMeta();
        this.behavior = meta != null ? meta.getBehavior() : null;
    }

    /** 停止播放：清空轨道（音频实例、覆盖层随之释放）。 */
    void stop(CompletionReason reason) {
        this.player.stop(reason);
    }

    /** 置入退场渐出态（渐出完成后 {@code CameraManager.deactivateNow} 才真正停用本实例）。 */
    void markStopping() {
        this.stopping = true;
    }

    /** 置入退出原因。 */
    void setExitReason(CompletionReason reason) {
        this.exitReason = reason;
    }

    // ========== 生命周期判定（按实例独立） ==========

    /** 本实例是否处于退场渐出中。 */
    public boolean isStopping() {
        return stopping;
    }

    /** 本实例的退出原因（{@code deactivateNow} 消费）。 */
    public CompletionReason exitReason() {
        return exitReason;
    }

    /** 默认值与 {@code CinematicController.revert()} 一致，保证无行为快照时判定结果不变。 */
    public boolean isSkippable() {
        return behavior == null || behavior.skippable();
    }

    public boolean isInterruptible() {
        return behavior == null || behavior.interruptible();
    }

    public boolean isHoldAtEnd() {
        return behavior != null && behavior.holdAtEnd();
    }

    public boolean isPauseWhenGamePaused() {
        return behavior == null || behavior.pauseWhenGamePaused();
    }
}
