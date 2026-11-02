package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 注视目标形态：{@link #PLAYER} 玩家激活位置 / {@link #COORDINATE} 固定坐标（或结构中心）/
 * {@link #ENTITY} 实体包围盒内取点 / {@link #BLOCK} 就近搜索的方块中心。
 */
public enum LookAtKind { PLAYER, COORDINATE, ENTITY, BLOCK }
