package com.immersivecinematics.immersive_cinematics.script.object.position;

import com.immersivecinematics.immersive_cinematics.script.object.ClipSampler;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectSlotLayout;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * 采样属性路径上的切线：用 {@code position.x/y/z} 在 {@code t ± epsilon} 的中心差分求导。
 *
 * <p>结果是<b>采样表达空间</b>的导数（{@code position.x/y/z} 三轴的含义取决于
 * {@code position_mode}/{@code position_space}/{@code position_origin}）：点源、参考系变换、
 * 世界坐标装配都由调用方负责，本类不做任何坐标变换、不读点源。</p>
 *
 * <p>实现：每个实例自持一块 {@link SampledClip} 缓冲（构造期一次，运行期零分配），
 * 两次 {@link ClipSampler#sample} 后按 {@code (b - a) / (2 * epsilon)} 返回。槽位布局缺少
 * {@code position.x}/{@code position.y}/{@code position.z} 任一槽（{@code slotOf < 0}）时
 * {@link PathTangent#direction} 返回 {@code null}；片段没写某轴 = 该轴保持缓冲初值 0，
 * 导数按 0 计（与采样语义一致：未声明 = 默认值 0）。</p>
 */
public final class SampledPathTangent {

    private SampledPathTangent() {
    }

    /**
     * 建切线（一次；缓冲区按本采样器的布局分配）。
     *
     * @param sampler 片段采样器（时间由调用方给出，不折叠 loop）
     * @param layout  该片段的槽位布局（必须与采样器一致）
     * @param epsilon 中心差分半宽（执行局部时间单位，正有限值）
     * @return 切线；布局缺位置槽时 {@code direction} 恒为 {@code null}
     */
    public static PathTangent of(ClipSampler sampler, ObjectSlotLayout layout, double epsilon) {
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(layout, "layout");
        if (!(epsilon > 0.0) || !Double.isFinite(epsilon)) {
            throw new IllegalArgumentException("epsilon 必须为正有限值");
        }
        int x = layout.slotOf("position.x");
        int y = layout.slotOf("position.y");
        int z = layout.slotOf("position.z");
        if (x < 0 || y < 0 || z < 0) {
            return MISSING;
        }
        return new Central(sampler, sampler.newBuffer(), x, y, z, epsilon);
    }

    /** 布局缺位置槽：方向恒不可用（不抛，交给调用方回退手动朝向）。 */
    private static final PathTangent MISSING = localTime -> null;

    /** 中心差分实现：两块采样写在同一个自持缓冲上。 */
    private static final class Central implements PathTangent {

        private final ClipSampler sampler;
        private final SampledClip buffer;
        private final int xSlot;
        private final int ySlot;
        private final int zSlot;
        private final double epsilon;

        private Central(ClipSampler sampler, SampledClip buffer, int xSlot, int ySlot, int zSlot, double epsilon) {
            this.sampler = sampler;
            this.buffer = buffer;
            this.xSlot = xSlot;
            this.ySlot = ySlot;
            this.zSlot = zSlot;
            this.epsilon = epsilon;
        }

        @Override
        public Vec3 direction(double localTime) {
            sampler.sample(localTime - epsilon, buffer);
            double ax = buffer.f(xSlot);
            double ay = buffer.f(ySlot);
            double az = buffer.f(zSlot);
            sampler.sample(localTime + epsilon, buffer);
            double bx = buffer.f(xSlot);
            double by = buffer.f(ySlot);
            double bz = buffer.f(zSlot);
            double scale = 1.0 / (2.0 * epsilon);
            return new Vec3((bx - ax) * scale, (by - ay) * scale, (bz - az) * scale);
        }
    }
}
