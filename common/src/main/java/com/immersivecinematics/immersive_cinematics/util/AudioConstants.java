package com.immersivecinematics.immersive_cinematics.util;

/**
 * 音频常量。
 * <p>
 * 相机听者模式下，电影相机可能离玩家很远；原版普通音效的衰减/服务端广播半径只有 16 格，
 * 会导致“生物音没有、环境音有”。这里统一放宽到 128 格，服务和客户端共用。
 */
public final class AudioConstants {

    /** 相机听者模式下的最小声音衰减/广播距离（格） */
    public static final float CAMERA_ATTENUATION_DISTANCE = 128.0F;

    private AudioConstants() {}
}
