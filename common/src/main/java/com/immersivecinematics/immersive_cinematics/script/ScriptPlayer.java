package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import net.minecraft.client.Minecraft;
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
 * 脚本播放器 — 驱动 CinematicScript 的运行时播放
 * <p>
 * 核心职责（重构后）：
 * <ul>
 *   <li>管理全局播放时间（基于 CameraManager 虚拟时钟）</li>
 *   <li>创建并调度 TrackPlayer 实例（Camera/Letterbox/Audio/ModEvent）</li>
 *   <li>管理当前脚本的运行时行为（从 ScriptMeta.RuntimeBehavior 直接持有）</li>
 *   <li>处理脚本结束 / holdAtEnd</li>
 * </ul>
 * <p>
 * 不再直接访问 CameraManager/OverlayManager 的写入方法 —
 * 所有轨道处理逻辑已抽取到各自的 TrackPlayer 实现中。
 */
public class ScriptPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptPlayer");

    /**
     * holdAtEnd 模式下的时间偏移量 — 略小于 totalDuration，
     * 避免 isFinished() 判定脚本已结束。
     * 0.1ms 的偏移在视觉上不可察觉，但确保插值仍取最后一帧的值。
     */
    private static final float HOLD_END_EPSILON = 0.0001f;

    // ========== 状态 ==========

    private CinematicScript script;
    private boolean playing = false;

    // 虚拟时间驱动（单位：秒，double 精度）
    private double startGameTimeSeconds = 0;

    // 相对模式基准位置（玩家激活时的位置）
    private Vec3 originPos = Vec3.ZERO;

    // 当前活跃的脚本运行时行为（从 ScriptMeta.RuntimeBehavior 直接持有）
    private ScriptMeta.RuntimeBehavior currentBehavior = null;

    private boolean stopping = false;

    // TrackPlayer 调度列表
    private List<TrackPlayer> trackPlayers = Collections.emptyList();

    /** 组 A：按轨道索引取 clips（动态数据源；支持同类型多条轨道——OVERLAY 多轨道） */
    public List<Clip> clipsForTrack(int trackIndex) {
        if (script == null) return Collections.emptyList();
        List<TimelineTrack> tracks = script.getTimeline().getTracks();
        if (trackIndex < 0 || trackIndex >= tracks.size()) return Collections.emptyList();
        return tracks.get(trackIndex).getClips();
    }

    /**
     * 组 A：增量替换脚本数据（编辑器编辑路径，常驻播放器零重启）。
     * <p>
     * 只替换 CinematicScript 引用并通知各 TrackPlayer 复位/重映射——
     * 不重建 TrackPlayer、不重新解码音频。相机/音频在下一帧用新数据驱动。
     */
    public void replaceScript(CinematicScript newScript) {
        if (newScript == null) return;
        List<TimelineTrack> oldTracks = script != null ? script.getTimeline().getTracks() : null;
        List<TimelineTrack> newTracks = newScript.getTimeline().getTracks();
        boolean layoutChanged = !sameTrackLayout(oldTracks, newTracks);

        this.script = newScript;
        this.currentBehavior = newScript.getMeta() != null ? newScript.getMeta().getBehavior() : null;

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
    }

    /** 轨道布局相同判断:轨道数 + 类型顺序一致(忽略 clip 内容) */
    private static boolean sameTrackLayout(List<TimelineTrack> a, List<TimelineTrack> b) {
        if (a == null || b == null) return false;
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

    /** 停止并清空当前所有 TrackPlayer（start 前与 stop 时共用，防音频实例泄漏） */
    private void cleanupTrackPlayers() {
        for (TrackPlayer tp : trackPlayers) {
            try {
                tp.onStop();
            } catch (Exception e) {
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer 停止异常", e);
            }
        }
        trackPlayers = Collections.emptyList();
    }

    /**
     * 启动脚本播放（预执行首帧从脚本开头开始——游戏内播放路径）
     *
     * @param script 已解析的脚本对象
     */
    public void start(CinematicScript script) {
        start(script, 0f);
    }

    /**
     * 启动脚本播放
     *
     * @param script        已解析的脚本对象
     * @param preExecuteAt  预执行首帧的期望 elapsed 时间（预览模式 = previewTime，
     *                      避免重启后首帧写 t=0 造成画面跳变；游戏内 = 0）
     */
    public void start(CinematicScript script, float preExecuteAt) {
        // D1：替换 trackPlayers 前先清理旧实例（否则 AudioTrackPlayer 的 OpenAL source 泄漏 → 重复播放/无法停止）
        cleanupTrackPlayers();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "无法启动脚本：玩家不存在");
            return;
        }

        this.script = script;
        this.originPos = mc.player.position();
        this.playing = true;
        this.stopping = false;
        this.startGameTimeSeconds = CameraManager.INSTANCE.getGameTimeSeconds();

        // 持有当前脚本的运行时行为
        ScriptMeta meta = script.getMeta();
        this.currentBehavior = meta.getBehavior();

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

        // 创建 TrackPlayer 实例（组 A：数据源动态化，后续 replaceScript 不重建；传轨道索引支持同类型多轨道）
        buildTrackPlayers(script);

        // 预执行第一帧（避免首帧闪烁）— 用调用方期望的 elapsed（预览模式 = previewTime，避免 t=0 跳变）
        float elapsedSeconds = preExecuteAt;
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

    /**
     * 停止脚本播放
     *
     * @param reason 完成原因
     */
    public void stop(CompletionReason reason) {
        if (script != null) {
            LOGGER.info("脚本播放停止: {} (原因: {})", script.getName(), reason);
        }

        // 通知所有 TrackPlayer 停止并清空
        cleanupTrackPlayers();

        this.playing = false;
        this.stopping = false;
        this.script = null;
        this.currentBehavior = null;
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * 脚本是否已播放完成（时间耗尽）
     * <p>
     * 注意：此方法仅检查时间是否耗尽，不检查 holdAtEnd。
     * holdAtEnd 的保持逻辑由 CameraManager.onRenderFrame() 负责：
     * <ul>
     *   <li>holdAtEnd=false → 播完后自动 deactivateNow()</li>
     *   <li>holdAtEnd=true → 播完后保持相机状态，等待用户退出或新脚本打断</li>
     * </ul>
     */
    public boolean isFinished() {
        if (!playing || script == null) return false;
        float totalDuration = script.getTotalDuration();
        if (totalDuration < 0) return false; // 无限循环脚本
        float elapsed = getElapsedSeconds();
        if (elapsed < totalDuration) return false;
        // 时间耗尽后：若存在已开始的无限循环片段（loop=true + loop_count=-1），脚本永不结束
        return !hasActiveInfiniteLoopClip(elapsed);
    }

    /**
     * 获取当前播放脚本的ID
     * <p>
     * 用于日志和调试，如 CameraManager 打印脚本抢占拒绝信息。
     *
     * @return 脚本ID，无脚本播放时返回 "<none>"
     */
    public String getScriptId() {
        return script != null ? script.getId() : "<none>";
    }

    /**
     * 获取剩余播放时间（秒）
     */
    public float getRemainingTime() {
        if (!playing || script == null) return 0f;
        float totalDuration = script.getTotalDuration();
        if (totalDuration < 0) return Float.MAX_VALUE; // 无限循环
        if (hasActiveInfiniteLoopClip(getElapsedSeconds())) return Float.MAX_VALUE; // 无限循环片段已开始
        return Math.max(0f, totalDuration - getElapsedSeconds());
    }

    public boolean hasActiveCameraTrack(float elapsed) {
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof CameraTrackPlayer && tp.isActiveAt(elapsed)) {
                return true;
            }
        }
        return false;
    }

    public CinematicScript getScript() {
        return script;
    }

    /**
     * 获取当前活跃的脚本运行时行为
     *
     * @return 当前脚本运行时行为，无脚本播放时返回 null
     */
    public ScriptMeta.RuntimeBehavior getCurrentProperties() {
        return currentBehavior;
    }

    // ========== 帧回调驱动 ==========

    /**
     * 每渲染帧驱动：由 CameraManager.onRenderFrame() 调用
     *
     * @param gameTimeSeconds 当前虚拟游戏时间（秒，double 精度）
     */
    public void onRenderFrame(double gameTimeSeconds) {
        if (!playing || script == null) return;

        float elapsedSeconds = getElapsedSeconds();
        float totalDuration = script.getTotalDuration();

        // 检查脚本是否结束
        if (totalDuration > 0 && elapsedSeconds >= totalDuration) {
            if (hasActiveInfiniteLoopClip(elapsedSeconds)) {
                // 无限循环片段（loop=true + loop_count=-1）已开始：脚本持续播放，不退出
            } else if (script.getMeta().isHoldAtEnd()) {
                // holdAtEnd: 保持在最后一帧
                elapsedSeconds = totalDuration - HOLD_END_EPSILON;
            } else {
                // 脚本结束，isFinished() 将返回 true
                return;
            }
        }

        // 调度所有 TrackPlayer（onRenderFrame 内部自行判断是否有活跃 clip，无需 isActiveAt() 预检查）
        for (TrackPlayer tp : trackPlayers) {
            try {
                tp.onRenderFrame(elapsedSeconds);
            } catch (Exception e) {
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer 执行异常", e);
            }
        }
    }

    /**
     * Align the internal clock so that the script elapsed time matches a desired value.
     * Called by CameraManager when the script is reloaded during preview.
     */
    public void alignTime(float desiredElapsedSeconds, double currentGameTimeSeconds) {
        this.startGameTimeSeconds = currentGameTimeSeconds - desiredElapsedSeconds;
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

    /**
     * Reposition audio tracks to match a new global time.
     * Called from CameraManager when editor playhead is dragged.
     */
    public void repositionAudio(float globalTime) {
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof AudioTrackPlayer atp) {
                atp.repositionAudio(globalTime);
            }
        }
    }

    // ========== 内部方法 ==========

    /**
     * 是否存在已开始（elapsed >= clip.startTime）且永不结束（无限时长或无限循环）的片段。
     * 用于时间耗尽后判定脚本是否因无限循环片段而持续播放。
     */
    private boolean hasActiveInfiniteLoopClip(float elapsed) {
        if (script == null) return false;
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            for (Clip clip : track.getClips()) {
                if (clip.isEffectivelyInfinite() && elapsed >= clip.getStartTime()) {
                    return true;
                }
            }
        }
        return false;
    }

    private float getElapsedSeconds() {
        return (float)(CameraManager.INSTANCE.getGameTimeSeconds() - startGameTimeSeconds);
    }
}
