package com.immersivecinematics.immersive_cinematics.util;

/**
 * 音频常量。
 *
 * 相机听者模式下电影相机可能离玩家很远，而原版普通音效的服务端广播 / 衰减半径只有 16 格，
 * 会出现生物音缺失、环境音仍在的现象；这里统一放宽到 128 格，服务端与客户端共用。
 */
public final class AudioConstants {

    /** 相机听者模式下的最小声音衰减/广播距离（格） */
    public static final float CAMERA_ATTENUATION_DISTANCE = 128.0F;

    private AudioConstants() {}
}
