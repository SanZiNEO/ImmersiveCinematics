package com.immersivecinematics.immersive_cinematics.script.path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.math.path.AxisConstraint;
import com.immersivecinematics.immersive_cinematics.math.path.LinearPathSpec;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipRef;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipResolver;
import com.immersivecinematics.immersive_cinematics.script.identity.UsageIndex;
import com.immersivecinematics.immersive_cinematics.script.identity.UsageKind;
import com.immersivecinematics.immersive_cinematics.script.object.ClipObjects;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectKeyframe;
import com.immersivecinematics.immersive_cinematics.script.object.SampledProperty;
import com.immersivecinematics.immersive_cinematics.script.schema.PathCurveException;
import com.immersivecinematics.immersive_cinematics.script.schema.PathCurveRegistry;
import com.immersivecinematics.immersive_cinematics.spi.PathSpec;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 脚本级 {@code paths[]} 集合的解析器（{@code ROUND-I-4-MAP} §1.6 / §0.2-9、10）。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>{@code paths} 缺省 → {@link PathCollection#EMPTY}；非数组 → 报错；</li>
 *   <li>条目：{@code id} 必填唯一（{@link #ID_PATTERN}）、{@code clip} 必填可解析、
 *       {@code from}/{@code to} 必填有限且 {@code from < to}、{@code axes} 可省（缺省三轴）、
 *       {@code geometry} 必填、{@code timing} 可省（缺省线性）；未知键报错；</li>
 *   <li><b>端点用关键帧时间引用</b>：{@code from}/{@code to} 必须命中目标片段
 *       {@code position.x/y/z} 关键帧时间的并集，且两者在并集内<b>相邻</b>（§0.2-9）；
 *       端点被归一化成节点时间（容忍 1e-4 秒的表示误差）；</li>
 *   <li>同一片段两条路径端点对完全相同 → 错误；区间部分重叠 → 合法（运行期由
 *       {@link PathBindingResolver} 的「起点更晚者优先」裁决）；</li>
 *   <li>解析期首个错误即抛 {@link ScriptParseException}（不汇总，汇总归校验器）。</li>
 * </ul>
 *
 * <p>本类的 {@code parse*} / {@code positionNodeTimes} / {@code indexOfTime} 等静态方法同时是
 * {@code ScriptValidator#checkPathCollection} 的唯一规则来源：校验器调用它们并把异常收成 issue。</p>
 *
 * <p>使用边：每条条目登记 {@link UsageKind#PATH_ENTRY}，宿主路径 = {@code paths[i]}。</p>
 */
public final class PathCollectionParser {

    /** 路径 id 的合法形态（与片段 / 规则 id 同字符集）。 */
    public static final String ID_PATTERN = "^[A-Za-z0-9_]{1,64}$";

    /** {@code line} 是 {@code linear} 的别名（§0.2-10）。 */
    public static final String LINE_TYPE = "line";

    /** 端点与节点时间的最大匹配误差（秒）：键帧时间与 {@code from}/{@code to} 都走 32 位浮点。 */
    public static final float TIME_EPSILON = 1e-4f;

    private static final Pattern ID = Pattern.compile(ID_PATTERN);

    private static final List<String> ENTRY_KEYS =
            List.of("id", "clip", "from", "to", "axes", "geometry", "timing");
    private static final List<String> TIMING_LINEAR_KEYS = List.of("type");
    private static final List<String> TIMING_KEYFRAME_KEYS = List.of("type", "points");
    private static final List<String> POINT_KEYS = List.of("t", "s");

    /** 位置节点时间来源：三个采样属性取并集。 */
    private static final String[] POSITION_PATHS = {"position.x", "position.y", "position.z"};

    private static final float[] NO_TIMES = new float[0];

    private PathCollectionParser() {}

    /** id 形态是否合法（解析器与校验器同一单源）。 */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * 解析脚本级路径集合。
     *
     * @param root     脚本根对象（{@code null} → {@link PathCollection#EMPTY}）
     * @param resolver 片段解析器（{@code null} → 任何 {@code clip} 都解析失败）
     * @param usage    使用边构建器（{@code null} = 不登记）
     * @param basePath 宿主路径前缀（{@code null} / 空 / {@code "<root>"} = 不加前缀）
     * @return 不可变路径集合
     * @throws ScriptParseException 结构 / 引用 / 端点非法
     */
    public static PathCollection parse(JsonObject root, ClipResolver resolver, UsageIndex.Builder usage,
                                       String basePath) throws ScriptParseException {
        if (root == null) return PathCollection.EMPTY;
        String path = path(basePath, "paths");
        JsonElement element = root.get("paths");
        if (element == null) return PathCollection.EMPTY;
        if (!element.isJsonArray()) {
            throw new ScriptParseException(path, "需要数组（路径集合；缺省 = 无路径）");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) return PathCollection.EMPTY;

        Set<String> ids = new LinkedHashSet<>();
        IdentityHashMap<Clip, List<float[]>> endpointPairs = new IdentityHashMap<>();
        List<PathEntry> entries = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String entryPath = path + "[" + i + "]";
            JsonElement entryElement = array.get(i);
            if (entryElement == null || !entryElement.isJsonObject()) {
                throw new ScriptParseException(entryPath, "需要对象（路径条目）");
            }
            entries.add(parseEntry(entryElement.getAsJsonObject(), entryPath, resolver, usage,
                    ids, endpointPairs));
        }
        return PathCollection.of(entries);
    }

    // ========== 条目 ==========

    private static PathEntry parseEntry(JsonObject entry, String entryPath,
                                        ClipResolver resolver, UsageIndex.Builder usage,
                                        Set<String> ids, IdentityHashMap<Clip, List<float[]>> endpointPairs)
            throws ScriptParseException {
        rejectUnknownKeys(entry, entryPath, ENTRY_KEYS);

        String id = requireString(entry, entryPath, "id");
        if (!ID.matcher(id).matches()) {
            throw new ScriptParseException(entryPath + ".id",
                    "必须匹配 " + ID_PATTERN + "，实际: " + id);
        }
        if (!ids.add(id)) {
            throw new ScriptParseException(entryPath + ".id", "路径 id 重复: " + id + "（脚本内必须唯一）");
        }

        String clipId = requireString(entry, entryPath, "clip");
        Clip target = resolver == null ? null : resolver.resolve(clipId);
        if (target == null) {
            throw new ScriptParseException(entryPath + ".clip",
                    "未知片段引用: " + clipId + "（宿主 " + entryPath + "）");
        }
        if (!target.hasObjects()) {
            throw new ScriptParseException(entryPath + ".clip",
                    "路径集合只支持 objects 片段（目标片段 " + clipId + " 没有 objects）");
        }

        float from = requireFiniteFloat(entry, entryPath, "from");
        float to = requireFiniteFloat(entry, entryPath, "to");
        if (!(from < to)) {
            throw new ScriptParseException(entryPath + ".to",
                    "to 必须大于 from（from=" + from + ", to=" + to + "）");
        }

        float[] nodeTimes = positionNodeTimes(target);
        int fromIndex = indexOfTime(nodeTimes, from);
        if (fromIndex < 0) {
            throw new ScriptParseException(entryPath + ".from",
                    "必须命中目标片段 " + clipId + " 的 position 节点时间（可用: " + formatTimes(nodeTimes)
                            + "，实际: " + from + "）");
        }
        int toIndex = indexOfTime(nodeTimes, to);
        if (toIndex < 0) {
            throw new ScriptParseException(entryPath + ".to",
                    "必须命中目标片段 " + clipId + " 的 position 节点时间（可用: " + formatTimes(nodeTimes)
                            + "，实际: " + to + "）");
        }
        if (toIndex != fromIndex + 1) {
            String next = fromIndex + 1 < nodeTimes.length ? String.valueOf(nodeTimes[fromIndex + 1]) : "（无）";
            throw new ScriptParseException(entryPath + ".to",
                    "端点必须与 from 相邻（from=" + nodeTimes[fromIndex] + " 的下一个 position 节点时间 = "
                            + next + "，实际 to=" + to + "；节点时间 " + formatTimes(nodeTimes) + "）");
        }
        // 端点归一化成节点时间：运行期区间判定与关键帧时间逐位一致
        from = nodeTimes[fromIndex];
        to = nodeTimes[toIndex];

        AxisConstraint axes = entry.has("axes")
                ? parseAxes(entry.get("axes"), entryPath + ".axes")
                : AxisConstraint.ALL;
        PathSpec geometry = parseGeometry(entry.get("geometry"), entryPath + ".geometry");
        PathTiming timing = entry.has("timing")
                ? parseTiming(entry.get("timing"), entryPath + ".timing")
                : PathTiming.linear();

        List<float[]> seen = endpointPairs.computeIfAbsent(target, k -> new ArrayList<>(2));
        for (float[] pair : seen) {
            if (pair[0] == from && pair[1] == to) {
                throw new ScriptParseException(entryPath + ".from",
                        "同一片段的端点对重复（clip=" + clipId + ", from=" + from + ", to=" + to
                                + "）；端点对必须唯一（区间部分重叠合法，端点对相同不合法）");
            }
        }
        seen.add(new float[]{from, to});

        if (usage != null) {
            usage.reference(UsageKind.PATH_ENTRY, entryPath, ClipRef.of(target));
        }
        return new PathEntry(id, target, from, to, axes, geometry, timing);
    }

    // ========== axes / geometry / timing（校验器共用） ==========

    /**
     * 解析 {@code axes}：数组、去重、顺序无关、至少一项；未知轴报错。
     *
     * @param element {@code axes} 元素（不可为 {@code null}）
     * @param path    字段路径（报错定位用）
     * @return 轴集合（{@code true} = 路径覆盖该轴）
     */
    public static AxisConstraint parseAxes(JsonElement element, String path) throws ScriptParseException {
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            throw new ScriptParseException(path, "需要数组（x / y / z 的子集；缺省 = 三轴）");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            throw new ScriptParseException(path, "至少需要一项（x / y / z）");
        }
        boolean x = false;
        boolean y = false;
        boolean z = false;
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (item == null || !isString(item)) {
                throw new ScriptParseException(path + "[" + i + "]", "需要字符串（x / y / z）");
            }
            switch (item.getAsString()) {
                case "x" -> x = true;
                case "y" -> y = true;
                case "z" -> z = true;
                default -> throw new ScriptParseException(path + "[" + i + "]",
                        "未知轴 '" + item.getAsString() + "'（可选: x, y, z）");
            }
        }
        return new AxisConstraint(x, y, z);
    }

    /**
     * 解析 {@code geometry}：必填对象、{@code type} 必填字符串；{@code line} → 线性路径，
     * 其余类型原样交给 {@link PathCurveRegistry#create}（未知类型 / 未知参数 / 参数非法都由它报）。
     *
     * @param element {@code geometry} 元素
     * @param path    字段路径（报错定位用）
     * @return 路径形状
     */
    public static PathSpec parseGeometry(JsonElement element, String path) throws ScriptParseException {
        if (element == null || element.isJsonNull()) {
            throw new ScriptParseException(path, "缺失（必填对象 {type, ...参数}）");
        }
        if (!element.isJsonObject()) {
            throw new ScriptParseException(path, "需要对象 {type, ...参数}");
        }
        JsonObject geometry = element.getAsJsonObject();
        JsonElement typeElement = geometry.get("type");
        if (typeElement == null || typeElement.isJsonNull()) {
            throw new ScriptParseException(path + ".type", "缺失（曲线类型，如 line / bezier）");
        }
        if (!isString(typeElement)) {
            throw new ScriptParseException(path + ".type", "需要字符串（曲线类型）");
        }
        String type = typeElement.getAsString();
        if (LINE_TYPE.equals(type)) {
            for (String key : geometry.keySet()) {
                if (!"type".equals(key)) {
                    throw new ScriptParseException(path + "." + key, "未知参数（line 只接受 type）");
                }
            }
            return LinearPathSpec.INSTANCE;
        }
        try {
            return PathCurveRegistry.create(geometry, path);
        } catch (PathCurveException e) {
            throw new ScriptParseException(e.fieldPath(), e.getMessage());
        }
    }

    /**
     * 解析 {@code timing}：缺省 / {@code {"type":"linear"}} → 线性；
     * {@code {"type":"keyframes","points":[{t,s},...]}} → {@link PathTiming#of}（端点 / 单调性由它校验）。
     *
     * @param element {@code timing} 元素（{@code null} / JSON null → 线性）
     * @param path    字段路径（报错定位用）
     * @return 时间映射
     */
    public static PathTiming parseTiming(JsonElement element, String path) throws ScriptParseException {
        if (element == null || element.isJsonNull()) return PathTiming.linear();
        if (!element.isJsonObject()) {
            throw new ScriptParseException(path, "需要对象 {type: linear | keyframes, ...}");
        }
        JsonObject timing = element.getAsJsonObject();
        JsonElement typeElement = timing.get("type");
        String type;
        if (typeElement == null || typeElement.isJsonNull()) {
            type = "linear";
        } else if (!isString(typeElement)) {
            throw new ScriptParseException(path + ".type", "需要字符串（linear / keyframes）");
        } else {
            type = typeElement.getAsString();
        }
        switch (type) {
            case "linear" -> {
                rejectUnknownKeys(timing, path, TIMING_LINEAR_KEYS);
                return PathTiming.linear();
            }
            case "keyframes" -> {
                rejectUnknownKeys(timing, path, TIMING_KEYFRAME_KEYS);
                JsonElement pointsElement = timing.get("points");
                if (pointsElement == null || pointsElement.isJsonNull()) {
                    throw new ScriptParseException(path + ".points",
                            "缺失（keyframes 需要至少 2 个点 {t, s}）");
                }
                if (!pointsElement.isJsonArray()) {
                    throw new ScriptParseException(path + ".points", "需要数组（元素 = {t, s}）");
                }
                JsonArray points = pointsElement.getAsJsonArray();
                if (points.size() < 2) {
                    throw new ScriptParseException(path + ".points",
                            "至少需要 2 个点，实际: " + points.size());
                }
                float[] times = new float[points.size()];
                float[] values = new float[points.size()];
                for (int i = 0; i < points.size(); i++) {
                    String pointPath = path + ".points[" + i + "]";
                    JsonElement pointElement = points.get(i);
                    if (pointElement == null || !pointElement.isJsonObject()) {
                        throw new ScriptParseException(pointPath, "需要对象 {t, s}");
                    }
                    JsonObject point = pointElement.getAsJsonObject();
                    rejectUnknownKeys(point, pointPath, POINT_KEYS);
                    times[i] = requireFiniteFloat(point, pointPath, "t");
                    values[i] = requireFiniteFloat(point, pointPath, "s");
                }
                try {
                    return PathTiming.of(times, values);
                } catch (IllegalArgumentException e) {
                    throw new ScriptParseException(path, e.getMessage());
                }
            }
            default -> throw new ScriptParseException(path + ".type",
                    "未知时间映射类型 '" + type + "'（可选: linear, keyframes）");
        }
    }

    // ========== 位置节点时间（校验器共用） ==========

    /** 目标片段 {@code position.x/y/z} 关键帧时间的并集（升序、去重）。 */
    public static float[] positionNodeTimes(Clip clip) {
        if (clip == null) return NO_TIMES;
        ClipObjects objects = clip.getObjects();
        if (objects == null || objects.isEmpty()) return NO_TIMES;
        List<Float> times = new ArrayList<>(8);
        for (String path : POSITION_PATHS) {
            SampledProperty property = objects.get(path);
            if (property == null) continue;
            for (ObjectKeyframe keyframe : property.keyframes()) {
                times.add(keyframe.time());
            }
        }
        return sortedUnique(times);
    }

    /**
     * 原始 JSON {@code objects} 对象上 {@code position.x/y/z} 关键帧时间的并集（升序、去重）。
     * 供校验器使用（与解析器同一口径）。
     *
     * @param objects 片段的 {@code objects} 对象；{@code null} → 空数组
     */
    public static float[] positionNodeTimes(JsonObject objects) {
        if (objects == null) return NO_TIMES;
        List<Float> times = new ArrayList<>(8);
        for (String path : POSITION_PATHS) {
            JsonElement element = objects.get(path);
            if (element == null || !element.isJsonObject()) continue;
            JsonElement keyframes = element.getAsJsonObject().get("keyframes");
            if (keyframes == null || !keyframes.isJsonArray()) continue;
            for (JsonElement keyframeElement : keyframes.getAsJsonArray()) {
                if (keyframeElement == null || !keyframeElement.isJsonObject()) continue;
                JsonElement timeElement = keyframeElement.getAsJsonObject().get("time");
                if (timeElement == null || !isNumber(timeElement)) continue;
                float time = timeElement.getAsFloat();
                if (Float.isFinite(time)) times.add(time);
            }
        }
        return sortedUnique(times);
    }

    /**
     * 在升序节点时间里定位一个端点（精确匹配优先，其次 {@link #TIME_EPSILON}）。
     *
     * @return 下标；未命中 = {@code -1}
     */
    public static int indexOfTime(float[] times, float value) {
        if (times == null) return -1;
        for (int i = 0; i < times.length; i++) {
            if (times[i] == value) return i;
        }
        for (int i = 0; i < times.length; i++) {
            if (Math.abs(times[i] - value) <= TIME_EPSILON) return i;
        }
        return -1;
    }

    /** 节点时间的可读列表（报错文案用，如 {@code [0.0, 2.0, 4.0]}）。 */
    public static String formatTimes(float[] times) {
        if (times == null || times.length == 0) return "[]";
        StringBuilder text = new StringBuilder(32).append('[');
        for (int i = 0; i < times.length; i++) {
            if (i > 0) text.append(", ");
            text.append(times[i]);
        }
        return text.append(']').toString();
    }

    // ========== 基础读取 ==========

    private static float[] sortedUnique(List<Float> values) {
        if (values.isEmpty()) return NO_TIMES;
        values.sort(Float::compare);
        float[] result = new float[values.size()];
        int size = 0;
        for (float value : values) {
            if (size == 0 || result[size - 1] != value) {
                result[size++] = value;
            }
        }
        if (size == result.length) return result;
        float[] trimmed = new float[size];
        System.arraycopy(result, 0, trimmed, 0, size);
        return trimmed;
    }

    private static void rejectUnknownKeys(JsonObject obj, String path, List<String> allowed)
            throws ScriptParseException {
        for (String key : obj.keySet()) {
            if (!allowed.contains(key)) {
                throw new ScriptParseException(path + "." + key,
                        "未知键（允许: " + String.join(", ", allowed) + "）");
            }
        }
    }

    private static String requireString(JsonObject obj, String path, String key)
            throws ScriptParseException {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ScriptParseException(path + "." + key, "缺失（需要字符串）");
        }
        if (!isString(element)) {
            throw new ScriptParseException(path + "." + key, "需要字符串，实际: " + element);
        }
        String value = element.getAsString();
        if (value.isEmpty()) {
            throw new ScriptParseException(path + "." + key, "不能为空");
        }
        return value;
    }

    private static float requireFiniteFloat(JsonObject obj, String path, String key)
            throws ScriptParseException {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ScriptParseException(path + "." + key, "缺失（需要有限数字）");
        }
        if (!isNumber(element)) {
            throw new ScriptParseException(path + "." + key, "需要数字，实际: " + element);
        }
        float value = element.getAsFloat();
        if (!Float.isFinite(value)) {
            throw new ScriptParseException(path + "." + key, "必须是有限数字（NaN / Infinity 不接受）");
        }
        return value;
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static String path(String basePath, String local) {
        if (basePath == null || basePath.isEmpty() || "<root>".equals(basePath)) return local;
        return basePath + "." + local;
    }
}
