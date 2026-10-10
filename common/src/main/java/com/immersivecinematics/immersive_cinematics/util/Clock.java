package com.immersivecinematics.immersive_cinematics.util;

/**
 * 单调秒时钟：运行时统一的时间读数口径（秒，double）。
 *
 * 读数单调不减——暂停 = 冻结在当前值，不回退。实现：游戏共享虚拟时钟 {@link GameClock}、
 * 预览播放头 {@link PreviewClock}；测试可注入假时钟（单方法接口，方法引用 / lambda 即可实现）。
 */
@FunctionalInterface
public interface Clock {

    /** 当前读数（秒）。 */
    double seconds();
}
