package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 朝向求值结果：世界角（度）。{@code yaw} = 水平角（0 = 南，90 = 西，与原版口径一致）；
 * {@code pitch} = 俯仰（0 = 水平，-90 = 正上）；{@code roll} 直接取自规格。
 */
public record OrientationResult(float yaw, float pitch, float roll) {
}
