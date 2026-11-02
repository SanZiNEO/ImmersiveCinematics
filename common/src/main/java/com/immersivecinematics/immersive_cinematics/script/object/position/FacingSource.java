package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 方向源（{@code position_space=base} 的基准系轴向来源）：
 * {@link #AUTO} 按载荷与原点来源回落；{@link #WORLD} / {@link #ENTITY} / {@link #LINE} 为显式选择。
 *
 * <p>方向源只有一个；{@code facing_source} 决定哪个载荷生效，未选中的载荷不参与。
 * 显式来源不可用时按空片段处理，不静默回落到 {@code auto} 或另一个载荷。</p>
 */
public enum FacingSource { AUTO, WORLD, ENTITY, LINE }
