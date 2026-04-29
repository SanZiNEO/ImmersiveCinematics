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
 * - 位置使用相对玩家偏移（dx, dy, dz），在 start() 时解析为绝对坐标
 * <p>
 * 每次 P 键激活从第一段开始播放，播放结束后自动停用，恢复玩家视角。
 * 测试完成后直接删除此文件，零残留。
 */
public class CameraTestPlayer {

    // ========== 关键帧 & 镜头段定义 ==========

    /**
     * 关键帧记录（相对偏移坐标）
     * dx/dy/dz 为相对于玩家激活位置的偏移量
     * 段内 time 从 0 开始，相对于该段起点
     * <p>
     * Minecraft 角度约定：
     * - yaw: 0=南(+Z), 90=西(-X), ±180=北(-Z), -90=东(+X)
     * - pitch: 正值=低头看地面, 负值=抬头看天空
     */
    private record TestKeyframe(
            float time,   // 段内时间点（秒）
            float dx,     // 相对玩家 X 偏移
            float dy,     // 相对玩家 Y 偏移
            float dz,     // 相对玩家 Z 偏移
            float yaw,    // 绝对偏航角（度）
            float pitch,  // 绝对俯仰角（度），正值=低头
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
     * 十二段自由运镜测试脚本
     * <p>
     * 所有位置使用相对玩家偏移（dx, dy, dz），在 start() 时解析为绝对坐标。
     * pitch 值全部 ≥ 0（低头看地面/水平），不抬头看天。
     * <p>
     * 运镜类型覆盖：推/拉/移/绕/升/降/摇/变焦/荷兰角/波浪/收束
     */
    private static final TestSegment[] SEGMENTS = {

            // ---- 1. 经典前推 (5秒) ----
            // 从玩家位置正前方推入，水平视角
            new TestSegment("forward_dolly", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, 0, 0, 0, 0, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 0, 0, 15, 0, 0, 0, 70, 1.0f),
            }),

            // ---- 2. 右侧横移 (5秒) ----
            // 硬切到侧面，朝西看，沿+Z横移（侧滑运镜）
            new TestSegment("right_lateral", new TestKeyframe[]{
                    new TestKeyframe(0.0f, -5, 0, 0, 90, 0, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, -5, 0, 20, 90, 5, 0, 70, 1.0f),
            }),

            // ---- 3. 左弧线扫过 (6秒) ----
            // 弧线运动+yaw旋转，朝向逐渐变化
            new TestSegment("swoop_left", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 5, 0, -5, -45, 5, 0, 70, 1.0f),
                    new TestKeyframe(3.0f, 10, 1, 5, -90, 10, 0, 72, 1.1f),
                    new TestKeyframe(6.0f, 5, 2, 15, -135, 5, 0, 70, 1.0f),
            }),

            // ---- 4. 后拉上升 (5秒) ----
            // 从近处后退+升高，视角缓慢下压
            new TestSegment("pull_back_rise", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, 0, 5, 0, 5, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 0, 5, -10, 0, 25, 0, 75, 1.0f),
            }),

            // ---- 5. 右绕1/4圈 (6秒) ----
            // 绕目标点做1/4圆弧，yaw旋转90°
            new TestSegment("orbit_right", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 10, 2, 0, -90, 10, 0, 70, 1.0f),
                    new TestKeyframe(6.0f, 0, 3, 10, 0, 15, 0, 70, 1.0f),
            }),

            // ---- 6. 低位前推 (5秒) ----
            // 相对玩家偏低，缓慢前进+下压视角
            new TestSegment("low_dolly", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, -1, -5, 0, 15, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 0, -1, 10, 0, 20, 0, 65, 1.2f),
            }),

            // ---- 7. 升降机上升 (5秒) ----
            // 垂直上升+俯角渐大（看地面），经典升降机镜头
            new TestSegment("crane_up", new TestKeyframe[]{
                    new TestKeyframe(0.0f, -3, 0, 0, 45, 0, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, -3, 8, 0, 45, 35, 0, 80, 1.0f),
            }),

            // ---- 8. 荷兰角 (4秒) ----
            // roll渐变+zoom放大，制造不安感
            new TestSegment("dutch_tilt", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, 2, 5, -20, 10, 0, 70, 1.0f),
                    new TestKeyframe(2.0f, 2, 2, 8, -15, 15, 15, 70, 1.8f),
                    new TestKeyframe(4.0f, 0, 2, 5, -10, 10, 30, 70, 2.5f),
            }),

            // ---- 9. 变焦推近 (5秒) ----
            // 位置微移+zoom放大，模拟光学变焦推近
            new TestSegment("zoom_in", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, 1, -3, 30, 5, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 1, 1, 0, 20, 10, 0, 70, 2.5f),
            }),

            // ---- 10. 大范围扫视 (6秒) ----
            // yaw大幅旋转+横向移动，全景扫视
            new TestSegment("pan_sweep", new TestKeyframe[]{
                    new TestKeyframe(0.0f, -8, 2, 0, -90, 10, 0, 70, 1.0f),
                    new TestKeyframe(3.0f, 0, 2, 5, 0, 5, 0, 72, 1.0f),
                    new TestKeyframe(6.0f, 8, 2, 0, 90, 10, 0, 70, 1.0f),
            }),

            // ---- 11. 波浪起伏 (5秒) ----
            // dy先降后升+pitch联动，波浪形运镜
            new TestSegment("wave_motion", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, 3, 0, 0, 10, 0, 70, 1.0f),
                    new TestKeyframe(2.5f, 5, 0, 5, -15, 20, 0, 68, 1.1f),
                    new TestKeyframe(5.0f, 10, 4, 10, -30, 5, 0, 72, 1.0f),
            }),

            // ---- 12. 收束 (6秒) ----
            // FOV收窄+微调回水平，隧道视觉效果收尾
            new TestSegment("final_converge", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, 3, 5, 0, 15, 0, 70, 1.0f),
                    new TestKeyframe(3.0f, 0, 1, 3, 0, 5, -5, 60, 1.2f),
                    new TestKeyframe(6.0f, 0, 0, 0, 0, 0, 0, 50, 1.5f),
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

    // 玩家激活时的基准位置（用于将相对偏移解析为绝对坐标）
    private float originX = 0f;
    private float originY = 0f;
    private float originZ = 0f;

    // ========== 生命周期 ==========

    /**
     * 启动测试播放器
     * 由 CameraManager.activate() 调用
     * <p>
     * 记录玩家当前位置作为基准，将相对偏移解析为绝对坐标
     */
    public void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 记录玩家位置作为基准
        Vec3 playerPos = mc.player.position();
        originX = (float) playerPos.x;
        originY = (float) playerPos.y;
        originZ = (float) playerPos.z;

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

        // 插值计算当前状态（相对偏移 → 绝对坐标）
        Vec3 pos = new Vec3(
                originX + lerp(from.dx(), to.dx(), t),
                originY + lerp(from.dy(), to.dy(), t),
                originZ + lerp(from.dz(), to.dz(), t)
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
     * 相对偏移 (dx, dy, dz) → 绝对坐标 (originX+dx, originY+dy, originZ+dz)
     * <p>
     * 调用后需调用 CameraManager.commitStagedState() 将 staged 原子替换到 active
     *
     * @param kf 要预置的关键帧
     */
    private void prepareStagedKeyframe(TestKeyframe kf) {
        CameraManager mgr = CameraManager.INSTANCE;
        mgr.stageTargetPosition(new Vec3(originX + kf.dx(), originY + kf.dy(), originZ + kf.dz()), 0f);
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
