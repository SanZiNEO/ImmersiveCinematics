package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.math.MathModel;
import com.immersivecinematics.immersive_cinematics.math.MathModelInstance;
import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 一条 CAMERA 轨的运动模型实例槽：实例归属「播放实例 → lane → 通道」，每 lane 至多 6 个实例。
 *
 * 通道与值槽（顺序 = 六参数写入顺序）：0~2 = 位置 x/y/z（VECTOR，世界空间方块坐标）；
 * 3 = yaw、4 = pitch、5 = roll（ANGLE，度）；6 = fov、7 = zoom（SCALAR）。
 * 定义来源 = 本 lane 本帧生效片段的 clip 级 {@code motion_*} 字段（解析期已归一，见 MathModelRegistry）；
 * 缺省 null = 该通道不接模型（本类不触碰对应值槽）。
 * 生命周期：片段切换时按通道比较定义对象（相等性 = 对象同一；解析期按 {@code type} + 参数键值 + 域驻留，
 * 见 {@code ScriptParser.parseMathModel}）——不变 = 实例与状态延续、变化 = 重建、变 null = 销毁；
 * {@link #reset()} 供 seek 全实例归位。lane 槽按层号索引、按需创建；某层本帧没有 lane 时实例保留（状态延续），
 * 下次被占用时按同一判据处置；模型实例只在片段边界建 / 销毁。
 * 线程与分配契约：仅客户端主线程；每渲染帧每 lane 每通道至多一次 step；稳态逐帧路径零分配（不新建集合 /
 * 不装箱）——分配只发生在片段边界（首建 lane / 通道槽、定义变化时 instantiate + 通道缓冲）。
 */
final class LaneMotionStepper {

    /** 六参数缓冲区长度：0~2 = 位置 x/y/z，3~7 = yaw/pitch/roll/fov/zoom。 */
    static final int VALUES = 8;

    /** 位置通道位（{@link #step} 返回值）：位置生效时调用方需按缓冲重建坐标；其余通道的值由 step 就地写回。 */
    static final int CH_POSITION = 1;

    /** 通道字段名（顺序 = 值槽顺序：位置 3 槽 + yaw/pitch/roll/fov/zoom 各 1 槽）。 */
    private static final String[] FIELDS = {
            "motion_position", "motion_yaw", "motion_pitch", "motion_roll", "motion_fov", "motion_zoom"};

    /** 每通道值槽数（位置 = 3，其余单通道 = 1）。 */
    private static final int[] SLOTS = {3, 1, 1, 1, 1, 1};

    /** 通道数（每 lane 至多 6 个实例）。 */
    private static final int CHANNELS = FIELDS.length;

    /** lane 槽（索引 = 距顶层 lane 的层号）；按需增长——只在带定义的 lane 首次求值时。 */
    private Lane[] lanes = new Lane[2];

    /**
     * 跑一条 lane 本帧的六通道模型 step：目标值由调用方写入 {@code values}，实际值就地写回。
     *
     * @param laneRank 距顶层 lane 的层号（{@code 0} = 写入全局相机状态的那条 lane）
     * @param clip     本 lane 本帧的生效片段（定义来源；重叠窗内 = 进入的片段）
     * @param t        本帧分发给本轨的 elapsed（秒，单调）
     * @param values   六参数缓冲区（长度 ≥ {@link #VALUES}）：入 = 目标值、出 = 实际值
     * @return 生效通道位掩码（位 i = {@link #FIELDS} 序号 i 的通道生效）；{@code 0} = 本 lane 无定义
     *         （{@code values} 逐位未变）。调用方按 {@link #CH_POSITION} 判定位置通道，其余通道直接取回缓冲。
     */
    int step(int laneRank, Clip clip, double t, double[] values) {
        Lane lane = laneRank < lanes.length ? lanes[laneRank] : null;
        if (lane == null) {
            if (!hasDefinition(clip)) return 0; // 缺省关：不建 lane 槽（零分配；每帧探测一次见 hasDefinition）
            lane = new Lane();
            grow(laneRank);
            lanes[laneRank] = lane;
        }
        if (lane.clip != clip) {
            lane.redefine(clip);
        }
        return lane.apply(t, values);
    }

    /** seek：全实例归位（下一次 step 以当帧输入为初值）并清 t 基准（下一帧 dt = 0）。 */
    void reset() {
        for (Lane lane : lanes) {
            if (lane != null) {
                lane.reset();
            }
        }
    }

    /**
     * 该片段是否至少有一个 {@code motion_*} 定义（建槽判据）：lane 槽不存在期间每帧调用一次（6 次 map 查表，
     * O(1)）；建槽后不再调用。缺省关（无定义）= 取值逐位不变 + 零分配，但并非零工作——每帧仍有这 6 次查表，
     * 调用方另有 8 次缓冲写入。
     */
    private static boolean hasDefinition(Clip clip) {
        for (String field : FIELDS) {
            if (clip.getObject(field) != null) {
                return true;
            }
        }
        return false;
    }

    /** 扩容 lane 槽数组到至少 {@code laneRank + 1} 个槽（只在建槽时调用）。 */
    private void grow(int laneRank) {
        if (laneRank < lanes.length) return;
        Lane[] grown = new Lane[laneRank + 1];
        System.arraycopy(lanes, 0, grown, 0, lanes.length);
        lanes = grown;
    }

    /** 一条 lane 的六通道实例槽 + 本帧生效片段。 */
    private static final class Lane {

        /** 本 lane 本帧的生效片段（定义变化判据 = 对象同一；解析期按 type + 参数键值驻留同一定义）。 */
        private Clip clip;

        /** 每通道实例槽（顺序见 {@link #FIELDS}）。 */
        private final Slot[] slots = new Slot[CHANNELS];

        Lane() {
            for (int i = 0; i < CHANNELS; i++) {
                slots[i] = new Slot(SLOTS[i]);
            }
        }

        /**
         * 片段切换：逐通道比对定义对象——不变 = 实例与状态延续、变化 = 重建（重置）、变 null = 销毁。
         * 只在片段边界执行（逐帧路径零分配前提）。
         */
        private void redefine(Clip clip) {
            this.clip = clip;
            for (int i = 0; i < CHANNELS; i++) {
                MathModel definition = clip.getObject(FIELDS[i]);
                Slot slot = slots[i];
                if (slot.definition != definition) {
                    slot.definition = definition;
                    slot.instance = definition == null ? null : definition.instantiate();
                    slot.in = definition == null ? null : new double[slot.arity];
                    slot.out = definition == null ? null : new double[slot.arity];
                    slot.tPrev = Double.NaN;
                }
            }
        }

        /**
         * 逐通道 step（有实例的通道才推进）：dt = 本帧 t − 上一帧 t（首步 = 0 = 播种），
         * 模型内部按 {@link MathModelInstance#MAX_DT} 钳制、负值按 0。
         *
         * @return 生效通道位掩码（位 i = 通道 i，与 {@link #FIELDS} 同序）
         */
        private int apply(double t, double[] values) {
            int applied = 0;
            int offset = 0;
            for (int i = 0; i < CHANNELS; i++) {
                Slot slot = slots[i];
                if (slot.instance != null) {
                    int arity = slot.arity;
                    for (int c = 0; c < arity; c++) {
                        slot.in[c] = values[offset + c];
                    }
                    double dt = Double.isNaN(slot.tPrev) ? 0.0 : t - slot.tPrev;
                    slot.tPrev = t;
                    slot.instance.step(t, dt, slot.in, slot.out);
                    for (int c = 0; c < arity; c++) {
                        values[offset + c] = slot.out[c];
                    }
                    applied |= 1 << i;
                }
                offset += slot.arity;
            }
            return applied;
        }

        /** seek：有实例的通道归位（{@code reset} 后首次 step 以输入为初值）+ 清 t 基准。 */
        private void reset() {
            for (Slot slot : slots) {
                if (slot.instance != null) {
                    slot.instance.reset();
                    slot.tPrev = Double.NaN;
                }
            }
        }
    }

    /** 一个通道的实例槽：定义 / 实例 / 输入输出缓冲（实例非 null ⟹ 缓冲非 null）。 */
    private static final class Slot {

        /** 通道值槽数（位置 = 3，其余 = 1；定义域由解析期注入的通道域保证一致）。 */
        private final int arity;

        /** 当前定义；{@code null} = 该通道无模型（实例已销毁）。 */
        private MathModel definition;

        /** 当前实例（{@code null} = 无定义）；状态由实例独占。 */
        private MathModelInstance instance;

        /** 输入缓冲（长度 = {@link #arity}），与 {@link #out} 一起只在建实例时分配。 */
        private double[] in;

        /** 输出缓冲（长度 = {@link #arity}）。 */
        private double[] out;

        /** 上一帧 step 的 t（秒）；NaN = 尚未 step（首步 dt = 0）。 */
        private double tPrev = Double.NaN;

        Slot(int arity) {
            this.arity = arity;
        }
    }
}
