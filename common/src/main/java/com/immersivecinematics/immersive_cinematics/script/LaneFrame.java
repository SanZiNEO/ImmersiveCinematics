package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;

/**
 * 一条画面 lane 的渲染侧完整快照：相机快照 + 产出它的 CAMERA 轨序号 + 该 lane 的调色参数 + 渲染维度。
 * {@code cameraTrackIndex} 按时间轴出现顺序 0 起（其它轨不占号）；{@code laneAdjust} 只承载
 * RGB（透明度在合成之后调控），{@code null} = 不跑调色 pass。
 * 每条 lane 各按自己的片段本地时间采样（重叠窗口下同一轨可产出多条 lane），由 {@link ScriptPlayer#collectCameraLanes()} 每帧按绘制顺序产出。
 *
 * @param lane             相机快照（六参数 + 片段 + 片段内本地时间）
 * @param cameraTrackIndex 产出该 lane 的 CAMERA 轨序号（0 起）
 * @param laneAdjust       该 lane 的调色参数；{@code null} = 无调色
 * @param dimension        该 lane 的渲染维度（来自片段 {@code dimension}）；空串 = 玩家当前维度。
 *                         非空 = 客户端从该维度渲染这一条 lane（世界 / 实体数据由服务端流式提供），
 *                         玩家本身不跨维度——这里没有任何服务端传送语义。
 */
public record LaneFrame(CameraLane lane, int cameraTrackIndex, ColorAdjustParams laneAdjust, String dimension) {
}
