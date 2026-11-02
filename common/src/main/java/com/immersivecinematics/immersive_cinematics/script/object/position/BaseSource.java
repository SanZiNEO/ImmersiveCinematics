package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 朝向单轴基准来源（{@code yaw_base} / {@code pitch_base}）：
 * {@link #WORLD} = 基准 0；{@link #ENTITY} = 实体身体朝向水平角 / 视线俯仰；
 * {@link #LINE} = 两端点连线方向（水平角 / 垂直角）。
 *
 * <p>两轴独立：可一轴 entity、另一轴 world；{@code yaw_lock} / {@code pitch_lock} 非 {@code null} 时
 * 替换该轴基准，{@code yaw} / {@code pitch} 偏移仍叠加。</p>
 */
public enum BaseSource { WORLD, ENTITY, LINE }
