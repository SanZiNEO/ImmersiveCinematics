package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 点源形态（五选一）：位置基准 / 基准朝向目标 / 连线端点 / 注视点共用。
 *
 * <p>字符串判据见 {@link PointKinds#kindOf}；本枚举只做编号 → 形态映射。</p>
 */
public enum PointKind {

    /** 玩家激活位置（脚本激活时玩家所在位置，整场不变）。 */
    WORLD,
    /** 固定坐标（世界空间方块坐标）。 */
    COORDINATE,
    /** 实体选择器：位置 = 实体渲染帧插值位置。 */
    SELECTOR,
    /** 就近搜索的方块中心（{@code block:id[:radius]}）。 */
    BLOCK,
    /** 结构中心（结构 bounding box 中心）。 */
    STRUCTURE;

    /**
     * 形态字符串 → 形态。
     *
     * @param value 形态字符串
     * @return 形态；无点源（空串 / {@code null}）= {@code null}
     */
    public static PointKind fromOrigin(String value) {
        return switch (PointKinds.kindOf(value)) {
            case PointKinds.PLAYER -> WORLD;
            case PointKinds.COORDINATE -> COORDINATE;
            case PointKinds.SELECTOR -> SELECTOR;
            case PointKinds.BLOCK -> BLOCK;
            case PointKinds.STRUCTURE -> STRUCTURE;
            default -> null;
        };
    }
}
