package com.immersivecinematics.immersive_cinematics.script.path;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.List;

/**
 * 脚本级路径绑定的运行时来源：按片段给出作用于该片段的绑定列表。
 *
 * <p>本轮只定义消费侧 seam；脚本级 {@code paths[]} 的解析、校验与「片段 → 绑定」索引归 I-4，
 * 届时由 I-4 实现本接口并接到 {@code ObjectCameraEvaluator#setPathBindingSource}。</p>
 *
 * <p>返回的列表在播放期必须只读；脚本替换 = 重建来源（{@link PathBindingResolver#clear()} 由
 * 播放器/求值器在重置时调用）。</p>
 */
public interface PathBindingSource {

    /**
     * @param clip 目标片段
     * @return 作用于该片段的绑定列表；无绑定 = 空列表（不能返回 {@code null}）
     */
    List<PathBinding> bindingsFor(Clip clip);

    /** 空来源：任何片段都没有路径绑定。 */
    PathBindingSource NONE = clip -> List.of();
}
