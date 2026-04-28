package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 阶段1测试用：硬编码关键帧序列，P键激活后自动播放
 * 验证完整数据通路：Position/Yaw/Pitch/FOV → CameraManager → Mixin → 渲染
 * <p>
 * 每次 P 键激活从玩家当前位置/朝向开始，不存储上次相机位置。
 * 播放结束后自动停用，恢复玩家视角。
 * 测试完成后直接删除此文件，零残留。
 */
public class CameraTestPlayer {

    // ========== 关键帧定义 ==========

    private record TestKeyframe(
            float time,   // 时间点（秒）
            float x,      // 绝对世界坐标 X
            float y,      // 绝对世界坐标 Y
            float z,      // 绝对世界坐标 Z
            float yaw,    // 绝对偏航角（度）
            float pitch,  // 绝对俯仰角（度）
            float fov     // 视场角（度）
    ) {}

    /**
     * 硬编码关键帧序列（绝对世界坐标）
     * <p>
     * 轨迹设计（超平坦世界，地面 Y≈0）：
     * 0~3s:  (0,-50,0) → (0,5,0)  从地下50格上升到地面之上，俯视下方
     * 3~6s:  (0,5,0)   → (50,5,0)  水平右移50格 + 右转90°
     * 6~10s: (50,5,0)  → (50,5,50) 前移50格 + 掉头180° + 仰视15° + 窄角40°
     * 10~13s:(50,5,50) → (0,-50,0) 斜线回起始点 + 恢复朝向/FOV
     */
    private static final TestKeyframe[] KEYFRAMES = {
            new TestKeyframe( 0.0f,   0, -50,   0,    0,   0,  70),   // 起点地下
            new TestKeyframe( 3.0f,   0,   5,   0,    0, -30,  50),   // 升到地面 + 俯视30°
            new TestKeyframe( 6.0f,  50,   5,   0,   90,   0, 100),   // 右移50格 + 右转90° + 广角
            new TestKeyframe(10.0f,  50,   5,  50,  180,  15,  40),   // 前移50格 + 掉头 + 仰视 + 窄角
            new TestKeyframe(13.0f,   0, -50,   0,    0,   0,  70),   // 回到起点地下
    };

    // ========== 状态 ==========

    private float currentTime = 0f;
    private boolean playing = false;

    // ========== 生命周期 ==========

    /**
     * 启动测试播放器
     * 由 CameraManager.activate() 调用
     */
    public void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        currentTime = 0f;
        playing = true;
    }

    /**
     * 停止测试播放器
     * 由 CameraManager.deactivate() 调用
     */
    public void stop() {
        playing = false;
    }

    /**
     * 检查播放是否已结束（供 CameraManager 判断是否需要停用）
     */
    public boolean isFinished() {
        return playing && currentTime >= KEYFRAMES[KEYFRAMES.length - 1].time;
    }

    // ========== 核心逻辑 ==========

    /**
     * 每tick驱动关键帧插值，并将结果写入 CameraManager
     *
     * @param deltaTime 距离上一tick的时间（秒），固定 1/20
     */
    public void tick(float deltaTime) {
        if (!playing) return;

        currentTime += deltaTime;

        // 找到当前时间所在的关键帧区间
        TestKeyframe from = KEYFRAMES[0];
        TestKeyframe to = KEYFRAMES[KEYFRAMES.length - 1];

        for (int i = 0; i < KEYFRAMES.length - 1; i++) {
            if (currentTime >= KEYFRAMES[i].time && currentTime < KEYFRAMES[i + 1].time) {
                from = KEYFRAMES[i];
                to = KEYFRAMES[i + 1];
                break;
            }
        }

        // 计算区间内插值进度 t ∈ [0, 1]
        float segmentDuration = to.time() - from.time();
        float t = (segmentDuration > 0f) ? (currentTime - from.time()) / segmentDuration : 1f;
        t = Math.min(1f, t);

        // 插值计算当前状态（绝对世界坐标）
        Vec3 pos = new Vec3(
                lerp(from.x(), to.x(), t),
                lerp(from.y(), to.y(), t),
                lerp(from.z(), to.z(), t)
        );
        float yaw = lerpAngle(from.yaw(), to.yaw(), t);
        float pitch = lerp(from.pitch(), to.pitch(), t);
        float fov = lerp(from.fov(), to.fov(), t);

        // 写入 CameraManager（使用 duration=0 直接设置，绕过共享插值状态）
        CameraManager mgr = CameraManager.INSTANCE;
        mgr.getPath().setTargetPosition(pos, 0f);
        mgr.getProperties().setTargetYaw(yaw, 0f);
        mgr.getProperties().setTargetPitch(pitch, 0f);
        mgr.getProperties().setTargetFov(fov, 0f);
    }

    // ========== 工具方法 ==========

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * 角度环绕插值（处理 -180° ~ 180° 的环绕）
     */
    private static float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return from + diff * t;
    }
}
