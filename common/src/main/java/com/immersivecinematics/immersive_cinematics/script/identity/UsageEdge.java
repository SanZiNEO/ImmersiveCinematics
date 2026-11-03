package com.immersivecinematics.immersive_cinematics.script.identity;

import java.util.Objects;

/**
 * 一条使用边：{@code 宿主路径} 以 {@code kind} 的方式引用了 {@code ref} 指向的片段。
 *
 * <p>宿主路径是 JSON / 结构定位串（如 {@code timeline.tracks[0].clips[2]}、
 * {@code rule_bindings[1].clip}、{@code paths[0]}），用于校验期报错与运行期诊断。</p>
 */
public record UsageEdge(UsageKind kind, String hostPath, ClipRef ref) {

    public UsageEdge {
        Objects.requireNonNull(kind, "kind 不能为 null");
        if (hostPath == null || hostPath.isBlank()) {
            throw new IllegalArgumentException("hostPath 不能为空");
        }
        Objects.requireNonNull(ref, "ref 不能为 null");
    }
}
