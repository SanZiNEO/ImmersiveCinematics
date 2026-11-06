package com.immersivecinematics.immersive_cinematics.math.path;

import com.immersivecinematics.immersive_cinematics.spi.PathCapability;
import net.minecraft.world.phys.Vec3;

/**
 * 路径表达式实例：把曲线参数 u 映射为位置与一阶导。
 * u 是曲线自身参数，不是时间也不是弧长；定义域由 {@link #domain()} 给出。
 * 求值是 u 的纯函数：同一实例重复求值同一 u 结果一致，不持有跨帧状态。
 * 二阶导缺省用中心差分；需要精确值时曲线应覆盖并声明
 * {@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}。
 */
public interface PathExpression {

    /** 曲线类型标识。 */
    String type();

    /** u 的定义域。 */
    PathDomain domain();

    /** u 处的位置。 */
    Vec3 position(double u);

    /** u 处的一阶导 {@code dP/du}。 */
    Vec3 derivative(double u);

    /**
     * u 处的一阶导模长 {@code |dP/du|}，世界单位 / u 单位；与 {@link #derivative(double)} 的模长一致。
     * 弧长链路逐帧高频调用，具体曲线 MUST 覆盖为纯标量实现，此路径上不得分配。
     * 缺省实现取 {@link #derivative(double)} 的模长，每次调用分配一个向量，只供临时实现使用。
     */
    default double speed(double u) {
        return PathMath.length(derivative(u));
    }

    /**
     * 二阶导：中心差分 {@code (P'(u+h) - P'(u-h)) / 2h}，{@code h = span * 1e-4}。
     * 差分窗口在定义域端点处整体向内平移；结果非有限时返回零向量。
     */
    default Vec3 secondDerivative(double u) {
        PathDomain domain = domain();
        double h = domain.span() * 1e-4;
        double center = domain.clamp(u);
        double lo = center - h;
        double hi = center + h;
        if (lo < domain.min()) {
            lo = domain.min();
            hi = Math.min(domain.max(), domain.min() + 2.0 * h);
        } else if (hi > domain.max()) {
            hi = domain.max();
            lo = Math.max(domain.min(), domain.max() - 2.0 * h);
        }
        double width = hi - lo;
        if (width <= 0.0) {
            return Vec3.ZERO;
        }
        Vec3 value = derivative(hi).subtract(derivative(lo)).scale(1.0 / width);
        return PathMath.isFinite(value) ? value : Vec3.ZERO;
    }

    /** 是否声明某项能力；缺省不声明。 */
    default boolean supports(PathCapability capability) {
        return false;
    }

    /** 周期（u 单位），仅 {@link PathCapability#PERIODIC} 有意义；缺省 NaN = 无周期。 */
    default double period() {
        return Double.NaN;
    }
}
