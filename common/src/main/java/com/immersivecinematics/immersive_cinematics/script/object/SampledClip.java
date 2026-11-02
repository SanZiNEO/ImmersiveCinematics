package com.immersivecinematics.immersive_cinematics.script.object;

import java.util.Arrays;
import java.util.Objects;

/**
 * 单个片段在某一 {@code executionLocalTime} 的采样结果：按 {@link ObjectSlotLayout} 预分配的 typed arrays。
 *
 * <p>同一轨道类型的所有片段共用槽位布局，所以缓冲可以按（播放实例，片段）预分配并逐帧复用：
 * 采样只写 primitive 数组，不分配 {@code Map}/临时对象。数组在构造期按 {@link ObjectSlotLayout#size()}
 * 定长分配，之后不再增长。</p>
 *
 * <p>数值按槽位类型分数组存放（{@code float[]}/{@code int[]}/{@code boolean[]}/{@code String[]}）；
 * {@code event_record}/{@code struct} 属性不进数值数组（事件由事件运行时单独持有，struct 只在
 * {@code value-only} 声明里存在），但它们的 {@code presence} 仍按属性是否声明填写。
 * {@code presence} 区分“作者没写”与“写了默认值”。</p>
 */
public final class SampledClip {

    /** 本次执行的第几次（播放控制写入；采样器不改）。 */
    public int executionIndex;

    /** 本次执行的片段局部时间。 */
    public double executionLocalTime;

    private final ObjectSlotLayout layout;

    /** 按槽位的 {@code float} 值（{@code float}/{@code nullable_float}）。 */
    final float[] floats;

    /** 按槽位的 {@code int} 值。 */
    final int[] ints;

    /** 按槽位的 {@code boolean} 值。 */
    final boolean[] bools;

    /** 按槽位的 {@code string}/{@code enum}/{@code ref} 值。 */
    final String[] strings;

    /** 按槽位是否有值（含静态值/范围内关键帧/schema 缺省值）。 */
    final boolean[] presence;

    /**
     * @param layout 该片段轨道类型的槽位布局
     */
    public SampledClip(ObjectSlotLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
        int size = layout.size();
        this.floats = new float[size];
        this.ints = new int[size];
        this.bools = new boolean[size];
        this.strings = new String[size];
        this.presence = new boolean[size];
    }

    /**
     * 复用前重置：{@code presence} 全清，{@code executionIndex}/{@code executionLocalTime} 归零。
     *
     * <p>数值数组内容不清——{@code presence=false} 时读到的值没有意义，调用方必须先看
     * {@link #present(int)}。零分配。</p>
     */
    public void clear() {
        executionIndex = 0;
        executionLocalTime = 0d;
        Arrays.fill(presence, false);
    }

    /** 槽位布局。 */
    public ObjectSlotLayout layout() {
        return layout;
    }

    /** 槽位的 {@code float} 值；调用方保证槽位类型与读取方法匹配。 */
    public float f(int slot) {
        return floats[slot];
    }

    /** 槽位的 {@code int} 值；调用方保证槽位类型与读取方法匹配。 */
    public int i(int slot) {
        return ints[slot];
    }

    /** 槽位的 {@code boolean} 值；调用方保证槽位类型与读取方法匹配。 */
    public boolean b(int slot) {
        return bools[slot];
    }

    /** 槽位的 {@code String} 值；未提供时为 {@code null}。 */
    public String s(int slot) {
        return strings[slot];
    }

    /** 槽位是否有值（静态值 / 范围内关键帧 / schema 缺省值任一存在）。 */
    public boolean present(int slot) {
        return presence[slot];
    }
}
