package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 切线朝向（0.3.5）：让相机沿路径切线方向看，并叠加水平/垂直偏移角。
 * <p>
 * 与运动模型解耦：这里只负责把“路径切线”转成 MC 的 yaw/pitch，
 * 不关心路径怎么生成、速度怎么分配。
 */
public final class TangentOrientation {

    private TangentOrientation() {}

    /**
     * 根据路径切线计算最终朝向。
     *
     * @param from       段起点世界坐标
     * @param to         段终点世界坐标
     * @param s          弧长进度 [0,1]
     * @param curve      贝塞尔曲线（可为 null）
     * @param strategy   路径策略（用于求切线）
     * @param yawOffset  水平偏移角（度）
     * @param pitchOffset 垂直偏移角（度）
     * @return [yaw, pitch]
     */
    public static float[] compute(Vec3 from, Vec3 to, float s, BezierCurve curve, PathStrategy strategy,
                                  float yawOffset, float pitchOffset) {
        Vec3 tangent = strategy.tangent(from, to, s, curve);
        double dx = tangent.x;
        double dy = tangent.y;
        double dz = tangent.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // 退化保护：切线为零时回退到 from→to 方向
        if (horizontal < 1.0E-6 && Math.abs(dy) < 1.0E-6) {
            dx = to.x - from.x;
            dy = to.y - from.y;
            dz = to.z - from.z;
            horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < 1.0E-6 && Math.abs(dy) < 1.0E-6) {
                return new float[]{0f, 0f};
            }
        }

        // 与 look_at / lineDir 保持同一套 MC 角度约定
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f + yawOffset;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal)) + pitchOffset;
        return new float[]{yaw, pitch};
    }
}
