package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * 点源快照表：时态 = 快照（{@link PointSource.Time#SNAPSHOT}）的选择器点源按「片段身份 + 槽位」分桶缓存取点。
 *
 * 键 = {@link Clip} 引用（同一片段的所有帧读同一桶，morph 两侧各持各的）+ 槽位（{@link #SLOTS} 个，与点源字段
 * 一一对应）；值 = 已展开偏移的世界空间方块坐标。未捕获 = 无表项（{@code null} 不入表：目标搜索期间不钉死
 * 「无点源」，下一帧继续取）。片段切换 / loop 折返不清（延续）；seek / 停止 / 脚本替换由持有方
 * {@link #clear()}（seek = 硬定位，快照重取）。
 * 分配契约：仅客户端主线程，位于每帧求值路径；命中 / 未命中路径都不新建集合、不装箱（线性扫描引用比较），
 * 分配只发生在带快照点源的新片段首次入表（槽数组扩容）——总次数 ∝ 片段数而非帧数。
 */
public final class PointSourceSnapshots {

    /** 槽位数（与点源字段一一对应，见 {@code SLOT_*}）。 */
    public static final int SLOTS = 6;

    /** 位置点源 {@code facing_origin}（位置基准点 / 基准坐标系原点）。 */
    public static final int SLOT_FACING_ORIGIN = 0;

    /** 基准坐标系前轴目标 {@code facing_target}。 */
    public static final int SLOT_FACING_TARGET = 1;

    /** 连线起点 {@code yaw_base_from}。 */
    public static final int SLOT_YAW_BASE_FROM = 2;

    /** 连线终点 {@code yaw_base_to}。 */
    public static final int SLOT_YAW_BASE_TO = 3;

    /** 注视点 {@code look_at}。 */
    public static final int SLOT_LOOK_AT = 4;

    /**
     * 位置侧点源作为 {@code selector_anchor = origin} 的锚点取值。与 {@link #SLOT_FACING_ORIGIN} 分桶：
     * 该路径固定按锚点 {@code camera} 解析（见 {@code EntityTargetResolver#originPointSource}），
     * 与位置基准点按配置锚点解析的结果可以不同，故各持各的首次取点。
     */
    public static final int SLOT_ANCHOR_ORIGIN = 5;

    /** 片段槽（引用比较）；按需增长——只在带快照点源的新片段首次捕获时。 */
    private Clip[] clips = new Clip[4];

    /** 取点值（索引 = 片段槽 × {@link #SLOTS} + 槽位）；{@code null} = 该槽未捕获。 */
    private Vec3[] values = new Vec3[4 * SLOTS];

    /** 已入表片段数。 */
    private int size;

    /** 该（片段，槽位）已捕获的取点；未捕获 = {@code null}（本类不存 {@code null}，故 {@code null} 即未命中）。 */
    public Vec3 get(Clip clip, int slot) {
        for (int i = 0; i < size; i++) {
            if (clips[i] == clip) return values[i * SLOTS + slot];
        }
        return null;
    }

    /** 记录该（片段，槽位）的取点（调用方保证非 {@code null}；重复写 = 覆盖）。 */
    public void put(Clip clip, int slot, Vec3 value) {
        for (int i = 0; i < size; i++) {
            if (clips[i] == clip) {
                values[i * SLOTS + slot] = value;
                return;
            }
        }
        if (size == clips.length) grow();
        clips[size] = clip;
        values[size * SLOTS + slot] = value;
        size++;
    }

    /** 全清（seek / 停止 / 脚本替换）：容量保留，下一次求值重新捕获。 */
    public void clear() {
        Arrays.fill(clips, null);
        Arrays.fill(values, null);
        size = 0;
    }

    /** 槽数组翻倍（只在新片段入表时调用；分配次数 ∝ 片段数）。 */
    private void grow() {
        int capacity = clips.length * 2;
        Clip[] grownClips = new Clip[capacity];
        Vec3[] grownValues = new Vec3[capacity * SLOTS];
        System.arraycopy(clips, 0, grownClips, 0, size);
        System.arraycopy(values, 0, grownValues, 0, size * SLOTS);
        clips = grownClips;
        values = grownValues;
    }
}
