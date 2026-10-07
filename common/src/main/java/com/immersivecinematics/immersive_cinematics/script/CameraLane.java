package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraState;

/**
 * 一条画面 lane 的脚本侧快照 —— 相机六参数 + 产出它的片段 + 该片段内的本地时间。
 *
 * <p>画面 lane 的<b>合成参数</b>（{@code opacity} / {@code dest} / {@code source}）是 CAMERA clip
 * 的关键帧字段（见 docs/SCRIPT_FORMAT.md §4「合成参数」），不属于相机六参数，所以不在这里求值：
 * 本记录只给出「哪一段关键帧、在哪一时刻」，渲染侧（{@code client.lane.ScriptLaneDriver}）据此
 * 按同一个插值器取该时刻的合成参数。</p>
 *
 * <p>{@code clipLocalTime} 与相机六参数求值用的是同一个值（{@code globalTime − clip.start_time}），
 * 因此合成参数与相机状态在同一时刻、同一 hold / loop 语义下取值。</p>
 *
 * @param state         该 lane 的相机六参数快照
 * @param clip          产出该 lane 的片段（合成参数的来源）
 * @param clipLocalTime 该片段内的本地时间（秒）
 */
public record CameraLane(CameraState state, Clip clip, float clipLocalTime) {
}
