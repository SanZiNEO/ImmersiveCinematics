package com.immersivecinematics.immersive_cinematics.spi;

import com.immersivecinematics.immersive_cinematics.math.path.AxisConstrainedSpec;
import com.immersivecinematics.immersive_cinematics.math.path.AxisConstraint;
import com.immersivecinematics.immersive_cinematics.math.path.PathAnchor;
import com.immersivecinematics.immersive_cinematics.math.path.PathDomain;
import com.immersivecinematics.immersive_cinematics.math.path.PathExpression;
import net.minecraft.world.phys.Vec3;

/**
 * 路径定义：解析期不可变数据（类型、定义域、锚点），可被多个调用点共享。
 * {@link #bind(Vec3, Vec3)} 在每个调用点实例化表达式；对 FREE / START 锚点 {@code to} 被忽略。
 * 轴约束用 {@link #constrain(AxisConstraint)} 叠加，不修改原定义。
 */
public interface PathSpec {

    /** 曲线类型标识。 */
    String type();

    /** u 的定义域。 */
    PathDomain domain();

    /** 端点如何参与曲线定义。 */
    PathAnchor anchor();

    /** 用调用点端点实例化表达式；FREE / START 类型的 {@code to} 被忽略。 */
    PathExpression bind(Vec3 from, Vec3 to);

    /** 是否声明某项能力；缺省不声明。 */
    default boolean supports(PathCapability capability) {
        return false;
    }

    /** 叠加轴约束：未选轴替换为端点间线性，已选轴保留曲线分量。 */
    default PathSpec constrain(AxisConstraint constraint) {
        return new AxisConstrainedSpec(this, constraint);
    }
}
