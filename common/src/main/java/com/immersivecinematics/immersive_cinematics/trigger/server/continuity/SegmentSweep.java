package com.immersivecinematics.immersive_cinematics.trigger.server.continuity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 连续性几何核：把相邻采样点之间的运动近似成「位置直线段 + 朝向最短弧」，
 * 判定这段运动是否碰到方体 / 球 / 朝向区间。
 *
 * <p><b>近似口径</b>：段是相邻采样点的<b>直线</b>（位置）与<b>最短弧</b>（yaw / pitch）插值，
 * <b>不是真连续时间轨迹</b>；误差上界是一次采样间隔内的真实位移 / 转角。
 * 本类只回答「按该插值是否碰到」，不对真实曲线负责。
 *
 * <p><b>语义</b>：与 {@code GeometryConditions} 的点判定同语义——
 * 闭区间（含边界）、方体角落顺序无关、球含边界、朝向区间含跨 ±180° 环绕、
 * {@code span == 0} = 整圈。端点落在区域内即命中；退化线段（零位移 / 零转角）退化为点判定。
 *
 * <p><b>零 MC 依赖</b>：只用 Gson 与 JDK，角度 wrap 自带等价实现（值域 {@code [-180, 180)}），
 * 因此可无头直调。{@link #hitsBox} / {@link #hitsSphere} / {@link #hitsFacing} /
 * {@link #substeps} / {@link #anyPoint} 均为纯函数。
 *
 * <p><b>分配契约</b>：热路径零分配——只读输入，不建集合、不装箱，返回 primitive。
 * <br><b>线程契约</b>：无状态静态函数，任意线程可调。
 */
public final class SegmentSweep {

    /** 结构扫掠子步数上限（每格一个子步，封顶该值）。 */
    public static final int SUBSTEP_MAX = 16;

    /** 每个子步覆盖的位移（格）。 */
    public static final double SUBSTEP_BLOCKS = 1.0;

    private SegmentSweep() {}

    /**
     * 线段 {@code a → b} 是否与轴对齐方体相交（闭区间，含端点与边界）。
     *
     * <p>角落各轴先 min/max 归一，故 {@code corner1} / {@code corner2} 的写入顺序无关
     * （与 {@code GeometryConditions.inBox} 一致）。任一角落缺 {@code x} / {@code y} / {@code z}
     * 数字分量 → {@code false}。
     *
     * <p>实现为 slab 法：把三轴的参数区间取交，区间非空即相交；
     * 退化线段（{@code a == b}）自然退化为点判定。
     */
    public static boolean hitsBox(MotionSample a, MotionSample b, JsonObject corner1, JsonObject corner2) {
        if (!hasXyz(corner1) || !hasXyz(corner2)) return false;

        double cx1 = num(corner1, "x");
        double cy1 = num(corner1, "y");
        double cz1 = num(corner1, "z");
        double cx2 = num(corner2, "x");
        double cy2 = num(corner2, "y");
        double cz2 = num(corner2, "z");
        double minX = Math.min(cx1, cx2);
        double maxX = Math.max(cx1, cx2);
        double minY = Math.min(cy1, cy2);
        double maxY = Math.max(cy1, cy2);
        double minZ = Math.min(cz1, cz2);
        double maxZ = Math.max(cz1, cz2);

        double ax = a.x();
        double ay = a.y();
        double az = a.z();
        double bx = b.x();
        double by = b.y();
        double bz = b.z();

        double tMin = 0.0;
        double tMax = 1.0;

        // X 轴 slab：与线段参数区间 [0, 1] 取交。
        if (ax == bx) {
            if (ax < minX || ax > maxX) return false;
        } else {
            double inv = 1.0 / (bx - ax);
            double t1 = (minX - ax) * inv;
            double t2 = (maxX - ax) * inv;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (!(t1 <= t2)) return false;          // NaN 防线（非有限输入不得判成命中）
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        // Y 轴 slab。
        if (ay == by) {
            if (ay < minY || ay > maxY) return false;
        } else {
            double inv = 1.0 / (by - ay);
            double t1 = (minY - ay) * inv;
            double t2 = (maxY - ay) * inv;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (!(t1 <= t2)) return false;
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        // Z 轴 slab。
        if (az == bz) {
            if (az < minZ || az > maxZ) return false;
        } else {
            double inv = 1.0 / (bz - az);
            double t1 = (minZ - az) * inv;
            double t2 = (maxZ - az) * inv;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (!(t1 <= t2)) return false;
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        return true;
    }

    /**
     * 线段 {@code a → b} 是否与闭球 {@code (position, radius)} 相交（含边界）。
     *
     * <p>{@code position} 缺 {@code x} / {@code y} / {@code z} 数字分量 → {@code false}；
     * {@code radius < 0}（含 NaN）→ {@code false}。判定为「球心到线段的最近点距离平方 ≤ radius²」，
     * 最近点用参数夹取到 {@code [0, 1]}，故退化线段自然退化为点判定。
     */
    public static boolean hitsSphere(MotionSample a, MotionSample b, JsonObject position, double radius) {
        if (!hasXyz(position)) return false;
        if (!(radius >= 0.0)) return false;             // 负数 / NaN

        double px = num(position, "x");
        double py = num(position, "y");
        double pz = num(position, "z");

        double ax = a.x();
        double ay = a.y();
        double az = a.z();
        double dx = b.x() - ax;
        double dy = b.y() - ay;
        double dz = b.z() - az;

        double len2 = dx * dx + dy * dy + dz * dz;
        double t = 0.0;
        if (len2 > 0.0) {
            t = ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / len2;
            if (t < 0.0) t = 0.0;
            else if (t > 1.0) t = 1.0;
        }

        double ex = ax + t * dx - px;
        double ey = ay + t * dy - py;
        double ez = az + t * dz - pz;
        return ex * ex + ey * ey + ez * ez <= radius * radius;
    }

    /**
     * 线段运动期间的朝向是否与目标朝向区间相交（pitch 区间重叠 + yaw 集合相交）。
     *
     * <p>与 {@code GeometryConditions.facingInRange} 同语义：
     * 目标 yaw 集合由 {@code span = wrap(wrap(yaw2) - wrap(yaw1))} 决定——
     * {@code span == 0} 为整圈（只受 pitch 限制）；{@code span > 0} 为正向弧
     * {@code delta ∈ [0, span]}；{@code span < 0} 为补集 {@code delta >= 0 || delta <= span}
     * （即排除开弧 {@code (span, 0)}）。pitch 目标区间取
     * {@code [min(pitch1, pitch2), max(pitch1, pitch2)]}（闭区间，顺序无关）。
     *
     * <p>运动 yaw 弧为 {@code a.yaw → b.yaw} 的<b>最短</b>弧（长度 {@code |wrap(b.yaw - a.yaw)|}，
     * 零长度退化为点判定）；运动 pitch 在 {@code [a.pitch, b.pitch]} 上连续变化。
     * 判定取必要近似：pitch 区间与目标区间有交，且运动 yaw 弧与目标 yaw 集合有交。
     *
     * <p>两条长度 &lt; 360° 的弧相交 ⇔ 任一弧的端点落在另一条弧内；
     * {@code span < 0} 时改用「运动弧是否被排除开弧完全覆盖」取反（严格不等式）。
     */
    public static boolean hitsFacing(MotionSample a, MotionSample b,
                                     float yaw1, float pitch1, float yaw2, float pitch2) {
        float targetPitchMin = Math.min(pitch1, pitch2);
        float targetPitchMax = Math.max(pitch1, pitch2);
        float motionPitchMin = Math.min(a.pitch(), b.pitch());
        float motionPitchMax = Math.max(a.pitch(), b.pitch());
        if (motionPitchMax < targetPitchMin || motionPitchMin > targetPitchMax) return false;

        float w1 = wrapDegrees(yaw1);
        float w2 = wrapDegrees(yaw2);
        float span = wrapDegrees(w2 - w1);
        if (span == 0.0f) return true;                  // 起止相同：整圈，yaw 不限制

        float motionStart = wrapDegrees(a.yaw());
        float motionDelta = wrapDegrees(b.yaw() - a.yaw());
        if (motionDelta == 0.0f) {
            return pointInYawRange(motionStart, w1, span);   // 无转向：点判定
        }

        if (span < 0.0f) {
            // 目标 = 全圈减去排除开弧 (span, 0)。运动弧长度 ≤ 180°，其两端都严格落在
            // 排除弧内 ⇔ 整段被排除（最短弧不会绕出长度 ≤ 180° 的开区间）。
            float dStart = wrapDegrees(motionStart - w1);
            float dEnd = wrapDegrees(wrapDegrees(b.yaw()) - w1);
            boolean excluded = dStart < 0.0f && dStart > span
                    && dEnd < 0.0f && dEnd > span;
            return !excluded;
        }

        float motionEnd = wrapDegrees(motionStart + motionDelta);
        // 目标弧 (w1, +span)；运动弧两端落在目标弧内，或目标弧两端落在运动弧内 → 相交。
        return pointInPositiveArc(w1, span, motionStart)
                || pointInPositiveArc(w1, span, motionEnd)
                || pointInMotionArc(motionStart, motionDelta, w1)
                || pointInMotionArc(motionStart, motionDelta, w1 + span);
    }

    /**
     * 按位移算结构扫掠子步数：{@code ceil(distance / SUBSTEP_BLOCKS)}，钳到 {@code [1, max]}。
     *
     * <p>{@code distance} 非有限或 {@code <= 0} → 1；{@code max < 1} → 1。
     * 返回值即 {@link #anyPoint} 要走的间隔数（访问点数 = 返回值 + 1）。
     */
    public static int substeps(double distance, int max) {
        if (max < 1 || !(distance > 0.0) || !Double.isFinite(distance)) return 1;
        double count = Math.ceil(distance / SUBSTEP_BLOCKS);
        return count >= (double) max ? max : (int) count;
    }

    /**
     * 按朝向转角算弧带子步数：yaw 取<b>最短弧</b>（值域 {@code [-180, 180)}）、pitch 线性，
     * {@code steps = ceil(max(|dyaw|, |dpitch|) / stepDegrees)}，钳到 {@code [1, SUBSTEP_MAX]}。
     *
     * <p>{@code stepDegrees} 非有限或 {@code <= 0} → 1；任一角度非有限 → 1。
     * 返回值即子步重放的间隔数（重放 {@code i = 1..steps}，参数 {@code t = i / steps}）。
     * 纯函数、零分配。
     */
    public static int arcSteps(MotionSample from, MotionSample to, double stepDegrees) {
        if (from == null || to == null) return 1;
        if (!(stepDegrees > 0.0) || !Double.isFinite(stepDegrees)) return 1;
        double dyaw = wrapDegrees((double) to.yaw() - (double) from.yaw());
        double dpitch = (double) to.pitch() - (double) from.pitch();
        double span = Math.max(Math.abs(dyaw), Math.abs(dpitch));
        if (!Double.isFinite(span)) return 1;
        double count = Math.ceil(span / stepDegrees);
        if (!(count >= 1.0)) return 1;
        return count >= (double) SUBSTEP_MAX ? SUBSTEP_MAX : (int) count;
    }

    /** 子步取样回调：{@code true} = 命中，立即停止。 */
    public interface Visitor {
        boolean test(double x, double y, double z);
    }

    /**
     * 沿线段 {@code a → b} 依次访问 {@code steps + 1} 个等距点（含两端，参数 {@code t = i / steps}），
     * 任一点 {@code visitor.test} 返回 {@code true} 即返回 {@code true}（命中即停）。
     *
     * <p>{@code steps < 1} 视为 1；末点直接用 {@code b} 的原始坐标，避免插值舍入。
     * 位置线性插值，不涉及朝向（朝向由 {@link #hitsFacing} 单独判定）。
     */
    public static boolean anyPoint(MotionSample a, MotionSample b, int steps, Visitor visitor) {
        int n = steps < 1 ? 1 : steps;
        double ax = a.x();
        double ay = a.y();
        double az = a.z();
        double dx = b.x() - ax;
        double dy = b.y() - ay;
        double dz = b.z() - az;

        for (int i = 0; i < n; i++) {
            double t = (double) i / (double) n;
            if (visitor.test(ax + dx * t, ay + dy * t, az + dz * t)) return true;
        }
        return visitor.test(b.x(), b.y(), b.z());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 与 {@code net.minecraft.util.Mth.wrapDegrees(float)} 等价的零依赖实现（值域 [-180, 180)）。 */
    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    /** 与 {@code wrapDegrees(float)} 同值域的 double 版本（值域 [-180, 180)）。 */
    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    /** 点 yaw 是否落在单点判定语义的目标集合内（与 facingInRange 的 yaw 部分逐位一致）。 */
    private static boolean pointInYawRange(float yaw, float w1, float span) {
        float delta = wrapDegrees(yaw - w1);
        if (span > 0.0f) return delta >= 0.0f && delta <= span;
        return delta >= 0.0f || delta <= span;
    }

    /** 点 yaw 是否落在从 {@code start} 起、正向长度 {@code span} 的弧内（{@code 0 < span < 180}）。 */
    private static boolean pointInPositiveArc(float start, float span, float yaw) {
        float delta = wrapDegrees(yaw - start);
        return delta >= 0.0f && delta <= span;
    }

    /** 点 yaw 是否落在从 {@code start} 起、带符号长度 {@code signedLen} 的最短弧内。 */
    private static boolean pointInMotionArc(float start, float signedLen, float yaw) {
        float delta = wrapDegrees(yaw - start);
        if (signedLen > 0.0f) return delta >= 0.0f && delta <= signedLen;
        return delta <= 0.0f && delta >= signedLen;
    }

    private static boolean hasXyz(JsonObject o) {
        return o != null && isNumber(o, "x") && isNumber(o, "y") && isNumber(o, "z");
    }

    private static boolean isNumber(JsonObject o, String key) {
        if (!o.has(key)) return false;
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static double num(JsonObject o, String key) {
        return o.get(key).getAsDouble();
    }
}
