package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 点源偏移的表达空间：{@link #WORLD} = 世界轴 {@code (x, y, z)}；
 * {@link #BASE} = 基准坐标系，{@code (x, y, z)} 读作 {@code (fwd, up, right)}。
 *
 * <p>基准坐标系的轴向由方向源决定（{@link FacingSpec}，见 {@code PositionEvaluator#frame}）：
 * 上轴固定世界竖直，前轴 / 右轴取水平面内分量。</p>
 */
public enum PointSpace { WORLD, BASE }
