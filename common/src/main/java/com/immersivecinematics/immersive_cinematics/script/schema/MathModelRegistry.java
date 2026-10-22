package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.math.BlendModel;
import com.immersivecinematics.immersive_cinematics.math.ChainModel;
import com.immersivecinematics.immersive_cinematics.math.CubicBezierCurve;
import com.immersivecinematics.immersive_cinematics.math.CurveFn;
import com.immersivecinematics.immersive_cinematics.math.CurveModel;
import com.immersivecinematics.immersive_cinematics.math.Curves;
import com.immersivecinematics.immersive_cinematics.math.ExponentialSmoothModel;
import com.immersivecinematics.immersive_cinematics.math.InertiaModel;
import com.immersivecinematics.immersive_cinematics.math.MathModel;
import com.immersivecinematics.immersive_cinematics.math.MathModelInstance;
import com.immersivecinematics.immersive_cinematics.math.ModelParams;
import com.immersivecinematics.immersive_cinematics.math.SpringModel;
import com.immersivecinematics.immersive_cinematics.math.ValueDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 数学模型注册表 — 「JSON → 模型定义」的唯一入口（模型数学在 {@code math} 包，本类不做求值）。
 *
 * 职责：按 type 名登记「参数 schema + 工厂」；{@link #create} 做类型归一、缺省填充、未知参数忽略、
 * 越界钳制与未注册回落（定稿 5：非法 → 限频告警 + 回落 {@link #DEFAULT_TYPE}，不阻断加载）。
 *
 * JSON 形态：{@code {"type": "spring", "stiffness": 8.0}}；组合子定义内联
 * （{@code chain} 的 {@code models} 数组 / {@code blend} 的 {@code a}、{@code b}）。
 * 结构键：{@code type}（注册名）、{@code domain}（scalar / angle / vector / color，可选，缺省继承所在通道 / 父定义，
 * 与父定义不一致 = 组合域不匹配 → 该子模型回落 {@link #DEFAULT_TYPE}）；其余键 = 各类型参数。
 *
 * 运行时守门：{@link #create} 返回的定义在 step 时检查输入 / 输出有限性 —— 非有限 → 实例 {@code reset()} +
 * 本帧输出 = 输入（定稿 5）。分配：create 与 instantiate 分配（解析 / 建实例路径），step 零分配。
 */
public final class MathModelRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/MathModelRegistry");

    /** 未注册 / 缺失 / 非法定义的回落类型（恒等 = 无效果）。 */
    public static final String DEFAULT_TYPE = "linear";

    /** 定义树深度上限（含根 = 1）：超限 → validator 拒绝 + 运行时整链回落 {@link #DEFAULT_TYPE}。 */
    public static final int MODEL_MAX_DEPTH = 8;

    /** 单定义节点数上限：超限同 {@link #MODEL_MAX_DEPTH}。 */
    public static final int MODEL_MAX_NODES = 32;

    /** 限频告警的去重上限（防异常脚本撑爆内存）。 */
    private static final int WARN_LIMIT = 256;

    private static final String CUBIC_BEZIER_TYPE = "cubic_bezier";

    /** type →（参数 schema，工厂）；插入顺序 = 注册顺序（编辑器类型下拉 / 导出顺序）。 */
    private static final Map<String, Entry> REGISTRY = new LinkedHashMap<>();

    /** 已告警过的位置（同一位置只告警一次，避免逐帧路径刷屏）。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** 通道字段 → 值域（CAMERA clip 的 6 个运动模型字段）。 */
    private static final Map<String, ValueDomain> CHANNEL_DOMAINS = Map.of(
            "motion_position", ValueDomain.VECTOR,
            "motion_yaw", ValueDomain.ANGLE,
            "motion_pitch", ValueDomain.ANGLE,
            "motion_roll", ValueDomain.ANGLE,
            "motion_fov", ValueDomain.SCALAR,
            "motion_zoom", ValueDomain.SCALAR);

    private static final Map<String, FieldDef> NO_PARAMS = Map.of();

    private static final Map<String, FieldDef> CUBIC_BEZIER_PARAMS = params(
            "x1", new FieldDef("float", (float) CubicBezierCurve.DEFAULT_X1),
            "y1", new FieldDef("float", (float) CubicBezierCurve.DEFAULT_Y1),
            "x2", new FieldDef("float", (float) CubicBezierCurve.DEFAULT_X2),
            "y2", new FieldDef("float", (float) CubicBezierCurve.DEFAULT_Y2));

    private static final Map<String, FieldDef> EXPONENTIAL_SMOOTH_PARAMS = params(
            "tau", new FieldDef("float", (float) ExponentialSmoothModel.DEFAULT_TAU));

    private static final Map<String, FieldDef> SPRING_PARAMS = params(
            "stiffness", new FieldDef("float", (float) SpringModel.DEFAULT_STIFFNESS),
            "damping", new FieldDef("float", (float) SpringModel.DEFAULT_DAMPING),
            "initial_velocity", new FieldDef("float", (float) SpringModel.DEFAULT_INITIAL_VELOCITY));

    private static final Map<String, FieldDef> INERTIA_PARAMS = params(
            "stiffness", new FieldDef("float", (float) InertiaModel.DEFAULT_STIFFNESS),
            "damping", new FieldDef("float", (float) InertiaModel.DEFAULT_DAMPING),
            "initial_velocity", new FieldDef("float", (float) InertiaModel.DEFAULT_INITIAL_VELOCITY));

    private static final Map<String, FieldDef> CHAIN_PARAMS = params(
            "models", new FieldDef("model_list", null));

    private static final Map<String, FieldDef> BLEND_PARAMS = params(
            "a", new FieldDef("model", null),
            "b", new FieldDef("model", null),
            "weight", new FieldDef("float", (float) BlendModel.DEFAULT_WEIGHT));

    static {
        registerCurve("linear", Curves::linear);
        registerCurve("smoothstep", Curves::smoothstep);
        registerCurve("ease_in", Curves::easeIn);
        registerCurve("ease_out", Curves::easeOut);
        registerCurve("ease_in_out", Curves::easeInOut);
        register(CUBIC_BEZIER_TYPE, CUBIC_BEZIER_PARAMS, MathModelRegistry::createCubicBezier);
        register(ExponentialSmoothModel.TYPE, EXPONENTIAL_SMOOTH_PARAMS, MathModelRegistry::createExponentialSmooth);
        register(SpringModel.TYPE, SPRING_PARAMS, MathModelRegistry::createSpring);
        register(InertiaModel.TYPE, INERTIA_PARAMS, MathModelRegistry::createInertia);
        register(ChainModel.TYPE, CHAIN_PARAMS, MathModelRegistry::createChain);
        register(BlendModel.TYPE, BLEND_PARAMS, MathModelRegistry::createBlend);
    }

    private MathModelRegistry() {} // 工具类，禁止实例化

    /**
     * 登记一个模型类型（重复注册 = 覆盖 + 告警）。
     *
     * @param type    注册名（snake_case，与 JSON 的 {@code type} 一致）
     * @param params  参数 schema（键顺序 = 编辑器表单顺序）
     * @param factory 归一参数 → 定义
     */
    public static void register(String type, Map<String, FieldDef> params,
                                Function<ModelParams, MathModel> factory) {
        if (REGISTRY.containsKey(type)) {
            LOGGER.warn("[数学模型] 类型 '{}' 已注册，将被覆盖", type);
        }
        REGISTRY.put(type, new Entry(Collections.unmodifiableMap(new LinkedHashMap<>(params)), factory));
    }

    /**
     * JSON 定义 → 模型定义（已归一：缺省 / 钳制 / 未知忽略 / 未注册回落；超深度 / 节点上限 → 整链回落）。
     *
     * 「定义对象同一」判据（调用方据以判定定义不变 = 实例延续 / 定义变化 = 重建）的唯一来源 =
     * {@code ScriptParser} 的归一驻留入口：后续调用方需要该判据时必须经驻留入口取定义，
     * 不要绕过它直接调本方法——同形态的两次 create 得到两个对象，会被误判为「定义变化」而重置实例状态。
     *
     * @param type 注册名（null / 空 / 未注册 → 回落 {@link #DEFAULT_TYPE}）
     * @param raw  定义对象的键值（含 {@code type} 与可选的 {@code domain}；非 null；
     *             嵌套子定义为 {@code Map} / {@code List}）
     * @return 可直接 {@code instantiate()} 的定义（已带运行时守门）
     */
    public static MathModel create(String type, Map<String, Object> raw) {
        ValueDomain domain = declaredDomain(raw, ValueDomain.SCALAR);
        int[] measure = {0, 0};
        measure(raw, 1, measure);
        if (measure[0] > MODEL_MAX_NODES || measure[1] > MODEL_MAX_DEPTH) {
            warnOnce("limits", "模型定义超限（节点 " + measure[0] + " / 上限 " + MODEL_MAX_NODES
                    + "，深度 " + measure[1] + " / 上限 " + MODEL_MAX_DEPTH + "）→ 整链回落 '" + DEFAULT_TYPE + "'");
            return guard(linear(domain));
        }
        return build(type, raw, null);
    }

    /** 已注册类型名的只读视图（注册顺序 = 编辑器类型下拉顺序）。 */
    public static Set<String> types() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    /** 某类型的参数 schema（编辑器表单 + 校验共用）；未注册 → 空表。 */
    public static Map<String, FieldDef> params(String type) {
        Entry entry = type == null ? null : REGISTRY.get(type);
        return entry == null ? Collections.emptyMap() : entry.params();
    }

    /** 通道字段 → 值域（{@code motion_position} = VECTOR、
     *  {@code motion_yaw} / {@code pitch} / {@code roll} = ANGLE、
     *  {@code motion_fov} / {@code motion_zoom} = SCALAR）；非运动模型字段 → SCALAR。 */
    public static ValueDomain channelDomain(String fieldName) {
        ValueDomain domain = CHANNEL_DOMAINS.get(fieldName);
        return domain == null ? ValueDomain.SCALAR : domain;
    }

    // ========== 定义构建 ==========

    /**
     * 递归构建一个定义；{@code expected} = 父定义域（顶层为 null）。
     *
     * 域不匹配 → 该子模型回落 {@link #DEFAULT_TYPE}；类型缺失 / 未注册 → 回落 {@link #DEFAULT_TYPE}。
     */
    private static MathModel build(String type, Map<String, Object> raw, ValueDomain expected) {
        ValueDomain domain = expected == null ? ValueDomain.SCALAR : expected;
        Object declared = raw.get("domain");
        if (declared instanceof String key) {
            ValueDomain parsed = ValueDomain.byKey(key);
            if (parsed == null) {
                warnOnce("domain." + key, "未知值域 '" + key + "'（合法：scalar / angle / vector / color）→ 忽略");
            } else if (expected != null && parsed != expected) {
                warnOnce("domain.mismatch." + key, "组合域不匹配（父定义 " + expected.key() + "，子定义 " + key
                        + "）→ 该子模型回落 '" + DEFAULT_TYPE + "'");
                return guard(linear(expected));
            } else {
                domain = parsed;
            }
        }
        Entry entry = type == null ? null : REGISTRY.get(type);
        if (entry == null) {
            warnOnce("type." + type, "未知数学模型类型 '" + type + "'（缺失 / 未注册）→ 回落 '" + DEFAULT_TYPE + "'");
            entry = REGISTRY.get(DEFAULT_TYPE);
        }
        return guard(entry.factory.apply(new ModelParams(domain, normalizeParams(type, entry.params(), raw, domain))));
    }

    /** 定义对象显式声明的域；缺失 / 未知 → {@code fallback}。 */
    private static ValueDomain declaredDomain(Map<String, Object> raw, ValueDomain fallback) {
        Object declared = raw.get("domain");
        if (declared instanceof String key) {
            ValueDomain parsed = ValueDomain.byKey(key);
            if (parsed != null) {
                return parsed;
            }
        }
        return fallback;
    }

    /** 归一参数：缺失 → FieldDef 缺省；非有限 / 类型不符 → 缺省 + 告警；未知键 → 忽略 + 告警。 */
    private static Map<String, Object> normalizeParams(String type, Map<String, FieldDef> declared,
                                                       Map<String, Object> raw, ValueDomain domain) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, FieldDef> entry : declared.entrySet()) {
            String key = entry.getKey();
            FieldDef def = entry.getValue();
            Object value = raw.get(key);
            switch (def.type()) {
                case "float" -> values.put(key, numberParam(type, key, value, def));
                case "model" -> {
                    if (value instanceof Map<?, ?> child) {
                        values.put(key, build(typeOf(child), asStringMap(child), domain));
                    } else if (value != null) {
                        warnOnce("param.model." + type + "." + key, "模型参数 " + type + "." + key
                                + " 需要对象（子模型定义，实际 " + value + "）→ 该子模型回落 '" + DEFAULT_TYPE + "'");
                    }
                }
                case "model_list" -> values.put(key, modelListParam(type, key, value, domain));
                default -> { }
            }
        }
        for (String key : raw.keySet()) {
            if (!declared.containsKey(key) && !"type".equals(key) && !"domain".equals(key)) {
                warnOnce("param.unknown." + type + "." + key, "未知模型参数 '" + key + "'（type = " + type
                        + "，可用: " + String.join(" / ", declared.keySet()) + "）→ 忽略");
            }
        }
        return values;
    }

    /** 浮点参数：缺失 → FieldDef 缺省（不告警）；非数字 / 非有限 → 缺省 + 告警（数据入口 NaN / ±Inf 口径）。 */
    private static double numberParam(String type, String key, Object value, FieldDef def) {
        double fallback = def.defaultValue() instanceof Number number ? number.doubleValue() : 0.0;
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        warnOnce("param.value." + type + "." + key, "模型参数 " + type + "." + key + " 需要有限数字（实际 "
                + value + "）→ 回落缺省 " + fallback);
        return fallback;
    }

    /** 子模型数组：缺失 → 空（= 恒等）；非数组 → 忽略 + 告警；元素非对象 → 该元素回落 {@link #DEFAULT_TYPE}。 */
    private static List<MathModel> modelListParam(String type, String key, Object value, ValueDomain domain) {
        List<MathModel> models = new ArrayList<>();
        if (value == null) {
            return models;
        }
        if (!(value instanceof List<?> list)) {
            warnOnce("param.list." + type + "." + key, "模型参数 " + type + "." + key + " 需要数组（子模型定义数组，实际 "
                    + value + "）→ 忽略");
            return models;
        }
        for (Object element : list) {
            if (element instanceof Map<?, ?> child) {
                models.add(build(typeOf(child), asStringMap(child), domain));
            } else {
                warnOnce("param.list.item." + type + "." + key, "模型参数 " + type + "." + key
                        + " 的元素需要对象（子模型定义，实际 " + element + "）→ 该元素回落 '" + DEFAULT_TYPE + "'");
                models.add(guard(linear(domain)));
            }
        }
        return models;
    }

    // ========== 工厂 ==========

    private static void registerCurve(String type, CurveFn curve) {
        register(type, NO_PARAMS, params -> new CurveModel(type, params.domain(), curve));
    }

    private static MathModel createCubicBezier(ModelParams params) {
        double x1 = params.number("x1", CubicBezierCurve.DEFAULT_X1);
        double y1 = params.number("y1", CubicBezierCurve.DEFAULT_Y1);
        double x2 = params.number("x2", CubicBezierCurve.DEFAULT_X2);
        double y2 = params.number("y2", CubicBezierCurve.DEFAULT_Y2);
        warnIfClamped(CUBIC_BEZIER_TYPE, "x1", x1, CubicBezierCurve.clampX(x1));
        warnIfClamped(CUBIC_BEZIER_TYPE, "y1", y1, CubicBezierCurve.clampY(y1));
        warnIfClamped(CUBIC_BEZIER_TYPE, "x2", x2, CubicBezierCurve.clampX(x2));
        warnIfClamped(CUBIC_BEZIER_TYPE, "y2", y2, CubicBezierCurve.clampY(y2));
        return new CurveModel(CUBIC_BEZIER_TYPE, params.domain(), new CubicBezierCurve(x1, y1, x2, y2));
    }

    private static MathModel createExponentialSmooth(ModelParams params) {
        double tau = params.number("tau", ExponentialSmoothModel.DEFAULT_TAU);
        warnIfClamped(ExponentialSmoothModel.TYPE, "tau", tau, ExponentialSmoothModel.clampTau(tau));
        return new ExponentialSmoothModel(params.domain(), tau);
    }

    private static MathModel createSpring(ModelParams params) {
        double stiffness = params.number("stiffness", SpringModel.DEFAULT_STIFFNESS);
        double damping = params.number("damping", SpringModel.DEFAULT_DAMPING);
        double velocity = params.number("initial_velocity", SpringModel.DEFAULT_INITIAL_VELOCITY);
        warnIfClamped(SpringModel.TYPE, "stiffness", stiffness, SpringModel.clampStiffness(stiffness));
        warnIfClamped(SpringModel.TYPE, "damping", damping, SpringModel.clampDamping(damping));
        warnIfClamped(SpringModel.TYPE, "initial_velocity", velocity, SpringModel.clampInitialVelocity(velocity));
        return new SpringModel(params.domain(), stiffness, damping, velocity);
    }

    private static MathModel createInertia(ModelParams params) {
        double stiffness = params.number("stiffness", InertiaModel.DEFAULT_STIFFNESS);
        double damping = params.number("damping", InertiaModel.DEFAULT_DAMPING);
        double velocity = params.number("initial_velocity", InertiaModel.DEFAULT_INITIAL_VELOCITY);
        warnIfClamped(InertiaModel.TYPE, "stiffness", stiffness, InertiaModel.clampStiffness(stiffness));
        warnIfClamped(InertiaModel.TYPE, "damping", damping, InertiaModel.clampDamping(damping));
        warnIfClamped(InertiaModel.TYPE, "initial_velocity", velocity, InertiaModel.clampInitialVelocity(velocity));
        return new InertiaModel(params.domain(), stiffness, damping, velocity);
    }

    private static MathModel createChain(ModelParams params) {
        return new ChainModel(params.domain(), params.modelList("models"));
    }

    private static MathModel createBlend(ModelParams params) {
        double weight = params.number("weight", BlendModel.DEFAULT_WEIGHT);
        warnIfClamped(BlendModel.TYPE, "weight", weight, BlendModel.clampWeight(weight));
        return new BlendModel(params.domain(), identity(params.model("a"), params.domain()),
                identity(params.model("b"), params.domain()), weight);
    }

    /** 缺失 / 无效子模型 → 恒等 {@link #DEFAULT_TYPE}（该层无效果）。 */
    private static MathModel identity(MathModel model, ValueDomain domain) {
        return model == null ? linear(domain) : model;
    }

    private static MathModel linear(ValueDomain domain) {
        return new CurveModel(DEFAULT_TYPE, domain, Curves::linear);
    }

    // ========== 运行时守门 ==========

    /** 运行时守门：in / out 非有限 → 实例 reset() + 本帧输出 = 输入；负 dt 限频告警（定稿 5）。 */
    private static MathModel guard(MathModel model) {
        return new GuardedModel(model);
    }

    // ========== 告警与工具 ==========

    /** 参数被钳制（越界 / NaN）时限频告警；{@code clamped != raw} 对 NaN 同样成立。 */
    private static void warnIfClamped(String type, String key, double raw, double clamped) {
        if (clamped != raw) {
            warnOnce("param.clamp." + type + "." + key, "模型参数 " + type + "." + key + " 越界 / 非有限（" + raw
                    + "）→ 钳制为 " + clamped);
        }
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.size() < WARN_LIMIT && WARNED.add(key)) {
            LOGGER.warn("[数学模型] {}", message);
        }
    }

    /** 负 dt 告警：先过限频门再格式化（step 路径不拼串的零分配契约；同一 JVM 至多一条）。 */
    private static void warnNegativeDt(double dt) {
        if (WARNED.size() >= WARN_LIMIT || !WARNED.add("dt.negative")) {
            return;
        }
        LOGGER.warn("[数学模型] 模型 step 收到负 dt（{}）→ 按 0 处理（状态保持）", dt);
    }

    /** 统计定义树的节点数与最大深度（深度含根 = 1）：{@code acc[0]} = 节点数、{@code acc[1]} = 最大深度。 */
    private static void measure(Map<String, Object> raw, int depth, int[] acc) {
        acc[0]++;
        if (depth > acc[1]) {
            acc[1] = depth;
        }
        for (Object value : raw.values()) {
            if (value instanceof Map<?, ?> child) {
                measure(asStringMap(child), depth + 1, acc);
            } else if (value instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Map<?, ?> child) {
                        measure(asStringMap(child), depth + 1, acc);
                    }
                }
            }
        }
    }

    private static String typeOf(Map<?, ?> raw) {
        Object type = raw.get("type");
        return type instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    /** 参数 schema 构造（键顺序 = 传入顺序）。 */
    private static Map<String, FieldDef> params(Object... keyDefPairs) {
        Map<String, FieldDef> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyDefPairs.length; i += 2) {
            map.put((String) keyDefPairs[i], (FieldDef) keyDefPairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    /** 一个注册项：参数 schema + 工厂。 */
    private record Entry(Map<String, FieldDef> params, Function<ModelParams, MathModel> factory) {}

    /** 定义包装：只加运行时守门，其余属性全部委托。 */
    private static final class GuardedModel implements MathModel {

        private final MathModel delegate;

        GuardedModel(MathModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public String type() {
            return delegate.type();
        }

        @Override
        public ValueDomain domain() {
            return delegate.domain();
        }

        @Override
        public int inputArity() {
            return delegate.inputArity();
        }

        @Override
        public int outputArity() {
            return delegate.outputArity();
        }

        @Override
        public boolean stateful() {
            return delegate.stateful();
        }

        @Override
        public MathModelInstance instantiate() {
            return new GuardedInstance(delegate.instantiate());
        }
    }

    /** 实例守门：输入 / 输出非有限 → reset() + 本帧输出 = 输入；step 正常路径零分配。 */
    private static final class GuardedInstance implements MathModelInstance {

        private final MathModelInstance delegate;

        GuardedInstance(MathModelInstance delegate) {
            this.delegate = delegate;
        }

        @Override
        public void step(double t, double dt, double[] in, double[] out) {
            if (dt < 0.0) {
                warnNegativeDt(dt);
            }
            if (!finite(in)) {
                delegate.reset();
                System.arraycopy(in, 0, out, 0, in.length);
                warnOnce("runtime.input", "模型输入非有限 → 实例 reset() + 本帧输出 = 输入");
                return;
            }
            delegate.step(t, dt, in, out);
            if (!finite(out)) {
                delegate.reset();
                System.arraycopy(in, 0, out, 0, in.length);
                warnOnce("runtime.output", "模型输出非有限 → 实例 reset() + 本帧输出 = 输入");
            }
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        private static boolean finite(double[] values) {
            for (double value : values) {
                if (!Double.isFinite(value)) {
                    return false;
                }
            }
            return true;
        }
    }
}
