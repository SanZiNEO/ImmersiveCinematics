package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 脚本播放器 — 驱动 CinematicScript 的运行时播放
 * <p>
 * 核心职责（重构后）：
 * <ul>
 *   <li>管理全局播放时间（基于 CameraManager 虚拟时钟）</li>
 *   <li>创建并调度 TrackPlayer 实例（Camera/Letterbox/Audio/ModEvent）</li>
 *   <li>管理 ScriptProperties 单例（脚本运行时行为标志位）</li>
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

    // 虚拟时间驱动
    private long startGameTimeNanos = 0;

    // 相对模式基准位置（玩家激活时的位置）
    private Vec3 originPos = Vec3.ZERO;

    // 脚本运行时属性（消费 ScriptMeta 中的标志位）
    private final ScriptProperties properties = new ScriptProperties();

    // 当前活跃的脚本属性（替代原 ScriptProperties 静态单例）
    private ScriptProperties currentProperties = null;

    // TrackPlayer 调度列表
    private List<TrackPlayer> trackPlayers = Collections.emptyList();

    // ========== 生命周期 ==========

    /**
     * 启动脚本播放
     *
     * @param script 已解析的脚本对象
     */
    public void start(CinematicScript script) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.error("无法启动脚本：玩家不存在");
            return;
        }

        this.script = script;
        this.originPos = mc.player.position();
        this.playing = true;
        this.startGameTimeNanos = CameraManager.INSTANCE.getGameTimeNanos();

        // 应用脚本元信息到 ScriptProperties，并设置为当前活跃属性
        ScriptMeta meta = script.getMeta();
        properties.apply(meta);
        currentProperties = properties;
        CameraManager.INSTANCE.setPauseWhenGamePaused(properties.isPauseWhenGamePaused());

        // 创建 TrackPlayer 实例
        trackPlayers = new ArrayList<>();
        InterpolationType scriptInterp = meta.getInterpolation();
        CurveCompositionMode compositionMode = meta.getCurveCompositionMode();
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            if (track.getType() != TrackType.EVENT) {  // event 轨道不在客户端处理
                trackPlayers.add(TrackPlayer.create(track, originPos,
                        CameraManager.INSTANCE, OverlayManager.INSTANCE,
                        scriptInterp, compositionMode));
            }
        }

        // 预执行第一帧（避免首帧闪烁）
        float elapsedSeconds = 0f;
        for (TrackPlayer tp : trackPlayers) {
            if (tp.isActiveAt(elapsedSeconds)) {
                try {
                    tp.onRenderFrame(elapsedSeconds);
                } catch (Exception e) {
                    LOGGER.error("TrackPlayer 首帧执行异常", e);
                }
            }
        }

        LOGGER.info("脚本播放开始: {} (总时长: {}s, TrackPlayer数: {})",
                script.getName(), script.getTotalDuration(), trackPlayers.size());
    }

    /**
     * 停止脚本播放
     */
    public void stop() {
        if (script != null) {
            LOGGER.info("脚本播放停止: {}", script.getName());
        }

        // 通知所有 TrackPlayer 停止
        for (TrackPlayer tp : trackPlayers) {
            try {
                tp.onStop();
            } catch (Exception e) {
                LOGGER.error("TrackPlayer 停止异常", e);
            }
        }

        this.playing = false;
        this.script = null;
        this.trackPlayers = Collections.emptyList();
        properties.revert();  // 重置所有标志位为默认值
        currentProperties = null;  // 清除当前活跃属性引用
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
        return elapsed >= totalDuration;
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
        return Math.max(0f, totalDuration - getElapsedSeconds());
    }

    public CinematicScript getScript() {
        return script;
    }

    /**
     * 获取当前活跃的脚本属性
     *
     * @return 当前脚本属性，无脚本播放时返回 null
     */
    public ScriptProperties getCurrentProperties() {
        return currentProperties;
    }

    // ========== 帧回调驱动 ==========

    /**
     * 每渲染帧驱动：由 CameraManager.onRenderFrame() 调用
     *
     * @param gameTimeNanos 当前虚拟游戏时间（纳秒）
     */
    public void onRenderFrame(long gameTimeNanos) {
        if (!playing || script == null) return;

        float elapsedSeconds = getElapsedSeconds();
        float totalDuration = script.getTotalDuration();

        // 检查脚本是否结束
        if (totalDuration > 0 && elapsedSeconds >= totalDuration) {
            if (script.getMeta().isHoldAtEnd()) {
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
                LOGGER.error("TrackPlayer 执行异常", e);
            }
        }
    }

    // ========== 内部方法 ==========

    private float getElapsedSeconds() {
        return (CameraManager.INSTANCE.getGameTimeNanos() - startGameTimeNanos) / 1_000_000_000f;
    }
}
