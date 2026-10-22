package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.api.CameraState;

/**
 * 一条画面 lane 的脚本侧快照：相机六参数 + 产出它的片段 + 片段内本地时间。
 * 合成参数（{@code opacity} / {@code dest} / {@code source}）是 CAMERA clip 的关键帧字段，不在本记录求值——
 * 渲染侧据 {@code clip} + {@code clipLocalTime} 按同一插值器取值，与相机状态同一时刻、同一 hold / loop 语义。
 *
 * @param state         相机六参数快照
 * @param clip          产出该 lane 的片段（合成参数的来源）
 * @param clipLocalTime 片段内本地时间（秒）
 */
public record CameraLane(CameraState state, Clip clip, float clipLocalTime) {
}
