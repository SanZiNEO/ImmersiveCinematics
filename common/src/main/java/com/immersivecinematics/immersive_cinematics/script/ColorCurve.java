package com.immersivecinematics.immersive_cinematics.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ADJUST 轨的 RGB 复合曲线（0.3.6 曲线组，形态 b：曲线定义一次 + {@code curve_strength} 关键帧控混合强度）。
 *
 * <h2>数据形态</h2>
 * clip 级字段 {@code curve} = 控制点数组 {@code [[x,y], ...]}（{@code x} 严格递增、各 0~1、≥2 点），
 * 由 {@code ScriptParser} 解析成本类；缺省（不写该字段）= 无曲线。
 * 关键帧只控 {@code curve_strength}（0~1，缺省 1 = 曲线全量生效），渲染侧按
 * {@code c = mix(c, curve(c), strength)} 逐通道混合——曲线本身不随时间变，故不进关键帧。
 *
 * <h2>LUT（CPU 侧采样，渲染侧查表）</h2>
 * 控制点在 CPU 侧采样成 {@value #LUT_SIZE} 点 LUT（{@code lut[i]} = 曲线在 {@code x = i / 255} 处的取值），
 * 上传成 256×1 纹理给 {@code ic_color_adjust.fsh} 查表——着色器里不做曲线求值。
 * <ul>
 *   <li>插值 = <b>Fritsch–Carlson 单调三次插值</b>（PCHIP）：单调控制点 ⇒ 单调 LUT，
 *       段内无过冲（不会出现「提亮黑场却把暗部压暗」这类越界），两端切线取单侧斜率；</li>
 *   <li>端点外钳制：{@code x < x0} 取 {@code y0}、{@code x > x_last} 取 {@code y_last}（不外推）；</li>
 *   <li><b>缓存</b>：LUT 在构造时算好、对象内持有——一个 clip 解析出一个对象、全生命周期复用，
 *       {@code AdjustTrackPlayer} 每帧拿到的是同一个数组（渲染侧按引用比较，只在换 clip 时重传纹理）。
 *       与 {@code BezierPathStrategy} 的 {@code lutCache} 同思路，只是键 = 曲线对象自身（不必再建 Map）。</li>
 * </ul>
 *
 * <p>不可变对象：控制点表与 LUT 都不再改动；{@link #lut()} 返回的数组只读，调用方不得修改。</p>
 */
public final class ColorCurve {

    /** 一个控制点（{@code x} 严格递增、{@code x} / {@code y} 各 0~1）。 */
    public record Point(float x, float y) {}

    /** LUT 采样点数（= 渲染侧 256×1 曲线纹理的宽度；{@code lut[i]} 对应 {@code x = i / (LUT_SIZE - 1)}）。 */
    public static final int LUT_SIZE = 256;

    /** 恒等曲线 LUT（{@code lut[i] = i / 255}，即 {@code y = x}）。 */
    private static final float[] IDENTITY_LUT = buildIdentityLut();

    private final List<Point> points;
    private final float[] lut;

    /**
     * 由控制点建一条曲线并采样出 LUT。
     *
     * <p><b>前置条件</b>（由 {@code ScriptParser} / {@code ScriptValidator} 保证，本类不再重复校验）：
     * ≥2 个点、{@code x} 严格递增、{@code x} / {@code y} 各在 0~1。</p>
     */
    public ColorCurve(List<Point> points) {
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.lut = sample(this.points);
    }

    /** 控制点表（只读，{@code x} 严格递增）。 */
    public List<Point> points() {
        return points;
    }

    /** 采样好的 {@value #LUT_SIZE} 点 LUT（只读；{@code lut[i]} = 曲线在 {@code x = i / 255} 处的取值）。 */
    public float[] lut() {
        return lut;
    }

    /** 恒等曲线的 LUT（{@code y = x}；渲染侧无曲线时绑它，避免未绑定采样器）。返回数组只读。 */
    public static float[] identityLut() {
        return IDENTITY_LUT;
    }

    private static float[] buildIdentityLut() {
        float[] lut = new float[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            lut[i] = i / (float) (LUT_SIZE - 1);
        }
        return lut;
    }

    /**
     * Fritsch–Carlson 单调三次插值（PCHIP）采样整条曲线。
     *
     * <p>步骤：区间斜率 {@code d} → 切线初值（端点取单侧斜率、内部取相邻斜率均值）→
     * 单调性修正（{@code d = 0} 时两端切线置 0；切线符号与区间斜率相反时置 0；
     * {@code α² + β² > 9} 时按 {@code 3 / √(α²+β²)} 缩回——这三条保证段内无过冲）→
     * 逐点 Hermite 求值；区间外钳制到端点值。</p>
     */
    private static float[] sample(List<Point> pts) {
        int n = pts.size();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i).x();
            ys[i] = pts.get(i).y();
        }

        // 区间斜率
        float[] d = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            d[i] = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]);
        }

        // 切线初值
        float[] m = new float[n];
        if (n == 2) {
            m[0] = d[0];
            m[1] = d[0];
        } else {
            m[0] = d[0];
            m[n - 1] = d[n - 2];
            for (int i = 1; i < n - 1; i++) {
                m[i] = 0.5f * (d[i - 1] + d[i]);
            }
        }

        // 单调性修正（Fritsch–Carlson）
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0.0f) {
                m[i] = 0.0f;
                m[i + 1] = 0.0f;
                continue;
            }
            float alpha = m[i] / d[i];
            float beta = m[i + 1] / d[i];
            if (alpha < 0.0f) {
                m[i] = 0.0f;
                alpha = 0.0f;
            }
            if (beta < 0.0f) {
                m[i + 1] = 0.0f;
                beta = 0.0f;
            }
            float sum = alpha * alpha + beta * beta;
            if (sum > 9.0f) {
                float tau = 3.0f / (float) Math.sqrt(sum);
                m[i] = tau * alpha * d[i];
                m[i + 1] = tau * beta * d[i];
            }
        }

        // 逐点求值（x 递增 → 区间游标只前进）
        float[] lut = new float[LUT_SIZE];
        int seg = 0;
        for (int i = 0; i < LUT_SIZE; i++) {
            float x = i / (float) (LUT_SIZE - 1);
            if (x <= xs[0]) {
                lut[i] = ys[0];                 // 左端外钳制
                continue;
            }
            if (x >= xs[n - 1]) {
                lut[i] = ys[n - 1];             // 右端外钳制
                continue;
            }
            while (seg < n - 2 && x > xs[seg + 1]) {
                seg++;
            }
            float h = xs[seg + 1] - xs[seg];
            float t = (x - xs[seg]) / h;
            float t2 = t * t;
            float t3 = t2 * t;
            float h00 = 2.0f * t3 - 3.0f * t2 + 1.0f;
            float h10 = t3 - 2.0f * t2 + t;
            float h01 = -2.0f * t3 + 3.0f * t2;
            float h11 = t3 - t2;
            lut[i] = h00 * ys[seg] + h10 * h * m[seg] + h01 * ys[seg + 1] + h11 * h * m[seg + 1];
        }
        return lut;
    }
}
