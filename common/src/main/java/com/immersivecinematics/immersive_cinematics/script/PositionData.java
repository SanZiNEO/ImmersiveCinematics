package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 位置数据 — 关键帧的坐标信息
 * <p>
 * 根据 position_mode 有两种结构：
 * <ul>
 *   <li>relative: dx/dy/dz — 相对基准点的偏移。基准默认 = 玩家激活位置，可用
 *       relative_origin 指定：{@code "coordinate"}（相对固定坐标，配合 relative_origin_x/y/z）
 *       或结构 id（相对结构中心，如 {@code "minecraft:village"}）</li>
 *   <li>absolute: x/y/z — 世界绝对坐标</li>
 * </ul>
 * <p>
 * Gson 反序列化时，根据 JSON 中是否存在 "dx" 或 "x" 字段自动映射。
 * 内部统一存储为三个 float，通过 isRelative() 区分语义。
 */
public class PositionData {

    /** 相对基准：玩家激活位置（默认） */
    public static final int ORIGIN_PLAYER = 0;
    /** 相对基准：固定坐标（relative_origin_x/y/z） */
    public static final int ORIGIN_COORDINATE = 1;
    /** 相对基准：结构中心（relative_origin 填结构 id） */
    public static final int ORIGIN_STRUCTURE = 2;

    /** 坐标模式：true=相对偏移(dx/dy/dz)，false=绝对坐标(x/y/z) */
    private final boolean relative;

    /** X 分量（绝对坐标）或 DX 分量（相对偏移） */
    private final float x;

    /** Y 分量（绝对坐标）或 DY 分量（相对偏移） */
    private final float y;

    /** Z 分量（绝对坐标）或 DZ 分量（相对偏移） */
    private final float z;

    /** 相对基准类型（仅 relative 有意义）：ORIGIN_PLAYER / ORIGIN_COORDINATE / ORIGIN_STRUCTURE */
    private final int originType;

    /** 相对基准坐标（ORIGIN_COORDINATE 时有效） */
    private final float ox;
    private final float oy;
    private final float oz;

    /** 相对基准结构 id（ORIGIN_STRUCTURE 时有效） */
    private final String originStructure;

    /**
     * 相对模式构造器（基准 = 玩家激活位置）
     *
     * @param dx 相对于基准的 X 偏移
     * @param dy 相对于基准的 Y 偏移
     * @param dz 相对于基准的 Z 偏移
     * @return 相对模式的 PositionData
     */
    public static PositionData relative(float dx, float dy, float dz) {
        return new PositionData(true, dx, dy, dz, ORIGIN_PLAYER, 0f, 0f, 0f, null);
    }

    /**
     * 相对模式构造器（基准 = 固定坐标）
     *
     * @param dx 相对于基准的 X 偏移
     * @param dy 相对于基准的 Y 偏移
     * @param dz 相对于基准的 Z 偏移
     * @param ox 基准 X 坐标
     * @param oy 基准 Y 坐标
     * @param oz 基准 Z 坐标
     */
    public static PositionData relativeToCoordinate(float dx, float dy, float dz, float ox, float oy, float oz) {
        return new PositionData(true, dx, dy, dz, ORIGIN_COORDINATE, ox, oy, oz, null);
    }

    /**
     * 相对模式构造器（基准 = 结构中心）
     *
     * @param dx           相对于基准的 X 偏移
     * @param dy           相对于基准的 Y 偏移
     * @param dz           相对于基准的 Z 偏移
     * @param structureId  基准结构 id（如 {@code "minecraft:village"}）
     */
    public static PositionData relativeToStructure(float dx, float dy, float dz, String structureId) {
        return new PositionData(true, dx, dy, dz, ORIGIN_STRUCTURE, 0f, 0f, 0f, structureId);
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
        return new PositionData(false, x, y, z, ORIGIN_PLAYER, 0f, 0f, 0f, null);
    }

    private PositionData(boolean relative, float x, float y, float z, int originType, float ox, float oy, float oz, String originStructure) {
        this.relative = relative;
        this.x = x;
        this.y = y;
        this.z = z;
        this.originType = originType;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.originStructure = originStructure;
    }

    /** 相对基准是否为固定坐标 */
    public boolean isOriginCoordinate() {
        return originType == ORIGIN_COORDINATE;
    }

    /** 相对基准坐标 X（ORIGIN_COORDINATE 时有效） */
    public float getOriginX() {
        return ox;
    }

    /** 相对基准坐标 Y（ORIGIN_COORDINATE 时有效） */
    public float getOriginY() {
        return oy;
    }

    /** 相对基准坐标 Z（ORIGIN_COORDINATE 时有效） */
    public float getOriginZ() {
        return oz;
    }

    /** 相对基准结构 id（ORIGIN_STRUCTURE 时有效），否则 null */
    public String getOriginStructure() {
        return originStructure;
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
