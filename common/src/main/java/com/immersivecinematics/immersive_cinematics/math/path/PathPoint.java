package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

/**
 * 路径控制点：{@code relative=true} 用 dx/dy/dz（相对段起点），否则用 x/y/z（世界坐标）。
 * 相对点只在 {@link #resolve(Vec3)} 时依赖段起点；{@link #toVec3()} 返回原始分量，不做相对解析。
 */
public record PathPoint(boolean relative, double x, double y, double z) {

    /** 绝对世界坐标控制点。 */
    public static PathPoint absolute(double x, double y, double z) {
        return new PathPoint(false, x, y, z);
    }

    /** 相对段起点的控制点。 */
    public static PathPoint relative(double dx, double dy, double dz) {
        return new PathPoint(true, dx, dy, dz);
    }

    /** 解析为世界坐标：relative → {@code segmentStart + (x,y,z)}，absolute → {@code (x,y,z)}。 */
    public Vec3 resolve(Vec3 segmentStart) {
        if (!relative) {
            return new Vec3(x, y, z);
        }
        if (segmentStart == null) {
            throw new IllegalArgumentException("相对控制点需要段起点: segmentStart=null");
        }
        return segmentStart.add(x, y, z);
    }

    /** 原始分量打包成向量；relative 时不做相对解析。 */
    public Vec3 toVec3() {
        return new Vec3(x, y, z);
    }

    /** 三个分量是否都有限。 */
    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
