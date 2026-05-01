package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.ScriptProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 相机统一调度器 — 单例模式
 * <p>
 * 重构后（C2）：
 * <ul>
 *   <li>移除 CameraTestPlayer，统一由 ScriptPlayer 驱动</li>
 *   <li>onRenderFrame() 只驱动 scriptPlayer</li>
 *   <li>staged/commit 双缓冲保留仅供编辑器预览 scrub 使用</li>
 *   <li>activate() 使用默认测试脚本（从 CameraTestPlayer 转换）</li>
 * </ul>
 * <p>
 * 🎬 帧回调驱动模式（ReplayMod 式）：
 * - onRenderFrame() 由 CameraMixin.onSetup() 每渲染帧调用
 * - 用 System.nanoTime() 实时时间驱动，精确计算当前帧的相机状态
 * <p>
 * 🎬 多时间轴架构：
 * - 相机属性（position/yaw/pitch/roll/fov/zoom）跟随镜头跳转逻辑
 * - 覆盖层属性（aspectRatio/文字/视频）独立于镜头跳转，全程保持
 * - 覆盖层由 OverlayManager 独立管理，CameraManager 只在激活/停用时控制生命周期
 * <p>
 * CameraProperties 和 CameraPath 互不知晓，CameraManager 是唯一桥梁。
 * Mixin 层不直接依赖 CameraProperties / CameraPath，统一从 CameraManager 读取。
 */
public class CameraManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    public static final CameraManager INSTANCE = new CameraManager();

    // ========== 活跃状态（active）：Mixin 层读取此状态渲染 ==========

    private final CameraProperties activeProperties = new CameraProperties();
    private final CameraPath activePath = new CameraPath();

    // ========== 预置状态（staged）：仅供编辑器预览 scrub 使用 ==========

    private final CameraProperties stagedProperties = new CameraProperties();
    private final CameraPath stagedPath = new CameraPath();
    private boolean stagedReady = false;

    // ========== 其他组件 ==========

    private final ScriptPlayer scriptPlayer = new ScriptPlayer();
    private boolean active = false;
    private boolean stopping = false;  // 正在执行退出动画（fade-out）

    // 🎬 暂停行为控制：当游戏暂停时，脚本是否冻结
    private boolean pauseWhenGamePaused = true;

    // 🎬 虚拟时钟：只在非暂停时前进，暂停时自动冻结
    private long gameTimeNanos = 0;
    private long lastRealNanos = 0;

    // ========== 生命周期 ==========

    /**
     * 从玩家当前位置/朝向激活相机
     * <p>
     * 仅设置相机初始位置/朝向和状态标志，不涉及覆盖层。
     * letterbox 由 {@code playScript()} 的 {@code LetterboxTrackPlayer} 驱动，或由调用者手动设置。
     */
    public void activate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 playerPos = mc.player.position();
        float playerYaw = mc.player.getYRot();
        float playerPitch = mc.player.getXRot();

        // 瞬时设置到 active
        activePath.setPositionDirect(playerPos);
        activeProperties.setYawDirect(playerYaw);
        activeProperties.setPitchDirect(playerPitch);

        stagedReady = false;
        active = true;
        stopping = false;

        // letterbox 由 playScript() 的 LetterboxTrackPlayer 驱动，或由调用者手动设置
    }

    /**
     * 停用相机，恢复玩家视角
     * <p>
     * 触发黑边退场动画（fade-out），动画结束后由 onRenderFrame() 执行实际清理。
     */
    public void deactivate() {
        if (!active) return;
        if (stopping) return;
        OverlayManager.INSTANCE.startFadeOut();
        stopping = true;
    }

    // ========== 脚本播放模式 ==========

    /**
     * 启动脚本播放模式
     * <p>
     * 脚本间抢占控制（interruptible）：
     * <ul>
     *   <li>如果当前有脚本正在播放且 interruptible=false，则拒绝新脚本，返回 false</li>
     *   <li>如果当前有脚本正在播放且 interruptible=true，则打断当前脚本，播放新脚本</li>
     *   <li>如果当前无脚本播放，直接启动新脚本</li>
     * </ul>
     * <p>
     * 典型用法：区域固定视角脚本（生化危机0式）可设 interruptible=false，
     * 防止玩家进入区域时其他脚本抢占固定视角。
     *
     * @param script 已解析的脚本对象
     * @return true=成功启动，false=被当前脚本拒绝（interruptible=false）
     */
    public boolean playScript(CinematicScript script) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;

        // 脚本间抢占控制：检查当前脚本的 interruptible 标志
        if (active && scriptPlayer.isPlaying()) {
            ScriptProperties currentProps = scriptPlayer.getCurrentProperties();
            if (currentProps != null && !currentProps.isInterruptible()) {
                LOGGER.debug("脚本 {} 不可打断(interruptible=false)，拒绝新脚本: {}",
                        scriptPlayer.getScriptId(), script.getId());
                return false;
            }
            // 当前脚本可打断，先停用
            deactivateNow();
        }

        Vec3 playerPos = mc.player.position();
        activePath.setPositionDirect(playerPos);
        activeProperties.setYawDirect(mc.player.getYRot());
        activeProperties.setPitchDirect(mc.player.getXRot());

        stagedReady = false;
        active = true;
        stopping = false;

        // 启动脚本播放器
        scriptPlayer.start(script);
        return true;
    }

    /**
     * 停止脚本播放
     */
    public void stopScript() {
        if (scriptPlayer.isPlaying()) {
            scriptPlayer.stop();
            deactivateNow();
        }
    }

    /**
     * 获取脚本播放器实例
     */
    public ScriptPlayer getScriptPlayer() {
        return scriptPlayer;
    }

    /**
     * 是否处于脚本播放模式
     */
    public boolean isScriptMode() {
        return scriptPlayer.isPlaying();
    }

    // ========== 预置状态写入（staged）— 仅供编辑器预览 ==========

    public void stageTargetPosition(Vec3 pos, float duration) {
        stagedPath.setTargetPosition(pos, duration);
        stagedReady = true;
    }

    public void stageTargetYaw(float yaw, float duration) {
        stagedProperties.setTargetYaw(yaw, duration);
        stagedReady = true;
    }

    public void stageTargetPitch(float pitch, float duration) {
        stagedProperties.setTargetPitch(pitch, duration);
        stagedReady = true;
    }

    public void stageTargetRoll(float roll, float duration) {
        stagedProperties.setTargetRoll(roll, duration);
        stagedReady = true;
    }

    public void stageTargetFov(float fov, float duration) {
        stagedProperties.setTargetFov(fov, duration);
        stagedReady = true;
    }

    public void stageTargetZoom(float zoom, float duration) {
        stagedProperties.setTargetZoom(zoom, duration);
        stagedReady = true;
    }

    /**
     * 提交预置状态到活跃状态（编辑器预览用）
     */
    public void commitStagedState() {
        if (!stagedReady) return;

        activePath.overrideFrom(stagedPath);
        activeProperties.overrideFrom(stagedProperties);

        stagedReady = false;
    }

    public boolean isStagedReady() {
        return stagedReady;
    }

    // ========== 🎬 帧回调驱动 ==========

    /**
     * 🎬 每渲染帧驱动：由 CameraMixin.onSetup() 调用
     * <p>
     * 重构后：只驱动 scriptPlayer，移除 testPlayer 分支。
     */
    public void onRenderFrame() {
        if (!active) return;

        // 暂停时冻结虚拟时钟
        if (Minecraft.getInstance().isPaused() && pauseWhenGamePaused) {
            lastRealNanos = 0;
            return;
        }

        // 推进虚拟时钟
        long now = System.nanoTime();
        long prevGameTimeNanos = gameTimeNanos;
        if (lastRealNanos != 0) {
            gameTimeNanos += now - lastRealNanos;
        }
        lastRealNanos = now;

        // 驱动覆盖层动画
        float deltaTime = (gameTimeNanos - prevGameTimeNanos) / 1_000_000_000f;
        OverlayManager.INSTANCE.update(deltaTime);

        // 自然结束前触发退场动画
        // holdAtEnd=true 时不触发退场动画：相机应保持最后一帧，等待用户退出或新脚本打断
        if (!stopping && scriptPlayer.isPlaying()) {
            ScriptProperties currentProps = scriptPlayer.getCurrentProperties();
            boolean holdAtEnd = currentProps != null && currentProps.isHoldAtEnd();
            if (!holdAtEnd) {
                float remaining = scriptPlayer.getRemainingTime();
                float fadeOut = OverlayManager.INSTANCE.getLetterboxLayer().getFadeOut();
                if (fadeOut > 0f && remaining > 0f && remaining <= fadeOut) {
                    OverlayManager.INSTANCE.startFadeOut();
                    stopping = true;
                }
            }
        }

        // 唯一驱动路径：scriptPlayer
        if (scriptPlayer.isPlaying()) {
            scriptPlayer.onRenderFrame(gameTimeNanos);
        }

        // 退场动画结束 → 实际停用
        if (stopping && !OverlayManager.INSTANCE.isAnimating()) {
            deactivateNow();
            return;
        }

        // 无 fade-out 的自然结束
        // holdAtEnd 控制：播完后保持最后一帧，不自动退出
        // 适用于固定视角区域，玩家持续处于脚本相机控制下
        if (!stopping && scriptPlayer.isPlaying() && scriptPlayer.isFinished()) {
            ScriptProperties currentProps = scriptPlayer.getCurrentProperties();
            boolean holdAtEnd = currentProps != null && currentProps.isHoldAtEnd();
            if (!holdAtEnd) {
                deactivateNow();
            }
            // holdAtEnd=true: 保持当前相机状态，等待用户退出或新脚本打断
        }
    }

    /**
     * 获取当前虚拟游戏时间（纳秒）
     */
    public long getGameTimeNanos() {
        return gameTimeNanos;
    }

    /**
     * 立即停用相机，不做退出动画
     */
    private void deactivateNow() {
        active = false;
        stopping = false;
        pauseWhenGamePaused = true;
        gameTimeNanos = 0;
        lastRealNanos = 0;
        scriptPlayer.stop();
        reset();
        OverlayManager.INSTANCE.reset();
    }

    // ========== tick 驱动（保留但简化） ==========

    public void tick() {
        if (!active) return;
        // 帧回调驱动模式下，位置/角度更新由 onRenderFrame() 负责
    }

    // ========== Mixin 读取接口（只读 active 状态）==========

    public CameraProperties getProperties() {
        return activeProperties;
    }

    public CameraPath getPath() {
        return activePath;
    }

    public boolean isActive() {
        return active;
    }

    public void setPauseWhenGamePaused(boolean pauseWhenGamePaused) {
        this.pauseWhenGamePaused = pauseWhenGamePaused;
    }

    public boolean isPauseWhenGamePaused() {
        return pauseWhenGamePaused;
    }

    // ========== 便捷方法 ==========

    /**
     * 获取当前活跃的脚本属性
     * <p>
     * 委托到 scriptPlayer.getCurrentProperties()，供 Mixin/Handler 便捷访问。
     *
     * @return 当前脚本属性，无脚本播放时返回 null
     */
    public ScriptProperties getCurrentProperties() {
        return scriptPlayer.getCurrentProperties();
    }

    public void reset() {
        activeProperties.reset();
        activePath.reset();
        stagedProperties.reset();
        stagedPath.reset();
        stagedReady = false;
    }
}
