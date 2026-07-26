package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 位置数据 — 关键帧的坐标信息
 * <p>
 * 根据 position_mode 有两种结构：
 * <ul>
 *   <li>relative: dx/dy/dz — 相对于玩家激活位置的偏移</li>
 *   <li>absolute: x/y/z — 世界绝对坐标</li>
 * </ul>
 * <p>
 * Gson 反序列化时，根据 JSON 中是否存在 "dx" 或 "x" 字段自动映射。
 * 内部统一存储为三个 float，通过 isRelative() 区分语义。
 */
public class PositionData {

    /** 坐标模式：true=相对偏移(dx/dy/dz)，false=绝对坐标(x/y/z) */
    private final boolean relative;

    /** X 分量（绝对坐标）或 DX 分量（相对偏移） */
    private final float x;

    /** Y 分量（绝对坐标）或 DY 分量（相对偏移） */
    private final float y;

    /** Z 分量（绝对坐标）或 DZ 分量（相对偏移） */
    private final float z;

    /**
     * 相对模式构造器
     *
     * @param dx 相对于玩家的 X 偏移
     * @param dy 相对于玩家的 Y 偏移
     * @param dz 相对于玩家的 Z 偏移
     * @return 相对模式的 PositionData
     */
    public static PositionData relative(float dx, float dy, float dz) {
        return new PositionData(true, dx, dy, dz);
    }

    /**
     * 绝对模式构造器
     *
     * @param x 世界绝对 X 坐标
     * @param y 世界绝对 Y 坐标
     * @param z 世界绝对 Z 坐标
     * @return 绝对模式的 PositionData
     */
    public static PositionData absolute(float x, float y, float z) {
        return new PositionData(false, x, y, z);
    }

    private PositionData(boolean relative, float x, float y, float z) {
        this.relative = relative;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** 是否为相对模式 */
    public boolean isRelative() {
        return relative;
    }

    /** 获取 X（绝对）或 DX（相对） */
    public float getX() {
        return x;
    }

    /** 获取 Y（绝对）或 DY（相对） */
    public float getY() {
        return y;
    }

    /** 获取 Z（绝对）或 DZ（相对） */
    public float getZ() {
        return z;
    }

    /** 相对模式的别名 */
    public float getDx() { return x; }
    public float getDy() { return y; }
    public float getDz() { return z; }

    /**
     * 转换为 Minecraft Vec3
     * <p>
     * 注意：相对模式下返回的是偏移量，需要由播放器加上玩家位置才是世界坐标。
     *
     * @return Vec3 表示
     */
    public Vec3 toVec3() {
        return new Vec3(x, y, z);
    }

    @Override
    public String toString() {
        if (relative) {
            return String.format("PositionData{relative, dx=%.2f, dy=%.2f, dz=%.2f}", x, y, z);
        } else {
            return String.format("PositionData{absolute, x=%.2f, y=%.2f, z=%.2f}", x, y, z);
        }
    }
}
