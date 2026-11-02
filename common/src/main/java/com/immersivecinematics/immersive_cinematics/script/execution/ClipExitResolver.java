package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 片段默认出口策略表（播放实例级，不写回脚本）。
 *
 * <p>解析优先级：入口显式覆盖 &gt; 片段默认策略 &gt; {@link ClipExitPolicy#CONTINUE}。
 * 用 {@link IdentityHashMap} 是因为 {@link Clip} 以对象身份为键（脚本解析后同一片段是同一实例）。</p>
 */
public final class ClipExitResolver {

    private final Map<Clip, ClipExitPolicy> defaults = new IdentityHashMap<>();

    /** 设置片段默认出口策略（覆盖旧值）。 */
    public void setDefault(Clip clip, ClipExitPolicy policy) {
        if (clip == null) {
            throw new IllegalArgumentException("片段不能为 null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("默认出口策略不能为 null");
        }
        defaults.put(clip, policy);
    }

    /** 清空全部默认策略。 */
    public void clear() {
        defaults.clear();
    }

    /** 片段默认策略；未设置或 {@code clip == null} → {@link ClipExitPolicy#CONTINUE}。 */
    public ClipExitPolicy defaultOf(Clip clip) {
        if (clip == null) {
            return ClipExitPolicy.CONTINUE;
        }
        ClipExitPolicy policy = defaults.get(clip);
        return policy != null ? policy : ClipExitPolicy.CONTINUE;
    }

    /** 解析实际生效策略：入口覆盖优先，其次片段默认，最后 {@code CONTINUE}。 */
    public ClipExitPolicy resolve(Clip clip, ClipExitPolicy entryOverride) {
        if (entryOverride != null) {
            return entryOverride;
        }
        return defaultOf(clip);
    }
}
