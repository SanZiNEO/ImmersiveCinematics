package com.immersivecinematics.immersive_cinematics.camera;

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
 * 3. active 的 previous 与 current 一致，消除 partialTick 插值导致的"飞过去"过渡
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

        // 瞬时设置到 active（duration=0），不插值
        activePath.setTargetPosition(playerPos, 0f);
        activeProperties.setTargetYaw(playerYaw, 0f);
        activeProperties.setTargetPitch(playerPitch, 0f);

        // 同步 previous = current，消除首个渲染帧的"飞过去"
        activePath.savePreviousTick();
        activeProperties.savePreviousTick();

        // 清空 staged
        stagedReady = false;

        active = true;

        // 阶段1：激活后启动测试播放器
        testPlayer.start();
    }

    /**
     * 停用相机，恢复玩家视角
     */
    public void deactivate() {
        active = false;
        testPlayer.stop();
        reset();
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
     * - active.current = staged.current
     * - active.previous = staged.current（关键！消除 partialTick 插值）
     * - active.target = staged.current
     * <p>
     * 调用后 stagedReady 重置为 false
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

    // ========== 每tick驱动 ==========

    /**
     * 由 ClientTickEvent 调用
     * 驱动 Properties 和 Path 的 tick 插值
     */
    public void tick() {
        if (!active) return;
        float deltaTime = 1f / 20f; // 每tick 0.05秒

        // 每tick开头保存当前值作为"上一帧基准"，供渲染帧 partialTick 插值使用。
        // 必须在所有 setTargetXxx() 调用之前执行，确保 previous 值不被覆盖。
        activePath.savePreviousTick();
        activeProperties.savePreviousTick();

        // 阶段1：驱动测试播放器（在 active tick 之前）
        testPlayer.tick(deltaTime);

        activeProperties.tick(deltaTime);
        activePath.tick(deltaTime);

        // 播放结束后自动停用
        if (testPlayer.isFinished()) {
            deactivate();
        }
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
