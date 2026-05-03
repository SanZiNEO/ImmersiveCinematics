package com.immersivecinematics.immersive_cinematics.script;

/**
 * 插值曲线类型（保留给未来缓动公式）
 * <p>
 * 当前关键帧之间为匀速直线运动，所有值等价于 LINEAR。
 * 后续可通过添加 easing 公式实现缓入缓出。
 */
public enum InterpolationType {
    LINEAR
}
