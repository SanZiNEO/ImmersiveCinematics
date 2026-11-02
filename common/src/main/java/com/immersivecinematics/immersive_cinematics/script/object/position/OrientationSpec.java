package com.immersivecinematics.immersive_cinematics.script.object.position;

/**
 * 朝向规格：三轴角度 + 每轴方向锁 + 基准来源 + 注视目标 + 朝向模式 + 切线偏移。
 *
 * <p>字段对应新格式对象（见 {@code track-camera.md} §3.3 / §3.4）：{@code yaw}/{@code pitch}/{@code roll}、
 * {@code yaw_lock}/{@code pitch_lock}（{@code null} = 不锁）、{@code yaw_base}/{@code pitch_base}、
 * {@code yaw_base_selector}、{@code yaw_base_from}/{@code yaw_base_to}、{@code look_at}、
 * {@code orient}、{@code yaw_offset}/{@code pitch_offset}。</p>
 *
 * <p>优先级（由 {@link OrientationEvaluator} 实现）：{@code look_at} 可解析 → 覆盖 yaw/pitch；
 * 否则 {@code orient=tangent} 且有切线 → 切线 + 偏移；否则手动（基准 / 锁 + {@code yaw}/{@code pitch}）。
 * {@code yaw_offset}/{@code pitch_offset} 只在切线支读取。</p>
 *
 * <p>本类不可变；用 {@link #builder()} 构造。默认值：三轴 0、锁 {@code null}、基准 {@code world}、
 * 选择器 {@code @p}、{@code look_at = null}、{@code orient = manual}、偏移 0。</p>
 */
public final class OrientationSpec {

    private static final String DEFAULT_SELECTOR = "@p";

    private final float yaw;
    private final float pitch;
    private final float roll;
    private final Float yawLock;
    private final Float pitchLock;
    private final BaseSource yawBase;
    private final BaseSource pitchBase;
    private final String yawBaseSelector;
    private final PointSourceSpec yawBaseFrom;
    private final PointSourceSpec yawBaseTo;
    private final LookAtSpec lookAt;
    private final Orient orient;
    private final double yawOffset;
    private final double pitchOffset;

    private OrientationSpec(Builder builder) {
        this.yaw = builder.yaw;
        this.pitch = builder.pitch;
        this.roll = builder.roll;
        this.yawLock = builder.yawLock;
        this.pitchLock = builder.pitchLock;
        this.yawBase = builder.yawBase == null ? BaseSource.WORLD : builder.yawBase;
        this.pitchBase = builder.pitchBase == null ? BaseSource.WORLD : builder.pitchBase;
        this.yawBaseSelector = builder.yawBaseSelector == null || builder.yawBaseSelector.isEmpty()
                ? DEFAULT_SELECTOR : builder.yawBaseSelector;
        this.yawBaseFrom = builder.yawBaseFrom;
        this.yawBaseTo = builder.yawBaseTo;
        this.lookAt = builder.lookAt;
        this.orient = builder.orient == null ? Orient.MANUAL : builder.orient;
        this.yawOffset = builder.yawOffset;
        this.pitchOffset = builder.pitchOffset;
    }

    /** 新建构造器（默认值见类 javadoc）。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 三轴角度偏移（度）。 */
    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float roll() {
        return roll;
    }

    /** yaw 轴锁值（世界角绝对值，度）；{@code null} = 不锁、跟基准。 */
    public Float yawLock() {
        return yawLock;
    }

    /** pitch 轴锁值（世界角绝对值，度）；{@code null} = 不锁、跟基准。 */
    public Float pitchLock() {
        return pitchLock;
    }

    /** yaw 轴基准来源。 */
    public BaseSource yawBase() {
        return yawBase;
    }

    /** pitch 轴基准来源。 */
    public BaseSource pitchBase() {
        return pitchBase;
    }

    /** {@code yaw_base}/{@code pitch_base = entity} 的选择器。 */
    public String yawBaseSelector() {
        return yawBaseSelector;
    }

    /** {@code yaw_base = line} 的起点；{@code null} = 无点源。 */
    public PointSourceSpec yawBaseFrom() {
        return yawBaseFrom;
    }

    /** {@code yaw_base = line} 的终点；{@code null} = 无点源。 */
    public PointSourceSpec yawBaseTo() {
        return yawBaseTo;
    }

    /** 注视目标；{@code null} = 不启用（手动 / 切线）。 */
    public LookAtSpec lookAt() {
        return lookAt;
    }

    /** 朝向模式。 */
    public Orient orient() {
        return orient;
    }

    /** 切线朝向的 yaw 叠加偏移（只在切线支读取）。 */
    public double yawOffset() {
        return yawOffset;
    }

    /** 切线朝向的 pitch 叠加偏移（只在切线支读取）。 */
    public double pitchOffset() {
        return pitchOffset;
    }

    /** 朝向规格构造器；{@link #build()} 产出的实例不可变。 */
    public static final class Builder {

        private float yaw;
        private float pitch;
        private float roll;
        private Float yawLock;
        private Float pitchLock;
        private BaseSource yawBase = BaseSource.WORLD;
        private BaseSource pitchBase = BaseSource.WORLD;
        private String yawBaseSelector = DEFAULT_SELECTOR;
        private PointSourceSpec yawBaseFrom;
        private PointSourceSpec yawBaseTo;
        private LookAtSpec lookAt;
        private Orient orient = Orient.MANUAL;
        private double yawOffset;
        private double pitchOffset;

        public Builder yaw(float value) {
            this.yaw = value;
            return this;
        }

        public Builder pitch(float value) {
            this.pitch = value;
            return this;
        }

        public Builder roll(float value) {
            this.roll = value;
            return this;
        }

        /** {@code null} = 不锁。 */
        public Builder yawLock(Float value) {
            this.yawLock = value;
            return this;
        }

        /** {@code null} = 不锁。 */
        public Builder pitchLock(Float value) {
            this.pitchLock = value;
            return this;
        }

        public Builder yawBase(BaseSource value) {
            this.yawBase = value;
            return this;
        }

        public Builder pitchBase(BaseSource value) {
            this.pitchBase = value;
            return this;
        }

        public Builder yawBaseSelector(String value) {
            this.yawBaseSelector = value;
            return this;
        }

        public Builder yawBaseFrom(PointSourceSpec value) {
            this.yawBaseFrom = value;
            return this;
        }

        public Builder yawBaseTo(PointSourceSpec value) {
            this.yawBaseTo = value;
            return this;
        }

        public Builder lookAt(LookAtSpec value) {
            this.lookAt = value;
            return this;
        }

        public Builder orient(Orient value) {
            this.orient = value;
            return this;
        }

        public Builder yawOffset(double value) {
            this.yawOffset = value;
            return this;
        }

        public Builder pitchOffset(double value) {
            this.pitchOffset = value;
            return this;
        }

        /** 产出不可变规格（缺省值在此收口）。 */
        public OrientationSpec build() {
            return new OrientationSpec(this);
        }
    }
}
