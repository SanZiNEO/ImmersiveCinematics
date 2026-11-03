package com.immersivecinematics.immersive_cinematics.script.path;

import com.immersivecinematics.immersive_cinematics.math.path.AxisConstraint;
import com.immersivecinematics.immersive_cinematics.math.path.PathSpec;
import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.Objects;

/**
 * 脚本级 {@code paths[]} 的一条路径定义（{@code path-collection.md} §3.1 / {@code ROUND-I-4-MAP} §1.6）：
 * 目标片段 + 端点区间 + 轴集合 + 几何 + 时间映射。
 *
 * <p>与运行期 {@link PathBinding} 的区别：本记录保留脚本 id（用于报错 / 编辑器 / 使用边反查），
 * 端点已在解析期归一化成目标片段 {@code position.x/y/z} 的节点时间；运行期由
 * {@link PathCollectionSource} 逐条映射为 {@link PathBinding}。</p>
 *
 * <p><b>{@link AxisConstraint} 在这里的含义是「路径覆盖该轴」</b>（{@code true} = 该轴走路径），
 * 与 {@link PathBinding} 同一口径，与 {@code math.path.AxisConstraint} 自身的约束语义相反。</p>
 *
 * @param id       路径 id（脚本内唯一，匹配 {@code ^[A-Za-z0-9_]{1,64}$}）
 * @param target   目标片段（不可为 null）
 * @param from     端点 A 的片段局部时间（秒，必须小于 {@code to}）
 * @param to       端点 B 的片段局部时间（秒）
 * @param axes     路径覆盖的轴（至少一项，不可为 null）
 * @param geometry 路径形状（不可为 null）
 * @param timing   时间映射（{@code null} = 线性）
 */
public record PathEntry(String id, Clip target, float from, float to, AxisConstraint axes,
                        PathSpec geometry, PathTiming timing) {

    public PathEntry {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("路径条目：id 不能为空");
        }
        Objects.requireNonNull(target, "路径条目：" + id + " 的 target 不能为 null");
        if (!(from < to)) {
            throw new IllegalArgumentException("路径条目：" + id + " 的 from 必须小于 to（from="
                    + from + ", to=" + to + "）");
        }
        Objects.requireNonNull(axes, "路径条目：" + id + " 的 axes 不能为 null");
        if (!axes.x() && !axes.y() && !axes.z()) {
            throw new IllegalArgumentException("路径条目：" + id + " 的 axes 至少要选中一个轴（x/y/z 不能全为 false）");
        }
        Objects.requireNonNull(geometry, "路径条目：" + id + " 的 geometry 不能为 null");
        if (timing == null) {
            timing = PathTiming.linear();
        }
    }

    /** 目标片段 id（未命名片段为 {@code null}；解析期已要求路径目标必须可解析，故正常路径恒有 id）。 */
    public String targetId() {
        return target.scriptClipId();
    }
}
