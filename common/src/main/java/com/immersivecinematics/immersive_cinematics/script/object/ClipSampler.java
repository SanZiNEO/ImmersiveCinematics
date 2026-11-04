package com.immersivecinematics.immersive_cinematics.script.object;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;
import com.immersivecinematics.immersive_cinematics.spi.PropertyType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个片段的通用采样器：同一 {@code executionLocalTime} 采样该片段声明的全部 {@link SampledProperty}，
 * 写入预分配的 {@link SampledClip}。
 *
 * <p>构造期把 {@code clip.getObjects()} 编译成按槽位排布 {@link Channel}（槽位 + 编译后的关键帧
 * 数组 + 游标）；每个通道的关键帧在 {@link SampledProperty#compile(float, boolean)} 里已按
 * {@code duration}/{@code infinite} 过滤并转成 typed arrays。运行期只做数组访问与数值插值：
 * {@link #sample(double, SampledClip)} 内不 {@code new}、不拼字符串、不装箱、不查 {@code Map}。</p>
 *
 * <p>游标：顺序播放时从上次命中的段向前推进（O(1) 摊还）；时间回退（seek/pingpong）或游标越界时
 * 用 {@link SampledProperty.Compiled#frameCount()}/{@link SampledProperty.Compiled#timeAt(int)}
 * 二分定位。游标只是缓存，不改变采样结果。</p>
 *
 * <p>采样器不读 {@code loop}/{@code loop_mode}：执行层负责把时间归一化到
 * {@code [0, duration]}（无限执行只要求 {@code >= 0}）。{@code executionIndex} 由播放控制写入
 * {@link SampledClip}，采样器不碰。旧格式片段（无 {@code objects}）也能构造，此时没有通道，
 * {@code sample} 只写 {@code executionLocalTime}。</p>
 */
public final class ClipSampler {

    private final ObjectSlotLayout layout;
    private final Channel[] channels;
    /** {@link SampledProperty.Compiled#sampleNullableFloat(double, float[])} 的输出缓冲（每采样器一份）。 */
    private final float[] nullableScratch;
    /** 上次采样的局部时间；用于判定时间回退。 */
    private double lastTime;

    /**
     * @param clip 片段；{@code objects} 为空时构造出无通道的采样器
     */
    public ClipSampler(Clip clip) {
        Objects.requireNonNull(clip, "clip");
        this.layout = ObjectSlotLayout.of(clip.getTrackType());
        this.nullableScratch = new float[1];
        this.lastTime = Double.NEGATIVE_INFINITY;

        ClipObjects objects = clip.getObjects();
        float duration = clip.getDuration();
        boolean infinite = clip.isInfinite();
        List<Channel> built = new ArrayList<>(objects.asMap().size());
        for (Map.Entry<String, SampledProperty> entry : objects.asMap().entrySet()) {
            int slot = layout.slotOf(entry.getKey());
            if (slot < 0) {
                // 数据已由 parser/validator 校验；手工构造的未知路径不进入采样（运行时不做抛异常）。
                continue;
            }
            ObjectDef def = layout.defs().get(slot);
            SampledProperty property = entry.getValue();
            built.add(new Channel(slot, def.type(), def.defaultValue(), property.value(),
                    property.compile(duration, infinite)));
        }
        this.channels = built.toArray(new Channel[0]);
    }

    /** 按本采样器的槽位布局新建一块采样缓冲。 */
    public SampledClip newBuffer() {
        return new SampledClip(layout);
    }

    /**
     * 在 {@code localTime} 采样全部通道并写入 {@code out}。
     *
     * <p>只写本片段声明过的槽位；未声明的槽位保持调用前的值（复用缓冲时先 {@link SampledClip#clear()}
     * 可让“未提供”确定可见）。零分配。</p>
     *
     * @param localTime 执行局部时间（执行层已归一化）
     * @param out 目标缓冲；布局必须与本采样器一致
     */
    public void sample(double localTime, SampledClip out) {
        if (out == null || out.layout() != layout) {
            throw new IllegalArgumentException("SampledClip 的槽位布局与本采样器不一致");
        }
        out.executionLocalTime = localTime;
        boolean seek = localTime < lastTime;
        for (int index = 0; index < channels.length; index++) {
            Channel channel = channels[index];
            locateCursor(channel, localTime, seek);

            int slot = channel.slot;
            switch (channel.type) {
                case FLOAT -> {
                    out.floats[slot] = channel.compiled.sampleFloat(localTime, channel.floatFallback);
                    out.presence[slot] = channel.hasPresence;
                }
                case INT -> {
                    out.ints[slot] = channel.compiled.sampleInt(localTime, channel.intFallback);
                    out.presence[slot] = channel.hasPresence;
                }
                case BOOL -> {
                    out.bools[slot] = channel.compiled.sampleBool(localTime, channel.boolFallback);
                    out.presence[slot] = channel.hasPresence;
                }
                case STRING, ENUM, REF -> {
                    out.strings[slot] = channel.compiled.sampleString(localTime, channel.stringFallback);
                    out.presence[slot] = channel.hasPresence;
                }
                case NULLABLE_FLOAT -> {
                    if (channel.compiled.sampleNullableFloat(localTime, nullableScratch)) {
                        out.floats[slot] = nullableScratch[0];
                        out.presence[slot] = true;
                    } else if (channel.nullableFallbackPresent) {
                        out.floats[slot] = channel.nullableFallback;
                        out.presence[slot] = true;
                    } else {
                        out.presence[slot] = false;
                    }
                }
                // struct / event_record 不进数值数组：事件由事件运行时单独持有，struct 只在 value-only 声明里。
                default -> out.presence[slot] = channel.hasPresence;
            }
        }
        lastTime = localTime;
    }

    /** 路径 → 槽位（转发布局）；未知路径返回 {@code -1}。 */
    public int slotOf(String path) {
        return layout.slotOf(path);
    }

    /** 是否没有任何通道（旧格式片段或空 {@code objects}）。 */
    public boolean isEmpty() {
        return channels.length == 0;
    }

    /**
     * 推进通道游标：回退/越界时二分，否则从上次命中位置向前走。
     *
     * <p>不分配；二分用 {@code frameCount()/timeAt(int)}，结果只缓存段下标，采样值仍由
     * {@link SampledProperty.Compiled} 的采样方法按纯函数计算。</p>
     */
    private static void locateCursor(Channel channel, double localTime, boolean seek) {
        SampledProperty.Compiled compiled = channel.compiled;
        int count = compiled.frameCount();
        if (count == 0) {
            channel.cursor = -1;
            return;
        }
        int cursor = channel.cursor;
        if (seek || cursor < 0 || cursor >= count) {
            channel.cursor = binarySearch(compiled, localTime, count);
            return;
        }
        while (cursor + 1 < count && compiled.timeAt(cursor + 1) <= localTime) {
            cursor++;
        }
        channel.cursor = cursor;
    }

    /** 最后一个 {@code timeAt(i) <= localTime} 的下标；首帧前返回 0。 */
    private static int binarySearch(SampledProperty.Compiled compiled, double localTime, int count) {
        if (localTime < compiled.timeAt(0)) {
            return 0;
        }
        int low = 0;
        int high = count - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (compiled.timeAt(middle) <= localTime) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** 一条采样通道：槽位 + 编译好的关键帧数组 + 游标。 */
    private static final class Channel {

        private final int slot;
        private final PropertyType type;
        private final SampledProperty.Compiled compiled;
        /** 属性是否有值：静态值 / 范围内关键帧 / schema 缺省值任一存在。 */
        private final boolean hasPresence;
        private final float floatFallback;
        private final int intFallback;
        private final boolean boolFallback;
        private final String stringFallback;
        private final float nullableFallback;
        private final boolean nullableFallbackPresent;
        /** 上次命中的段下标（最后一个 {@code timeAt(i) <= lastTime}）；{@code -1} = 未定位。 */
        private int cursor;

        private Channel(int slot, PropertyType type, Object defaultValue, Object value,
                        SampledProperty.Compiled compiled) {
            this.slot = slot;
            this.type = type;
            this.compiled = compiled;
            this.cursor = -1;
            this.hasPresence = value != null || compiled.frameCount() > 0 || defaultValue != null;
            this.floatFallback = defaultValue instanceof Number number ? number.floatValue() : 0f;
            this.intFallback = defaultValue instanceof Number number ? number.intValue() : 0;
            this.boolFallback = defaultValue instanceof Boolean bool ? bool : false;
            this.stringFallback = defaultValue instanceof String string ? string : null;
            if (defaultValue instanceof Number number) {
                this.nullableFallback = number.floatValue();
                this.nullableFallbackPresent = true;
            } else {
                this.nullableFallback = 0f;
                this.nullableFallbackPresent = false;
            }
        }
    }
}
