package com.immersivecinematics.immersive_cinematics.math.path;

import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * 运行时绑定缓存：按 {@code (spec 引用相等, from/to 六分量逐位相等)} 复用 {@link PathExpression}
 * 与 {@link ArcLengthTable}。端点或 spec 变化 = 新绑定；容量上限 {@value #MAX_BINDINGS}，
 * 超出按环形覆盖最旧项。{@code s} 是归一化弧长进度 [0,1]，内部先反解 u 再求值。
 * 零长曲线：位置返回 {@code from}，切线返回 {@code to-from}，仍为零则返回零向量。
 */
public final class PathEvaluator {

    /** 绑定缓存容量。 */
    public static final int MAX_BINDINGS = 32;

    private final PathSpec[] specs = new PathSpec[MAX_BINDINGS];
    private final PathExpression[] expressions = new PathExpression[MAX_BINDINGS];
    private final ArcLengthTable[] tables = new ArcLengthTable[MAX_BINDINGS];
    private final double[] endpoints = new double[MAX_BINDINGS * 6];
    private int count;
    private int next;

    /** 归一化弧长进度 s 处的位置；s 越界按 clamp 处理。 */
    public Vec3 position(PathSpec spec, Vec3 from, Vec3 to, double s) {
        int index = indexOf(spec, from, to);
        ArcLengthTable table = tables[index];
        if (table.length() <= 0.0) {
            return from;
        }
        return expressions[index].position(table.uAt(s));
    }

    /** 归一化弧长进度 s 处的一阶导（未归一化，单位 / u）。 */
    public Vec3 tangent(PathSpec spec, Vec3 from, Vec3 to, double s) {
        int index = indexOf(spec, from, to);
        ArcLengthTable table = tables[index];
        if (table.length() <= 0.0) {
            Vec3 delta = to.subtract(from);
            return PathMath.length(delta) == 0.0 ? Vec3.ZERO : delta;
        }
        return expressions[index].derivative(table.uAt(s));
    }

    /**
     * 动态路径求值：调用方为每条会变的路径持有一个 {@link DynamicArcLengthTable}。
     * 小变化复用旧表、大变化重建、连续抖动降级为数值积分（见该类）。
     *
     * @param spec  路径定义（控制点/权重/节点每帧可不同）
     * @param from  段起点
     * @param to    段终点
     * @param s     归一化弧长 [0,1]
     * @param cache 该动态路径专属的表缓存（非 null）
     */
    public Vec3 positionDynamic(PathSpec spec, Vec3 from, Vec3 to, double s, DynamicArcLengthTable cache) {
        if (spec == null || cache == null) {
            throw new IllegalArgumentException("spec / cache 不能为 null");
        }
        cache.update(spec.bind(from, to));
        return cache.position(s);
    }

    /** 动态路径在归一化弧长 {@code s} 处的一阶导（未归一化，单位 / u）。 */
    public Vec3 tangentDynamic(PathSpec spec, Vec3 from, Vec3 to, double s, DynamicArcLengthTable cache) {
        if (spec == null || cache == null) {
            throw new IllegalArgumentException("spec / cache 不能为 null");
        }
        cache.update(spec.bind(from, to));
        return cache.tangent(s);
    }

    /** 清空全部绑定。 */
    public void clear() {
        Arrays.fill(specs, null);
        Arrays.fill(expressions, null);
        Arrays.fill(tables, null);
        count = 0;
        next = 0;
    }

    /** 当前绑定数量（诊断 / 探针）。 */
    public int bindingCount() {
        return count;
    }

    private int indexOf(PathSpec spec, Vec3 from, Vec3 to) {
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为 null");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("端点不能为 null: from=" + from + ", to=" + to);
        }
        for (int i = 0; i < count; i++) {
            if (specs[i] == spec && matches(i, from, to)) {
                return i;
            }
        }
        return insert(spec, from, to);
    }

    private boolean matches(int index, Vec3 from, Vec3 to) {
        int base = index * 6;
        return bits(endpoints[base], from.x)
                && bits(endpoints[base + 1], from.y)
                && bits(endpoints[base + 2], from.z)
                && bits(endpoints[base + 3], to.x)
                && bits(endpoints[base + 4], to.y)
                && bits(endpoints[base + 5], to.z);
    }

    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private int insert(PathSpec spec, Vec3 from, Vec3 to) {
        int index;
        if (count < MAX_BINDINGS) {
            index = count++;
        } else {
            index = next;
            next = (next + 1) % MAX_BINDINGS;
        }
        PathExpression expression = spec.bind(from, to);
        specs[index] = spec;
        expressions[index] = expression;
        tables[index] = new ArcLengthTable(expression);
        int base = index * 6;
        endpoints[base] = from.x;
        endpoints[base + 1] = from.y;
        endpoints[base + 2] = from.z;
        endpoints[base + 3] = to.x;
        endpoints[base + 4] = to.y;
        endpoints[base + 5] = to.z;
        return index;
    }
}
