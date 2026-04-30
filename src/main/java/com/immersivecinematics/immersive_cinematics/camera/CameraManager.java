package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.overlay.LetterboxLayer;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 相机统一调度器 — 单例模式
 * <p>
 * 双缓冲架构：
 * - active（活跃状态）：当前正在渲染的相机状态，由 Mixin 层读取
 * - staged（预置状态）：预选放置好的下一段镜头状态，用于硬切换
 * <p>
 * 硬切换流程：
 * 1. Player 在当前段快结束时，通过 stageXxx() 写入下一段第一帧到 staged
 * 2. 切换时刻调用 commitStagedState()，原子替换 active ← staged
 * 3. active 的状态直接被新值覆盖，无需 partialTick 插值
 * <p>
 * 🎬 帧回调驱动模式（ReplayMod 式）：
 * - onRenderFrame() 由 CameraMixin.onSetup() 每渲染帧调用
 * - 用 System.nanoTime() 实时时间驱动，精确计算当前帧的相机状态
 * - 不再依赖 tick() 驱动位置/角度更新
 * - tick() 保留但只用于非位置逻辑（如未来可能需要的 staged 过渡驱动）
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

    public static final CameraManager INSTANCE = new CameraManager();

    // ========== 活跃状态（active）：Mixin 层读取此状态渲染 ==========

    private final CameraProperties activeProperties = new CameraProperties();
    private final CameraPath activePath = new CameraPath();

    // ========== 预置状态（staged）：预选放置好的下一段镜头状态 ==========

    private final CameraProperties stagedProperties = new CameraProperties();
    private final CameraPath stagedPath = new CameraPath();
    private boolean stagedReady = false;  // staged 是否已准备就绪

    // ========== 其他组件 ==========

    private final CameraTestPlayer testPlayer = new CameraTestPlayer();
    private boolean active = false;
    private boolean stopping = false;  // 正在执行退出动画（fade-out）

    // 🎬 暂停行为控制：当游戏暂停时，脚本是否冻结
    // true（默认）= 暂停菜单时脚本冻结；false = 暂停菜单时脚本继续播放
    // 由 ScriptPlayer.start() 根据脚本属性 pause_when_game_paused 设置
    // 当前 CameraTestPlayer 阶段默认 true，保持原有行为
    private boolean pauseWhenGamePaused = true;

    // 🎬 虚拟时钟：只在非暂停时前进，暂停时自动冻结
    // 所有消费者（CameraTestPlayer/OverlayManager）使用虚拟时间，无需各自处理暂停补偿
    private long gameTimeNanos = 0;    // 虚拟游戏时间（纳秒），暂停时不增长
    private long lastRealNanos = 0;    // 上一帧的 System.nanoTime()，用于计算增量

    // ========== 生命周期 ==========

    /**
     * 从玩家当前位置/朝向激活相机
     */
    public void activate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 playerPos = mc.player.position();
        float playerYaw = mc.player.getYRot();
        float playerPitch = mc.player.getXRot();

        // 🎬 用 Direct 方法瞬时设置到 active，不插值
        activePath.setPositionDirect(playerPos);
        activeProperties.setYawDirect(playerYaw);
        activeProperties.setPitchDirect(playerPitch);

        // 清空 staged
        stagedReady = false;

        active = true;
        stopping = false;

        // 启动测试播放器
        testPlayer.start();

        // 🎬 设置覆盖层：配置 fade 时长 + 触发 fade-in 入场动画
        // 后续由脚本 properties 控制，当前测试默认值
        LetterboxLayer letterbox = OverlayManager.INSTANCE.getLetterboxLayer();
        letterbox.setFadeIn(0.5f);
        letterbox.setFadeOut(0.5f);
        letterbox.setAspectRatio(2.35f);  // 触发 FADE_IN
    }

    /**
     * 停用相机，恢复玩家视角
     * <p>
     * 触发黑边退场动画（fade-out），动画结束后由 onRenderFrame() 执行实际清理。
     * 如果正在执行退出动画中，忽略重复调用。
     */
    public void deactivate() {
        if (!active) return;
        if (stopping) return;  // 已在退出中，不重复触发
        // 触发退场动画
        OverlayManager.INSTANCE.startFadeOut();
        stopping = true;
    }

    /**
     * 切换相机激活/停用
     */
    public void toggle() {
        if (active) {
            deactivate();
        } else {
            activate();
        }
    }

    // ========== 预置状态写入（staged）==========

    /**
     * 向 staged 缓冲区写入目标位置（带过渡时长）
     *
     * @param pos 目标位置
     * @param duration 过渡时长（秒），0 = 瞬时跳转
     */
    public void stageTargetPosition(Vec3 pos, float duration) {
        stagedPath.setTargetPosition(pos, duration);
        stagedReady = true;
    }

    /**
     * 向 staged 缓冲区写入目标 yaw
     */
    public void stageTargetYaw(float yaw, float duration) {
        stagedProperties.setTargetYaw(yaw, duration);
        stagedReady = true;
    }

    /**
     * 向 staged 缓冲区写入目标 pitch
     */
    public void stageTargetPitch(float pitch, float duration) {
        stagedProperties.setTargetPitch(pitch, duration);
        stagedReady = true;
    }

    /**
     * 向 staged 缓冲区写入目标 roll
     */
    public void stageTargetRoll(float roll, float duration) {
        stagedProperties.setTargetRoll(roll, duration);
        stagedReady = true;
    }

    /**
     * 向 staged 缓冲区写入目标 fov
     */
    public void stageTargetFov(float fov, float duration) {
        stagedProperties.setTargetFov(fov, duration);
        stagedReady = true;
    }

    /**
     * 向 staged 缓冲区写入目标 zoom
     */
    public void stageTargetZoom(float zoom, float duration) {
        stagedProperties.setTargetZoom(zoom, duration);
        stagedReady = true;
    }

    // ========== 硬切换：commitStagedState ==========

    /**
     * 提交预置状态到活跃状态，实现硬切换
     * <p>
     * 原子操作：active ← staged
     * 调用后 stagedReady 重置为 false
     * <p>
     * 注意：覆盖层属性（aspectRatio等）不受硬切换影响
     */
    public void commitStagedState() {
        if (!stagedReady) return;

        activePath.overrideFrom(stagedPath);
        activeProperties.overrideFrom(stagedProperties);

        stagedReady = false;
    }

    /**
     * staged 缓冲区是否已准备就绪
     */
    public boolean isStagedReady() {
        return stagedReady;
    }

    // ========== 🎬 帧回调驱动（核心改动） ==========

    /**
     * 🎬 每渲染帧驱动：由 CameraMixin.onSetup() 调用
     * <p>
     * 虚拟时钟架构：
     * - gameTimeNanos 只在非暂停时前进，暂停时自动冻结
     * - 所有消费者（CameraTestPlayer/OverlayManager）使用虚拟时间，无需各自处理暂停补偿
     * - 暂停时 return early，gameTimeNanos 不增长 → 相机冻结在当前帧
     * - 恢复后 lastRealNanos=0 → 首帧 deltaTime=0 → 无跳帧
     * <p>
     * 退场时序：
     * - 当剩余时间 ≤ fadeOut 时长时，自动触发黑边退场动画
     * - 退场动画期间相机继续播放
     * - 退场动画结束后执行实际停用（deactivateNow）
     * <p>
     * ⚠️ update(deltaTime) 必须在 isAnimating() 检查之前执行，
     * 否则同一帧内 letterbox 在渲染阶段才消失，而相机在设置阶段还认为动画在进行，
     * 导致闪帧（1-2帧无黑边的电影画面）。
     */
    public void onRenderFrame() {
        if (!active) return;

        // 🎬 暂停时冻结虚拟时钟：gameTimeNanos 不增长，相机停在当前帧
        // 恢复后 lastRealNanos=0 → 首帧 deltaTime=0 → 无跳帧
        // ⚠️ 修正：查询 pauseWhenGamePaused 标志，支持 pause_when_game_paused=false 的脚本
        // 当 pauseWhenGamePaused=false 时，游戏暂停但脚本继续播放（虚拟时钟继续前进）
        if (Minecraft.getInstance().isPaused() && pauseWhenGamePaused) {
            lastRealNanos = 0;
            return;
        }

        // 🎬 推进虚拟时钟（只在非暂停时执行）
        long now = System.nanoTime();
        long prevGameTimeNanos = gameTimeNanos;  // 记录推进前的虚拟时间
        if (lastRealNanos != 0) {
            gameTimeNanos += now - lastRealNanos;
        }
        lastRealNanos = now;

        // 🎬 驱动覆盖层动画（必须在 isAnimating() 检查之前！）
        // deltaTime = 本帧虚拟时钟增量，暂停后首帧为0（因为 lastRealNanos 刚被重置）
        float deltaTime = (gameTimeNanos - prevGameTimeNanos) / 1_000_000_000f;
        OverlayManager.INSTANCE.update(deltaTime);

        // 🎬 自然结束前触发退场动画
        if (!stopping) {
            float remaining = testPlayer.getRemainingTime();
            float fadeOut = OverlayManager.INSTANCE.getLetterboxLayer().getFadeOut();
            if (fadeOut > 0f && remaining > 0f && remaining <= fadeOut) {
                OverlayManager.INSTANCE.startFadeOut();
                stopping = true;
            }
        }

        // 驱动测试播放器（即使 stopping 也继续播放，保持最后一帧）
        if (!testPlayer.isFinished()) {
            testPlayer.onRenderFrame(gameTimeNanos);
        }

        // 🎬 退场动画结束 → 实际停用
        if (stopping && !OverlayManager.INSTANCE.isAnimating()) {
            deactivateNow();
            return;
        }

        // 无 fade-out 的自然结束（fadeOut=0 时的回退路径）
        if (!stopping && testPlayer.isFinished()) {
            deactivateNow();
        }
    }

    /**
     * 获取当前虚拟游戏时间（纳秒）
     * <p>
     * 只在非暂停时前进，暂停时冻结。
     * 供消费者（如 CameraTestPlayer）计算经过时间。
     */
    public long getGameTimeNanos() {
        return gameTimeNanos;
    }

    /**
     * 立即停用相机，不做退出动画
     * <p>
     * 仅在退出动画结束后由 onRenderFrame() 调用，
     * 或用于强制清理（如 fadeOut=0 时）。
     */
    private void deactivateNow() {
        active = false;
        stopping = false;
        pauseWhenGamePaused = true;  // 重置为默认值
        gameTimeNanos = 0;
        lastRealNanos = 0;
        testPlayer.stop();
        reset();
        OverlayManager.INSTANCE.reset();
    }

    // ========== tick 驱动（保留但简化） ==========

    /**
     * 由 ClientTickEvent 调用
     * <p>
     * 🎬 在帧回调驱动模式下，tick() 不再驱动位置/角度更新。
     * 保留此方法供未来可能需要的非位置逻辑使用。
     */
    public void tick() {
        if (!active) return;
        // 帧回调驱动模式下，位置/角度更新由 onRenderFrame() 负责
        // tick() 不再驱动 testPlayer、activeProperties、activePath
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

    /**
     * 设置暂停行为：游戏暂停时脚本是否冻结
     * <p>
     * 由 ScriptPlayer.start() 根据脚本属性 pause_when_game_paused 调用。
     * 默认 true（暂停时冻结），设为 false 时游戏暂停但脚本继续播放。
     */
    public void setPauseWhenGamePaused(boolean pauseWhenGamePaused) {
        this.pauseWhenGamePaused = pauseWhenGamePaused;
    }

    /**
     * 获取当前暂停行为设置
     */
    public boolean isPauseWhenGamePaused() {
        return pauseWhenGamePaused;
    }

    // ========== 便捷方法 ==========

    /**
     * 重置两个子系统（active + staged）
     */
    public void reset() {
        activeProperties.reset();
        activePath.reset();
        stagedProperties.reset();
        stagedPath.reset();
        stagedReady = false;
    }
}
