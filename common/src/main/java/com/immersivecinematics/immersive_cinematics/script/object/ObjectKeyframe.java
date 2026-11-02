package com.immersivecinematics.immersive_cinematics.script.object;

/**
 * 单个属性的关键帧：片段局部时间 + 该属性在这一帧的值。
 *
 * <p>关键帧只属于一个属性，不再携带全字段快照。{@code time} 相对片段起点；
 * 有限性、单调递增、范围内过滤由 {@link SampledProperty} 在构造与编译期负责。</p>
 *
 * @param time 片段局部时间（秒）
 * @param value 该属性的值；{@code nullable_float} 允许 {@code null}
 */
public record ObjectKeyframe(float time, Object value) {
}
