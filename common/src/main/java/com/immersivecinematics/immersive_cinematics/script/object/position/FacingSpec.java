package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 方向源规格（{@code facing_source} + 两个载荷）：决定 {@code position_space=base} 时基准系的轴向。
 *
 * <p>载荷约束（validator 保证，求值层按显式来源分派）：{@code auto} 下 {@link #target()} 与
 * {@link #entity()} 同时非空 = 冲突；显式 {@link FacingSource#WORLD} 忽略两个载荷；
 * {@link FacingSource#ENTITY} 只用 {@link #entity()}（缺省回落原点实体 / 玩家）；
 * {@link FacingSource#LINE} 只用 {@link #target()}。</p>
 *
 * <p>{@code entity} 只取实体身体水平朝向（body yaw），视线俯仰属于 {@code yaw_base}/{@code pitch_base}；
 * {@code entity} 始终实时，没有冻结开关（要固定方向用 {@code facing_source=line} +
 * {@code facing_target_freeze=true}）。</p>
 */
public record FacingSpec(FacingSource source, PointSourceSpec target, String entity) {

    public FacingSpec {
        source = source == null ? FacingSource.AUTO : source;
        if (entity != null && entity.isEmpty()) {
            entity = null;
        }
    }

    /** 缺省：按载荷与原点来源回落（{@link FacingSource#AUTO}）。 */
    public static FacingSpec auto() {
        return new FacingSpec(FacingSource.AUTO, null, null);
    }

    /** 世界轴：{@code fwd=+Z}、{@code right=-X}、{@code up=+Y}。 */
    public static FacingSpec world() {
        return new FacingSpec(FacingSource.WORLD, null, null);
    }

    /** 实体身体水平朝向（{@code selector} 为 {@code null} = 回落原点实体 / 玩家）。 */
    public static FacingSpec entity(String selector) {
        return new FacingSpec(FacingSource.ENTITY, null, selector);
    }

    /** 原点 → 目标点连线（水平面方向）。 */
    public static FacingSpec line(PointSourceSpec target) {
        return new FacingSpec(FacingSource.LINE, target, null);
    }
}
