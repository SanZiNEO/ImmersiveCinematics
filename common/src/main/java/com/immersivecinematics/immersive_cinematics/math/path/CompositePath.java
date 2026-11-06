package com.immersivecinematics.immersive_cinematics.math.path;

import com.immersivecinematics.immersive_cinematics.spi.PathCapability;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 复合路径：把若干首尾相接的路径表达式按「段定义域跨度比例」拼成一条 {@code u ∈ [0,1]} 的路径表达式。
 * 复合本身仍是 {@link PathExpression}，可继续被弧长、轴约束等算子消费；它不是新的作者面曲线类型，
 * 不进 {@code PathCurveRegistry}。
 *
 * <p><b>参数映射</b>：第 {@code i} 段分到 {@code fraction_i = span_i / Σ span}，
 * 全局 {@code u} 落在该段时局部参数取 {@code seg.min + (u - uStart_i) · Σ span}；
 * 段内 {@code u} 线性扫过整段定义域，段边界按左闭右开处理（{@code u == uStart_{i+1}} 归后一段），
 * 因此 {@code u = 0} 精确落在首段起点、{@code u = 1} 精确落在末段终点。
 *
 * <p><b>链式求导</b>：{@code dv/du = Σ span} 对每段都相同（因为 {@code span_i / fraction_i = Σ span}），
 * 故 {@code derivative(u) = seg'·Σspan}、{@code secondDerivative(u) = seg''·(Σspan)²}、
 * {@code speed(u) = seg.speed·Σspan}。{@code speed} 与 {@code |derivative|} 同源，数值上一致。
 *
 * <p><b>连续性</b>：构造期对每个相邻接缝跑一次 {@link Continuity#check}，结果只读保存；
 * {@link #achieved()} 取所有接缝的最低阶次（任一处退化到 {@code null} 则整体为 {@code null}，无接缝视为 G2）。
 * 校验只记录不修正几何：声明高于实际时由调用方决定告警或插入过渡段。
 *
 * <p><b>能力</b>：
 * <ul>
 *   <li>{@link PathCapability#ANALYTIC_SECOND_DERIVATIVE}：所有段都声明才声明。</li>
 *   <li>{@link PathCapability#ANALYTIC_ARC_LENGTH}：所有段都实现 {@link AnalyticArcLength} 才声明；
 *       否则声明为「不支持」。{@link #arcLength} 始终返回正确值——解析段走解析式、其余段退回
 *       {@link ArcLength} 数值积分，所以 {@link ArcLength} 的 instanceof 快路径在任何组合下都不会算错。</li>
 * </ul>
 * 声明通道是 {@link #supports(PathCapability)}；一个 {@code final} 类无法「有条件地实现接口」，
 * 所以本类固定实现 {@link AnalyticArcLength}，由 {@code supports} 给出真实能力。
 */
public final class CompositePath implements PathExpression, AnalyticArcLength {

    private final List<PathExpression> segments;
    private final Continuity.Order declared;
    /** 每段在全局 u 上的起点，{@code starts[0] = 0}，严格递增。 */
    private final double[] starts;
    /** 每段分到的全局 u 跨度 {@code fraction_i = span_i / Σspan}。 */
    private final double[] fractions;
    /** {@code Σ span}，即 {@code dv/du}；每段的 {@code span_i / fraction_i} 都等于它。 */
    private final double totalSpan;
    private final List<Continuity.Report> reports;
    private final Continuity.Order achieved;
    private final boolean analyticArcLength;
    private final boolean analyticSecondDerivative;

    /**
     * 按给定顺序把段拼成一条复合路径。
     *
     * @param segments 段序列，至少 1 个元素且不含 null；元素定义域跨度必须为有限正数（{@link PathDomain} 已保证）
     * @param declared 段间接缝声明的最低连续性
     * @throws IllegalArgumentException 段列表为 null / 为空 / 含 null，或 {@code declared} 为 null
     */
    public CompositePath(List<PathExpression> segments, Continuity.Order declared) {
        if (segments == null) {
            throw new IllegalArgumentException("段列表不能为 null");
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("复合路径至少需要 1 段: segments=[]");
        }
        if (declared == null) {
            throw new IllegalArgumentException("连续性声明不能为 null");
        }
        int count = segments.size();
        double[] spans = new double[count];
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            PathExpression segment = segments.get(i);
            if (segment == null) {
                throw new IllegalArgumentException("段不能为 null: index=" + i);
            }
            spans[i] = segment.domain().span();
            total += spans[i];
        }
        this.segments = List.copyOf(segments);
        this.declared = declared;
        this.totalSpan = total;

        this.starts = new double[count];
        this.fractions = new double[count];
        double prefix = 0.0;
        for (int i = 0; i < count; i++) {
            this.starts[i] = prefix / total;
            this.fractions[i] = spans[i] / total;
            prefix += spans[i];
        }

        List<Continuity.Report> seams = new ArrayList<>(Math.max(0, count - 1));
        for (int i = 0; i + 1 < count; i++) {
            seams.add(Continuity.check(this.segments.get(i), this.segments.get(i + 1), declared));
        }
        this.reports = List.copyOf(seams);

        Continuity.Order lowest = Continuity.Order.G2;
        boolean analyticLength = true;
        boolean analyticSecond = true;
        for (Continuity.Report report : this.reports) {
            Continuity.Order order = report.achieved();
            if (order == null) {
                lowest = null;
                break;
            }
            if (lowest != null && order.ordinal() < lowest.ordinal()) {
                lowest = order;
            }
        }
        for (PathExpression segment : this.segments) {
            analyticLength &= segment instanceof AnalyticArcLength;
            analyticSecond &= segment.supports(PathCapability.ANALYTIC_SECOND_DERIVATIVE);
        }
        this.achieved = lowest;
        this.analyticArcLength = analyticLength;
        this.analyticSecondDerivative = analyticSecond;
    }

    /** 段序列（只读，顺序即拼接顺序）。 */
    public List<PathExpression> segments() {
        return segments;
    }

    /** 构造时声明的连续性。 */
    public Continuity.Order declared() {
        return declared;
    }

    /** 每个相邻接缝的校验结果（只读，长度 = 段数 - 1）。 */
    public List<Continuity.Report> reports() {
        return reports;
    }

    /** 所有接缝的最低实际阶次；任一处位置不连续为 {@code null}；无接缝为 {@link Continuity.Order#G2}。 */
    public Continuity.Order achieved() {
        return achieved;
    }

    /** 第 {@code index} 段在全局 u 上的起点。 */
    public double uStart(int index) {
        if (index < 0 || index >= starts.length) {
            throw new IllegalArgumentException("段下标越界: index=" + index + ", 段数=" + starts.length);
        }
        return starts[index];
    }

    /** 第 {@code index} 段分到的全局 u 跨度。 */
    public double uFraction(int index) {
        if (index < 0 || index >= fractions.length) {
            throw new IllegalArgumentException("段下标越界: index=" + index + ", 段数=" + fractions.length);
        }
        return fractions[index];
    }

    @Override
    public String type() {
        return "composite";
    }

    @Override
    public PathDomain domain() {
        return PathDomain.UNIT;
    }

    @Override
    public boolean supports(PathCapability capability) {
        if (capability == PathCapability.ANALYTIC_ARC_LENGTH) {
            return analyticArcLength;
        }
        if (capability == PathCapability.ANALYTIC_SECOND_DERIVATIVE) {
            return analyticSecondDerivative;
        }
        return false;
    }

    @Override
    public Vec3 position(double u) {
        double t = PathDomain.UNIT.clamp(u);
        int index = locate(t);
        return segments.get(index).position(local(index, t));
    }

    @Override
    public Vec3 derivative(double u) {
        double t = PathDomain.UNIT.clamp(u);
        int index = locate(t);
        return segments.get(index).derivative(local(index, t)).scale(totalSpan);
    }

    @Override
    public Vec3 secondDerivative(double u) {
        double t = PathDomain.UNIT.clamp(u);
        int index = locate(t);
        return segments.get(index).secondDerivative(local(index, t)).scale(totalSpan * totalSpan);
    }

    @Override
    public double speed(double u) {
        double t = PathDomain.UNIT.clamp(u);
        int index = locate(t);
        return segments.get(index).speed(local(index, t)) * totalSpan;
    }

    /**
     * 区间弧长：按段裁剪后累加，解析段直接取解析式、其余段退回 {@link ArcLength} 数值积分。
     * 参数先 clamp 到 {@code [0,1]}；{@code u1 <= u0} 返回 0。
     */
    @Override
    public double arcLength(double u0, double u1) {
        double a = PathDomain.UNIT.clamp(u0);
        double b = PathDomain.UNIT.clamp(u1);
        if (b <= a) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < segments.size(); i++) {
            double segmentStart = starts[i];
            double segmentEnd = i + 1 < starts.length ? starts[i + 1] : 1.0;
            double lo = Math.max(a, segmentStart);
            double hi = Math.min(b, segmentEnd);
            if (hi <= lo) {
                continue;
            }
            PathExpression segment = segments.get(i);
            PathDomain domain = segment.domain();
            double localLo = domain.clamp(domain.min() + (lo - segmentStart) * totalSpan);
            double localHi = domain.clamp(domain.min() + (hi - segmentStart) * totalSpan);
            if (localHi <= localLo) {
                continue;
            }
            total += segment instanceof AnalyticArcLength analytic
                    ? analytic.arcLength(localLo, localHi)
                    : ArcLength.length(segment, localLo, localHi);
        }
        return total;
    }

    /** 全局 u 落在第几段：取满足 {@code starts[i] <= t} 的最大下标（段边界归后一段）。 */
    private int locate(double t) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** 全局 u 映射到第 {@code index} 段的局部参数；夹到段定义域，保证端点精确。 */
    private double local(int index, double t) {
        PathDomain domain = segments.get(index).domain();
        return domain.clamp(domain.min() + (t - starts[index]) * totalSpan);
    }
}
