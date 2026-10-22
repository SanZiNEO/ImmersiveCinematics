package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.camera.cover.CameraCoverChain;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.rule.RuleEngine;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.script.TrackType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * 播放实例 — 一次脚本播放的独立载体（播放 = 实例）。
 *
 * 一个实例 = 一个 {@link ScriptPlayer}：时间轴、轨道状态、音频实例、玩家移动控制全部随实例走，
 * 实例之间不共享可变状态。运行时行为快照（{@link #behavior()}）在启动时取自脚本 meta，
 * {@link #replaceScript}（编辑器增量替换）时随新脚本刷新；跳过 / 打断 / 末尾保持 / 暂停联动
 * 一律按本实例判定。退场渐出（{@link #isStopping()}）与退出原因（{@link #exitReason()}）随实例走。
 *
 * 多实例并行：跨脚本请求新建实例并行播放，同脚本同玩家保持单实例（第二个请求替换或入队）；
 * 帧驱动按列表顺序遍历（后来者居上），实例结束只影响它自己，仍有实例在播时共享时钟 / 相机状态 /
 * 覆盖层都不复位。
 *
 * 预览实例（{@link #isPreview()}）独立：只受编辑器预览通道驱动，以预览播放头为时钟源、恒为实例
 * 列表末位，不参与暂停联动 / 行为并集与网络账本。生命周期驱动方法为包内可见：实例只由
 * {@code CameraManager} 创建与驱动。
 */
public final class PlaybackInstance {

    private final ScriptPlayer player = new ScriptPlayer();

    /**
     * 是否为编辑器预览实例。
     *
     * 预览 = 一个独立实例：与游戏实例并行共存、互不替换；有自己的时钟（预览播放头）、
     * 不参与暂停联动 / 行为并集，也不上报网络账本（开始回执 / 结束通知 / 暂停握手）与跳过投票。
     */
    private final boolean preview;

    /** 覆盖链（Modifier 注册 / 注销的落点；链由 {@code CameraManager} 持有）。 */
    private final CameraCoverChain coverChain;

    /** 本实例已注册进链的 Modifier（= 本脚本里 clip 级静态声明的注册形态）；停止 / 脚本替换时注销。 */
    private final List<ClipModifierGate> modifiers = new ArrayList<>();

    /**
     * 判定规则引擎（{@code clip.rules}）：判定端 = 客户端逐实例，每帧由 {@code FrameDriver}
     * 在轨道分发之后驱动。规则槽随脚本重建（{@link #start} / {@link #replaceScript}），停止时清空。
     */
    private final RuleEngine ruleEngine = new RuleEngine();

    PlaybackInstance(CameraCoverChain coverChain, boolean preview) {
        this.coverChain = coverChain;
        this.preview = preview;
        this.ruleEngine.attachOwner(this);
    }

    /** 本实例的判定规则引擎（帧驱动调用 {@code evaluate}；探针直取以注入上下文 / 时钟）。 */
    public RuleEngine ruleEngine() {
        return ruleEngine;
    }

    /** 是否为编辑器预览实例（独立实例，与游戏实例并行共存）。 */
    public boolean isPreview() {
        return preview;
    }

    /**
     * 本实例的播放实例 id：服务端播放请求携带的账本键，{@link #start} 时传入；
     * 本地来源（编辑器预览/重载等）为空串。实例身份不随 {@link #replaceScript}（编辑器增量替换）改变。
     */
    private String instanceId = "";

    /** 启动时快照的运行时行为（脚本 meta 的那一份；与 {@link ScriptPlayer} 持有的为同一对象） */
    private ScriptMeta.RuntimeBehavior behavior;

    /**
     * 启动时快照的播放模式（脚本 meta 的那一份）：跨脚本到达裁决读本实例，与同脚本再触发的
     * {@link #isInterruptible()} 分工不同。不进 {@link ScriptMeta.RuntimeBehavior}——behavior 参与按位并集。
     */
    private ScriptMeta.PlayMode playMode = ScriptMeta.PlayMode.PARALLEL;

    /** 退场渐出中（渐出完成后才真正停用）。 */
    private boolean stopping = false;

    /** 退出原因：由 {@code requestExit} 置入、{@code deactivateNow} 消费。 */
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

    /** 本实例的播放实例 id；本地来源为空串，{@link #replaceScript} 不改变它。 */
    public String instanceId() {
        return instanceId;
    }

    /** 启动时快照的运行时行为；未开始或已停止时为 null（判定方法自带默认值）。 */
    public ScriptMeta.RuntimeBehavior behavior() {
        return behavior;
    }

    /** 本实例的播放模式（跨脚本关系，启动 / 脚本替换时快照）；未开始时为 {@link ScriptMeta.PlayMode#PARALLEL}。 */
    public ScriptMeta.PlayMode playMode() {
        return playMode;
    }

    // ========== 生命周期驱动（CameraManager） ==========

    /**
     * 开始播放：启动本实例的播放器，并快照脚本的运行时行为。
     *
     * @param script       已解析的脚本对象
     * @param instanceId   服务端播放请求的播放实例 id；本地来源传空串
     * @param preExecuteAt 预执行首帧的期望 elapsed 时间（预览模式 = 播放头，游戏内 = 0）
     */
    void start(CinematicScript script, String instanceId, float preExecuteAt) {
        this.instanceId = instanceId != null ? instanceId : "";
        // 层归属先接线（建 TrackPlayer / 建层发生在 player.start 内）：层按本实例过滤渐出与清理
        this.player.attachOwner(this);
        this.player.start(script, preExecuteAt);
        ScriptMeta meta = script.getMeta();
        this.behavior = meta != null ? meta.getBehavior() : null;
        this.playMode = meta != null ? meta.getPlayMode() : ScriptMeta.PlayMode.PARALLEL;
        syncModifiers(script);
        ruleEngine.sync(script, player);
    }

    /** 编辑器增量替换脚本数据（常驻实例零重启）：播放数据与行为快照一起刷新。 */
    void replaceScript(CinematicScript newScript) {
        if (newScript == null) return;
        this.player.attachOwner(this);
        this.player.replaceScript(newScript);
        ScriptMeta meta = newScript.getMeta();
        this.behavior = meta != null ? meta.getBehavior() : null;
        this.playMode = meta != null ? meta.getPlayMode() : ScriptMeta.PlayMode.PARALLEL;
        syncModifiers(newScript);
        ruleEngine.sync(newScript, player);
    }

    /** 停止播放：清空轨道（音频实例、覆盖层随之释放）+ 注销本实例的 Modifier + 清规则槽（释放挂起）。 */
    void stop(CompletionReason reason) {
        this.player.stop(reason);
        syncModifiers(null);
        ruleEngine.reset(player);
        // 层兜底：TrackPlayer 清理抛异常时也不留残层（层归属 = 本实例，只清自己的）
        OverlayManager.INSTANCE.removeLayersOf(this);
    }

    /** 置入退场渐出态（渐出完成后 {@code CameraManager.deactivateNow} 才真正停用本实例）。 */
    void markStopping() {
        this.stopping = true;
    }

    /** 置入退出原因。 */
    void setExitReason(CompletionReason reason) {
        this.exitReason = reason;
    }

    // ========== Modifier 接线（播放实例级） ==========

    /**
     * 本实例的 Modifier 集与脚本同步：先注销旧集、再注册新脚本里全部 clip 级静态声明
     * （{@code null} = 只注销）。注册形态 = {@link ClipModifierGate}（活跃窗门 + 声明），
     * 于是每条声明只在自己片段的活跃窗内参与合成（时间源 = 本实例的播放器）。
     *
     * 调用点：{@link #start}（播放开始注册）、{@link #replaceScript}（编辑器增量替换重同步）、
     * {@link #stop}（停播注销）。不在帧内调用。
     */
    private void syncModifiers(CinematicScript script) {
        for (int i = 0; i < modifiers.size(); i++) {
            coverChain.unregister(modifiers.get(i));
        }
        modifiers.clear();
        if (script == null) return;
        for (ClipModifierGate gate : collectModifiers(script, player::timelineTime)) {
            coverChain.register(gate);
            modifiers.add(gate);
        }
    }

    /**
     * 脚本里全部片段级静态 Modifier 的注册形态（只取 CAMERA 轨道，按轨道顺序展开；无声明 → 空列表）。
     *
     * @param timelineTime 时间轴读数来源（每实例一份：游戏实例 = 全局虚拟时钟、预览实例 = 预览播放头）
     */
    static List<ClipModifierGate> collectModifiers(CinematicScript script, DoubleSupplier timelineTime) {
        List<ClipModifierGate> collected = new ArrayList<>();
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            if (track.getType() != TrackType.CAMERA) continue;
            List<Clip> clips = track.getClips();
            for (int i = 0; i < clips.size(); i++) {
                ClipModifier declaration = clips.get(i).getModifier();
                if (declaration == null) continue;
                Clip next = i + 1 < clips.size() ? clips.get(i + 1) : null;
                collected.add(new ClipModifierGate(declaration, clips.get(i), next, timelineTime));
            }
        }
        return collected;
    }

    // ========== 规则动作 Modifier 接线（camera_set；注册 / 注销由规则引擎的工件生命周期驱动） ==========

    /**
     * 注册一条规则动作建出的 Modifier（{@code camera_set} 触发时；见 {@code RuleEngine.attachArtifact}）。
     * 与 {@link #syncModifiers} 的静态声明分开记账：本类不持有它们的清单——回收由引擎在片段窗末 /
     * 重建 / 停播时调 {@link #detachRuleModifier}。
     */
    public void attachRuleModifier(CameraModifier modifier) {
        if (coverChain != null && modifier != null) coverChain.register(modifier);
    }

    /** 注销一条规则动作建出的 Modifier（片段窗末 / 引擎重建 / 停播；未注册过时为无操作）。 */
    public void detachRuleModifier(CameraModifier modifier) {
        if (coverChain != null && modifier != null) coverChain.unregister(modifier);
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
