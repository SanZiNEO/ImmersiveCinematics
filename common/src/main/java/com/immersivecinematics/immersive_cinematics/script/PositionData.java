package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 位置数据：关键帧的位置通道 = 位置点源 + 偏移，或世界绝对坐标。
 *
 * 三种形态（由 position 对象的字段判据，{@code position_mode} 不参与判据）：
 * 世界轴偏移 {@code dx/dy/dz} = 相对位置点源的世界轴偏移；基准坐标系偏移 {@code fwd/up/right}（配 {@code up_axis}）
 * = 相对本帧基准坐标系的偏移；绝对 {@code x/y/z} = 世界坐标（无点源、无偏移）。
 *
 * 位置点源 = 关键帧字段 {@code facing_origin}（五形态，见 {@link #originKindOf}），载荷存 origin* 字段；
 * 基准坐标系的前轴 = 关键帧字段 {@code facing_target}（空 = 点源自身朝向）。
 * 单位与坐标空间：世界空间方块坐标；偏移与坐标同单位。
 */
public class PositionData {

    /** 相对基准：玩家激活位置（默认） */
    public static final int ORIGIN_PLAYER = 0;
    /** 相对基准：固定坐标（facing_origin_x/y/z） */
    public static final int ORIGIN_COORDINATE = 1;
    /** 相对基准：结构中心（facing_origin 填结构 id） */
    public static final int ORIGIN_STRUCTURE = 2;
    /** 相对基准：玩家附近搜索的方块（facing_origin 填 block:id[:radius]） */
    public static final int ORIGIN_BLOCK = 3;
    /** 相对基准：实体选择器（facing_origin 填 selector）——两种偏移表达空间都可用。 */
    public static final int ORIGIN_SELECTOR = 4;

    /** block 基准的默认搜索半径（格） */
    public static final int DEFAULT_BLOCK_RADIUS = 16;

    /** 点源形态判据的「无点源」结果（空串 / {@code null}） */
    public static final int NO_ORIGIN = -1;

    /**
     * 点源形态判据（唯一一份，五形态）：形态字符串 → {@link #ORIGIN_PLAYER} / {@link #ORIGIN_COORDINATE} /
     * {@link #ORIGIN_STRUCTURE} / {@link #ORIGIN_BLOCK} / {@link #ORIGIN_SELECTOR}。
     * {@code "@…"} / {@code "uuid:…"} = 实体选择器；{@code "block:…"} = 方块；{@code "player"} = 玩家激活位置；
     * {@code "coordinate"} = 固定坐标；其余非空字符串 = 结构 id；空串 / {@code null} = {@link #NO_ORIGIN}（无点源）。
     *
     * @param value 形态字符串（脚本字段值 / 位置侧携带的来源字符串）
     * @return {@link #ORIGIN_*} 之一，或 {@link #NO_ORIGIN}
     */
    public static int originKindOf(String value) {
        if (value == null || value.isEmpty()) return NO_ORIGIN;
        if (value.startsWith("@") || value.startsWith("uuid:")) return ORIGIN_SELECTOR;
        if (value.startsWith("block:")) return ORIGIN_BLOCK;
        if ("player".equals(value)) return ORIGIN_PLAYER;
        if ("coordinate".equals(value)) return ORIGIN_COORDINATE;
        return ORIGIN_STRUCTURE;
    }

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

    /** 基准坐标系偏移的基准点实体选择器（ORIGIN_SELECTOR 时有效，如 "@e[type=…]"） */
    private final String originSelector;

    /** 基准坐标系前轴的目标（facing_target 点源）：前轴 = 位置点源 → 该目标（两点连线方向）；空 = 位置点源自身朝向 */
    private final String facingTarget;

    /** 偏移表达空间：true = 基准坐标系（fwd/up/right），false = 世界轴（dx/dy/dz） */
    private final boolean baseSpace;

    /** 基准坐标系：沿前轴 前后 的偏移（正=前 负=后） */
    private final float fwd;

    /** 基准坐标系：沿上轴 上下 的偏移（正=上 负=下） */
    private final float up;

    /** 基准坐标系：沿右轴 左右 的偏移（正=右 负=左） */
    private final float right;

    /** 垂直面基准："view"=上轴随前轴俯仰（默认），"world"=上轴保持世界竖直 */
    private final String upAxis;

    /**
     * 世界轴偏移（dx/dy/dz），位置点源 = 玩家激活位置（缺省形态）。
     *
     * @param dx 相对位置点源的世界 X 偏移
     * @param dy 相对位置点源的世界 Y 偏移
     * @param dz 相对位置点源的世界 Z 偏移
     */
    public static PositionData relative(float dx, float dy, float dz) {
        return new PositionData(true, dx, dy, dz, ORIGIN_PLAYER, 0f, 0f, 0f, null, null, 0, null,
                false, 0f, 0f, 0f, "world", null);
    }

    /** 世界轴偏移（dx/dy/dz），位置点源 = 固定坐标（{@code facing_origin: "coordinate"}）。 */
    public static PositionData relativeToCoordinate(float dx, float dy, float dz, float ox, float oy, float oz) {
        return new PositionData(true, dx, dy, dz, ORIGIN_COORDINATE, ox, oy, oz, null, null, 0, null,
                false, 0f, 0f, 0f, "world", null);
    }

    /** 世界轴偏移（dx/dy/dz），位置点源 = 结构中心（{@code facing_origin} 填结构 id，如 {@code "minecraft:village"}）。 */
    public static PositionData relativeToStructure(float dx, float dy, float dz, String structureId) {
        return new PositionData(true, dx, dy, dz, ORIGIN_STRUCTURE, 0f, 0f, 0f, structureId, null, 0, null,
                false, 0f, 0f, 0f, "world", null);
    }

    /** 世界轴偏移（dx/dy/dz），位置点源 = 玩家附近搜索到的方块中心（{@code facing_origin} 填 {@code block:id[:radius]}）。 */
    public static PositionData relativeToBlock(float dx, float dy, float dz, String blockId, int radius) {
        return new PositionData(true, dx, dy, dz, ORIGIN_BLOCK, 0f, 0f, 0f, null, blockId, radius, null,
                false, 0f, 0f, 0f, "world", null);
    }

    /** 世界轴偏移（dx/dy/dz），位置点源 = 实体选择器（每帧取实体脚底，动态）。 */
    public static PositionData relativeToEntity(float dx, float dy, float dz, String selector) {
        return new PositionData(true, dx, dy, dz, ORIGIN_SELECTOR, 0f, 0f, 0f, null, null, 0,
                selector != null ? selector : "@p",
                false, 0f, 0f, 0f, "world", null);
    }

    /** 基准坐标系偏移（fwd/up/right），位置点源 = 玩家激活位置（脚本激活时玩家所在位置，整场不变）。 */
    public static PositionData facingToPlayer(float fwd, float up, float right, String upAxis) {
        return new PositionData(true, 0f, 0f, 0f, ORIGIN_PLAYER, 0f, 0f, 0f, null, null, 0, null,
                true, fwd, up, right, upAxis != null ? upAxis : "view", null);
    }

    /**
     * 基准坐标系偏移（fwd/up/right），位置点源 = 指定实体选择器（{@code facing_origin}）。
     * 例如以 A 为基准点、A→B 为基准朝向来摆机位（前轴目标见 {@link #withFacingTarget}）。
     *
     * @param originSelector 位置点源实体选择器（如 {@code "@e[type=…]"}）；空 = {@code "@p"}
     * @param fwd            沿前轴 前后（正=前 负=后）
     * @param up             沿上轴 上下（正=上 负=下）
     * @param right          沿右轴 左右（正=右 负=左）
     * @param upAxis         垂直面基准："view"=上轴随前轴俯仰（默认）；"world"=上轴保持世界竖直
     */
    public static PositionData facingToEntity(String originSelector, float fwd, float up, float right, String upAxis) {
        return new PositionData(true, 0f, 0f, 0f, ORIGIN_SELECTOR, 0f, 0f, 0f, null, null, 0,
                originSelector != null ? originSelector : "@p",
                true, fwd, up, right, upAxis != null ? upAxis : "view", null);
    }

    /** 基准坐标系偏移（fwd/up/right），位置点源 = 固定坐标（{@code facing_origin: "coordinate"}）。 */
    public static PositionData facingToCoordinate(float fwd, float up, float right, String upAxis,
                                                 float ox, float oy, float oz) {
        return new PositionData(true, 0f, 0f, 0f, ORIGIN_COORDINATE, ox, oy, oz, null, null, 0, null,
                true, fwd, up, right, upAxis != null ? upAxis : "view", null);
    }

    /** 基准坐标系偏移（fwd/up/right），位置点源 = 结构中心（{@code facing_origin} 填结构 id）。 */
    public static PositionData facingToStructure(float fwd, float up, float right, String upAxis, String structureId) {
        return new PositionData(true, 0f, 0f, 0f, ORIGIN_STRUCTURE, 0f, 0f, 0f, structureId, null, 0, null,
                true, fwd, up, right, upAxis != null ? upAxis : "view", null);
    }

    /** 基准坐标系偏移（fwd/up/right），位置点源 = 玩家附近搜索到的方块中心（{@code facing_origin} 填 {@code block:id[:radius]}）。 */
    public static PositionData facingToBlock(float fwd, float up, float right, String upAxis, String blockId, int radius) {
        return new PositionData(true, 0f, 0f, 0f, ORIGIN_BLOCK, 0f, 0f, 0f, null, blockId, radius, null,
                true, fwd, up, right, upAxis != null ? upAxis : "view", null);
    }

    /**
     * 附上基准坐标系前轴的目标（关键帧字段 {@code facing_target}）：前轴 = 位置点源 → 该目标（两点连线方向）。
     * 用于"以 A 为基准点、A→B 为基准朝向"摆机位，避免基准随 A 自身转头抖动。
     *
     * @param facingTarget 前轴目标点源形态字符串；null / 空 = 位置点源自身朝向
     * @return 附上目标的新 PositionData
     */
    public PositionData withFacingTarget(String facingTarget) {
        return new PositionData(relative, x, y, z, originType, ox, oy, oz, originStructure, originBlockId,
                originBlockRadius, originSelector, baseSpace, fwd, up, right, upAxis, facingTarget);
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
        return new PositionData(false, x, y, z, ORIGIN_PLAYER, 0f, 0f, 0f, null, null, 0, null,
                false, 0f, 0f, 0f, "world", null);
    }

    private PositionData(boolean relative, float x, float y, float z, int originType, float ox, float oy, float oz,
                         String originStructure, String originBlockId, int originBlockRadius, String originSelector,
                         boolean baseSpace, float fwd, float up, float right, String upAxis, String facingTarget) {
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
        this.originSelector = originSelector;
        this.facingTarget = facingTarget;
        this.baseSpace = baseSpace;
        this.fwd = fwd;
        this.up = up;
        this.right = right;
        this.upAxis = upAxis;
    }

    /** 相对基准类型（{@link #ORIGIN_PLAYER} / {@link #ORIGIN_COORDINATE} / {@link #ORIGIN_STRUCTURE} / {@link #ORIGIN_BLOCK} / {@link #ORIGIN_SELECTOR}） */
    public int getOriginType() {
        return originType;
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

    /** 偏移表达空间是否为基准坐标系（true = fwd/up/right 相对基准坐标系；false = 世界轴 dx/dy/dz） */
    public boolean isBaseSpace() {
        return baseSpace;
    }

    /** 位置点源是否为实体选择器形态（facing_origin） */
    public boolean isOriginSelector() {
        return originType == ORIGIN_SELECTOR;
    }

    /** 位置点源实体选择器（facing_origin），非 ORIGIN_SELECTOR 时返回 null */
    public String getOriginSelector() {
        return originSelector;
    }

    /** 基准坐标系前轴目标（facing_target 点源）：前轴 = 位置点源 → 该目标；空 = 位置点源自身朝向 */
    public String getFacingTarget() {
        return facingTarget;
    }

    /** 沿前轴 前后 偏移（正=前 负=后） */
    public float getFwd() {
        return fwd;
    }

    /** 沿上轴 上下 偏移（正=上 负=下） */
    public float getUp() {
        return up;
    }

    /** 沿右轴 左右 偏移（正=右 负=左） */
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
     * 世界轴分量：绝对模式 = 世界坐标，相对模式 = 相对位置点源的世界轴偏移（dx/dy/dz）。
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
