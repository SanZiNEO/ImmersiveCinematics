package com.immersivecinematics.immersive_cinematics.camera;

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

        // 启动测试播放器
        testPlayer.start();

        // 🎬 设置覆盖层：黑边全程保持，独立于镜头跳转
        // 后续由脚本/UI 控制画幅比，当前测试默认 2.35:1
        OverlayManager.INSTANCE.getLetterboxLayer().setAspectRatio(2.35f);
    }

    /**
     * 停用相机，恢复玩家视角
     */
    public void deactivate() {
        active = false;
        testPlayer.stop();
        reset();
        // 重置覆盖层（黑边消失等）
        OverlayManager.INSTANCE.reset();
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
     * ReplayMod 式帧回调驱动：
     * - 用 System.nanoTime() 实时时间驱动
     * - CameraTestPlayer 用实时时间精确计算当前帧的相机位置/角度
     * - 直接设置到 active 缓冲区，不需要 partialTick 插值
     * - 比tick驱动更丝滑，不受tick抖动影响
     */
    public void onRenderFrame() {
        if (!active) return;

        // 驱动测试播放器：用实时时间计算精确位置，直接写入 active
        testPlayer.onRenderFrame();

        // 播放结束后自动停用
        if (testPlayer.isFinished()) {
            deactivate();
        }
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
