package com.immersivecinematics.immersive_cinematics.script.object.position;

import net.minecraft.world.phys.Vec3;

/**
 * 点源偏移：向量 + 表达空间。分量按表达空间对应：
 * {@link PositionSpace#WORLD} = {@code (dx, dy, dz)} 世界轴；
 * {@link PositionSpace#BASE} = {@code (fwd, up, right)} 基准坐标系三轴。
 */
public record PointOffset(Vec3 vector, PositionSpace space) {

    /** 无偏移。 */
    public static final PointOffset NONE = new PointOffset(Vec3.ZERO, PositionSpace.WORLD);

    /** 世界轴偏移（dx / dy / dz）。 */
    public static PointOffset world(float dx, float dy, float dz) {
        return new PointOffset(new Vec3(dx, dy, dz), PositionSpace.WORLD);
    }

    /** 基准坐标系偏移（fwd / up / right）。 */
    public static PointOffset base(float fwd, float up, float right) {
        return new PointOffset(new Vec3(fwd, up, right), PositionSpace.BASE);
    }

    /**
     * 把偏移施加到来源取点上。
     * {@link PositionSpace#BASE} 需要本帧基准坐标系：{@code frame} 为 {@code null}（本帧没有基准坐标系）= 偏移不生效。
     */
    public Vec3 apply(Vec3 point, PointFrame frame) {
        if (space == PositionSpace.WORLD) return point.add(vector);
        if (frame == null) return point;
        return point.add(frame.fwd().scale(vector.x))
                .add(frame.right().scale(vector.z))
                .add(frame.up().scale(vector.y));
    }
}
