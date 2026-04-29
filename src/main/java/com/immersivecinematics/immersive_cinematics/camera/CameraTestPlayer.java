package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 阶段2测试用：多段镜头硬编码测试脚本
 * <p>
 * 验证完整数据通路 + 多段镜头间的硬切换逻辑（双缓冲 staged/commit 架构）：
 * Position/Yaw/Pitch/Roll/FOV/Zoom → CameraManager → Mixin → 渲染
 * <p>
 * 核心设计：
 * - 一个脚本 = 多段镜头（TestSegment[]）
 * - 段内：关键帧间平滑插值 → 写入 active 状态
 * - 段间：预置下一段第一帧到 staged → commitStagedState() 原子替换 → 真正硬切换
 * - 总时长由各段时长累加得出，不硬编码
 * <p>
 * 每次 P 键激活从第一段开始播放，播放结束后自动停用，恢复玩家视角。
 * 测试完成后直接删除此文件，零残留。
 */
public class CameraTestPlayer {

    // ========== 关键帧 & 镜头段定义 ==========

    /**
     * 关键帧记录（绝对世界坐标）
     * 段内 time 从 0 开始，相对于该段起点
     */
    private record TestKeyframe(
            float time,   // 段内时间点（秒）
            float x,      // 绝对世界坐标 X
            float y,      // 绝对世界坐标 Y
            float z,      // 绝对世界坐标 Z
            float yaw,    // 绝对偏航角（度）
            float pitch,  // 绝对俯仰角（度）
            float roll,   // 滚转角（度），0=正放
            float fov,    // 视场角（度）
            float zoom    // 缩放倍率，1.0=正常，>1=放大
    ) {}

    /**
     * 一段镜头：包含段标识和关键帧序列
     * 段内关键帧平滑插值，段间硬切换
     */
    private record TestSegment(
            String id,                  // 段标识（方便日志调试）
            TestKeyframe[] keyframes    // 该段的关键帧序列（段内时间从0开始）
    ) {
        /** 该段的时长 = 最后一个关键帧的 time */
        float getDuration() {
            return keyframes[keyframes.length - 1].time();
        }
    }

    /**
     * 六段自由运镜测试脚本
     * <p>
     * 超平坦世界，地面 Y≈0，相机起点 (0, -50, 0)，yaw=0（朝南）
     * 起点朝向与移动方向呈90°右偏角——经典横移运镜
     * <p>
     * 镜头1: 横移推镜 — 朝南看，沿+X横移，同时缓慢偏转+推近
     * 镜头2: 环绕俯拍 — 绕焦点做半圆弧运动，同时升高+俯视
     * 镜头3: 低角度跟推 — 低位前进，缓慢抬高视角
     * 镜头4: 荷兰角特写 — roll渐变制造不安感，zoom放大模拟特写
     * 镜头5: 上升鸟瞰 — 快速拉高，俯角增大到接近垂直
     * 镜头6: 下降收束 — 缓慢回落，FOV收窄制造隧道视觉
     */
    private static final TestSegment[] SEGMENTS = {

            // ---- 镜头1: 横移推镜 (8秒) ----
            new TestSegment("lateral_track", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, -50, 0, 0, 0, 0, 70, 1.0f),
                    new TestKeyframe(8.0f, 30, -50, 0, -15, -5, 0, 70, 1.5f),
            }),

            // ---- 镜头2: 环绕俯拍 (10秒) ----
            new TestSegment("orbit_overhead", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 15, -50, 15, 45, -15, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 0, -45, 30, 135, -25, 0, 75, 1.2f),
                    new TestKeyframe(10.0f, -15, -40, 15, 225, -35, 0, 80, 1.0f),
            }),

            // ---- 镜头3: 低角度跟推 (7秒) ----
            new TestSegment("low_angle_push", new TestKeyframe[]{
                    new TestKeyframe(0.0f, -15, -52, 10, 200, -5, 0, 70, 1.0f),
                    new TestKeyframe(7.0f, -15, -48, 30, 180, -20, 8, 65, 1.3f),
            }),

            // ---- 镜头4: 荷兰角特写 (6秒) ----
            new TestSegment("dutch_angle", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, -48, 20, 160, -10, 0, 70, 1.0f),
                    new TestKeyframe(3.0f, 5, -48, 25, 170, -15, 15, 70, 2.0f),
                    new TestKeyframe(6.0f, 0, -48, 20, 180, -10, 30, 70, 3.0f),
            }),

            // ---- 镜头5: 上升鸟瞰 (8秒) ----
            new TestSegment("ascend_birds_eye", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, -48, 0, 0, -10, 0, 70, 1.0f),
                    new TestKeyframe(8.0f, 0, -20, 0, 0, -80, 0, 90, 1.0f),
            }),

            // ---- 镜头6: 下降收束 (8秒) ----
            new TestSegment("descend_converge", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, -25, 0, 0, -60, 0, 70, 1.0f),
                    new TestKeyframe(4.0f, 0, -35, 5, -10, -40, -5, 60, 1.2f),
                    new TestKeyframe(8.0f, 0, -48, 10, 0, -20, 0, 50, 1.5f),
            }),
    };

    /**
     * 总脚本时长 = 各段时长累加，不硬编码
     */
    private static final float TOTAL_DURATION = computeTotalDuration();

    private static float computeTotalDuration() {
        float total = 0f;
        for (TestSegment seg : SEGMENTS) {
            total += seg.getDuration();
        }
        return total;
    }

    // ========== 状态 ==========

    private int currentSegmentIndex = 0;
    private float segmentTime = 0f;       // 当前段内已播放时间
    private boolean playing = false;

    // ========== 生命周期 ==========

    /**
     * 启动测试播放器
     * 由 CameraManager.activate() 调用
     */
    public void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        currentSegmentIndex = 0;
        segmentTime = 0f;
        playing = true;

        // 初始化：通过 staged + commit 将第一段第一帧写入 active
        // 这保证了 active.previous = active.current，消除首个渲染帧的 partialTick 插值
        prepareStagedKeyframe(SEGMENTS[0].keyframes()[0]);
        CameraManager.INSTANCE.commitStagedState();
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
        return playing && currentSegmentIndex >= SEGMENTS.length;
    }

    // ========== 核心逻辑 ==========

    /**
     * 每tick驱动关键帧插值，并将结果写入 CameraManager
     * <p>
     * 段内：关键帧间平滑插值 → 写入 active 状态
     * 段间：预置下一段第一帧到 staged → commitStagedState() 原子替换
     *
     * @param deltaTime 距离上一tick的时间（秒），固定 1/20
     */
    public void tick(float deltaTime) {
        if (!playing) return;

        segmentTime += deltaTime;

        TestSegment currentSeg = SEGMENTS[currentSegmentIndex];

        // 检查当前段是否播放完毕
        if (segmentTime >= currentSeg.getDuration()) {
            float overflow = segmentTime - currentSeg.getDuration();
            currentSegmentIndex++;

            // 所有段播放完毕
            if (currentSegmentIndex >= SEGMENTS.length) {
                return; // isFinished() 将返回 true
            }

            // 硬切换到下一段：
            // 1. 预置新段第一帧到 staged 缓冲区
            // 2. commitStagedState() 原子替换 active ← staged
            //    active.previous = active.current = 新段第一帧
            //    → partialTick 插值恒等于新段第一帧，无"飞过去"过渡
            segmentTime = overflow;
            prepareStagedKeyframe(SEGMENTS[currentSegmentIndex].keyframes()[0]);
            CameraManager.INSTANCE.commitStagedState();
            return;
        }

        // ---- 段内插值 → 写入 active ----

        TestKeyframe[] kfs = currentSeg.keyframes();

        // 找到当前时间所在的关键帧区间
        TestKeyframe from = kfs[0];
        TestKeyframe to = kfs[kfs.length - 1];

        for (int i = 0; i < kfs.length - 1; i++) {
            if (segmentTime >= kfs[i].time() && segmentTime < kfs[i + 1].time()) {
                from = kfs[i];
                to = kfs[i + 1];
                break;
            }
        }

        // 计算区间内插值进度 t ∈ [0, 1]
        float segDuration = to.time() - from.time();
        float t = (segDuration > 0f) ? (segmentTime - from.time()) / segDuration : 1f;
        t = Math.min(1f, t);

        // 插值计算当前状态（绝对世界坐标）
        Vec3 pos = new Vec3(
                lerp(from.x(), to.x(), t),
                lerp(from.y(), to.y(), t),
                lerp(from.z(), to.z(), t)
        );
        float yaw = lerpAngle(from.yaw(), to.yaw(), t);
        float pitch = lerp(from.pitch(), to.pitch(), t);
        float roll = lerpAngle(from.roll(), to.roll(), t);
        float fov = lerp(from.fov(), to.fov(), t);
        float zoom = lerp(from.zoom(), to.zoom(), t);

        // 写入 CameraManager active 状态（duration=0 直接设置，绕过共享插值状态）
        CameraManager mgr = CameraManager.INSTANCE;
        mgr.getPath().setTargetPosition(pos, 0f);
        mgr.getProperties().setTargetYaw(yaw, 0f);
        mgr.getProperties().setTargetPitch(pitch, 0f);
        mgr.getProperties().setTargetRoll(roll, 0f);
        mgr.getProperties().setTargetFov(fov, 0f);
        mgr.getProperties().setTargetZoom(zoom, 0f);
    }

    // ========== 内部方法 ==========

    /**
     * 将关键帧状态写入 CameraManager 的 staged 缓冲区（预置下一段镜头位置）
     * <p>
     * 调用后需调用 CameraManager.commitStagedState() 将 staged 原子替换到 active
     *
     * @param kf 要预置的关键帧
     */
    private void prepareStagedKeyframe(TestKeyframe kf) {
        CameraManager mgr = CameraManager.INSTANCE;
        mgr.stageTargetPosition(new Vec3(kf.x(), kf.y(), kf.z()), 0f);
        mgr.stageTargetYaw(kf.yaw(), 0f);
        mgr.stageTargetPitch(kf.pitch(), 0f);
        mgr.stageTargetRoll(kf.roll(), 0f);
        mgr.stageTargetFov(kf.fov(), 0f);
        mgr.stageTargetZoom(kf.zoom(), 0f);
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
