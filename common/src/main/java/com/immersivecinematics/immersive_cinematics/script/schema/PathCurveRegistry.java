package com.immersivecinematics.immersive_cinematics.script.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.math.path.ArcPathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.BSplinePathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.BezierPathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.CatmullRomPathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.ClothoidPathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.EllipsePathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.HermitePathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.LinearPathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.NurbsPathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.PathCapability;
import com.immersivecinematics.immersive_cinematics.math.path.PathPoint;
import com.immersivecinematics.immersive_cinematics.math.path.PathSpec;
import com.immersivecinematics.immersive_cinematics.math.path.RationalBezierPathSpec;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 路径曲线注册表 — 「JSON {@code curve} → 路径定义」的唯一入口（路径数学在 {@code math.path}，本类不做求值）。
 *
 * 职责：按 {@code type} 名登记「能力声明 + 参数 schema + 工厂」；{@link #create} 做严格归一：
 * 未知类型 / 未知参数 / 参数类型不符 / 必填缺失一律抛 {@link PathCurveException}，绝不静默回落。
 * 解析期（{@code ScriptParser}）与静态校验（{@code ScriptValidator}）走同一入口，两条口径不会漂移；
 * {@link SchemaExporter} 把同一份声明导出给编辑器（类型下拉 + 参数表单）。
 *
 * JSON 形态：{@code {"type": "bezier", "control_points": [{dx,dy,dz}, ...]}}；{@code type} 缺失时取
 * {@link #DEFAULT_TYPE}（与旧解析器的缺省一致），写了但不是非空字符串 = 报错。
 */
public final class PathCurveRegistry {

    /** {@code type} 缺失时的缺省曲线类型（与旧解析器一致）。 */
    public static final String DEFAULT_TYPE = "bezier";

    /** 保留键：{@code type} 不参与参数表。 */
    private static final String TYPE_KEY = "type";

    /** 绝对坐标控制点的键集。 */
    private static final Set<String> ABSOLUTE_KEYS = Set.of("x", "y", "z");

    /** 相对段起点控制点的键集。 */
    private static final Set<String> RELATIVE_KEYS = Set.of("dx", "dy", "dz");

    /** type → （能力声明，参数表，工厂）；插入顺序 = 注册顺序（编辑器类型下拉 / 导出顺序）。 */
    private static final Map<String, Entry> REGISTRY = new LinkedHashMap<>();

    static {
        register("linear", Set.of(), List.of(), params -> LinearPathSpec.INSTANCE);
        register("bezier", Set.of(PathCapability.ANALYTIC_SECOND_DERIVATIVE),
                List.of(new PathParamDef("control_points", PathParamKind.POINT_LIST, null, true, null,
                        "两个内部控制点，相对段起点用 dx/dy/dz，绝对用 x/y/z")),
                params -> new BezierPathSpec(params.points("control_points")));
        register("rational_bezier", Set.of(PathCapability.ANALYTIC_SECOND_DERIVATIVE),
                List.of(
                        new PathParamDef("control_points", PathParamKind.POINT_LIST, null, true, null,
                                "内部控件点：1 个 = 二次，2 个 = 三次；相对段起点用 dx/dy/dz，绝对用 x/y/z"),
                        new PathParamDef("weights", PathParamKind.NUMBER_LIST, null, true, null,
                                "与 control_points 等长的内部权重（无量纲），每项必须是有限正数；端点权重恒为 1")),
                params -> new RationalBezierPathSpec(params.points("control_points"), params.numbers("weights")));
        register("arc", Set.of(PathCapability.ANALYTIC_ARC_LENGTH,
                        PathCapability.ANALYTIC_SECOND_DERIVATIVE),
                List.of(
                        new PathParamDef("center", PathParamKind.POINT, null, true, null,
                                "圆心；相对段起点用 dx/dy/dz，绝对用 x/y/z（世界单位）"),
                        new PathParamDef("radius", PathParamKind.NUMBER, null, true, null,
                                "半径（世界单位，有限正数）"),
                        new PathParamDef("start_angle", PathParamKind.NUMBER, null, true, null,
                                "起始角（度）"),
                        new PathParamDef("end_angle", PathParamKind.NUMBER, null, true, null,
                                "结束角（度），跨度非零且 |Δ| ≤ 360"),
                        new PathParamDef("normal", PathParamKind.VECTOR, ArcPathSpec.DEFAULT_NORMAL, false, null,
                                "圆弧所在平面的法向 {x,y,z}（世界轴，缺省 (0,1,0)），不得与 reference_axis 平行"),
                        new PathParamDef("reference_axis", PathParamKind.VECTOR, ArcPathSpec.DEFAULT_REFERENCE_AXIS,
                                false, null,
                                "起始角 0 的方向 {x,y,z}（世界轴，缺省 (1,0,0)）")),
                params -> new ArcPathSpec(params.point("center"),
                        params.requiredNumber("radius"),
                        params.requiredNumber("start_angle"),
                        params.requiredNumber("end_angle"),
                        params.vector("normal", ArcPathSpec.DEFAULT_NORMAL),
                        params.vector("reference_axis", ArcPathSpec.DEFAULT_REFERENCE_AXIS)));
        register("ellipse", Set.of(PathCapability.ANALYTIC_SECOND_DERIVATIVE),
                List.of(
                        new PathParamDef("center", PathParamKind.POINT, null, true, null,
                                "圆心；相对段起点用 dx/dy/dz，绝对用 x/y/z（世界单位）"),
                        new PathParamDef("semi_major", PathParamKind.NUMBER, null, true, null,
                                "长半轴（世界单位，有限正数）"),
                        new PathParamDef("semi_minor", PathParamKind.NUMBER, null, true, null,
                                "短半轴（世界单位，有限正数）"),
                        new PathParamDef("rotation", PathParamKind.NUMBER, 0.0, false, null,
                                "长短轴在法向平面内的旋转（度，缺省 0）"),
                        new PathParamDef("start_angle", PathParamKind.NUMBER, null, true, null,
                                "起始参数角（度，单位圆参数角而非几何极角）"),
                        new PathParamDef("end_angle", PathParamKind.NUMBER, null, true, null,
                                "结束参数角（度），跨度非零且 |Δ| ≤ 360"),
                        new PathParamDef("normal", PathParamKind.VECTOR, EllipsePathSpec.DEFAULT_NORMAL, false, null,
                                "椭圆所在平面的法向 {x,y,z}（世界轴，缺省 (0,1,0)），不得与 reference_axis 平行"),
                        new PathParamDef("reference_axis", PathParamKind.VECTOR, EllipsePathSpec.DEFAULT_REFERENCE_AXIS,
                                false, null,
                                "参数角 0 的方向 {x,y,z}（世界轴，缺省 (1,0,0)）")),
                params -> new EllipsePathSpec(params.point("center"),
                        params.requiredNumber("semi_major"),
                        params.requiredNumber("semi_minor"),
                        params.number("rotation", 0.0),
                        params.requiredNumber("start_angle"),
                        params.requiredNumber("end_angle"),
                        params.vector("normal", EllipsePathSpec.DEFAULT_NORMAL),
                        params.vector("reference_axis", EllipsePathSpec.DEFAULT_REFERENCE_AXIS)));
        register("bspline", Set.of(PathCapability.ANALYTIC_SECOND_DERIVATIVE, PathCapability.KNOT_VECTOR),
                List.of(
                        new PathParamDef("control_points", PathParamKind.POINT_LIST, null, true, null,
                                "内部控制点（不含端点），端点由关键帧给出"),
                        new PathParamDef("degree", PathParamKind.INTEGER, 3, false, null,
                                "B-spline 次数（1 ~ 控制点数-1；缺省 3）"),
                        new PathParamDef("knots", PathParamKind.NUMBER_LIST, null, false, null,
                                "可选节点向量：长度必须为 控制点数+次数+1，非减且跨度为正；缺省钳制均匀节点")),
                params -> {
                    double[] knots = params.numbers("knots");
                    return new BSplinePathSpec(params.points("control_points"),
                            params.integer("degree", 3),
                            knots.length == 0 ? null : knots);
                });
        register("nurbs", Set.of(PathCapability.ANALYTIC_SECOND_DERIVATIVE,
                        PathCapability.RATIONAL_WEIGHTS, PathCapability.KNOT_VECTOR),
                List.of(
                        new PathParamDef("control_points", PathParamKind.POINT_LIST, null, true, null,
                                "内部控制点（不含端点）"),
                        new PathParamDef("weights", PathParamKind.NUMBER_LIST, null, true, null,
                                "内部权重，端点权重恒为 1"),
                        new PathParamDef("degree", PathParamKind.INTEGER, 3, false, null,
                                "NURBS 次数（1 ~ 控制点数-1；缺省 3）"),
                        new PathParamDef("knots", PathParamKind.NUMBER_LIST, null, false, null,
                                "可选节点向量：长度必须为 控制点数+次数+1，非减且跨度为正；缺省钳制均匀节点")),
                params -> {
                    double[] knots = params.numbers("knots");
                    return new NurbsPathSpec(params.points("control_points"), params.numbers("weights"),
                            params.integer("degree", 3),
                            knots.length == 0 ? null : knots);
                });
        register("catmull_rom", Set.of(PathCapability.ANALYTIC_SECOND_DERIVATIVE),
                List.of(
                        new PathParamDef("points", PathParamKind.POINT_LIST, null, true, null,
                                "内部过点（不含端点），端点由关键帧给出"),
                        new PathParamDef("parameterization", PathParamKind.ENUM, "centripetal", false,
                                List.of("uniform", "chordal", "centripetal"),
                                "参数化：uniform（α=0）/ chordal（α=1）/ centripetal（α=0.5，缺省）"),
                        new PathParamDef("tension", PathParamKind.NUMBER, 1.0, false, null,
                                "张力（0 ~ 1，缺省 1.0）：乘在 Barry–Goldman 切线上，0 = 切线全零"),
                        new PathParamDef("endpoint", PathParamKind.ENUM, "mirror", false,
                                List.of("mirror", "clamp"),
                                "端点幽灵点策略：mirror（镜像，缺省）/ clamp（重复端点）")),
                params -> new CatmullRomPathSpec(params.points("points"),
                        CatmullRomPathSpec.Parameterization.fromString(params.text("parameterization", "centripetal")),
                        params.number("tension", 1.0),
                        CatmullRomPathSpec.EndpointMode.fromString(params.text("endpoint", "mirror"))));
        register("hermite", Set.of(PathCapability.ANALYTIC_SECOND_DERIVATIVE),
                List.of(new PathParamDef("tangents", PathParamKind.POINT_LIST, null, true, null,
                        "两端切线；relative 为从各自端点出发的方向向量")),
                params -> HermitePathSpec.fromTangents(params.points("tangents")));
        register("clothoid", Set.of(PathCapability.ANALYTIC_ARC_LENGTH,
                        PathCapability.ANALYTIC_SECOND_DERIVATIVE),
                List.of(
                        new PathParamDef("start_curvature", PathParamKind.NUMBER, null, true, null,
                                "起点曲率 1/方块，负值=反向"),
                        new PathParamDef("end_curvature", PathParamKind.NUMBER, null, true, null,
                                "终点曲率 1/方块"),
                        new PathParamDef("length", PathParamKind.NUMBER, null, true, null,
                                "弧长（方块），必须 > 0"),
                        new PathParamDef("start_direction", PathParamKind.VECTOR,
                                ClothoidPathSpec.DEFAULT_START_DIRECTION, false, null,
                                "起点切线方向 {x,y,z}（世界轴，缺省 (1,0,0)）"),
                        new PathParamDef("normal", PathParamKind.VECTOR, ClothoidPathSpec.DEFAULT_NORMAL,
                                false, null,
                                "过渡平面法向 {x,y,z}（世界轴，缺省 (0,1,0)），不得与 start_direction 平行")),
                params -> new ClothoidPathSpec(params.requiredNumber("start_curvature"),
                        params.requiredNumber("end_curvature"),
                        params.requiredNumber("length"),
                        params.vector("start_direction", ClothoidPathSpec.DEFAULT_START_DIRECTION),
                        params.vector("normal", ClothoidPathSpec.DEFAULT_NORMAL)));
    }

    private PathCurveRegistry() {} // 工具类，禁止实例化

    /**
     * 登记一个曲线类型。
     *
     * @param type         注册名（与 JSON 的 {@code type} 一致）
     * @param capabilities 该曲线声明具备的能力（{@link PathSpec#supports} 之外的元数据，供调用方选快路径）
     * @param params       参数 schema（键顺序 = 编辑器表单顺序）
     * @param factory      归一参数 → 路径定义；抛 {@link IllegalArgumentException} 视为定义非法
     * @throws IllegalArgumentException 重复注册 / 参数名重复或占用保留键 / 缺省值与 kind 不符
     */
    public static void register(String type, Set<PathCapability> capabilities,
                                List<PathParamDef> params, Function<PathParams, PathSpec> factory) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("曲线类型名不能为空");
        }
        if (capabilities == null || params == null || factory == null) {
            throw new IllegalArgumentException("曲线类型 '" + type + "' 的注册参数不能为 null");
        }
        if (REGISTRY.containsKey(type)) {
            throw new IllegalArgumentException("曲线类型 '" + type + "' 已注册");
        }
        Set<String> names = new LinkedHashSet<>();
        for (PathParamDef def : params) {
            if (def == null) {
                throw new IllegalArgumentException("曲线类型 '" + type + "' 的参数表含 null 项");
            }
            if (TYPE_KEY.equals(def.name())) {
                throw new IllegalArgumentException("曲线类型 '" + type + "' 的参数名 '" + TYPE_KEY + "' 是保留键");
            }
            if (!names.add(def.name())) {
                throw new IllegalArgumentException("曲线类型 '" + type + "' 的参数名重复: " + def.name());
            }
            validateDefault(type, def);
        }
        REGISTRY.put(type, new Entry(Collections.unmodifiableSet(new LinkedHashSet<>(capabilities)),
                List.copyOf(params), factory));
    }

    /**
     * JSON {@code curve} 对象 → 路径定义（严格：未知类型 / 未知参数 / 类型不符 / 必填缺失一律报错）。
     *
     * @param obj  曲线对象（非 null）
     * @param path 该对象在脚本里的 JSON 路径（报错定位用）
     * @return 可直接交给 {@code PathEvaluator} 求值的定义
     * @throws PathCurveException 定义非法；工厂抛出的 {@link IllegalArgumentException} 也收敛成它
     */
    public static PathSpec create(JsonObject obj, String path) {
        if (obj == null) {
            throw new PathCurveException(path, "曲线定义不能为空（需要对象 {type, ...参数}）");
        }
        String type = readType(obj, path);
        Entry entry = REGISTRY.get(type);
        if (entry == null) {
            throw new PathCurveException(path + "." + TYPE_KEY,
                    "未知曲线类型 '" + type + "'（已注册: " + String.join(", ", REGISTRY.keySet()) + "）");
        }
        // 未知键：拼错参数名 = 作者意图丢失，必须报错（不静默忽略）
        for (String key : obj.keySet()) {
            if (TYPE_KEY.equals(key) || entry.paramNames().contains(key)) {
                continue;
            }
            throw new PathCurveException(path + "." + key,
                    "未知参数（" + type + " 允许: " + String.join(", ", entry.allowedKeys()) + "）");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (PathParamDef def : entry.params()) {
            JsonElement element = obj.get(def.name());
            if (element == null || element.isJsonNull()) {
                if (def.required()) {
                    throw new PathCurveException(path + "." + def.name(), "缺失（必填参数）");
                }
                if (def.defaultValue() != null) {
                    values.put(def.name(), def.defaultValue());
                }
                continue;
            }
            values.put(def.name(), read(def, element, path + "." + def.name()));
        }
        try {
            return entry.factory().apply(new PathParams(path, Collections.unmodifiableMap(values)));
        } catch (IllegalArgumentException e) {
            // 工厂的结构校验（如「控制点必须恰好 2 个」）与注册表同一口径：解析期报错，不静默降级
            throw new PathCurveException(path, e.getMessage());
        }
    }

    /** 该类型是否已注册。 */
    public static boolean isRegistered(String type) {
        return type != null && REGISTRY.containsKey(type);
    }

    /** 已注册类型名（注册顺序 = 编辑器类型下拉顺序）。 */
    public static List<String> types() {
        return List.copyOf(REGISTRY.keySet());
    }

    /** 某类型的参数表（编辑器表单 + 校验共用）；未注册 → 空表。 */
    public static List<PathParamDef> params(String type) {
        Entry entry = type == null ? null : REGISTRY.get(type);
        return entry == null ? List.of() : entry.params();
    }

    /** 某类型声明的能力；未注册 → 空集。 */
    public static Set<PathCapability> capabilities(String type) {
        Entry entry = type == null ? null : REGISTRY.get(type);
        return entry == null ? Set.of() : entry.capabilities();
    }

    // ========== 取值 ==========

    /** 读 {@code type}：缺失 → {@link #DEFAULT_TYPE}；非字符串 / 空白 → 报错。 */
    private static String readType(JsonObject obj, String path) {
        JsonElement element = obj.get(TYPE_KEY);
        if (element == null) {
            return DEFAULT_TYPE;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new PathCurveException(path + "." + TYPE_KEY, "需要字符串（曲线类型名），实际: " + element);
        }
        String type = element.getAsString();
        if (type.isBlank()) {
            throw new PathCurveException(path + "." + TYPE_KEY, "曲线类型名不能为空");
        }
        return type;
    }

    /** 按 kind 严格读值；类型不符 / 非有限 → {@link PathCurveException}（路径 = {@code fieldPath}）。 */
    private static Object read(PathParamDef def, JsonElement element, String fieldPath) {
        return switch (def.kind()) {
            case NUMBER -> readNumber(element, fieldPath);
            case INTEGER -> readInteger(element, fieldPath);
            case BOOLEAN -> readBoolean(element, fieldPath);
            case ENUM -> readEnum(element, fieldPath, def.enumValues());
            case TEXT -> readText(element, fieldPath);
            case POINT -> readPoint(element, fieldPath);
            case POINT_LIST -> readPointList(element, fieldPath);
            case NUMBER_LIST -> readNumberList(element, fieldPath);
            case VECTOR -> readVector(element, fieldPath);
        };
    }

    private static double readNumber(JsonElement element, String fieldPath) {
        if (!isNumber(element)) {
            throw new PathCurveException(fieldPath, "需要有限数字，实际: " + element);
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new PathCurveException(fieldPath, "需要有限数字，实际: " + element);
        }
        return value;
    }

    private static int readInteger(JsonElement element, String fieldPath) {
        double value = readNumber(element, fieldPath);
        if (value != Math.rint(value)) {
            throw new PathCurveException(fieldPath, "需要整数值，实际: " + element);
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new PathCurveException(fieldPath, "整数越界（int 范围），实际: " + element);
        }
        return (int) value;
    }

    private static boolean readBoolean(JsonElement element, String fieldPath) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new PathCurveException(fieldPath, "需要布尔值（true / false），实际: " + element);
        }
        return element.getAsBoolean();
    }

    private static String readEnum(JsonElement element, String fieldPath, List<String> enumValues) {
        String value = readText(element, fieldPath);
        if (!enumValues.isEmpty() && !enumValues.contains(value)) {
            throw new PathCurveException(fieldPath,
                    "未知取值 '" + value + "'（可选: " + String.join(", ", enumValues) + "）");
        }
        return value;
    }

    private static String readText(JsonElement element, String fieldPath) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new PathCurveException(fieldPath, "需要字符串，实际: " + element);
        }
        return element.getAsString();
    }

    /** 控制点：恰好 {@code {x,y,z}} 或恰好 {@code {dx,dy,dz}}（混合 / 缺分量 / 多余键都报错）。 */
    private static PathPoint readPoint(JsonElement element, String fieldPath) {
        if (!element.isJsonObject()) {
            throw new PathCurveException(fieldPath, "需要控制点对象 {x,y,z} 或 {dx,dy,dz}，实际: " + element);
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.keySet().equals(ABSOLUTE_KEYS)) {
            return PathPoint.absolute(readNumber(obj.get("x"), fieldPath + ".x"),
                    readNumber(obj.get("y"), fieldPath + ".y"),
                    readNumber(obj.get("z"), fieldPath + ".z"));
        }
        if (obj.keySet().equals(RELATIVE_KEYS)) {
            return PathPoint.relative(readNumber(obj.get("dx"), fieldPath + ".dx"),
                    readNumber(obj.get("dy"), fieldPath + ".dy"),
                    readNumber(obj.get("dz"), fieldPath + ".dz"));
        }
        throw new PathCurveException(fieldPath, "控制点必须恰好是 {x,y,z}（绝对）或 {dx,dy,dz}（相对段起点），实际键: "
                + obj.keySet());
    }

    private static List<PathPoint> readPointList(JsonElement element, String fieldPath) {
        if (!element.isJsonArray()) {
            throw new PathCurveException(fieldPath, "需要控制点数组 [{x,y,z} | {dx,dy,dz}, ...]，实际: " + element);
        }
        JsonArray array = element.getAsJsonArray();
        List<PathPoint> points = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            points.add(readPoint(array.get(i), fieldPath + "[" + i + "]"));
        }
        return List.copyOf(points);
    }

    private static double[] readNumberList(JsonElement element, String fieldPath) {
        if (!element.isJsonArray()) {
            throw new PathCurveException(fieldPath, "需要数字数组，实际: " + element);
        }
        JsonArray array = element.getAsJsonArray();
        double[] values = new double[array.size()];
        for (int i = 0; i < array.size(); i++) {
            values[i] = readNumber(array.get(i), fieldPath + "[" + i + "]");
        }
        return values;
    }

    /** 向量：恰好 {@code {x,y,z}}（无相对形态）。 */
    private static Vec3 readVector(JsonElement element, String fieldPath) {
        if (!element.isJsonObject() || !element.getAsJsonObject().keySet().equals(ABSOLUTE_KEYS)) {
            throw new PathCurveException(fieldPath, "需要向量 {x,y,z}，实际: " + element);
        }
        JsonObject obj = element.getAsJsonObject();
        return new Vec3(readNumber(obj.get("x"), fieldPath + ".x"),
                readNumber(obj.get("y"), fieldPath + ".y"),
                readNumber(obj.get("z"), fieldPath + ".z"));
    }

    private static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    // ========== 注册期自检 ==========

    /** 缺省值必须与 kind 同形（静态声明写错 = 编程错误，注册期就炸，不留到脚本解析期）。 */
    private static void validateDefault(String type, PathParamDef def) {
        Object value = def.defaultValue();
        if (value == null) {
            return;
        }
        boolean ok = switch (def.kind()) {
            case NUMBER -> value instanceof Number number && Double.isFinite(number.doubleValue());
            case INTEGER -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.rint(number.doubleValue());
            case BOOLEAN -> value instanceof Boolean;
            case ENUM -> value instanceof String text
                    && (def.enumValues().isEmpty() || def.enumValues().contains(text));
            case TEXT -> value instanceof String;
            case POINT -> value instanceof PathPoint point && point.isFinite();
            case POINT_LIST -> value instanceof List<?> list
                    && list.stream().allMatch(e -> e instanceof PathPoint point && point.isFinite());
            case NUMBER_LIST -> value instanceof double[] array
                    && Arrays.stream(array).allMatch(Double::isFinite);
            case VECTOR -> value instanceof Vec3 vector
                    && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
        };
        if (!ok) {
            throw new IllegalArgumentException("曲线类型 '" + type + "' 的参数 '" + def.name()
                    + "' 缺省值与 " + def.kind() + " 不符: " + value);
        }
    }

    /** 注册项：能力 / 参数表 / 工厂，附带建表时算好的索引（未知键判定 / 报错提示用）。 */
    private static final class Entry {

        private final Set<PathCapability> capabilities;
        private final List<PathParamDef> params;
        private final Function<PathParams, PathSpec> factory;
        /** 参数名集合。 */
        private final Set<String> paramNames;
        /** 允许出现的键（{@code type} + 参数名）。 */
        private final List<String> allowedKeys;

        Entry(Set<PathCapability> capabilities, List<PathParamDef> params,
              Function<PathParams, PathSpec> factory) {
            this.capabilities = capabilities;
            this.params = params;
            this.factory = factory;
            Set<String> names = new LinkedHashSet<>();
            List<String> keys = new ArrayList<>();
            keys.add(TYPE_KEY);
            for (PathParamDef def : params) {
                names.add(def.name());
                keys.add(def.name());
            }
            this.paramNames = Collections.unmodifiableSet(names);
            this.allowedKeys = List.copyOf(keys);
        }

        Set<PathCapability> capabilities() {
            return capabilities;
        }

        List<PathParamDef> params() {
            return params;
        }

        Function<PathParams, PathSpec> factory() {
            return factory;
        }

        Set<String> paramNames() {
            return paramNames;
        }

        List<String> allowedKeys() {
            return allowedKeys;
        }
    }
}
