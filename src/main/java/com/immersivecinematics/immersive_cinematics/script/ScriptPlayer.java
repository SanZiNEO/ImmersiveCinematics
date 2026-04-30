package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.overlay.LetterboxLayer;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 脚本播放器 — 驱动 CinematicScript 的运行时播放
 * <p>
 * 核心职责：
 * <ul>
 *   <li>管理全局播放时间（基于 CameraManager 虚拟时钟）</li>
 *   <li>每帧定位活跃片段（clip），调用 KeyframeInterpolator 计算相机状态</li>
 *   <li>将计算结果写入 CameraManager 的 active 缓冲区</li>
 *   <li>驱动 letterbox/audio/event 等轨道</li>
 *   <li>处理脚本结束 / holdAtEnd / 循环</li>
 * </ul>
 * <p>
 * 与 CameraTestPlayer 的关系：
 * - CameraTestPlayer 是硬编码测试播放器，P 键触发
 * - ScriptPlayer 是脚本驱动播放器，/cinematic play 命令触发
 * - 两者互斥，同一时间只有一个在运行
 * - 都通过 CameraManager 的虚拟时钟驱动
 */
public class ScriptPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptPlayer");

    // ========== 状态 ==========

    private CinematicScript script;
    private boolean playing = false;

    // 虚拟时间驱动
    private long startGameTimeNanos = 0;

    // 相对模式基准位置（玩家激活时的位置）
    private Vec3 originPos = Vec3.ZERO;

    // 当前活跃的 camera clip 索引（用于检测 clip 切换）
    private int currentCameraClipIndex = -1;

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
        this.currentCameraClipIndex = -1;

        // 应用脚本元信息到 CameraManager
        ScriptMeta meta = script.getMeta();
        CameraManager.INSTANCE.setPauseWhenGamePaused(meta.isPauseWhenGamePaused());

        // 设置 letterbox（从脚本 letterbox 轨道读取，或使用默认值）
        setupLetterboxFromScript();

        LOGGER.info("脚本播放开始: {} (总时长: {}s)", script.getName(), script.getTotalDuration());
    }

    /**
     * 停止脚本播放
     */
    public void stop() {
        if (script != null) {
            LOGGER.info("脚本播放停止: {}", script.getName());
        }
        this.playing = false;
        this.script = null;
        this.currentCameraClipIndex = -1;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isFinished() {
        if (!playing || script == null) return false;
        float totalDuration = script.getTotalDuration();
        if (totalDuration < 0) return false; // 无限循环脚本
        float elapsed = getElapsedSeconds();
        return elapsed >= totalDuration && !script.getMeta().isHoldAtEnd();
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
                elapsedSeconds = totalDuration - 0.0001f;
            } else {
                // 脚本结束，isFinished() 将返回 true
                return;
            }
        }

        // 处理各轨道
        processCameraTrack(elapsedSeconds);
        processLetterboxTrack(elapsedSeconds);
        // audio/event/mod_event 轨道暂不实现，后续迭代
    }

    // ========== Camera 轨道处理 ==========

    private void processCameraTrack(float globalTime) {
        TimelineTrack cameraTrack = script.getTimeline().getCameraTrack().orElse(null);
        if (cameraTrack == null) return;

        List<CameraClip> clips = cameraTrack.getCameraClips();
        if (clips.isEmpty()) return;

        // 找到当前时间所在的 clip
        CameraClip activeClip = findActiveClip(clips, globalTime);
        if (activeClip == null) return;

        float clipLocalTime = globalTime - activeClip.getStartTime();

        // 处理循环
        if (activeClip.isLoop() && activeClip.getDuration() > 0) {
            float animDuration = activeClip.getDuration();
            if (activeClip.getLoopCount() > 0) {
                float maxTime = animDuration * activeClip.getLoopCount();
                if (clipLocalTime >= maxTime) {
                    clipLocalTime = animDuration - 0.0001f; // 停在末帧
                } else {
                    clipLocalTime = clipLocalTime % animDuration;
                }
            } else {
                // 无限循环
                clipLocalTime = clipLocalTime % animDuration;
            }
        }

        // 计算插值（三层插值：脚本级 → 片段/关键帧级）
        KeyframeInterpolator.InterpolationResult result =
                KeyframeInterpolator.computeInterpolation(clipLocalTime, activeClip,
                        script.getMeta().getInterpolation());

        if (result == null) return;

        // 插值所有属性
        Vec3 pos = KeyframeInterpolator.interpolatePosition(result.from, result.to, result.adjustedT, activeClip);
        float yaw = KeyframeInterpolator.interpolateYaw(result.from, result.to, result.adjustedT);
        float pitch = KeyframeInterpolator.interpolatePitch(result.from, result.to, result.adjustedT);
        float roll = KeyframeInterpolator.interpolateRoll(result.from, result.to, result.adjustedT);
        float fov = KeyframeInterpolator.interpolateFov(result.from, result.to, result.adjustedT);
        float zoom = KeyframeInterpolator.interpolateZoom(result.from, result.to, result.adjustedT);
        float dof = KeyframeInterpolator.interpolateDof(result.from, result.to, result.adjustedT);

        // 相对模式：加上玩家基准位置
        if (activeClip.isPositionModeRelative()) {
            pos = originPos.add(pos);
        }

        // NaN/Infinity 防护
        pos = MathUtil.sanitizeVec3(pos, Vec3.ZERO);
        yaw = MathUtil.sanitizeFloat(yaw, 0f);
        pitch = MathUtil.sanitizeFloat(pitch, 0f);
        roll = MathUtil.sanitizeFloat(roll, 0f);
        fov = MathUtil.sanitizeFloat(fov, 70f);
        zoom = MathUtil.sanitizeFloat(zoom, 1f);
        dof = MathUtil.sanitizeFloat(dof, 0f);

        // 写入 CameraManager active 缓冲区
        CameraManager mgr = CameraManager.INSTANCE;
        mgr.getPath().setPositionDirect(pos);
        mgr.getProperties().setYawDirect(yaw);
        mgr.getProperties().setPitchDirect(pitch);
        mgr.getProperties().setRollDirect(roll);
        mgr.getProperties().setFovDirect(fov);
        mgr.getProperties().setZoomDirect(zoom);
        mgr.getProperties().setDofDirect(dof);
    }

    /**
     * 找到当前全局时间所在的 camera clip
     * <p>
     * 如果时间在所有 clip 之前，返回第一个 clip；
     * 如果时间在所有 clip 之后，返回最后一个 clip；
     * 如果在两个 clip 之间的间隙，返回前一个 clip（保持最后一帧）。
     */
    private CameraClip findActiveClip(List<CameraClip> clips, float globalTime) {
        CameraClip lastClip = clips.get(clips.size() - 1);

        // 在最后一个 clip 之后
        if (globalTime >= lastClip.getStartTime() + lastClip.getDuration() && lastClip.getDuration() > 0) {
            return lastClip;
        }

        // 在第一个 clip 之前
        if (globalTime < clips.get(0).getStartTime()) {
            return clips.get(0);
        }

        // 找到包含当前时间的 clip
        for (int i = 0; i < clips.size(); i++) {
            CameraClip clip = clips.get(i);
            float clipEnd = clip.getStartTime() + clip.getDuration();
            if (globalTime >= clip.getStartTime() && globalTime < clipEnd) {
                return clip;
            }
        }

        // 在 clip 间隙中：返回时间上最近的前一个 clip
        for (int i = clips.size() - 1; i >= 0; i--) {
            if (globalTime >= clips.get(i).getStartTime()) {
                return clips.get(i);
            }
        }

        return clips.get(0);
    }

    // ========== Letterbox 轨道处理 ==========

    private void processLetterboxTrack(float globalTime) {
        TimelineTrack letterboxTrack = script.getTimeline().getLetterboxTrack().orElse(null);
        if (letterboxTrack == null) return;

        List<LetterboxClip> clips = letterboxTrack.getLetterboxClips();
        LetterboxLayer letterbox = OverlayManager.INSTANCE.getLetterboxLayer();

        // 检查当前是否在任何 letterbox clip 的活跃期间
        LetterboxClip activeClip = null;
        for (LetterboxClip clip : clips) {
            float clipEnd = clip.getStartTime() + clip.getDuration();
            if (globalTime >= clip.getStartTime() && globalTime < clipEnd) {
                activeClip = clip;
                break;
            }
        }

        if (activeClip != null && activeClip.isEnabled()) {
            // letterbox clip 活跃：确保 letterbox 层可见
            // LetterboxLayer 的 fade-in/fade-out 由自身的 tick() 动画驱动
            // 只需设置 aspectRatio（如果当前 HIDDEN 会自动触发 FADE_IN）
            if (letterbox.getAspectRatio() != activeClip.getAspectRatio()) {
                letterbox.setFadeIn(activeClip.getFadeIn());
                letterbox.setFadeOut(activeClip.getFadeOut());
                letterbox.setAspectRatio(activeClip.getAspectRatio());
            }
        } else if (activeClip == null) {
            // 不在任何 letterbox clip 中：触发 fade-out
            letterbox.startFadeOut();
        }
    }

    // ========== 内部方法 ==========

    private void setupLetterboxFromScript() {
        TimelineTrack letterboxTrack = script.getTimeline().getLetterboxTrack().orElse(null);
        LetterboxLayer letterbox = OverlayManager.INSTANCE.getLetterboxLayer();

        if (letterboxTrack != null && !letterboxTrack.getLetterboxClips().isEmpty()) {
            LetterboxClip firstClip = letterboxTrack.getLetterboxClips().get(0);
            letterbox.setFadeIn(firstClip.getFadeIn());
            letterbox.setFadeOut(firstClip.getFadeOut());
            letterbox.setAspectRatio(firstClip.getAspectRatio());
        } else {
            // 默认 letterbox 设置
            letterbox.setFadeIn(0.5f);
            letterbox.setFadeOut(0.5f);
            letterbox.setAspectRatio(2.35f);
        }
    }

    private float getElapsedSeconds() {
        return (CameraManager.INSTANCE.getGameTimeNanos() - startGameTimeNanos) / 1_000_000_000f;
    }
}
