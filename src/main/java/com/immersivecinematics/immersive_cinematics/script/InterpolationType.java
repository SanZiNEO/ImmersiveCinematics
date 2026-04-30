package com.immersivecinematics.immersive_cinematics.script;

/**
 * 插值曲线类型枚举 — 控制关键帧间进度 t 如何映射到 adjustedT
 * <p>
 * 在片段级设置，描述整段镜头的运动风格。
 * 关键帧只存储目标值，插值曲线由播放器每帧实时计算。
 * <p>
 * 对应公式：
 * <ul>
 *   <li>LINEAR: t</li>
 *   <li>SMOOTH: 3t² - 2t³ (Hermite)</li>
 *   <li>EASE_IN: t²</li>
 *   <li>EASE_OUT: 1 - (1-t)²</li>
 *   <li>EASE_IN_OUT: 两段二次曲线</li>
 * </ul>
 */
public enum InterpolationType {

    /** 线性 — 匀速，机械感 */
    LINEAR,

    /** 平滑 — 缓入缓出，自然感 (3t² - 2t³) */
    SMOOTH,

    /** 缓入 — 起步慢，结束快 (t²) */
    EASE_IN,

    /** 缓出 — 起步快，结束慢 (1 - (1-t)²) */
    EASE_OUT,

    /** 缓入缓出 — 两端慢，中间快 (两段二次曲线) */
    EASE_IN_OUT
}
