package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 点源形态词汇表：形态字符串 → 形态编号，以及方块形态字符串的解析。
 *
 * <p>形态字符串判据（唯一一份）：{@code "@…"} / {@code "uuid:…"} = 实体选择器；
 * {@code "block:…"} = 方块；{@code "player"} = 玩家激活位置；{@code "coordinate"} = 固定坐标；
 * 其余非空字符串 = 结构 id；空串 / {@code null} = {@link #NO_KIND}（无点源）。</p>
 */
public final class PointKinds {

    /** 玩家激活位置（脚本激活时玩家所在位置，整场不变）。 */
    public static final int PLAYER = 0;
    /** 固定坐标。 */
    public static final int COORDINATE = 1;
    /** 结构中心（结构 bounding box 中心，就近搜索）。 */
    public static final int STRUCTURE = 2;
    /** 就近搜索的方块中心（{@code block:id[:radius]}）。 */
    public static final int BLOCK = 3;
    /** 实体选择器（{@code "@…"} / {@code "uuid:…"}）。 */
    public static final int SELECTOR = 4;

    /** 方块形态的默认搜索半径（格）。 */
    public static final int DEFAULT_BLOCK_RADIUS = 16;

    /** 形态判据的「无点源」结果（空串 / {@code null}）。 */
    public static final int NO_KIND = -1;

    private PointKinds() {}

    /**
     * 形态字符串 → 形态编号。
     *
     * @param value 形态字符串（脚本字段值 / 位置侧携带的来源字符串）
     * @return {@link #PLAYER} / {@link #COORDINATE} / {@link #STRUCTURE} / {@link #BLOCK} / {@link #SELECTOR}
     *         之一，或 {@link #NO_KIND}
     */
    public static int kindOf(String value) {
        if (value == null || value.isEmpty()) return NO_KIND;
        if (value.startsWith("@") || value.startsWith("uuid:")) return SELECTOR;
        if (value.startsWith("block:")) return BLOCK;
        if ("player".equals(value)) return PLAYER;
        if ("coordinate".equals(value)) return COORDINATE;
        return STRUCTURE;
    }

    /**
     * 解析方块形态字符串 → 方块 id + 搜索半径。
     * 接受 {@code "block:id[:radius]"} 与裸 {@code "id[:radius]"}（无前缀时直接解析）。
     * id 本身可含冒号（如 {@code minecraft:obsidian}），故取最后一段为全数字且大于 0 时才算显式半径；
     * 非法 / 缺省半径回落 {@link #DEFAULT_BLOCK_RADIUS}。
     *
     * @param value 方块形态字符串
     * @return 方块 id 与搜索半径
     */
    public static BlockRef parseBlock(String value) {
        String body = value.startsWith("block:") ? value.substring("block:".length()) : value;
        int lastColon = body.lastIndexOf(':');
        String radiusPart = lastColon >= 0 ? body.substring(lastColon + 1) : "";
        if (!radiusPart.isEmpty() && radiusPart.chars().allMatch(Character::isDigit)) {
            int radius = Integer.parseInt(radiusPart);
            if (radius > 0) {
                return new BlockRef(body.substring(0, lastColon), radius);
            }
        }
        return new BlockRef(body, DEFAULT_BLOCK_RADIUS);
    }

    /** 方块形态的解析结果：方块 id + 搜索半径（格）。 */
    public record BlockRef(String blockId, int radius) {}
}
