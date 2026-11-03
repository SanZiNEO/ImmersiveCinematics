package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

/**
 * 事实发生时的场景快照：维度 / 群系 / 坐标。
 *
 * <p>字符串为空串表示未知；坐标是事件发生时刻的玩家位置，不是查询时刻的位置。
 */
public record FactScene(String dimension, String biome, double x, double y, double z) {

    public FactScene {
        dimension = dimension == null ? "" : dimension;
        biome = biome == null ? "" : biome;
    }
}
