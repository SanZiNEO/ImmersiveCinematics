package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 一条显式执行依赖：{@code dependent} 按 {@code kind} 的语义等待 {@code prerequisite}。
 *
 * <p>只描述调用方声明的依赖边；不表示时间轴上的相邻关系，也不推断同轨后续片段。</p>
 */
public record ExecutionDependency(Clip dependent, Clip prerequisite, DependencyKind kind) {
}
