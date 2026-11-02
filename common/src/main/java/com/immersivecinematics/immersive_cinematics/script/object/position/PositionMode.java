package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 位置模式：{@link #ABSOLUTE} = 三轴直接是世界坐标（无点源）；{@link #RELATIVE} = 点源 + 偏移。
 *
 * <p>与 {@code position_space} 的合法组合由 validator 保证（{@code absolute} 必须配 {@code world}、
 * {@code position_origin} 为空）；求值层只按本枚举分派，不做冲突修复。</p>
 */
public enum PositionMode { RELATIVE, ABSOLUTE }
