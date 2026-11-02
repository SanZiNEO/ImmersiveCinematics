package com.immersivecinematics.immersive_cinematics.script.path;

import com.immersivecinematics.immersive_cinematics.math.path.PathEvaluator;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 路径绑定的运行期求值器：按片段缓存绑定列表，找出覆盖当前 {@code localTime} 的条目，按
 * {@code timing → 归一化弧长} 求几何位置与切线（{@code path-collection.md} §3.4）。
 *
 * <p>{@link #position} / {@link #tangent} 返回<b>完整</b> {@link Vec3}；选轴合并由调用方完成
 * （只取 {@link PathBinding#axes()} 选中的分量，未选轴保留采样属性值）。端点由调用方给出的
 * {@link PositionSampler} 在 {@code startTime}/{@code endTime} 采样——必须使用独立于主采样缓冲的
 * 缓冲，避免破坏同一帧的 {@code SampledClip}。</p>
 *
 * <p>绑定列表按 {@link Clip} 对象身份缓存（{@link IdentityHashMap}）；{@link #clear()} 丢弃缓存与
 * 几何求值缓存。稳态调用不新建集合或除 {@link Vec3} 外的临时对象；仅客户端主线程使用。</p>
 */
public final class PathBindingResolver {

    private static final PathBinding[] EMPTY = new PathBinding[0];

    private final PathBindingSource source;
    private final PathEvaluator evaluator = new PathEvaluator();
    private final Map<Clip, PathBinding[]> bindings = new IdentityHashMap<>();

    /**
     * @param source 绑定来源（不可为 null；无绑定用 {@link PathBindingSource#NONE}）
     */
    public PathBindingResolver(PathBindingSource source) {
        this.source = Objects.requireNonNull(source, "路径绑定来源不能为 null");
    }

    /**
     * 覆盖 {@code localTime} 的绑定：{@code startTime <= localTime <= endTime}。
     *
     * <p>多条同时覆盖时取「起点更晚」的那条（最后开始者胜）；起点相同取来源列表靠前者。
     * 无覆盖 = {@code null}。</p>
     */
    public PathBinding bindingFor(Clip clip, double localTime) {
        PathBinding best = null;
        for (PathBinding binding : bindings(clip)) {
            if (localTime < binding.startTime() || localTime > binding.endTime()) {
                continue;
            }
            if (best == null || binding.startTime() > best.startTime()) {
                best = binding;
            }
        }
        return best;
    }

    /**
     * 绑定在 {@code localTime} 的路径位置（完整三轴；调用方按 {@link PathBinding#axes()} 合并）。
     *
     * @param sampler 端点在表达空间的采样器（采样 {@code position.x/y/z}）
     */
    public Vec3 position(PathBinding binding, Clip clip, double localTime, PositionSampler sampler) {
        Vec3 from = sampler.positionAt(clip, binding.startTime());
        Vec3 to = sampler.positionAt(clip, binding.endTime());
        return evaluator.position(binding.geometry(), from, to, progress(binding, localTime));
    }

    /**
     * 绑定在 {@code localTime} 的路径一阶导（未归一化，方向用；调用方按选轴与采样导数合成）。
     */
    public Vec3 tangent(PathBinding binding, Clip clip, double localTime, PositionSampler sampler) {
        Vec3 from = sampler.positionAt(clip, binding.startTime());
        Vec3 to = sampler.positionAt(clip, binding.endTime());
        return evaluator.tangent(binding.geometry(), from, to, progress(binding, localTime));
    }

    /** 丢弃片段绑定缓存与几何求值缓存（停播 / 脚本替换 / seek）。 */
    public void clear() {
        bindings.clear();
        evaluator.clear();
    }

    /** 表达空间位置采样：{@code position.x/y/z} 的采样值（未声明 = schema 缺省）。 */
    @FunctionalInterface
    public interface PositionSampler {

        /**
         * @param clip      目标片段
         * @param localTime 片段局部时间（端点时刻）
         */
        Vec3 positionAt(Clip clip, double localTime);
    }

    private PathBinding[] bindings(Clip clip) {
        PathBinding[] cached = bindings.get(clip);
        if (cached != null) {
            return cached;
        }
        List<PathBinding> list = source.bindingsFor(clip);
        PathBinding[] resolved = list == null || list.isEmpty() ? EMPTY : list.toArray(EMPTY);
        bindings.put(clip, resolved);
        return resolved;
    }

    /** {@code u → timing.progress(u) → s}，两次 clamp（NaN 收敛到 0）。 */
    private static double progress(PathBinding binding, double localTime) {
        double duration = (double) binding.endTime() - binding.startTime();
        double u = (localTime - binding.startTime()) / duration;
        if (!(u > 0.0)) {
            u = 0.0;
        } else if (u > 1.0) {
            u = 1.0;
        }
        double s = binding.timing().progress(u);
        if (!(s > 0.0)) {
            return 0.0;
        }
        return s < 1.0 ? s : 1.0;
    }
}
