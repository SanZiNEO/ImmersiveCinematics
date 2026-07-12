package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 贝塞尔弧长查找表 — 基于德卡斯特里奥自适应细分构建
 * <p>
 * 将贝塞尔曲线的参数 t 映射到等弧长参数 s ∈ [0,1]，
 * 使得匀速的 s 变化产生匀速的曲线运动。
 * <p>
 * 构建方法：
 * <ol>
 *   <li>使用德卡斯特里奥算法递归细分曲线</li>
 *   <li>通过中间层点距离判断平坦度，自适应决定采样密度</li>
 *   <li>弯曲处密集采样（高精度），直线段稀疏采样（省内存）</li>
 * </ol>
 * <p>
 * 查询方法：给定 s，二分查找对应的 t，线性插值精化。
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
     * 使用默认参数构建弧长 LUT
     */
    public ArcLengthLUT(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3) {
        this(p0, p1, p2, p3, DEFAULT_TOLERANCE, DEFAULT_MAX_DEPTH);
    }

    /**
     * 使用自定义参数构建弧长 LUT
     */
    public ArcLengthLUT(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float tolerance, int maxDepth) {
        this.p0 = p0;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;

        // 第一步：自适应细分收集采样点
        List<Float> rawT = new ArrayList<>();
        rawT.add(0f);
        subdivide(rawT, p0, p1, p2, p3, tolerance, maxDepth, 0f, 1f);
        rawT.add(1f);
        Collections.sort(rawT);

        // 第二步：去重
        List<Float> uniqueT = new ArrayList<>();
        for (float t : rawT) {
            if (uniqueT.isEmpty() || Math.abs(t - uniqueT.get(uniqueT.size() - 1)) > 1e-7f) {
                uniqueT.add(t);
            }
        }

        // 第三步：计算累积弧长并归一化
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

        // 第四步：填充查找表
        for (int i = 0; i < uniqueT.size(); i++) {
            tValues.add(uniqueT.get(i));
            double normalized = (total > 0.0) ? cumLengths.get(i) / total : (double)i / (uniqueT.size() - 1);
            arcLengths.add((float)normalized);
        }
    }

    /**
     * 给定归一化弧长 s ∈ [0,1]，查找对应的贝塞尔参数 t
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

    /** 获取采样点数量 */
    public int size() {
        return tValues.size();
    }

    /**
     * 自适应细分 — 收集需要采样的 t 值
     * <p>
     * 使用德卡斯特里奥算法的中间层点判断曲线平坦度。
     */
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
