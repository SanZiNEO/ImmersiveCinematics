package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.camera.cover.CameraCoverChain;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.rule.RuleEngine;
import com.immersivecinematics.immersive_cinematics.client.lane.CameraLeasePool;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipBranchController;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipBranchHost;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExecution;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitResolver;
import com.immersivecinematics.immersive_cinematics.script.rule.runtime.RuleRuntime;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 编辑器播放实例（{@link #isEditorDriven()}）独立：只受编辑器播放通道驱动，以编辑器播放头为时钟源、
 * 恒为实例列表末位；参与<b>内容行为并集</b>（HUD / lane / 相机 / 音频 / 覆盖层），不参与<b>游戏控制</b>
 * （键鼠屏蔽、暂停联动、播放队列、网络账本、跳过投票）。生命周期驱动方法为包内可见：实例只由
 * {@code CameraManager} 创建与驱动。
 */
public final class PlaybackInstance implements ClipBranchHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    private final ScriptPlayer player = new ScriptPlayer();

    /**
     * 是否为编辑器播放实例。
     *
     * 编辑器播放 = 播放引擎的另一种时钟源：与游戏实例并行共存、互不替换；时钟源是编辑器播放头，
     * 参与<b>内容行为并集</b>（HUD / lane / 相机 / 音频 / 覆盖层），不参与<b>游戏控制</b>
     * （键鼠屏蔽、暂停联动、播放队列、网络账本、跳过投票）。
     */
    private final boolean editorDriven;

    /** 覆盖链（Modifier 注册 / 注销的落点；链由 {@code CameraManager} 持有）。 */
    private final CameraCoverChain coverChain;

    /** 本实例已注册进链的 Modifier（= 本脚本里 clip 级静态声明的注册形态）；停止 / 脚本替换时注销。 */
    private final List<ClipModifierGate> modifiers = new ArrayList<>();

    /**
     * 判定规则引擎（{@code clip.rules}）：判定端 = 客户端逐实例，每帧由 {@code FrameDriver}
     * 在轨道分发之后驱动。规则槽随脚本重建（{@link #start} / {@link #replaceScript}），停止时清空。
     */
    private final RuleEngine ruleEngine = new RuleEngine();

    /**
     * 脚本级规则运行态（{@code rules} + {@code rule_bindings}；每实例一份）。与旧 {@link RuleEngine}
     * 并存但不同脚本：同一脚本同时出现两套规则由解析期拒绝。帧驱动在旧引擎之后调用
     * {@link RuleRuntime#evaluate}。
     */
    private final RuleRuntime rules = new RuleRuntime();

    /**
     * 片段默认出口策略表（播放实例级，不写回脚本）：{@link #start} / {@link #replaceScript} 时按脚本
     * 全部片段（内联 + 独立）填充，{@link #stop} 时清空。解析优先级 = 入口覆盖 &gt; 片段默认 &gt; CONTINUE
     * （见 {@link ClipExitResolver}）。
     */
    private final ClipExitResolver exits = new ClipExitResolver();

    /**
     * 分支出口驱动器（单分支、无嵌套/调用栈）。构造时绑定本实例的账本，{@link #start} /
     * {@link #replaceScript} 时随播放器重建（{@link ScriptPlayer} 在每次启播/替换脚本时换新账本实例，
     * 控制器必须指向当前账本，否则分支读到的是已废弃的记录）。
     */
    private ClipBranchController branch = new ClipBranchController(player.executions(), exits, this);

    /**
     * 网络侧播放账本：本实例的 seek 上报出口。由 {@code PlaybackLifecycle} 在创建实例时注入；
     * {@code null} = 未接线（编辑器预览 / 探针），此时只做本地 seek、不发包。
     */
    private PlaybackLedger ledger;

    PlaybackInstance(CameraCoverChain coverChain, boolean editorDriven) {
        this.coverChain = coverChain;
        this.editorDriven = editorDriven;
        this.ruleEngine.attachOwner(this);
        this.rules.attachOwner(this);
        // 待执行动作参与自然完成：判定成功后已承诺的动作不得被内容采样结束静默丢弃
        this.player.setCompletionDependency(() -> rules.hasPendingActions());
    }

    /** 本实例的判定规则引擎（帧驱动调用 {@code evaluate}；探针直取以注入上下文 / 时钟）。 */
    public RuleEngine ruleEngine() {
        return ruleEngine;
    }

    /** 本实例的脚本级规则运行态（帧驱动调用 {@code evaluate}；探针直取以注入上下文 / 时钟）。 */
    public RuleRuntime rules() {
        return rules;
    }

    /** 本实例的分支出口驱动器（{@code goto_clip} / 片段 {@code exit} 的运行期状态机）。 */
    public ClipBranchController branch() {
        return branch;
    }

    /** 本实例的片段默认出口策略表（解析优先级 = 入口覆盖 &gt; 片段默认 &gt; CONTINUE）。 */
    public ClipExitResolver exits() {
        return exits;
    }

    /** 是否由编辑器播放驱动（独立时钟源，与游戏实例并行共存；不参与游戏控制语义）。 */
    public boolean isEditorDriven() {
        return editorDriven;
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
     * @param preExecuteAt 预执行首帧的期望 elapsed 时间（编辑器播放 = 播放头，游戏内 = 0）
     */
    void start(CinematicScript script, String instanceId, double preExecuteAt) {
        this.instanceId = instanceId != null ? instanceId : "";
        // 层归属先接线（建 TrackPlayer / 建层发生在 player.start 内）：层按本实例过滤渐出与清理
        this.player.attachOwner(this);
        this.player.start(script, preExecuteAt);
        ScriptMeta meta = script.getMeta();
        this.behavior = meta != null ? meta.getBehavior() : null;
        this.playMode = meta != null ? meta.getPlayMode() : ScriptMeta.PlayMode.PARALLEL;
        syncModifiers(script);
        ruleEngine.sync(script, player);
        rules.sync(script, player);
        syncExits(script);
    }

    /** 编辑器增量替换脚本数据（常驻实例零重启）：播放数据与行为快照一起刷新。 */
    void replaceScript(CinematicScript newScript) {
        if (newScript == null) return;
        // 旧脚本的片段身份失效：先归还本实例全部租约，新脚本从干净状态重新获取
        CameraLeasePool.releaseOwner(this);
        this.player.attachOwner(this);
        this.player.replaceScript(newScript);
        ScriptMeta meta = newScript.getMeta();
        this.behavior = meta != null ? meta.getBehavior() : null;
        this.playMode = meta != null ? meta.getPlayMode() : ScriptMeta.PlayMode.PARALLEL;
        syncModifiers(newScript);
        ruleEngine.sync(newScript, player);
        rules.sync(newScript, player);
        syncExits(newScript);
    }

    /** 停止播放：清空轨道（音频实例、覆盖层随之释放）+ 注销本实例的 Modifier + 清规则槽（释放挂起）。 */
    void stop(CompletionReason reason) {
        // 生命周期收口：实例停止即归还全部租约（视角快照 / 颜色历史 / 剔除槽）
        CameraLeasePool.releaseOwner(this);
        this.player.stop(reason);
        syncModifiers(null);
        ruleEngine.reset(player);
        rules.reset(player);
        exits.clear();
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
        for (ClipModifierGate gate : collectModifiers(script, player, player::timelineTime)) {
            coverChain.register(gate);
            modifiers.add(gate);
        }
    }

    /**
     * 脚本里全部片段级静态 Modifier 的注册形态（只取 CAMERA 轨道，按轨道顺序展开；无声明 → 空列表）。
     *
     * @param player       所属播放实例（执行账本 = 活跃窗 / 有限窗口末端来源）
     * @param timelineTime 时间轴读数来源（每实例一份：游戏实例 = 全局虚拟时钟、编辑器实例 = 编辑器播放头）
     */
    static List<ClipModifierGate> collectModifiers(CinematicScript script, ScriptPlayer player,
                                                   DoubleSupplier timelineTime) {
        List<ClipModifierGate> collected = new ArrayList<>();
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            if (!TrackTypeId.CAMERA.equals(track.getType())) continue;
            List<Clip> clips = track.getClips();
            for (int i = 0; i < clips.size(); i++) {
                ClipModifier declaration = clips.get(i).getModifier();
                if (declaration == null) continue;
                Clip next = i + 1 < clips.size() ? clips.get(i + 1) : null;
                collected.add(new ClipModifierGate(declaration, clips.get(i), next, player, timelineTime));
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

    // ========== 出口策略 / 分支接线（播放实例级） ==========

    /**
     * 按脚本同步片段默认出口策略表，并重建分支控制器。
     *
     * <p>覆盖内联片段（timeline 全部轨道）与脚本级独立片段：只要片段声明了 {@code exit} 就进表；
     * 未声明 = 运行期 {@code CONTINUE}。控制器必须重建，因为 {@link ScriptPlayer} 在启播 / 替换脚本时
     * 换新执行账本实例，旧的控制器会指向已废弃的记录。</p>
     *
     * 调用点：{@link #start}、{@link #replaceScript}。不在帧内调用。
     */
    void syncExits(CinematicScript script) {
        exits.clear();
        if (script != null) {
            for (TimelineTrack track : script.getTimeline().getTracks()) {
                for (Clip clip : track.getClips()) {
                    if (clip.hasExitPolicy()) exits.setDefault(clip, clip.getExitPolicy());
                }
            }
            List<Clip> standalone = script.getStandaloneClips();
            if (standalone != null) {
                for (Clip clip : standalone) {
                    if (clip.hasExitPolicy()) exits.setDefault(clip, clip.getExitPolicy());
                }
            }
        }
        this.branch = new ClipBranchController(player.executions(), exits, this);
    }

    // ========== 分支宿主（ClipBranchHost） ==========

    /** 当前播放头读数（秒）：与轨道分发同一口径（{@code ScriptPlayer.timelineTime()}）。 */
    @Override
    public double now() {
        return player.timelineTime();
    }

    /** 请求脚本整体结束：走声明式退出入口（作用于本实例，跳过末尾驻留）。 */
    @Override
    public void requestScriptEnd() {
        CameraManager.INSTANCE.requestDeclaredExit(this);
    }

    /**
     * 分支入口已生效。
     *
     * <p>规则运行态无需显式通知：{@link RuleRuntime} 在下一帧按执行身份变化自行建立激活
     * （见 {@code RuleRuntime.evaluate} 的 executionId 检测）。这里只留诊断日志。</p>
     */
    @Override
    public void onBranchStarted(Clip clip, ClipExecution execution) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("分支入口启动: clip={} execution={} local={}",
                    clip != null ? clip.getClipId() : "<null>",
                    execution != null ? execution.executionId() : -1L,
                    execution != null ? execution.localTime() : 0.0);
        }
    }

    /**
     * 分支失败：账本已恢复到 launch 快照、播放头已 seek 回快照位置（{@link ClipBranchController#fail}）。
     *
     * <p><b>边界</b>：只恢复执行事实，不重放内容、不回滚音频 / 覆盖层副作用——各层按自身生命周期收口。
     * 规则运行态作废后按当前脚本重建空运行态：分支已回主线，规则继续从执行事实重新建立激活，
     * 而不是带着失败分支的激活 / 事实继续判定。</p>
     */
    @Override
    public void onBranchFailed(Clip clip, String reason) {
        ErrorLog.logRateLimited("Branch", "failed", 5000L,
                "分支失败，回到主线（" + (clip != null ? clip.getClipId() : "<null>") + "）：" + reason);
        rules.reset(player);
        rules.sync(script(), player);
    }

    // ========== 时间线定位 ==========

    /**
     * 供 {@code PlaybackLifecycle} 在创建实例时注入网络账本（seek 上报出口）。
     */
    void attachLedger(PlaybackLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * 定位本实例的内容时间线到 {@code timelineSeconds}（全局秒）。
     *
     * <p>本地定位始终执行（{@link ScriptPlayer#seekTo}：时钟基准 + 各轨派生状态一起归位）。
     * 上报条件：非编辑器实例、正在播放、脚本 id 非 {@code "<none>"}、实例 id 非空、已接线账本——
     * 满足才发 {@code C2SScriptSeek}，让服务端 EVENT 时间线对齐（前跳不补触发、后跳不重复）。
     * 编辑器预览实例只动本地播放头，不动游戏播放侧的账。</p>
     */
    @Override
    public void seekTo(double timelineSeconds) {
        if (!Double.isFinite(timelineSeconds)) return;
        player.seekTo(timelineSeconds);
        if (editorDriven || ledger == null || !player.isPlaying()) return;
        if (instanceId == null || instanceId.isEmpty()) return;
        String scriptId = scriptId();
        if (scriptId == null || scriptId.isEmpty() || "<none>".equals(scriptId)) return;
        ledger.sendSeekPacket(scriptId, instanceId, timelineSeconds);
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
