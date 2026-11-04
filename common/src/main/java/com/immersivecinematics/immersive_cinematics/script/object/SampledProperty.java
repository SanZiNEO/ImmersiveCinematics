package com.immersivecinematics.immersive_cinematics.script.object;

import com.immersivecinematics.immersive_cinematics.spi.Interp;
import com.immersivecinematics.immersive_cinematics.spi.PropertyMode;
import com.immersivecinematics.immersive_cinematics.spi.PropertyType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单个采样属性：路径 + 形态 + 类型 + 插值 + 静态值 + 关键帧。
 *
 * <p>实例不可变。构造期做形态校验：
 * <ul>
 *   <li>{@code mode=value} 不许 keyframes，且 {@code interp=none}；</li>
 *   <li>{@code mode=event} 必须有 keyframes、{@code type=event_record}、{@code interp=none}；</li>
 *   <li>{@code mode=animated} 必须 {@code interp!=none}、{@code type!=event_record}；</li>
 *   <li>keyframes 的 time 必须有限且严格递增。</li>
 * </ul>
 *
 * <p>{@link #compile(float, boolean)} 过滤范围内关键帧并编译成按类型的数组；采样器只读编译结果。
 * 采样规则见 {@code object-model.md} §3.4：范围内无帧 → value；单帧恒值；{@code t} 在首帧前取首值、
 * 末帧后取末值；区间内按 interp 求值。</p>
 */
public final class SampledProperty {

    private final String path;
    private final PropertyMode mode;
    private final PropertyType type;
    private final Interp interp;
    private final Object value;
    private final List<ObjectKeyframe> keyframes;

    /**
     * @param path 点分路径（{@link PropertyPath#isValid} 必须为真）
     * @param mode 形态
     * @param type 值类型
     * @param interp 插值方式（显式声明；缺省推导由 {@link Interp#infer} 完成）
     * @param value 静态值；{@code nullable_float} 允许 {@code null}
     * @param keyframes 关键帧；{@code null} 等价空表
     */
    public SampledProperty(String path, PropertyMode mode, PropertyType type, Interp interp,
                           Object value, List<ObjectKeyframe> keyframes) {
        if (!PropertyPath.isValid(path)) {
            throw new IllegalArgumentException("非法属性路径: " + path);
        }
        this.path = path;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.type = Objects.requireNonNull(type, "type");
        this.interp = Objects.requireNonNull(interp, "interp");
        this.value = value;

        List<ObjectKeyframe> copy = keyframes == null ? List.of() : List.copyOf(keyframes);
        if (type == PropertyType.STRUCT) {
            if (mode != PropertyMode.VALUE) {
                throw new IllegalArgumentException(path + ": struct 只允许 mode=value");
            }
            if (interp != Interp.NONE) {
                throw new IllegalArgumentException(path + ": struct 的 interp 必须为 none");
            }
            if (!copy.isEmpty()) {
                throw new IllegalArgumentException(path + ": struct 不允许 keyframes");
            }
        }
        switch (mode) {
            case VALUE -> {
                if (!copy.isEmpty()) {
                    throw new IllegalArgumentException(path + ": mode=value 不允许 keyframes");
                }
                if (interp != Interp.NONE) {
                    throw new IllegalArgumentException(path + ": mode=value 时 interp 必须为 none");
                }
            }
            case EVENT -> {
                if (copy.isEmpty()) {
                    throw new IllegalArgumentException(path + ": mode=event 必须有 keyframes");
                }
                if (type != PropertyType.EVENT_RECORD) {
                    throw new IllegalArgumentException(path + ": mode=event 的 type 必须是 event_record");
                }
                if (interp != Interp.NONE) {
                    throw new IllegalArgumentException(path + ": mode=event 时 interp 必须为 none");
                }
            }
            case ANIMATED -> {
                if (interp == Interp.NONE) {
                    throw new IllegalArgumentException(path + ": mode=animated 需要插值方式");
                }
                if (type == PropertyType.EVENT_RECORD) {
                    throw new IllegalArgumentException(path + ": event_record 只能用于 mode=event");
                }
            }
        }
        float previous = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < copy.size(); i++) {
            ObjectKeyframe keyframe = copy.get(i);
            if (keyframe == null) {
                throw new IllegalArgumentException(path + ".keyframes[" + i + "]: 关键帧不能为空");
            }
            float time = keyframe.time();
            if (!Float.isFinite(time)) {
                throw new IllegalArgumentException(path + ".keyframes[" + i + "].time: 必须为有限数字");
            }
            if (i > 0 && time <= previous) {
                throw new IllegalArgumentException(path + ".keyframes[" + i + "].time: 关键帧时间必须单调递增，前一帧: " + previous);
            }
            previous = time;
        }
        this.keyframes = copy;
    }

    /** 点分路径。 */
    public String path() {
        return path;
    }

    /** 形态。 */
    public PropertyMode mode() {
        return mode;
    }

    /** 值类型。 */
    public PropertyType type() {
        return type;
    }

    /** 插值方式。 */
    public Interp interp() {
        return interp;
    }

    /** 静态值；可能为 {@code null}。 */
    public Object value() {
        return value;
    }

    /** 关键帧；不可变、已校验单调递增。 */
    public List<ObjectKeyframe> keyframes() {
        return keyframes;
    }

    /**
     * 过滤范围内关键帧并编译成采样视图。
     *
     * <p>范围：有限片段 {@code 0 <= time <= duration}；无限执行 {@code time >= 0}。
     * 越界关键帧按正常语义丢弃，不报错。</p>
     *
     * @param duration 片段时长；无限执行时忽略
     * @param infinite 是否无限执行（负 duration 的格式表示）
     * @return 不可变编译视图
     */
    public Compiled compile(float duration, boolean infinite) {
        List<ObjectKeyframe> kept = new ArrayList<>(keyframes.size());
        for (ObjectKeyframe keyframe : keyframes) {
            float time = keyframe.time();
            boolean inRange = infinite ? time >= 0f : (time >= 0f && time <= duration);
            if (inRange) {
                kept.add(keyframe);
            }
        }

        int count = kept.size();
        float[] times = new float[count];
        for (int i = 0; i < count; i++) {
            times[i] = kept.get(i).time();
        }

        float[] floats = null;
        boolean[] present = null;
        int[] ints = null;
        boolean[] bools = null;
        String[] strings = null;
        Object[] records = null;

        if (type == PropertyType.FLOAT) {
            floats = new float[count];
            for (int i = 0; i < count; i++) {
                floats[i] = requireFloat(kept.get(i), i);
            }
        } else if (type == PropertyType.NULLABLE_FLOAT) {
            floats = new float[count];
            present = new boolean[count];
            for (int i = 0; i < count; i++) {
                Float number = optionalFloat(kept.get(i), i);
                if (number != null) {
                    floats[i] = number;
                    present[i] = true;
                }
            }
        } else if (type == PropertyType.INT) {
            ints = new int[count];
            for (int i = 0; i < count; i++) {
                ints[i] = requireInt(kept.get(i), i);
            }
        } else if (type == PropertyType.BOOL) {
            bools = new boolean[count];
            for (int i = 0; i < count; i++) {
                bools[i] = requireBoolean(kept.get(i), i);
            }
        } else if (type == PropertyType.STRING || type == PropertyType.ENUM || type == PropertyType.REF) {
            strings = new String[count];
            for (int i = 0; i < count; i++) {
                strings[i] = requireString(kept.get(i), i);
            }
        } else if (type == PropertyType.EVENT_RECORD) {
            records = new Object[count];
            for (int i = 0; i < count; i++) {
                records[i] = requireRecord(kept.get(i), i);
            }
        }
        return new Compiled(type, interp, value, times, floats, present, ints, bools, strings, records);
    }

    private float requireFloat(ObjectKeyframe keyframe, int index) {
        Object raw = keyframe.value();
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要有限数字");
        }
        float result = number.floatValue();
        if (!Float.isFinite(result)) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要有限数字");
        }
        return result;
    }

    private Float optionalFloat(ObjectKeyframe keyframe, int index) {
        Object raw = keyframe.value();
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要 null 或有限数字");
        }
        float result = number.floatValue();
        if (!Float.isFinite(result)) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要 null 或有限数字");
        }
        return result;
    }

    private int requireInt(ObjectKeyframe keyframe, int index) {
        Object raw = keyframe.value();
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要整数");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.floor(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要整数");
        }
        return (int) value;
    }

    private boolean requireBoolean(ObjectKeyframe keyframe, int index) {
        Object raw = keyframe.value();
        if (!(raw instanceof Boolean result)) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要布尔值");
        }
        return result;
    }

    private String requireString(ObjectKeyframe keyframe, int index) {
        Object raw = keyframe.value();
        if (!(raw instanceof String result)) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要字符串");
        }
        return result;
    }

    private Object requireRecord(ObjectKeyframe keyframe, int index) {
        Object raw = keyframe.value();
        if (raw == null) {
            throw new IllegalArgumentException(path + ".keyframes[" + index + "].value: 需要事件记录对象");
        }
        return raw;
    }

    /**
     * 编译后的不可变采样视图：内部为 {@code float[] times} + 按类型的数组，无装箱。
     *
     * <p>实例由 {@link SampledProperty#compile(float, boolean)} 创建；采样是纯函数，不修改状态。
     * 数据已假设通过 parser / validator：采样路径不做类型检查、不抛异常、不分配临时对象。</p>
     */
    public static final class Compiled {

        private final PropertyType type;
        private final Interp interp;
        private final Object value;
        private final float[] times;
        private final float[] floats;
        private final boolean[] present;
        private final int[] ints;
        private final boolean[] bools;
        private final String[] strings;
        private final Object[] records;

        private Compiled(PropertyType type, Interp interp, Object value, float[] times,
                         float[] floats, boolean[] present, int[] ints, boolean[] bools,
                         String[] strings, Object[] records) {
            this.type = type;
            this.interp = interp;
            this.value = value;
            this.times = times;
            this.floats = floats;
            this.present = present;
            this.ints = ints;
            this.bools = bools;
            this.strings = strings;
            this.records = records;
        }

        /** 范围内关键帧数量。 */
        public int frameCount() {
            return times.length;
        }

        /** 第 {@code index} 帧的片段局部时间。 */
        public float timeAt(int index) {
            if (index < 0 || index >= times.length) {
                throw new IndexOutOfBoundsException("关键帧下标越界: " + index);
            }
            return times[index];
        }

        /**
         * 采样 {@code float} 属性（{@code type=float}）。
         *
         * @param t 片段局部时间
         * @param fallback 无帧且无静态值时使用
         * @return 采样值；非 {@code float} 属性返回 {@code fallback}
         */
        public float sampleFloat(double t, float fallback) {
            if (type != PropertyType.FLOAT) {
                return fallback;
            }
            if (times.length == 0) {
                return value instanceof Number number ? number.floatValue() : fallback;
            }
            if (t <= times[0]) {
                return floats[0];
            }
            int last = times.length - 1;
            if (t >= times[last]) {
                return floats[last];
            }
            int index = locate(t);
            float a = floats[index];
            float b = floats[index + 1];
            double s = (t - times[index]) / (double) (times[index + 1] - times[index]);
            return interpolateFloat(a, b, s);
        }

        /**
         * 采样 {@code int} 属性（{@code type=int}）：区间内按 step 语义保持前一帧。
         *
         * @param t 片段局部时间
         * @param fallback 无帧且无静态值时使用
         * @return 采样值；非 {@code int} 属性返回 {@code fallback}
         */
        public int sampleInt(double t, int fallback) {
            if (type != PropertyType.INT) {
                return fallback;
            }
            if (times.length == 0) {
                return value instanceof Number number ? number.intValue() : fallback;
            }
            if (t <= times[0]) {
                return ints[0];
            }
            int last = times.length - 1;
            if (t >= times[last]) {
                return ints[last];
            }
            return ints[locate(t)];
        }

        /**
         * 采样 {@code bool} 属性（{@code type=bool}）：区间内按 step 语义保持前一帧。
         *
         * @param t 片段局部时间
         * @param fallback 无帧且无静态值时使用
         * @return 采样值；非 {@code bool} 属性返回 {@code fallback}
         */
        public boolean sampleBool(double t, boolean fallback) {
            if (type != PropertyType.BOOL) {
                return fallback;
            }
            if (times.length == 0) {
                return value instanceof Boolean result ? result : fallback;
            }
            if (t <= times[0]) {
                return bools[0];
            }
            int last = times.length - 1;
            if (t >= times[last]) {
                return bools[last];
            }
            return bools[locate(t)];
        }

        /**
         * 采样 {@code string}/{@code enum}/{@code ref} 属性：区间内按 step 语义保持前一帧。
         *
         * @param t 片段局部时间
         * @param fallback 无帧且无静态值时使用
         * @return 采样值；类型不匹配返回 {@code fallback}
         */
        public String sampleString(double t, String fallback) {
            if (type != PropertyType.STRING && type != PropertyType.ENUM && type != PropertyType.REF) {
                return fallback;
            }
            if (times.length == 0) {
                return value instanceof String result ? result : fallback;
            }
            if (t <= times[0]) {
                return strings[0];
            }
            int last = times.length - 1;
            if (t >= times[last]) {
                return strings[last];
            }
            return strings[locate(t)];
        }

        /**
         * 采样 {@code nullable_float} 属性（{@code type=nullable_float}），不装箱。
         *
         * <p>规则：无帧 → 静态值（{@code null} 返回 {@code false}）；单帧恒值；{@code t} 在首帧前取首值、
         * 末帧后取末值。区间内任一端为 {@code null} 时按 step（在非空值的时间点切换）；两端非空时按
         * 声明的 interp（缺省 step；声明 {@code angle} 时走最短路径）。</p>
         *
         * @param t 片段局部时间
         * @param out 接收结果；长度必须 {@code >= 1}，{@code out[0]} 只在返回 {@code true} 时有效
         * @return 是否得到非 {@code null} 值；{@code false} = null / 缺省
         */
        public boolean sampleNullableFloat(double t, float[] out) {
            if (out == null || out.length < 1) {
                throw new IllegalArgumentException("out 至少需要 1 个元素");
            }
            if (type != PropertyType.NULLABLE_FLOAT) {
                return false;
            }
            if (times.length == 0) {
                if (value instanceof Number number) {
                    out[0] = number.floatValue();
                    return true;
                }
                return false;
            }
            if (t <= times[0]) {
                return writeNullable(0, out);
            }
            int last = times.length - 1;
            if (t >= times[last]) {
                return writeNullable(last, out);
            }
            int index = locate(t);
            boolean aPresent = present[index];
            boolean bPresent = present[index + 1];
            if (!aPresent || !bPresent) {
                return writeNullable(index, out);
            }
            double s = (t - times[index]) / (double) (times[index + 1] - times[index]);
            float a = floats[index];
            float b = floats[index + 1];
            float result = switch (interp) {
                case ANGLE -> angle(a, b, s);
                case LINEAR -> a + (b - a) * (float) s;
                default -> s < 1d ? a : b;
            };
            out[0] = result;
            return true;
        }

        /** 事件数量；非 {@code event_record} 属性返回 0。 */
        public int eventCount() {
            return records == null ? 0 : records.length;
        }

        /** 第 {@code index} 个事件记录值；只在 {@code event_record} 属性上调用。 */
        public Object eventValue(int index) {
            if (records == null) {
                throw new IllegalStateException("不是 event_record 属性，没有事件值");
            }
            if (index < 0 || index >= records.length) {
                throw new IndexOutOfBoundsException("事件下标越界: " + index);
            }
            return records[index];
        }

        private boolean writeNullable(int index, float[] out) {
            if (!present[index]) {
                return false;
            }
            out[0] = floats[index];
            return true;
        }

        /** 最后一个 {@code times[i] <= t} 的下标；调用方保证 {@code times[0] < t < times[last]}。 */
        private int locate(double t) {
            int low = 0;
            int high = times.length - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (times[middle] <= t) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return low;
        }

        private float interpolateFloat(float a, float b, double s) {
            return switch (interp) {
                case LINEAR -> a + (b - a) * (float) s;
                case ANGLE -> angle(a, b, s);
                case GEOMETRIC -> geometric(a, b, s);
                case STEP, NONE -> s < 1d ? a : b;
            };
        }

        /**
         * 角度最短路径插值；结果归一到 {@code (-180, 180]}。
         */
        private static float angle(float a, float b, double s) {
            float delta = (b - a) % 360f;
            if (delta > 180f) {
                delta -= 360f;
            } else if (delta <= -180f) {
                delta += 360f;
            }
            float result = (a + delta * (float) s) % 360f;
            if (result > 180f) {
                result -= 360f;
            } else if (result <= -180f) {
                result += 360f;
            }
            return result;
        }

        /**
         * 几何插值 {@code exp(log a + (log b - log a) * s)}。
         *
         * <p>任一端 {@code <= 0} 时回落 {@code a}：validator 应已在加载期拦截非正缩放，
         * 运行时不做静默改线性，也不抛异常。</p>
         */
        private static float geometric(float a, float b, double s) {
            if (a <= 0f || b <= 0f) {
                return a;
            }
            return (float) Math.exp(Math.log(a) + (Math.log(b) - Math.log(a)) * s);
        }
    }
}
