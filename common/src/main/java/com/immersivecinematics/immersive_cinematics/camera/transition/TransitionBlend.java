package com.immersivecinematics.immersive_cinematics.camera.transition;

/**
 * 六参数过渡混合公式（过渡混合口径的唯一定义点）——按通道数值域给出 A / B 两侧的插值方式。
 *
 * {@link #axis}：位置逐轴线性（世界坐标，方块）。{@link #linear}：pitch / fov 线性（度）。{@link #angle}：
 * yaw / roll 走最短路径（度，差值环绕到 [-180, 180) 后按权重推进）。{@link #zoom}：zoom 几何域
 * （两侧均 > 0 时按 {@code exp(log a · (1−w) + log b · w)}，否则回退线性）——倍率的等比推进。
 * 权重域 {@code 0 ~ 1}：越界不钳制，由调用方按自己的口径钳制。全部为纯函数、零分配。
 * 差值环绕区间 = [-180, 180)（{@code AngleUtil.wrap180} 用 (-180, 180]）——两者皆为合法最短路，仅端点归属口径不同。
 */
public final class TransitionBlend {

    private TransitionBlend() {} // 工具类，禁止实例化

    /** 位置逐轴线性混合（世界坐标，方块）。 */
    public static double axis(double a, double b, float weight) {
        return a * (1f - weight) + b * weight;
    }

    /** 线性混合（pitch / fov，度）。 */
    public static float linear(float a, float b, float weight) {
        return a * (1f - weight) + b * weight;
    }

    /** 最短路径角度混合（度）：差值环绕到 [-180, 180) 后按权重推进。 */
    public static float angle(float a, float b, float weight) {
        float diff = ((b - a) % 360f + 540f) % 360f - 180f;
        return a + diff * weight;
    }

    /** 几何域混合（zoom）：两侧均 > 0 时按对数等比推进，否则回退线性。 */
    public static float zoom(float a, float b, float weight) {
        if (a <= 0f || b <= 0f) {
            return linear(a, b, weight);
        }
        return (float) Math.exp(Math.log(a) * (1f - weight) + Math.log(b) * weight);
    }
}
