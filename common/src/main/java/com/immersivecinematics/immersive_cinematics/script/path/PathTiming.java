package com.immersivecinematics.immersive_cinematics.script.path;

import java.util.Objects;

/**
 * 路径时间映射：片段局部时间归一化值 {@code u = (t - start) / (end - start)} → 归一化弧长进度
 * {@code s ∈ [0, 1]}，单调不减（{@code path-collection.md} §3.4）。
 *
 * <p>形状（geometry）与运动（timing）分离：timing 只决定沿路径推进多快，不改变路径形状。
 * 缺省 = 线性。实现必须零分配，并允许在同一实例上被顺序调用（关键帧实现可持有游标）。</p>
 *
 * <p>输入 {@code normalizedTime} 已由调用方归一化；实现仍应自行 clamp（越界、NaN 都收敛到
 * {@code [0, 1]} 端点）。</p>
 */
public interface PathTiming {

    /**
     * @param normalizedTime 归一化片段局部时间（{@code 0} = 段起点，{@code 1} = 段终点）
     * @return 归一化弧长进度，恒在 {@code [0, 1]}
     */
    double progress(double normalizedTime);

    /** 线性：{@code s = u}（零分配单例）。 */
    static PathTiming linear() {
        return LinearTiming.INSTANCE;
    }

    /**
     * 单调关键帧映射：{@code times}/{@code values} 端点固定为 0 与 1，逐段线性插值。
     *
     * <p>{@code times} 与 {@code values} 都必须单调非减（{@code times} 允许相等，相等时刻按后值跳变）；
     * 两端固定 {@code times[0] = values[0] = 0}、{@code times[n-1] = values[n-1] = 1}。
     * 数组会被复制，调用方之后修改原数组不影响本实例。</p>
     *
     * @throws IllegalArgumentException 长度不足、长度不一致、端点不是 0/1、或数组不单调
     */
    static PathTiming of(float[] times, float[] values) {
        return KeyframeTiming.of(times, values);
    }
}

/** 线性映射（无状态单例）。 */
final class LinearTiming implements PathTiming {

    static final LinearTiming INSTANCE = new LinearTiming();

    private LinearTiming() {
    }

    @Override
    public double progress(double normalizedTime) {
        if (!(normalizedTime > 0.0)) {
            return 0.0;
        }
        return normalizedTime < 1.0 ? normalizedTime : 1.0;
    }
}

/** 单调关键帧映射：二分/游标定位，逐段线性插值；实例持有调用游标但不改变结果。 */
final class KeyframeTiming implements PathTiming {

    private final float[] times;
    private final float[] values;
    /** 上次命中的段下标（缓存，不改变结果）。 */
    private int cursor;

    private KeyframeTiming(float[] times, float[] values) {
        this.times = times;
        this.values = values;
    }

    static PathTiming of(float[] times, float[] values) {
        Objects.requireNonNull(times, "路径 timing：times 不能为 null");
        Objects.requireNonNull(values, "路径 timing：values 不能为 null");
        if (times.length < 2 || values.length < 2) {
            throw new IllegalArgumentException("路径 timing：times/values 长度必须 >= 2（实际 times="
                    + times.length + ", values=" + values.length + "）");
        }
        if (times.length != values.length) {
            throw new IllegalArgumentException("路径 timing：times 与 values 长度不一致（times="
                    + times.length + ", values=" + values.length + "）");
        }
        int last = times.length - 1;
        if (times[0] != 0f || times[last] != 1f) {
            throw new IllegalArgumentException("路径 timing：times 端点必须固定为 0 与 1（首="
                    + times[0] + ", 末=" + times[last] + "）");
        }
        if (values[0] != 0f || values[last] != 1f) {
            throw new IllegalArgumentException("路径 timing：values 端点必须固定为 0 与 1（首="
                    + values[0] + ", 末=" + values[last] + "）");
        }
        for (int i = 1; i < times.length; i++) {
            if (times[i] < times[i - 1]) {
                throw new IllegalArgumentException("路径 timing：times 必须单调非减（下标 " + (i - 1)
                        + "→" + i + "：" + times[i - 1] + " → " + times[i] + "）");
            }
            if (values[i] < values[i - 1]) {
                throw new IllegalArgumentException("路径 timing：values 必须单调非减（下标 " + (i - 1)
                        + "→" + i + "：" + values[i - 1] + " → " + values[i] + "）");
            }
        }
        return new KeyframeTiming(times.clone(), values.clone());
    }

    @Override
    public double progress(double normalizedTime) {
        double x = normalizedTime > 0.0 ? Math.min(normalizedTime, 1.0) : 0.0;
        int segment = locate(x);
        cursor = segment;
        double t0 = times[segment];
        double t1 = times[segment + 1];
        double v0 = values[segment];
        double v1 = values[segment + 1];
        double fraction = t1 > t0 ? (x - t0) / (t1 - t0) : 1.0;
        double progress = v0 + (v1 - v0) * fraction;
        return progress > 0.0 ? Math.min(progress, 1.0) : 0.0;
    }

    /** 最大的段下标 {@code i ∈ [0, n-2]} 使 {@code times[i] <= x}；相等时刻取最后一段。 */
    private int locate(double x) {
        int terminal = times.length - 2;
        int index = cursor;
        if (index > terminal) {
            index = terminal;
        }
        if (index < 0 || x < times[index]) {
            return search(x);
        }
        while (index < terminal && x >= times[index + 1]) {
            index++;
        }
        return index;
    }

    private int search(double x) {
        int lo = 0;
        int hi = times.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (times[mid] <= x) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
