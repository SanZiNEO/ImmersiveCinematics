package com.immersivecinematics.immersive_cinematics.script.path;

import com.immersivecinematics.immersive_cinematics.math.path.AxisConstraint;
import com.immersivecinematics.immersive_cinematics.math.path.PathSpec;
import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.Objects;

/**
 * 一条脚本级路径绑定：目标片段 + 端点区间（相邻位置关键帧的局部时间）+ geometry + timing
 * （{@code path-collection.md} §3.2）。
 *
 * <p>运行时由 {@link PathBindingResolver} 消费：{@code position.x/y/z} 中 {@link #axes()} 选中的轴
 * 取路径求值结果，未选轴保留 {@code SampledProperty} 的采样值；{@code orient=tangent} 的切线同样按
 * 选轴合成（{@code track-camera.md} §3.2 末段 / §3.3）。</p>
 *
 * <p><b>{@link AxisConstraint} 在本记录中的含义是「路径覆盖该轴」</b>（{@code true} = 选用路径分量），
 * 与 {@code math.path.AxisConstraint} 自身「{@code true} = 保留曲线分量」的约束语义相反——这里只是
 * 三布尔载体，不会传给 {@link PathSpec#constrain(AxisConstraint)}。</p>
 *
 * @param target    目标片段（不可为 null）
 * @param startTime 端点 A 的片段局部时间（秒）
 * @param endTime   端点 B 的片段局部时间（必须晚于 {@code startTime}）
 * @param geometry  路径形状（不可为 null）
 * @param axes      路径覆盖的轴（至少选一个；不可为 null）
 * @param timing    时间映射（{@code null} = 线性）
 */
public record PathBinding(Clip target, float startTime, float endTime, PathSpec geometry,
                          AxisConstraint axes, PathTiming timing) {

    public PathBinding {
        Objects.requireNonNull(target, "路径绑定：target 不能为 null");
        if (!(startTime < endTime)) {
            throw new IllegalArgumentException("路径绑定：startTime 必须小于 endTime（startTime="
                    + startTime + ", endTime=" + endTime + "）");
        }
        Objects.requireNonNull(geometry, "路径绑定：geometry 不能为 null");
        Objects.requireNonNull(axes, "路径绑定：axes 不能为 null");
        if (!axes.x() && !axes.y() && !axes.z()) {
            throw new IllegalArgumentException("路径绑定：axes 至少要选中一个轴（x/y/z 不能全为 false）");
        }
        if (timing == null) {
            timing = PathTiming.linear();
        }
    }
}
