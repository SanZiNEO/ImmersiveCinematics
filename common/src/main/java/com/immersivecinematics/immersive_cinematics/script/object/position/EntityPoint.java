package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 实体形态的取点：实体包围盒内的一个点，按每轴百分比定位。
 *
 * <p>单位与坐标空间：每轴百分比（0 = 该轴最小侧、100 = 最大侧），坐标空间 = 实体包围盒局部
 * （世界轴对齐，不随实体朝向旋转；横向 / 前后按包围盒宽、纵向按包围盒高，底面中心 = 实体位置）。
 * 数值契约：越界值钳制到 0 ~ 100，取点恒在包围盒内（不伸到体外）；NaN 按 50 处理。</p>
 */
public record EntityPoint(double xPct, double yPct, double zPct) {

    /** 脚底中心（50 / 0 / 50）：位置基准 / 连线端点 / 锚点。 */
    public static final EntityPoint FOOT = new EntityPoint(50, 0, 50);

    /** 包围盒中心（50 / 50 / 50）：注视点 / 基准朝向目标。 */
    public static final EntityPoint CENTER = new EntityPoint(50, 50, 50);

    public EntityPoint {
        xPct = clampPercent(xPct);
        yPct = clampPercent(yPct);
        zPct = clampPercent(zPct);
    }

    /** 钳制到 0 ~ 100；NaN = 50（中心）。 */
    private static double clampPercent(double v) {
        if (Double.isNaN(v)) return 50.0;
        if (v < 0.0) return 0.0;
        return v > 100.0 ? 100.0 : v;
    }
}
