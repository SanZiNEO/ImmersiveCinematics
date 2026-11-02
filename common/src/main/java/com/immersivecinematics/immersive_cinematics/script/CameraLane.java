package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.api.CameraState;

/**
 * 一条画面 lane 的脚本侧快照：相机六参数 + 产出它的片段 + 片段内本地时间 + 合成参数快照。
 *
 * <p>合成参数（{@code opacity} / {@code dest} / {@code source} / 遮罩）的来源分两种：</p>
 * <ul>
 *   <li>{@code composite != null}：新格式片段，参数已由相机求值器从同一 {@code SampledClip} 算出
 *       （与六参数同一时刻），渲染侧直接读取，不再重新插值 / 采样；</li>
 *   <li>{@code composite == null}：旧格式片段，渲染侧据 {@code clip} + {@code clipLocalTime}
 *       按旧插值器取值（与相机状态同一时刻、同一 hold / loop 语义），像素不变。</li>
 * </ul>
 *
 * @param state         相机六参数快照
 * @param clip          产出该 lane 的片段（旧格式合成参数的来源）
 * @param clipLocalTime 片段内本地时间（秒，double 精度）
 * @param composite     新格式合成参数快照；旧格式 = {@code null}
 */
public record CameraLane(CameraState state, Clip clip, double clipLocalTime, CameraLaneComposite composite) {
}
