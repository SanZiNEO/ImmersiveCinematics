package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;

/**
 * 一条画面 lane 的渲染侧完整快照 —— 相机快照 + 产出它的相机轨序号 + 该轨的 lane 级调色参数。
 *
 * <p>{@link CameraLane} 只描述「哪一段关键帧、在哪一时刻」（渲染侧据此取合成参数 opacity /
 * dest / source）；本记录把渲染侧还需要知道的另外两件事一并带上：</p>
 * <ul>
 *   <li>{@code cameraTrackIndex} —— 该 lane 属于时间轴里第几条 <b>CAMERA 轨</b>（0 起，按出现顺序；
 *       OVERLAY / AUDIO 等其它轨不占号）。lane 级 ADJUST 轨道按这个序号定位目标
 *       （见 {@link AdjustTrackPlayer} 的 {@code scope=lane} 与 clip 的 {@code lane} 字段——同一编号）。</li>
 *   <li>{@code laneAdjust} —— 该相机轨的 lane 级调色参数；{@code null} = 无（走原路径：
 *       lane 渲染完直接进合成，不跑调色 pass）。<b>同一条相机轨本帧产出的所有 lane 共用同一份</b>
 *       （重叠窗口下 {@code captureLowerLanes} 会为同一轨产出多条 lane）。</li>
 * </ul>
 *
 * <p>由 {@link ScriptPlayer#collectCameraLanes()} 每帧按绘制顺序产出，渲染侧
 * （{@code client.lane.ScriptLaneDriver}）逐条注册给 {@code LaneRenderer}。</p>
 *
 * @param lane             相机快照（相机六参数 + 片段 + 片段内本地时间）
 * @param cameraTrackIndex 产出该 lane 的 CAMERA 轨序号（0 起，按 CAMERA 轨出现顺序）
 * @param laneAdjust       该相机轨的 lane 级调色参数；{@code null} = 无 lane 级调色
 */
public record LaneFrame(CameraLane lane, int cameraTrackIndex, ColorAdjustParams laneAdjust) {
}
