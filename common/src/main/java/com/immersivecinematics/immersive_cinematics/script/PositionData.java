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
    /** 相对基准：玩家附近搜索的方块（relative_origin 填 block:id[:radius]） */
    public static final int ORIGIN_BLOCK = 3;

    /** block 基准的默认搜索半径（格） */
    public static final int DEFAULT_BLOCK_RADIUS = 16;

    /** 坐标模式：true=相对偏移(dx/dy/dz)，false=绝对坐标(x/y/z) */
    private final boolean relative;

    /** X 分量（绝对坐标）或 DX 分量（相对偏移） */
    private final float x;

    /** Y 分量（绝对坐标）或 DY 分量（相对偏移） */
    private final float y;

    /** Z 分量（绝对坐标）或 DZ 分量（相对偏移） */
    private final float z;

    /** 相对基准类型（仅 relative 有意义）：ORIGIN_PLAYER / ORIGIN_COORDINATE / ORIGIN_STRUCTURE / ORIGIN_BLOCK */
    private final int originType;

    /** 相对基准坐标（ORIGIN_COORDINATE 时有效） */
    private final float ox;
    private final float oy;
    private final float oz;

    /** 相对基准结构 id（ORIGIN_STRUCTURE 时有效） */
    private final String originStructure;

    /** 相对基准方块 id（ORIGIN_BLOCK 时有效，如 "minecraft:obsidian"） */
    private final String originBlockId;

    /** 相对基准方块搜索半径（ORIGIN_BLOCK 时有效，格） */
    private final int originBlockRadius;

    /** 是否为"基准空间坐标系"偏移（fwd/up/right 相对基准朝向，仅实体/玩家基准有效） */
    private final boolean facingRelative;

    /** 基准空间系：沿基准朝向 前后 的偏移（正=前 负=后） */
    private final float fwd;

    /** 基准空间系：沿基准朝向 上下 的偏移（正=上 负=下） */
    private final float up;

    /** 基准空间系：沿基准朝向 左右 的偏移（正=右 负=左） */
    private final float right;

    /** 基准空间系：y(上下)轴是否跟随俯仰——"view"=全三维（默认），"world"=保持世界竖直 */
    private final String upAxis;

    /**
     * 相对模式构造器（基准 = 玩家激活位置）
     *
     * @param dx 相对于基准的 X 偏移
     * @param dy 相对于基准的 Y 偏移
     * @param dz 相对于基准的 Z 偏移
     * @return 相对模式的 PositionData
     */
    public static PositionData relative(float dx, float dy, float dz) {
        return new PositionData(true, dx, dy, dz, ORIGIN_PLAYER, 0f, 0f, 0f, null, null, 0,
                false, 0f, 0f, 0f, "world");
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
        return new PositionData(true, dx, dy, dz, ORIGIN_COORDINATE, ox, oy, oz, null, null, 0,
                false, 0f, 0f, 0f, "world");
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
        return new PositionData(true, dx, dy, dz, ORIGIN_STRUCTURE, 0f, 0f, 0f, structureId, null, 0,
                false, 0f, 0f, 0f, "world");
    }

    /**
     * 相对模式构造器（基准 = 玩家附近搜索到的方块）
     *
     * @param dx       相对于基准的 X 偏移
     * @param dy       相对于基准的 Y 偏移
     * @param dz       相对于基准的 Z 偏移
     * @param blockId  基准方块 id（如 {@code "minecraft:obsidian"}）
     * @param radius   搜索半径（格）
     */
    public static PositionData relativeToBlock(float dx, float dy, float dz, String blockId, int radius) {
        return new PositionData(true, dx, dy, dz, ORIGIN_BLOCK, 0f, 0f, 0f, null, blockId, radius,
                false, 0f, 0f, 0f, "world");
    }

    /**
     * 基准空间坐标系偏移（fwd/up/right 相对基准朝向）——仅玩家/实体基准有效。
     *
     * @param fwd    沿基准朝向 前后（正=前 负=后）
     * @param up     沿基准朝向 上下（正=上 负=下）
     * @param right  沿基准朝向 左右（正=右 负=左）
     * @param upAxis y 轴开关："view"=up 随俯仰全三维（默认）；"world"=up 保持世界竖直
     */
    public static PositionData facing(float fwd, float up, float right, String upAxis) {
        return new PositionData(true, 0f, 0f, 0f, ORIGIN_PLAYER, 0f, 0f, 0f, null, null, 0,
                true, fwd, up, right, upAxis != null ? upAxis : "view");
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
        return new PositionData(false, x, y, z, ORIGIN_PLAYER, 0f, 0f, 0f, null, null, 0,
                false, 0f, 0f, 0f, "world");
    }

    private PositionData(boolean relative, float x, float y, float z, int originType, float ox, float oy, float oz,
                         String originStructure, String originBlockId, int originBlockRadius,
                         boolean facingRelative, float fwd, float up, float right, String upAxis) {
        this.relative = relative;
        this.x = x;
        this.y = y;
        this.z = z;
        this.originType = originType;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.originStructure = originStructure;
        this.originBlockId = originBlockId;
        this.originBlockRadius = originBlockRadius;
        this.facingRelative = facingRelative;
        this.fwd = fwd;
        this.up = up;
        this.right = right;
        this.upAxis = upAxis;
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

    /** 相对基准是否为搜索到的方块（ORIGIN_BLOCK） */
    public boolean isOriginBlock() {
        return originType == ORIGIN_BLOCK;
    }

    /** 相对基准方块 id（ORIGIN_BLOCK 时有效），否则 null */
    public String getOriginBlockId() {
        return originBlockId;
    }

    /** 相对基准方块搜索半径（ORIGIN_BLOCK 时有效） */
    public int getOriginBlockRadius() {
        return originBlockRadius > 0 ? originBlockRadius : DEFAULT_BLOCK_RADIUS;
    }

    /** 是否为基准空间坐标系偏移（fwd/up/right 相对基准朝向） */
    public boolean isFacingRelative() {
        return facingRelative;
    }

    /** 沿基准朝向 前后 偏移（正=前 负=后） */
    public float getFwd() {
        return fwd;
    }

    /** 沿基准朝向 上下 偏移（正=上 负=下） */
    public float getUp() {
        return up;
    }

    /** 沿基准朝向 左右 偏移（正=右 负=左） */
    public float getRight() {
        return right;
    }

    /** y 轴开关："view"=up 随俯仰全三维（默认）；"world"=up 保持世界竖直 */
    public String getUpAxis() {
        return upAxis != null ? upAxis : "view";
    }

    /**
     * 解析 "block:id" / "block:id:radius" 字符串 → [blockId, radius]。
     * id 本身可含冒号（如 minecraft:obsidian），故取最后一段为数字时才算显式半径。
     * 缺省半径用 {@link #DEFAULT_BLOCK_RADIUS}。
     */
    public static String[] parseBlockOriginString(String origin) {
        String body = origin.substring("block:".length());
        int lastColon = body.lastIndexOf(':');
        String radiusPart = lastColon >= 0 ? body.substring(lastColon + 1) : "";
        if (!radiusPart.isEmpty() && radiusPart.chars().allMatch(Character::isDigit)) {
            int radius = Integer.parseInt(radiusPart);
            if (radius > 0) {
                return new String[]{body.substring(0, lastColon), radiusPart};
            }
        }
        return new String[]{body, String.valueOf(DEFAULT_BLOCK_RADIUS)};
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
