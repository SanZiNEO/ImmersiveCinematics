package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;

/**
 * 一条画面 lane 的渲染侧完整快照：相机快照 + 产出它的 CAMERA 轨序号 + 该 lane 的调色参数。
 * {@code cameraTrackIndex} 按时间轴出现顺序 0 起（其它轨不占号）；{@code laneAdjust} 只承载
 * RGB（透明度在合成之后调控），{@code null} = 不跑调色 pass。
 * 每条 lane 各按自己的片段本地时间采样（重叠窗口下同一轨可产出多条 lane），由 {@link ScriptPlayer#collectCameraLanes()} 每帧按绘制顺序产出。
 *
 * @param lane             相机快照（六参数 + 片段 + 片段内本地时间）
 * @param cameraTrackIndex 产出该 lane 的 CAMERA 轨序号（0 起）
 * @param laneAdjust       该 lane 的调色参数；{@code null} = 无调色
 */
public record LaneFrame(CameraLane lane, int cameraTrackIndex, ColorAdjustParams laneAdjust) {
}
