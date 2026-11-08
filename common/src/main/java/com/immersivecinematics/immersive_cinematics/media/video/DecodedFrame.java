package com.immersivecinematics.immersive_cinematics.media.video;

/**
 * 一帧解码结果。
 *
 * 像素格式固定 RGBA8，{@code rgba.length == decodeWidth * decodeHeight * 4}；
 * 行序自顶向下（第 0 行 = 画面顶部），可直接作为 OpenGL 纹理上传源（上传由上层负责）。
 *
 * @param index 全局帧号：{@code round(localTimeSeconds * fps) + 本轮第 n 帧}
 * @param rgba  RGBA8 像素缓冲（每帧独立数组，调用方可安全持有）
 */
public record DecodedFrame(long index, byte[] rgba) {
}
