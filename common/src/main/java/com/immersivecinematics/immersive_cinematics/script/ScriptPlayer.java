package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExecutionLedger;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipTimeSource;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import com.immersivecinematics.immersive_cinematics.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 脚本播放器 —— 驱动 {@link CinematicScript} 的运行时播放。
 *
 * 管理播放时间（按本实例的 {@link Clock} 换算）；创建并调度 {@link TrackPlayer} 实例
 * （相机 / 黑边 / 音频 / 事件）；持有当前脚本的运行时行为（{@link ScriptMeta.RuntimeBehavior}）；
 * 处理脚本结束与 {@code hold_at_end} 驻留。宏观循环开启时对外分发圈内局部时间。
 *
 * 轨道处理全部在各自的 TrackPlayer 内完成，本类只做调度与时间 / 内容事实的账本。
 */
public class ScriptPlayer implements ClipTimeSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptPlayer");

    /**
     * hold_at_end 的驻留读数偏移：驻留位置 = 内容终点 − 本值，略小于终点以免内容谓词判定脚本已结束；
     * 偏移量在视觉上不可察觉，插值仍取最后一帧的值。
     */
    private static final double HOLD_END_EPSILON = 0.0001;

    /** total_duration 一致性提示的最小输出间隔（毫秒；内容未播完而时间已过时限频提示作者）。 */
    private static final long OVERRUN_WARN_INTERVAL_MS = 10_000L;

    // ========== 状态 ==========

    private CinematicScript script;
    private boolean playing = false;

    // 虚拟时间驱动（单位：秒，double 精度）
    private double startGameTimeSeconds = 0;

    /**
     * 挂起期间保持的内容位置（已播放秒；NaN = 未挂起）：{@link #beginSuspend()} 记下挂起瞬间的读数，
     * 挂起中 {@link #getElapsedSeconds()} 恒返回它，{@link #endSuspend()} 以它重定起始读数——
     * 挂起时长不累计（位置即真源，恢复从该位置续）。
     */
    private double heldElapsed = Double.NaN;

    /**
     * 本实例的时钟源（{@link Clock}，秒）：默认 = 游戏共享虚拟时钟；编辑器播放实例注入编辑器播放头。
     * 编辑器播放与游戏播放各自独立计时，任一侧的暂停 / 播放不影响另一侧的时间轴。
     */
    private Clock clockSource = CameraManager.INSTANCE::getGameTimeSeconds;

    // 相对模式基准位置（玩家激活时的位置）
    private Vec3 originPos = Vec3.ZERO;

    // 玩家移动控制（EVENT 关键帧 position 驱动，假输入走原版链路；不建 TrackPlayer，直接读 EVENT 轨道数据）
    private final PlayerMoveController playerMovement = new PlayerMoveController();

    /**
     * 层归属 token（不透明，值 = 创建本播放器的播放实例引用）：建层时随 {@link #owner()} 传给覆盖层，
     * 使渐出 / 动画判定 / 退场清理按实例过滤。{@code Object} 类型以免 script/ → camera/ 包依赖；
     * 未接线时为 {@code null}（层为无主层）。
     */
    private Object owner;

    /** 接线层归属（由 {@code PlaybackInstance} 在 start / replaceScript 时注入；幂等）。 */
    public void attachOwner(Object owner) {
        this.owner = owner;
    }

    /** 本播放器的层归属 token；未接线时为 {@code null}（无主层）。 */
    public Object owner() {
        return owner;
    }

    public PlayerMoveController getPlayerMovement() { return playerMovement; }
    public Vec3 getOriginPos() { return originPos; }

    // 当前活跃的脚本运行时行为（从 ScriptMeta.RuntimeBehavior 直接持有）
    private ScriptMeta.RuntimeBehavior currentBehavior = null;

    /**
     * 宏观循环区间起点 a（秒）：timeline {@code loop_start}，缺省 0（负值按 0 处理）。
     * 语义 = "从 a 到 b 这一段重复执行"——循环是播放控制（执行层的重复），不是时间折叠。
     */
    private double macroLoopStart = 0.0;

    /**
     * 宏观循环区间终点 b（秒）；NaN = 未启用宏观循环（时间照直走）。
     * b 优先取 timeline 显式声明的 {@code loop_end}（作者声明的子区间），缺省取宏观末端。
     * 脚本开始时推导一次并缓存。
     */
    private double macroLoopEnd = Double.NaN;

    /** 圈长度（b − a，秒）；macroLoopEnd 有效时恒 &gt; 0 */
    private double macroLoopSpan = 0.0;

    /** 宏观循环次数：-1 = 无限重复（脚本不再自然结束）；正整数 N = 重复 N 圈后自然结束 */
    private int macroLoopCount = -1;

    /** 是否允许宏观循环（编辑器播放实例关闭：编辑器播放按真实时间线播放，循环不展开也不折叠） */
    private boolean macroLoopAllowed = true;

    /** 上一帧分发给轨道的圈内局部时间（检测圈边界折返）；NaN = 无上一帧 */
    private double lastLocalElapsed = Double.NaN;

    /**
     * 执行账本：执行次数 / 执行身份 / 局部采样时间 / 完成事实的唯一真源。
     * 换脚本时按新脚本重建（{@link #start} / {@link #replaceScript}），seek / 宏观折返走
     * {@link ClipExecutionLedger#reset(double)}，正常帧走 {@link ClipExecutionLedger#advance(double)}。
     */
    private ClipExecutionLedger executions = new ClipExecutionLedger(null);

    /**
     * 本帧分发给轨道的时间轴读数（秒；宏观循环 = 圈内局部时间）——片段活跃窗判定的唯一读侧出口
     * （{@code ClipModifierGate} 按它比较声明片段的窗口）。初始为 {@code 0}，{@link #start} 后为首帧预执行时间。
     */
    private double timelineTime = 0.0;

    private boolean stopping = false;

    // TrackPlayer 调度列表
    private List<TrackPlayer> trackPlayers = Collections.emptyList();

    /** 本帧活跃 CAMERA 片段的拼接缓冲（{@link #collectActiveCameraClips()} 复用；调用方可当帧追加）。 */
    private final List<Clip> activeCameraClips = new ArrayList<>();

    /**
     * CAMERA lane 调色的对象采样缓存：仅 {@code lane.clip().hasObjects()} 时使用（旧格式 lane 仍走
     * {@link ColorAdjustSampler#sample(Clip, double)}，像素路径不变）。脚本替换 / 停播时清空。
     */
    private final ClipRuntimeCache cameraLaneRuntime = new ClipRuntimeCache(TrackType.CAMERA);
    private final ColorAdjustSampler.CurveCache cameraLaneCurves = new ColorAdjustSampler.CurveCache();

    /**
     * 规则动作 {@code play_sound} 的一次性发声宿主（懒建）：与轨道列表无关——
     * 没有 AUDIO 轨道的脚本同样能发声。不参与轨道分发（只用到一次性声音容器 / 回收 / 停止），
     * 随实例停止清理（见 {@link #cleanupTrackPlayers()}）。
     */
    private AudioTrackPlayer oneShotHost;

    /** 按轨道索引取 clips（动态数据源；支持同类型多条轨道，如 OVERLAY）。 */
    public List<Clip> clipsForTrack(int trackIndex) {
        if (script == null) return Collections.emptyList();
        List<TimelineTrack> tracks = script.getTimeline().getTracks();
        if (trackIndex < 0 || trackIndex >= tracks.size()) return Collections.emptyList();
        return tracks.get(trackIndex).getClips();
    }

    /**
     * 增量替换脚本数据（编辑器编辑路径）：只替换 {@link CinematicScript} 引用并通知各 TrackPlayer
     * 复位 / 重映射，不重建 TrackPlayer、不重新解码音频；相机 / 音频在下一帧用新数据驱动。
     */
    public void replaceScript(CinematicScript newScript) {
        if (newScript == null) return;
        // LUT：旧脚本的 clip 引用释放（新脚本的片段在下一帧采样时按新 clipId 重新登记）
        releaseLutClipRefs(this.script);
        List<TimelineTrack> oldTracks = script != null ? script.getTimeline().getTracks() : null;
        List<TimelineTrack> newTracks = newScript.getTimeline().getTracks();
        boolean layoutChanged = !sameTrackLayout(oldTracks, newTracks);

        this.script = newScript;
        this.currentBehavior = newScript.getMeta() != null ? newScript.getMeta().getBehavior() : null;
        this.executions = new ClipExecutionLedger(newScript);
        this.executions.reset(timelineTime);
        this.computeMacroLoop();

        if (layoutChanged) {
            // 轨道布局变化(轨道数/类型顺序不同):旧 TrackPlayer 绑定的轨道索引对新脚本失效,
            // 必须重建——否则编辑器加载不同轨道结构的脚本后,OVERLAY/AUDIO 等会读到错位/越界的轨道
            // (症状:OVERLAY 层不出现、AUDIO 把字幕轨当音频报错)
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) this.originPos = mc.player.position();
            cleanupTrackPlayers();
            buildTrackPlayers(newScript);
        } else {
            for (TrackPlayer tp : trackPlayers) {
                try {
                    tp.onScriptReplaced();
                } catch (Exception e) {
                    com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer onScriptReplaced 异常", e);
                }
            }
        }
        // 片段对象已换：CAMERA lane 的采样缓冲 / 曲线解析结果失效
        cameraLaneRuntime.clear();
        cameraLaneCurves.clear();
        // 玩家移动控制：增量替换后重新提取 EVENT position 关键帧
        playerMovement.onScriptStart(newScript, originPos);
    }
    /** 轨道布局相同判断:轨道数 + 类型顺序一致(忽略 clip 内容) */
    private static boolean sameTrackLayout(List<TimelineTrack> a, List<TimelineTrack> b) {        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getType() != b.get(i).getType()) return false;
        }
        return true;
    }

    /** 按脚本轨道创建 TrackPlayer(跳过 EVENT 轨道;trackIndex 用原始轨道索引,轨道布局变化时必须重建) */
    private void buildTrackPlayers(CinematicScript script) {
        trackPlayers = new ArrayList<>();
        List<TimelineTrack> tracks = script.getTimeline().getTracks();
        for (int ti = 0; ti < tracks.size(); ti++) {
            TimelineTrack track = tracks.get(ti);
            if (track.getType() != TrackType.EVENT) {  // event 轨道不在客户端处理
                trackPlayers.add(TrackPlayer.create(track.getType(), this, originPos,
                        CameraManager.INSTANCE, OverlayManager.INSTANCE, ti));
            }
        }
    }

    // ========== 生命周期 ==========

    /** 停止并清空当前所有 TrackPlayer（start 前与 stop 时共用，防音频实例泄漏）；一次性声音宿主一并清理。 */
    private void cleanupTrackPlayers() {
        for (TrackPlayer tp : trackPlayers) {
            try {
                tp.onStop();
            } catch (Exception e) {
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer 停止异常", e);
            }
        }
        trackPlayers = Collections.emptyList();
        activeCameraClips.clear();
        cameraLaneRuntime.clear();
        cameraLaneCurves.clear();
        if (oneShotHost != null) {
            oneShotHost.onStop();
            oneShotHost = null;
        }
    }

    /**
     * 释放脚本里带 {@code lut} 的片段在 {@link CubeLutLoader} 的 clip 引用（播放结束 / 换脚本时）。
     * 只摘「哪些 clip 引用了 LUT」的引用表，按路径共享的解析缓存不动——同一 LUT 的其它脚本 / 后续播放
     * 仍复用同一不可变实例；资源重载走 {@link CubeLutLoader#clearCache()}。
     */
    private static void releaseLutClipRefs(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return;
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            for (Clip clip : track.getClips()) {
                if (clip.getLut() != null) {
                    CubeLutLoader.releaseClip(clip.getClipId());
                }
            }
        }
    }

    /**
     * 启动脚本播放（预执行首帧从脚本开头开始——游戏内播放路径）
     *
     * @param script 已解析的脚本对象
     */
    public void start(CinematicScript script) {
        start(script, 0.0);
    }

    /**
     * 启动脚本播放
     *
     * @param script        已解析的脚本对象
     * @param preExecuteAt  预执行首帧的 elapsed（预览 = 播放头时间，避免首帧跳变；游戏内 = 0）
     */
    public void start(CinematicScript script, double preExecuteAt) {
        // 替换 trackPlayers 前先清理旧实例（否则 AudioTrackPlayer 的 OpenAL source 泄漏 → 重复播放 / 无法停止）
        cleanupTrackPlayers();
        // LUT：start 顶掉的旧脚本的 clip 引用一并释放（此时 this.script 仍指向旧脚本）
        releaseLutClipRefs(this.script);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "无法启动脚本：玩家不存在");
            return;
        }

        this.script = script;
        this.originPos = mc.player.position();
        this.playing = true;
        this.stopping = false;
        this.startGameTimeSeconds = clockSeconds();

        ScriptMeta meta = script.getMeta();
        this.currentBehavior = meta.getBehavior();

        // 宏观循环：推导区间 [a, b] 与圈数并缓存（NaN = 不循环）
        this.executions = new ClipExecutionLedger(script);
        this.executions.reset(preExecuteAt);
        computeMacroLoop();

        // block_mob_ai：清空已锁定玩家的生物目标
        if (currentBehavior.blockMobAi() && mc.level != null) {
            Player player = mc.player;
            AABB range = new AABB(player.blockPosition()).inflate(128);
            List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class, range);
            for (Mob mob : mobs) {
                if (mob.getTarget() == player) mob.setTarget(null);
                LivingEntity le = mob;
                if (le.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                        && le.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == player) {
                    le.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, Optional.empty());
                }
            }
        }

        // 创建 TrackPlayer 实例（replaceScript 复用；传轨道索引支持同类型多轨道）
        buildTrackPlayers(script);

        // 玩家移动控制：提取 EVENT position 关键帧
        playerMovement.onScriptStart(script, originPos);

        // 预执行第一帧（避免首帧闪烁）— 用调用方期望的 elapsed（编辑器播放 = previewTime，避免 t=0 跳变）
        double elapsedSeconds = preExecuteAt;
        timelineTime = elapsedSeconds;
        for (TrackPlayer tp : trackPlayers) {
            if (tp.isActiveAt(elapsedSeconds)) {
                try {
                    tp.onRenderFrame(elapsedSeconds);
                } catch (Exception e) {
                    com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer 首帧执行异常", e);
                }
            }
        }

        LOGGER.info("脚本播放开始: {} (总时长: {}s, TrackPlayer数: {})",
                script.getName(), script.getTotalDuration(), trackPlayers.size());
    }

    public void stop(CompletionReason reason) {
        if (script != null) {
            LOGGER.info("脚本播放停止: {} (原因: {})", script.getName(), reason);
        }

        // 通知所有 TrackPlayer 停止并清空
        cleanupTrackPlayers();
        // LUT：本脚本的 clip 引用释放（按路径共享的解析缓存不动；资源重载走 CubeLutLoader#clearCache）
        releaseLutClipRefs(this.script);
        // 玩家移动控制：停止驱动
        playerMovement.onStop();

        this.playing = false;
        this.stopping = false;
        this.script = null;
        this.currentBehavior = null;
        this.executions = new ClipExecutionLedger(null);
        resetMacroLoop();
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * 脚本是否已播放完成 = {@link #allContentConsumed()}（实际执行路径上已无剩余可执行内容）。
     * 宏观循环：有限 N 圈按 {@link #macroLoopTotalEnd()}，无限圈恒 false。
     * 不检查 hold_at_end——播完后驻留 / 退出由调用方（{@code FrameDriver}）处置。
     */
    public boolean isFinished() {
        if (!playing || script == null) return false;
        if (isMacroLoopActive()) {
            // 无限重复 → 脚本不再自然结束（退出走 skippable / interruptible 路径）
            if (macroLoopCount < 0) return false;
            // 有限次数 → 播完第 N 圈后自然结束
            return getElapsedSeconds() >= macroLoopTotalEnd();
        }
        return allContentConsumed();
    }

    /**
     * 内容播完谓词：读执行账本（全部片段 {@code done} = 无待进入 / 执行中的片段；
     * 无限片段永不 done，故无限内容恒 false）。本方法不读时钟。
     * 挂起中恒 false（内容完成度含「无挂起在跑」）。
     */
    private boolean allContentConsumed() {
        if (isSuspended()) return false;
        if (script == null || script.getTimeline() == null) return false;
        return executions.allDone();
    }

    /** 当前播放脚本的 ID；无脚本时为 {@code "<none>"}。 */
    public String getScriptId() {
        return script != null ? script.getId() : "<none>";
    }

    /**
     * 距内容终点的剩余秒数（终点 = 执行账本的 {@link ClipExecutionLedger#contentEnd()}，不是 {@code total_duration}）。
     * 无限内容（永不结束片段 / 宏观无限圈）→ {@link Double#MAX_VALUE}（「不会自然结束」哨兵，读方无需判 NaN）；
     * 已过终点 → {@code 0}；未播放 → {@code 0}。宏观循环按 {@code a + N × (b − a)} 计；内容比声明时长长时
     * 本值可大于「{@code total_duration} − 已播时间」——时间不是结束判据。
     */
    public double getRemainingTime() {
        if (!playing || script == null) return 0.0;
        if (isMacroLoopActive()) {
            if (macroLoopCount < 0) return Double.MAX_VALUE; // 无限重复：不自然结束
            return Math.max(0.0, macroLoopTotalEnd() - getElapsedSeconds());
        }
        if (!executions.hasFiniteContentEnd()) return Double.MAX_VALUE; // 无限内容：无内容终点
        return Math.max(0.0, executions.contentEnd() - getElapsedSeconds());
    }

    /**
     * hold_at_end 的驻留位置（秒）= 有限内容末端退 {@link #HOLD_END_EPSILON}，让最后一帧保持住；
     * 无有限内容末端（永不结束片段 / 空时间轴）→ 当前播放头。
     * 驻留点由实际内容自然完成给出，不由 {@code total_duration}（统计位，可能短于实际内容）。
     */
    public double holdElapsed() {
        if (!executions.hasFiniteContentEnd()) return getElapsedSeconds();
        return Math.max(0.0, executions.contentEnd() - HOLD_END_EPSILON);
    }

    /**
     * 画面 lane 快照：按绘制顺序（轨道层级 → 轨道内 clip 顺序，后画的在上）扁平列出各 CAMERA 轨
     * 本帧全部活跃 clip 的 {@link LaneFrame}；列表最后一个元素 = 顶层相机（写入全局相机状态的那份）。
     * 每份 LaneFrame 带产出片段 / 片段内本地时间 / CAMERA 轨序号（0 起，按出现顺序）/ 片段在轨内下标
     * （0 起）/ 该 lane 的调色参数（恒等 = {@code null}；只承载 RGB，透明度归合成层）。
     * 本方法只读快照，不改变相机行为。
     *
     * @return 无 CAMERA 轨 / 未播放 / 各轨本帧均无画面时为空列表
     */
    public List<LaneFrame> collectCameraLanes() {
        List<LaneFrame> frames = new ArrayList<>();
        int cameraLaneIndex = -1;
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof CameraTrackPlayer ctp) {
                cameraLaneIndex++;
                for (CameraLane lane : ctp.getLaneSnapshots()) {
                    // 每条 lane 各自用「自己的相机片段 + 片段本地时间」采样调色（重叠窗口下各自独立）。
                    // 新格式走对象采样（57 通道 + value-only 曲线）；旧格式仍扫关键帧，像素路径不变。
                    ColorAdjustParams adjust;
                    if (lane.clip().hasObjects()) {
                        SampledClip sample = cameraLaneRuntime.sample(lane.clip(), lane.clipLocalTime());
                        adjust = ColorAdjustSampler.sampleCameraLaneObjects(lane.clip(), sample,
                                cameraLaneRuntime.values(lane.clip()), cameraLaneCurves);
                    } else {
                        adjust = ColorAdjustSampler.sample(lane.clip(), lane.clipLocalTime());
                    }
                    // 恒等参数归一化为 null —— 渲染侧因此保持 lane FBO 直接进合成的原路径。
                    // dimension：空串 = 玩家当前维度；非空 = 客户端按该维度渲染这条 lane（无服务端传送）
                    frames.add(new LaneFrame(lane, cameraLaneIndex, ctp.indexOfClip(lane.clip()),
                            adjust.isIdentity() ? null : adjust, lane.clip().getDimension()));
                }
            }
        }
        return frames;
    }

    /**
     * 本帧活跃 CAMERA 片段的拼接列表（各 {@link CameraTrackPlayer#frameActiveClips()} 按轨道顺序拼接）：
     * 租约对账口径——片段仍活跃但本帧不产画面（目标不可用 / 单帧空档 / morph 两侧）时保留租约。
     *
     * <p>返回的是复用缓冲，仅本帧有效；调用方可在本帧内追加片段（例如本帧 lane 里出现过的片段）。</p>
     *
     * @return 活跃片段列表（可能为空；不新建集合）
     */
    public List<Clip> collectActiveCameraClips() {
        activeCameraClips.clear();
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof CameraTrackPlayer ctp) {
                activeCameraClips.addAll(ctp.frameActiveClips());
            }
        }
        return activeCameraClips;
    }

    public boolean hasActiveCameraTrack(double elapsed) {
        // 宏观循环：渲染门控（相机/FOV/roll/遮挡/玩家模型）必须与实际分发给轨道的时间一致，
        // 否则第二圈起 hasActiveCameraClip() 变 false → 相机不渲染
        double t = localTimeFor(elapsed);
        if (Double.isNaN(t)) return false; // 有限次数已播完：不渲染，等自然结束
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof CameraTrackPlayer && tp.isActiveAt(t)) {
                return true;
            }
        }
        return false;
    }

    public CinematicScript getScript() {
        return script;
    }

    /** 当前脚本运行时行为；无脚本播放时为 {@code null}。 */
    public ScriptMeta.RuntimeBehavior getCurrentProperties() {
        return currentBehavior;
    }

    // ========== 帧回调驱动 ==========

    /**
     * 每渲染帧驱动
     *
     * @param elapsedSeconds 本实例已播放秒（由调用方按本实例时钟换算：挂起时 = 保持位，
     *                       hold_at_end 时 = 钳制到内容末端的读数）——本方法不再自行读时钟
     */
    public void onRenderFrame(double elapsedSeconds) {
        if (!playing || script == null) return;

        double totalDuration = script.getTotalDuration();

        if (isMacroLoopActive()) {
            // 宏观循环（repeat）：从 a 到 b 重复执行；对外分发的一律是圈内局部时间。
            // 作用点唯一——TrackPlayer / PlayerMoveController 拿到的是圈内局部时间，不感知循环。
            double local = localTimeFor(elapsedSeconds);
            if (Double.isNaN(local)) {
                // 有限次数已播完：isFinished() 为 true，由 CameraManager 走自然结束
                return;
            }
            // 圈边界（本帧局部时间 < 上帧）→ 折返重置（其余状态每帧按时间重查，天然自愈）
            boolean folded = !Double.isNaN(lastLocalElapsed) && local < lastLocalElapsed;
            if (folded) {
                playerMovement.onScriptLoop();
                for (TrackPlayer tp : trackPlayers) {
                    try {
                        tp.onLoopRestart();
                    } catch (Exception e) {
                        com.immersivecinematics.immersive_cinematics.util.ErrorLog.log(
                                "Playback", "TrackPlayer onLoopRestart 异常", e);
                    }
                }
            }
            // 正常帧先推进；圈边界折返（或首帧播放头回退）按新位置重导，片段的新执行获得新身份
            if (folded || local < executions.lastHead()) {
                executions.reset(local);
            } else {
                executions.advance(local);
            }
            lastLocalElapsed = local;
            elapsedSeconds = local;
        } else {
            // 执行账本：按本帧播放头推进执行事实（进入 / 次数耗尽 / done；判定读事实；零分配）
            // 只在播放头前进时推进：hold 钳制 / 同帧重复驱动会让读数不前进甚至回退，
            // 账本停在已记录的最远事实位置（不回退、不抛异常）。
            if (elapsedSeconds > executions.lastHead()) {
                executions.advance(elapsedSeconds);
            }
            if (allContentConsumed()) {
                // 内容播完：hold_at_end 驻留在内容末端最后一帧，否则本帧不分发
                // （isFinished() 为 true，由调用方走自然结束 / 驻留）
                if (script.getMeta().isHoldAtEnd()) {
                    elapsedSeconds = holdElapsed();
                } else {
                    return;
                }
            } else if (totalDuration > 0.0 && elapsedSeconds >= totalDuration) {
                // total_duration 是统计位（记录 / 真同步），不是结束判据：内容比声明长 → 限频提示作者核对
                if (executions.hasFiniteContentEnd()) {
                    warnDurationOverrun(elapsedSeconds, totalDuration, executions.contentEnd());
                }
            }
        }

        // 调度所有 TrackPlayer（onRenderFrame 内部自行判断是否有活跃 clip，无需 isActiveAt() 预检查）
        timelineTime = elapsedSeconds;
        for (TrackPlayer tp : trackPlayers) {
            try {
                tp.onRenderFrame(elapsedSeconds);
            } catch (Exception e) {
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer 执行异常", e);
            }
        }
        // 一次性声音（规则动作 play_sound）的帧内回收：已播完的从容器移除
        if (oneShotHost != null) {
            oneShotHost.pruneOneShots();
        }

        // 玩家移动控制（EVENT position 关键帧驱动，与相机同一虚拟时钟，暂停感知）
        playerMovement.onRenderFrame(elapsedSeconds);
    }

    /**
     * 挂起本实例（执行状态暂停：保持当前内容位置，后续关键帧不推进）。幂等。
     * 挂起期间真实时钟照走，但 {@link #getElapsedSeconds()} 恒为挂起瞬间的位置。
     */
    public void beginSuspend() {
        if (isSuspended()) return;
        heldElapsed = getElapsedSeconds();
    }

    /**
     * 解除挂起：从保持的内容位置续播（起始读数按它重定，挂起时长不计入播放时间）。
     * 幂等——未挂起时无操作。
     */
    public void endSuspend() {
        if (!isSuspended()) return;
        startGameTimeSeconds = clockSeconds() - heldElapsed;
        heldElapsed = Double.NaN;
    }

    /** 本实例是否处于挂起（内容位置保持中）状态。 */
    public boolean isSuspended() {
        return !Double.isNaN(heldElapsed);
    }

    /**
     * 定位到期望的已播放时间（秒）：起始读数按 {@link #alignTime} 换算，各轨按时间推进的派生状态一并归位
     * （{@link #onSeek()}）——两半必须成对，只改读数不归位会让运动模型从错误的时间差重新推进。
     */
    public void seekTo(double desiredElapsedSeconds) {
        alignTime(desiredElapsedSeconds, clockSeconds());
        onSeek();
    }

    /**
     * 对齐内部时钟，使脚本已播放时间等于期望值（编辑器重载脚本 / 定位编辑器播放头 / {@code goto_clip} 跳转）。
     *
     * 起始读数 = 基准读数 − 期望时间：与 {@link #getElapsedSeconds()} 同一公式（两处必须同步）。
     * 挂起中定位改写保持的内容位置（恢复仍从该位置续）；定位即路径变更，执行事实按新位置重导。
     */
    public void alignTime(double desiredElapsedSeconds, double currentGameTimeSeconds) {
        this.startGameTimeSeconds = currentGameTimeSeconds - desiredElapsedSeconds;
        if (isSuspended()) heldElapsed = desiredElapsedSeconds;
        executions.reset(desiredElapsedSeconds);
    }

    /**
     * 编辑器 seek：本实例各轨道按时间推进的派生状态归位（相机运动模型实例经 {@link TrackPlayer#onSeek()}），
     * 使之后从 seek 点重新推进；时钟基准由 {@link #alignTime} 单独调整。
     */
    public void onSeek() {
        for (TrackPlayer tp : trackPlayers) {
            try {
                tp.onSeek();
            } catch (Exception e) {
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer onSeek 异常", e);
            }
        }
    }

    /**
     * 播一条一次性声音（规则动作 {@code play_sound}；本实例作用域）。宿主懒建：无 AUDIO 轨道的脚本也能发声。
     *
     * @param sound    音效事件（已解析）
     * @param category 声音分类
     * @param volume   音量（1 = 原值）
     * @param pitch    音调（1 = 原值）
     */
    public void playOneShot(SoundEvent sound, SoundSource category, float volume, float pitch) {
        if (oneShotHost == null) {
            oneShotHost = new AudioTrackPlayer(this, TrackType.AUDIO, Vec3.ZERO, -1);
        }
        oneShotHost.playOneShot(sound, category, volume, pitch);
    }

    /** 停本实例的一次性声音（规则挂起 / 停播；无宿主 = 无操作）。 */
    public void stopOneShotSounds() {
        if (oneShotHost != null) {
            oneShotHost.stopOneShots();
        }
    }

    /**
     * 暂停所有音频轨道（游戏暂停时调用）
     */
    public void pauseAudio() {
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof AudioTrackPlayer atp) {
                atp.pauseAll();
            }
        }
    }

    /**
     * 恢复所有音频轨道（游戏恢复时调用）
     */
    public void resumeAudio() {
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof AudioTrackPlayer atp) {
                atp.resumeAll();
            }
        }
    }

    /** 把音频轨道重定位到新的全局时间（编辑器拖动播放头时由 {@code CameraManager} 调用）。 */
    public void repositionAudio(double globalTime) {
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof AudioTrackPlayer atp) {
                atp.repositionAudio(globalTime);
            }
        }
    }

    // ========== 内部方法 ==========

    /**
     * 开关宏观循环。编辑器播放实例必须关闭——编辑器播放按真实时间线播放：
     * 循环是运行时播放控制，编辑器时间轴既不展开也不折叠。
     */
    public void setMacroLoopAllowed(boolean allowed) {
        if (this.macroLoopAllowed == allowed) return;
        this.macroLoopAllowed = allowed;
        computeMacroLoop();
    }

    /** 清空宏观循环参数（不循环） */
    private void resetMacroLoop() {
        macroLoopStart = 0.0;
        macroLoopEnd = Double.NaN;
        macroLoopSpan = 0.0;
        macroLoopCount = -1;
        lastLocalElapsed = Double.NaN;
    }

    /** 宏观循环是否生效（区间 [a, b] 已推导出来） */
    private boolean isMacroLoopActive() {
        return !Double.isNaN(macroLoopEnd);
    }

    /** 有限次数宏观循环的总播放时长（秒）：a + N × (b − a)；仅在 macroLoopCount &gt; 0 时有意义 */
    private double macroLoopTotalEnd() {
        return macroLoopStart + macroLoopSpan * macroLoopCount;
    }

    /**
     * 推导宏观循环参数（区间 [a, b] 与圈数）；不满足条件 → 不循环（macroLoopEnd = NaN）。
     * 条件：{@code meta.macro_loop=true} + 宏观循环被允许（编辑器播放实例关闭）+ 区间非空。
     * a = {@code timeline.loop_start}（缺省 0，负值按 0）；b 优先取 {@code timeline.loop_end}
     * （作者声明的子区间），缺省取宏观末端——此时存在永不结束片段 → 末端不存在 → 不循环。
     * 脚本开始时调用一次并缓存。
     */
    private void computeMacroLoop() {
        resetMacroLoop();
        if (script == null || script.getMeta() == null || !macroLoopAllowed) return;
        if (!script.getMeta().isMacroLoop()) return;
        Timeline timeline = script.getTimeline();
        if (timeline == null) return;

        double a = Math.max(0.0, timeline.getLoopStart());
        double b;
        if (timeline.getLoopEnd() > a) {
            b = timeline.getLoopEnd(); // 作者声明的子区间末端
        } else {
            Double macroEnd = computeMacroEnd();
            if (macroEnd == null) return; // 末端不存在（存在永不结束片段）→ 不循环
            b = macroEnd;
        }
        if (!(b > a)) return; // 空区间 → 不循环（避免对 0 取模）

        macroLoopStart = a;
        macroLoopEnd = b;
        macroLoopSpan = b - a;
        int count = script.getMeta().getMacroLoopCount();
        macroLoopCount = count > 0 ? count : -1;
    }

    /**
     * 圈内局部时间（repeat 语义）：t &lt; a 直通；t ≥ a → a + (t − a) mod (b − a)。
     * 圈边界（本帧局部时间 &lt; 上帧）即折返重置点。
     */
    private double toLocalTime(double elapsed) {
        if (elapsed < macroLoopStart) return elapsed;
        double rel = elapsed - macroLoopStart;
        return macroLoopStart + (rel % macroLoopSpan);
    }

    /**
     * 分发给轨道的圈内局部时间；有限次数已播完 → NaN（不渲染，脚本已结束）。
     * {@code hold_at_end} 时停在区间末端最后一帧。未开启循环 → 原值返回。
     */
    private double localTimeFor(double elapsed) {
        if (!isMacroLoopActive()) return elapsed;
        if (macroLoopCount > 0 && elapsed >= macroLoopTotalEnd()) {
            return script != null && script.getMeta().isHoldAtEnd()
                    ? Math.max(macroLoopStart, macroLoopEnd - HOLD_END_EPSILON)
                    : Double.NaN;
        }
        return toLocalTime(elapsed);
    }

    /**
     * 宏观末端（秒）= 执行账本的 {@link ClipExecutionLedger#contentEnd()}；不存在
     * （存在无限执行 / 空时间轴 / 零长度）→ 返回 null（不循环）。
     */
    private Double computeMacroEnd() {
        if (!executions.hasFiniteContentEnd()) return null;
        double end = executions.contentEnd();
        return end <= 0.0 ? null : end; // 空时间轴 / 零长度：不循环，避免对 0 取模
    }

    /**
     * {@code total_duration} 一致性提示：内容未播完而播放头已过声明时长 → 限频（{@link #OVERRUN_WARN_INTERVAL_MS}）
     * 提示作者核对声明时长（内容比声明长是合法用法：按内容继续播完，本值只作记录 / 真同步）。
     * 只在异常路径调用（拼串 / 装箱不进每帧零分配契约）。
     */
    private void warnDurationOverrun(double elapsed, double totalDuration, double end) {
        String endText = end + "s";
        com.immersivecinematics.immersive_cinematics.util.ErrorLog.logRateLimited(
                "Playback", "duration-overrun:" + getScriptId(), OVERRUN_WARN_INTERVAL_MS,
                String.format("脚本 '%s' 内容未播完而 total_duration(%ss) 已过（elapsed %ss，内容终点 %s）："
                                + "按内容继续播完（total_duration 是统计位，不决定结束时刻）",
                        getScriptId(), totalDuration, elapsed, endText));
    }

    /** 本帧分发给轨道的时间轴读数（秒，double 精度；宏观循环 = 圈内局部时间）。 */
    public double timelineTime() {
        return timelineTime;
    }

    /**
     * 内容相对位置 = (片段起始秒, 片段内偏移秒)：{@link #timelineTime()} 所在片段 + 片段内偏移秒。
     * 片段 = 扁平序（轨道序 → 轨道内片段序）上第一个覆盖该读数的片段——全轨道共用一条时间轴，
     * 故任一覆盖片段都确定同一位置；无片段覆盖（片段间隙 / 空时间轴）= 起始秒 {@link Float#NaN}、偏移 = 读数本身。
     *
     * <p>缓冲保持 {@code float[]}：这是编辑器 UI 读用的兼容容器（调用方按 float 装配属性面板），
     * 故在此处显式窄化——时间链本身全程 double，窄化只发生在缓冲写入这一步。</p>
     *
     * @param out 调用方复用缓冲（长度 ≥ 2）：{@code out[0]} = 片段起始秒，{@code out[1]} = 片段内偏移秒；零分配
     */
    public void contentPosition(float[] out) {
        double head = timelineTime;
        if (script != null && script.getTimeline() != null) {
            for (TimelineTrack track : script.getTimeline().getTracks()) {
                for (Clip clip : track.getClips()) {
                    if (!executions.isActive(clip, head)) continue;
                    out[0] = (float) executions.windowStart(clip);
                    out[1] = (float) executions.localTime(clip, head);
                    return;
                }
            }
        }
        out[0] = Float.NaN;
        out[1] = (float) head;
    }

    // ========== 执行账本 / ClipTimeSource ==========

    /** 本实例的执行账本（只读访问器：轨道 / 探针查执行事实）。 */
    public ClipExecutionLedger executions() {
        return executions;
    }

    @Override
    public boolean isClipActive(Clip clip, double head) {
        return executions.isActive(clip, head);
    }

    @Override
    public double executionLocalTime(Clip clip, double head) {
        return executions.localTime(clip, head);
    }

    @Override
    public double finiteWindowEnd(Clip clip) {
        return executions.windowEnd(clip);
    }

    /**
     * 本实例已播放时间（秒）= 时钟读数 − 起始读数；挂起中 = 保持的内容位置（{@link #heldElapsed}）。
     *
     * 挂起期间本值恒定、恢复后从该值继续（起始读数在 {@link #endSuspend()} 按它重定）。
     * 时钟源随实例走（{@link #setClockSource}）：游戏实例 = 全局虚拟时钟；编辑器播放实例 = 编辑器播放头。
     */
    public double getElapsedSeconds() {
        if (isSuspended()) return heldElapsed;
        return clockSeconds() - startGameTimeSeconds;
    }

    /**
     * 设置本实例的时钟源（秒）；{@code null} = 保持当前（默认游戏共享虚拟时钟）。
     * 须在 {@link #start(CinematicScript, double)} 之前设置（起始读数在 start 时取）。
     * 编辑器播放实例注入编辑器播放头。
     */
    public void setClockSource(Clock source) {
        if (source != null) this.clockSource = source;
    }

    /** 本实例当前时钟读数（秒）。 */
    private double clockSeconds() {
        return clockSource.seconds();
    }
}
