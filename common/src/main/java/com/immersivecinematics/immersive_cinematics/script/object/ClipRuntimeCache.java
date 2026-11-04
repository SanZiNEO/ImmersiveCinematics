package com.immersivecinematics.immersive_cinematics.script.object;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单条轨道的采样装配层：按片段对象身份缓存 {@link ClipSampler} / {@link SampledClip} /
 * {@link ClipValueView}，让轨道播放器每帧只做「拿缓冲 → 采样 → 读槽位」。
 *
 * <p>缓存键是 {@link Clip} 的对象身份（{@link IdentityHashMap}）：片段没有重写 {@code equals}，
 * 两个内容相同的片段仍是两份独立执行状态。槽位布局在构造期按轨道类型解析一次，缓冲也按该布局分配，
 * 运行期不再查 schema。</p>
 *
 * <p>{@link #sample(Clip, double)} 复用该片段上次采样用的缓冲：先 {@link SampledClip#clear()} 再写入，
 * 因此「未声明」的槽位在每次采样后都是确定不可见的。稳态下不分配集合/字符串/装箱。时间不做校验或
 * 归一化——调用方传入已归一化的 {@code executionLocalTime}。</p>
 *
 * <p>本类不持有线程状态，但缓存与缓冲都是可变的：仅在客户端主线程使用。脚本替换或停播时调用
 * {@link #clear()} 丢弃全部缓存，下次访问会按新片段重建。</p>
 */
public final class ClipRuntimeCache {

    private final ObjectSlotLayout layout;
    private final Map<Clip, Entry> entries = new IdentityHashMap<>();

    /**
     * @param type 本轨道类型；{@code null} 不接受
     */
    public ClipRuntimeCache(TrackTypeId type) {
        Objects.requireNonNull(type, "type");
        this.layout = ObjectSlotLayout.of(type);
    }

    /** 片段的采样器；惰性创建，按对象身份缓存。 */
    public ClipSampler sampler(Clip clip) {
        Entry entry = entry(clip);
        ClipSampler sampler = entry.sampler;
        if (sampler == null) {
            sampler = new ClipSampler(clip);
            entry.sampler = sampler;
        }
        return sampler;
    }

    /**
     * 在 {@code executionLocalTime} 采样一次，返回该片段复用的缓冲。
     *
     * <p>同一片段多次调用返回同一个 {@link SampledClip} 实例；每次采样前先 {@link SampledClip#clear()}。</p>
     */
    public SampledClip sample(Clip clip, double executionLocalTime) {
        Entry entry = entry(clip);
        SampledClip buffer = entry.buffer;
        if (buffer == null) {
            buffer = newBuffer();
            entry.buffer = buffer;
        }
        buffer.clear();
        samplerOf(entry, clip).sample(executionLocalTime, buffer);
        return buffer;
    }

    /**
     * 该片段上次采样用的缓冲；从未采样过时创建并返回一块已清空的缓冲（不写入采样值）。
     */
    public SampledClip buffer(Clip clip) {
        Entry entry = entry(clip);
        SampledClip buffer = entry.buffer;
        if (buffer == null) {
            buffer = newBuffer();
            entry.buffer = buffer;
        }
        return buffer;
    }

    /** 片段的 value-only 读取视图；惰性创建，按对象身份缓存。 */
    public ClipValueView values(Clip clip) {
        Entry entry = entry(clip);
        ClipValueView values = entry.values;
        if (values == null) {
            values = new ClipValueView(clip);
            entry.values = values;
        }
        return values;
    }

    /** 脚本替换/停播：丢弃全部片段的缓存。 */
    public void clear() {
        entries.clear();
    }

    /** 当前缓存的片段数（探针用）。 */
    public int size() {
        return entries.size();
    }

    /** 取或建片段条目；只在首次见到该片段时插入。 */
    private Entry entry(Clip clip) {
        Objects.requireNonNull(clip, "clip");
        Entry entry = entries.get(clip);
        if (entry == null) {
            entry = new Entry();
            entries.put(clip, entry);
        }
        return entry;
    }

    /** 取或建采样器（{@link #sample} 内部用，避免二次查找条目）。 */
    private static ClipSampler samplerOf(Entry entry, Clip clip) {
        ClipSampler sampler = entry.sampler;
        if (sampler == null) {
            sampler = new ClipSampler(clip);
            entry.sampler = sampler;
        }
        return sampler;
    }

    /** 按本轨布局分配缓冲；布局与采样器一致（同一轨道类型的布局实例共享）。 */
    private SampledClip newBuffer() {
        return new SampledClip(layout);
    }

    /** 单个片段的缓存条目：三个部件都惰性创建。 */
    private static final class Entry {
        private ClipSampler sampler;
        private SampledClip buffer;
        private ClipValueView values;
    }
}
