package com.immersivecinematics.immersive_cinematics.media.video;

import java.nio.file.Path;

/**
 * 视频元数据快照（由 ffprobe 探测得到）。
 *
 * @param path            视频文件路径
 * @param width           源视频像素宽（> 0）
 * @param height          源视频像素高（> 0）
 * @param fps             平均帧率（> 0；探测不出时回落 25）
 * @param durationSeconds 时长秒数；未知为 0
 * @param hasAudio        是否含至少一条音频流
 */
public record VideoMetadata(Path path, int width, int height, double fps,
                            double durationSeconds, boolean hasAudio) {
}
