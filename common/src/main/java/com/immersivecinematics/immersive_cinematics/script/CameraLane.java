package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;

/**
 * 一条画面 lane 的脚本侧快照：相机六参数 + 产出它的片段 + 该片段的采样结果 + 合成参数快照。
 *
 * <p>合成参数（{@code opacity} / {@code dest} / {@code source} / 遮罩）已由相机求值器从同一
 * {@link SampledClip} 算出（与六参数同一时刻），渲染侧直接读取，不再重新插值 / 采样；
 * {@code composite == null} = 本帧无求值结果，渲染侧回落 {@link CameraLaneComposite#DEFAULT}。</p>
 *
 * @param state     相机六参数快照
 * @param clip      产出该 lane 的片段（租约对账 / 轨内下标定位需要它）
 * @param sample    该片段本帧的采样结果（调色对象采样直接复用）
 * @param composite 合成参数快照；{@code null} = 本帧无求值结果
 */
public record CameraLane(CameraState state, Clip clip, SampledClip sample, CameraLaneComposite composite) {
}
