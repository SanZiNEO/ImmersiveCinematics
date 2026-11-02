package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 朝向模式：{@link #MANUAL} 手动角度（基准 + 关键帧偏移）；{@link #TANGENT} 由路径切线决定，
 * {@code yaw_offset} / {@code pitch_offset} 只在本模式且没有 {@code look_at} 时读取。
 */
public enum Orient { MANUAL, TANGENT }
