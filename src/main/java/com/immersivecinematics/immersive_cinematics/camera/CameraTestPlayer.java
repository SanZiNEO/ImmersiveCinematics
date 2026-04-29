package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
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
 * - 段内：关键帧间平滑插值 → 每渲染帧用实时时间精确计算
 * - 段间：预置下一段第一帧到 staged → commitStagedState() 原子替换 → 真正硬切换
 * - 总时长由各段时长累加得出，不硬编码
 * - 位置使用相对玩家偏移（dx, dy, dz），在 start() 时解析为绝对坐标
 * <p>
 * 🎬 帧回调驱动模式（ReplayMod 式）：
 * - 不再使用 tick() 驱动，改为 onRenderFrame() 由渲染帧回调驱动
 * - 用 System.nanoTime() 实时时间计算精确位置
 * - 每帧直接算出精确值 → 不需要 MC 的 partialTick 插值
 * - 更丝滑、更低的逻辑复杂度、不受 tick 抖动影响
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
     * <p>
     * 朝向玩家的yaw计算：从 (cx,cy,cz) 看向 (0,0,0)
     * yaw = atan2(cx, -cz) 转角度
     * 例: 东侧(25,_,0) → yaw=90; 西侧(-5,_,0) → yaw=-90=270;
     *     南侧(0,_,15) → yaw=180; 北侧(0,_,-10) → yaw=0
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
     * 十七段叙事运镜测试脚本 —— "世界中的一刻"
     * <p>
     * 所有位置使用相对玩家偏移（dx, dy, dz），在 start() 时解析为绝对坐标。
     * pitch 值全部 ≥ 0（低头看地面/水平），段7低位仰拍除外（微抬头看人，pitch小负值）。
     * <p>
     * 叙事弧线（5幕）：
     * Act 1「发现」— 从远处发现一个人影，慢慢靠近
     * Act 2「注视」— 从各个角度仔细看这个人：侧面、绕行、越肩、仰拍、特写
     * Act 3「环境」— 相机从人转向世界（他所在的世界是什么样的？）
     * Act 4「戏剧」— 情绪变化，荷兰角+上升
     * Act 5「回归」— 从高空回落，最终回到人身上，FOV收束收尾
     * <p>
     * 每段镜头的yaw均经过计算，朝向它的叙事主体（玩家/环境方向）。
     * 运镜类型覆盖：推/拉/横移/绕行/越肩/仰拍/变焦/转头/全景/俯瞰/荷兰角/升降/收束
     */
    private static final TestSegment[] SEGMENTS = {

            // ================================================================
            // Act 1: 发现 (17秒)
            // 叙事：从远处发现一个人影，慢慢靠近，人影逐渐变清晰
            // ================================================================

            // ---- 1. 远景推入 (6秒) ----
            // 从东侧远处慢慢推入，人影在画面中心
            // (30,2,0) → yaw=atan2(30,0)=90 朝西看玩家
            new TestSegment("distant_approach", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 30, 2, 0, 90, 5, 0, 70, 1.0f),
                    new TestKeyframe(6.0f, 20, 2, 0, 90, 5, 0, 70, 1.0f),
            }),

            // ---- 2. 继续推近 (6秒) ----
            // 硬切到更近，人影变清晰
            // (18,1.5,0) → yaw=90
            new TestSegment("closer_dolly", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 18, 1.5f, 0, 90, 5, 0, 70, 1.0f),
                    new TestKeyframe(6.0f, 8, 1.5f, 0, 90, 8, 0, 70, 1.0f),
            }),

            // ---- 3. 近景侧角 (5秒) ----
            // 微侧角度推入，不完全是正面——更自然的观察视角
            // (7,1.5,2) → yaw=atan2(7,-2)≈105° 朝向玩家偏南
            new TestSegment("intimate_push", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 7, 1.5f, 2, 105, 5, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 3, 1.5f, 1, 100, 8, 0, 68, 1.0f),
            }),

            // ================================================================
            // Act 2: 注视 (35秒)
            // 叙事：从各个角度仔细看这个人——侧面、绕行、越肩、仰拍、特写
            // ================================================================

            // ---- 4. 右侧横移 (6秒) ----
            // 从西侧，侧颜跟拍
            // (-5,1,-5)→(-5,1,10) 始终 yaw=270 朝东看玩家
            new TestSegment("right_lateral", new TestKeyframe[]{
                    new TestKeyframe(0.0f, -5, 1, -5, 270, 5, 0, 70, 1.0f),
                    new TestKeyframe(6.0f, -5, 1, 10, 270, 8, 0, 70, 1.0f),
            }),

            // ---- 5. 右绕半圈 (8秒) ----
            // 从东南绕到西北，yaw始终朝向玩家
            // t=0: (10,1.5,3) → yaw=atan2(10,-3)≈107°
            // t=4: (0,2,-2) → yaw=atan2(0,2)=0°
            // t=8: (-10,1.5,3) → yaw=atan2(-10,-3)≈-107°→253°
            new TestSegment("orbit_right", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 10, 1.5f, 3, 107, 5, 0, 70, 1.0f),
                    new TestKeyframe(4.0f, 0, 2, -2, 0, 8, 0, 72, 1.1f),
                    new TestKeyframe(8.0f, -10, 1.5f, 3, -107, 5, 0, 70, 1.0f),
            }),

            // ---- 6. 越肩看世界 (6秒) ----
            // 从玩家身后往前看，展示玩家面前的世界
            // (-1,2.5,-4) → yaw=atan2(-1,4)≈-14° 朝南偏东看
            new TestSegment("behind_shoulder", new TestKeyframe[]{
                    new TestKeyframe(0.0f, -1, 2.5f, -4, -14, 5, 0, 70, 1.0f),
                    new TestKeyframe(6.0f, -1, 3, -8, -7, 15, 0, 75, 1.0f),
            }),

            // ---- 7. 低位仰拍 (5秒) ----
            // 从东南低位仰拍，英雄感。微抬头看人（pitch小负值合理）
            // (4,-1,4) → yaw=atan2(4,-4)≈135°
            new TestSegment("low_hero", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 4, -1, 4, 135, -8, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 3, -1, 3, 135, -5, 0, 68, 1.1f),
            }),

            // ---- 8. 变焦特写 (5秒) ----
            // 从东侧，位置微移+zoom放大，凝视
            // (5,1.5,0) → yaw=90
            new TestSegment("close_zoom", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 5, 1.5f, 0, 90, 5, 0, 70, 1.0f),
                    new TestKeyframe(2.5f, 4.5f, 1.5f, 0, 90, 8, 0, 68, 1.5f),
                    new TestKeyframe(5.0f, 4, 1.5f, 0, 90, 10, 0, 65, 2.5f),
            }),

            // ---- 9. 缓慢后退 (5秒) ----
            // 特写后退回中景，释放紧张感
            // (4,1.5,0) → (8,1.5,0) yaw=90
            new TestSegment("pull_back", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 4, 1.5f, 0, 90, 10, 0, 65, 2.0f),
                    new TestKeyframe(5.0f, 8, 1.5f, 0, 90, 5, 0, 70, 1.0f),
            }),

            // ================================================================
            // Act 3: 环境 (22秒)
            // 叙事：相机从人转向世界——"他所在的世界是什么样的？"
            // ================================================================

            // ---- 10. 转头·叙事转折 (5秒) ----
            // 原地转头：从注视玩家(yaw=90)转向看北方环境(yaw=180)
            // (6,2,0) 位置不变，纯yaw旋转
            new TestSegment("turn_to_world", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 6, 2, 0, 90, 5, 0, 70, 1.0f),
                    new TestKeyframe(2.5f, 6, 2, 0, 135, 8, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 6, 2, 0, 180, 10, 0, 70, 1.0f),
            }),

            // ---- 11. 环境全景横移 (7秒) ----
            // 从西到东横移，朝北看环境（建筑/自然景观）
            // (-8,3,-15)→(8,3,-15) yaw=180 朝北
            new TestSegment("env_pan", new TestKeyframe[]{
                    new TestKeyframe(0.0f, -8, 3, -15, 180, 10, 0, 70, 1.0f),
                    new TestKeyframe(3.5f, 0, 3, -15, 180, 10, 0, 72, 1.0f),
                    new TestKeyframe(7.0f, 8, 3, -15, 180, 10, 0, 70, 1.0f),
            }),

            // ---- 12. 环境细节 (5秒) ----
            // 近景扫过环境细节（假设东北方向有建筑/结构）
            // (10,1,-8)→(14,1,-5) yaw渐变
            new TestSegment("env_detail", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 10, 1, -8, 140, 12, 0, 70, 1.0f),
                    new TestKeyframe(5.0f, 14, 1, -5, 150, 15, 0, 68, 1.1f),
            }),

            // ---- 13. 俯瞰全貌 (5秒) ----
            // 高处俯瞰，位置几乎不动，pitch变化展示高度
            // (0,12,0) yaw=180看北，pitch=50~55看地面
            new TestSegment("env_overhead", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, 12, 0, 180, 55, 0, 80, 1.0f),
                    new TestKeyframe(5.0f, 0, 12, 0, 180, 50, 0, 80, 1.0f),
            }),

            // ================================================================
            // Act 4: 戏剧 (10秒)
            // 叙事：情绪变化——荷兰角的不安，戏剧性上升拉远
            // ================================================================

            // ---- 14. 荷兰角·不安 (4秒) ----
            // 画面倾斜+zoom放大=不安感，但yaw仍然朝向玩家（有主体！）
            // (6,2,3) → yaw=atan2(6,-3)≈116°
            new TestSegment("dutch_tension", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 6, 2, 3, 116, 10, 0, 70, 1.0f),
                    new TestKeyframe(2.0f, 5.5f, 2, 2.5f, 115, 12, 15, 70, 1.5f),
                    new TestKeyframe(4.0f, 5, 2, 2, 115, 15, 30, 70, 2.0f),
            }),

            // ---- 15. 戏剧性上升 (6秒) ----
            // 从侧面升高，yaw始终朝向玩家
            // (-6,1,5) → yaw=atan2(-6,-5)≈-130°→230°
            new TestSegment("dramatic_rise", new TestKeyframe[]{
                    new TestKeyframe(0.0f, -6, 1, 5, -130, 5, 0, 70, 1.0f),
                    new TestKeyframe(6.0f, -6, 10, 5, -130, 40, 0, 85, 1.0f),
            }),

            // ================================================================
            // Act 5: 回归 (18秒)
            // 叙事：从高空回落，最终回到人身上，FOV收束收尾
            // ================================================================

            // ---- 16. 下降回落 (6秒) ----
            // 从高空缓缓下降，回到人的视线高度
            // (0,10,8) → yaw=atan2(0,-8)=180 朝北看玩家
            new TestSegment("descend_return", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 0, 10, 8, 180, 40, 0, 80, 1.0f),
                    new TestKeyframe(3.0f, 0, 6, 6, 180, 25, 0, 75, 1.0f),
                    new TestKeyframe(6.0f, 0, 3, 4, 180, 15, 0, 70, 1.0f),
            }),

            // ---- 17. 正面推近 (6秒) ----
            // 从东南方向正面推近玩家
            // (8,1.5,3) → yaw=atan2(8,-3)≈110°
            new TestSegment("final_walk", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 8, 1.5f, 3, 110, 5, 0, 70, 1.0f),
                    new TestKeyframe(6.0f, 4, 1.5f, 1, 100, 5, 0, 68, 1.0f),
            }),

            // ---- 18. 最终构图 (6秒) ----
            // 微调位置，FOV缓慢收窄，隧道视觉收尾
            // (5,2,2) → yaw=atan2(5,-2)≈112°
            new TestSegment("final_composition", new TestKeyframe[]{
                    new TestKeyframe(0.0f, 5, 2, 2, 112, 5, 0, 70, 1.0f),
                    new TestKeyframe(3.0f, 4.5f, 1.5f, 1, 105, 5, -3, 60, 1.2f),
                    new TestKeyframe(6.0f, 4, 1.5f, 1, 100, 5, 0, 50, 1.5f),
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
    private float segmentTime = 0f;       // 当前段内已播放时间（秒）
    private boolean playing = false;

    // 🎬 实时时间驱动：纳秒级时间戳
    private long startNanoTime = 0;       // 播放开始时的 System.nanoTime()
    private float[] segmentStartTimes;    // 每段在总时间轴上的起始时间（秒）

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

        // 🎬 初始化实时时间
        startNanoTime = System.nanoTime();

        // 预计算每段在时间轴上的起始时间
        segmentStartTimes = new float[SEGMENTS.length];
        float cumulative = 0f;
        for (int i = 0; i < SEGMENTS.length; i++) {
            segmentStartTimes[i] = cumulative;
            cumulative += SEGMENTS[i].getDuration();
        }

        // 初始化：通过 staged + commit 将第一段第一帧写入 active
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

    // ========== 核心逻辑：帧回调驱动 ==========

    /**
     * 🎬 每渲染帧驱动：用实时时间精确计算当前位置，直接写入 CameraManager
     * <p>
     * ReplayMod 式：每帧用 System.nanoTime() 算精确时间，直接计算精确位置，
     * 不需要 MC 的 partialTick 插值。比 tick 驱动更丝滑，不受 tick 抖动影响。
     * <p>
     * 由 CameraManager.onRenderFrame() 调用
     */
    public void onRenderFrame() {
        if (!playing) return;

        // 🎬 用实时时间计算总经过时间（秒）
        float elapsedSeconds = (System.nanoTime() - startNanoTime) / 1_000_000_000f;

        // 检查总时长
        if (elapsedSeconds >= TOTAL_DURATION) {
            // 播放结束，isFinished() 将返回 true
            currentSegmentIndex = SEGMENTS.length;
            return;
        }

        // 根据总经过时间定位到当前段
        int newSegmentIndex = 0;
        for (int i = SEGMENTS.length - 1; i >= 0; i--) {
            if (elapsedSeconds >= segmentStartTimes[i]) {
                newSegmentIndex = i;
                break;
            }
        }

        // 检测段间切换
        if (newSegmentIndex != currentSegmentIndex) {
            // 硬切换到新段：
            // 1. 预置新段第一帧到 staged 缓冲区
            // 2. commitStagedState() 原子替换 active ← staged
            currentSegmentIndex = newSegmentIndex;
            prepareStagedKeyframe(SEGMENTS[currentSegmentIndex].keyframes()[0]);
            CameraManager.INSTANCE.commitStagedState();
            return;  // 这一帧先跳到第一帧，下一帧再插值
        }

        // 计算段内时间
        segmentTime = elapsedSeconds - segmentStartTimes[currentSegmentIndex];

        // ---- 段内插值 → 写入 active ----

        TestSegment currentSeg = SEGMENTS[currentSegmentIndex];
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
                originX + MathUtil.lerp(from.dx(), to.dx(), t),
                originY + MathUtil.lerp(from.dy(), to.dy(), t),
                originZ + MathUtil.lerp(from.dz(), to.dz(), t)
        );
        float yaw = MathUtil.lerpAngle(from.yaw(), to.yaw(), t);
        float pitch = MathUtil.lerp(from.pitch(), to.pitch(), t);
        float roll = MathUtil.lerpAngle(from.roll(), to.roll(), t);
        float fov = MathUtil.lerp(from.fov(), to.fov(), t);
        float zoom = MathUtil.lerp(from.zoom(), to.zoom(), t);

        // 🎬 直接设置到 active（duration=0），每帧都精确重算，不需要过渡插值
        CameraManager mgr = CameraManager.INSTANCE;
        mgr.getPath().setPositionDirect(pos);
        mgr.getProperties().setYawDirect(yaw);
        mgr.getProperties().setPitchDirect(pitch);
        mgr.getProperties().setRollDirect(roll);
        mgr.getProperties().setFovDirect(fov);
        mgr.getProperties().setZoomDirect(zoom);
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

}
