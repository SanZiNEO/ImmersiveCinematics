package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 贝塞尔弧长查找表：把曲线参数 t 映射到等弧长参数 s ∈ [0,1]，使匀速的 s 变化产生匀速的曲线运动。
 * 构建 = 德卡斯特里奥递归细分，按中间层点距离判平坦度（弯曲处密、直线段疏）；查询 = 累积弧长上二分 + 线性插值精化。
 * 缺省容差 {@value #DEFAULT_TOLERANCE} 世界单位、最大递归深度 {@value #DEFAULT_MAX_DEPTH}（≤ 2^8 个采样点）。
 */
public class ArcLengthLUT {

    /** 平坦度容差（世界坐标单位，约 1mm） */
    private static final float DEFAULT_TOLERANCE = 0.001f;

    /** 最大递归深度（最多 2⁸ = 256 个采样点） */
    private static final int DEFAULT_MAX_DEPTH = 8;

    /** 贝塞尔控制点 */
    private final Vec3 p0, p1, p2, p3;

    /** 弧长参数 t 值列表（从小到大排序） */
    private final List<Float> tValues = new ArrayList<>();

    /** 归一化累积弧长列表（与 tValues 一一对应，范围 [0,1]） */
    private final List<Float> arcLengths = new ArrayList<>();

    /**
     * 使用默认容差与递归深度构建弧长 LUT。
     *
     * @param p0 曲线起点
     * @param p1 控制点 1
     * @param p2 控制点 2
     * @param p3 曲线终点
     */
    public ArcLengthLUT(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3) {
        this(p0, p1, p2, p3, DEFAULT_TOLERANCE, DEFAULT_MAX_DEPTH);
    }

    /**
     * 使用自定义参数构建弧长 LUT。
     *
     * @param p0       曲线起点
     * @param p1       控制点 1
     * @param p2       控制点 2
     * @param p3       曲线终点
     * @param tolerance 平坦度容差（世界坐标单位；越小采样越密）
     * @param maxDepth  最大递归深度（采样点数上限 2^maxDepth）
     */
    public ArcLengthLUT(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float tolerance, int maxDepth) {
        this.p0 = p0;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;

        List<Float> rawT = new ArrayList<>();
        rawT.add(0f);
        subdivide(rawT, p0, p1, p2, p3, tolerance, maxDepth, 0f, 1f);
        rawT.add(1f);
        Collections.sort(rawT);

        List<Float> uniqueT = new ArrayList<>();
        for (float t : rawT) {
            if (uniqueT.isEmpty() || Math.abs(t - uniqueT.get(uniqueT.size() - 1)) > 1e-7f) {
                uniqueT.add(t);
            }
        }

        double total = 0.0;
        List<Double> cumLengths = new ArrayList<>();
        cumLengths.add(0.0);

        Vec3 prev = evaluate(uniqueT.get(0));
        for (int i = 1; i < uniqueT.size(); i++) {
            Vec3 curr = evaluate(uniqueT.get(i));
            total += prev.distanceTo(curr);
            cumLengths.add(total);
            prev = curr;
        }

        for (int i = 0; i < uniqueT.size(); i++) {
            tValues.add(uniqueT.get(i));
            double normalized = (total > 0.0) ? cumLengths.get(i) / total : (double)i / (uniqueT.size() - 1);
            arcLengths.add((float)normalized);
        }
    }

    /**
     * 给定归一化弧长 s ∈ [0,1]，查找对应的贝塞尔参数 t。
     *
     * @param s 归一化弧长（≤0 / ≥1 直接取端点）
     * @return 贝塞尔参数 t ∈ [0,1]
     */
    public float lookupT(float s) {
        if (tValues.isEmpty() || s <= 0f) return 0f;
        if (s >= 1f) return tValues.get(tValues.size() - 1);

        // 二分查找
        int lo = 0;
        int hi = arcLengths.size() - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) / 2;
            if (arcLengths.get(mid) < s) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        // 线性插值精化
        float sLo = arcLengths.get(lo);
        float sHi = arcLengths.get(hi);
        float range = sHi - sLo;
        if (range <= 0f) return tValues.get(hi);

        float frac = (s - sLo) / range;
        float tLo = tValues.get(lo);
        float tHi = tValues.get(hi);
        return tLo + (tHi - tLo) * frac;
    }

    /**
     * 获取采样点数量。
     *
     * @return 采样点数（≤ 2^maxDepth）
     */
    public int size() {
        return tValues.size();
    }

    /** 自适应细分：在 t=0.5 处德卡斯特里奥分割，平坦度 = 中间层线段最大距离。 */
    private static void subdivide(List<Float> outT,
                                  Vec3 cp0, Vec3 cp1, Vec3 cp2, Vec3 cp3,
                                  float tolerance, int maxDepth,
                                  float tStart, float tEnd) {
        // 德卡斯特里奥算法在 t=0.5 处细分
        Vec3 q0 = lerp(cp0, cp1, 0.5f);
        Vec3 q1 = lerp(cp1, cp2, 0.5f);
        Vec3 q2 = lerp(cp2, cp3, 0.5f);
        Vec3 r0 = lerp(q0, q1, 0.5f);
        Vec3 r1 = lerp(q1, q2, 0.5f);
        Vec3 mid = lerp(r0, r1, 0.5f);

        float tMid = (tStart + tEnd) / 2f;

        // 平坦度判断：中间层线段的最大距离
        double flatness = Math.max(
                q0.distanceTo(q2),
                r0.distanceTo(r1)
        );

        if (flatness < tolerance || maxDepth <= 0) {
            outT.add(tMid);
        } else {
            // 左半段：cp0, q0, r0, mid
            subdivide(outT, cp0, q0, r0, mid, tolerance, maxDepth - 1, tStart, tMid);
            // 右半段：mid, r1, q2, cp3
            subdivide(outT, mid, r1, q2, cp3, tolerance, maxDepth - 1, tMid, tEnd);
        }
    }

    /**
     * 计算贝塞尔曲线上参数 t 对应的点
     */
    private Vec3 evaluate(float t) {
        return MathUtil.cubicBezier(p0, p1, p2, p3, t);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, float t) {
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
        );
    }
}
