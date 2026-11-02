package com.immersivecinematics.immersive_cinematics.script.object;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.script.PositionData;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.schema.ObjectDef;
import com.immersivecinematics.immersive_cinematics.script.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 新格式（{@code objects}）片段的静态校验器。
 *
 * <p>只处理带 {@code objects} 的片段的语义校验；旧 {@code keyframes} 链路仍由
 * {@code ScriptValidator} 自己的检查负责，本类不读旧字段。校验口径：
 * {@code schema-and-types.md} §3.7、{@code track-camera.md} §3.2、
 * {@code track-overlay-letterbox.md} §3.1/§3.2、{@code track-audio-adjust.md} §3.2。</p>
 *
 * <p>只收集 issue，不修改脚本、不补关键帧、不排序、不钳制。所有 issue 带片段路径前缀
 * （{@code timeline.tracks[i].clips[j]: ...}），对象级文案与 parser 共用同一套
 * {@code <path>: ...} 句式。</p>
 */
public final class ObjectValidator {

    /** 新格式片段允许的 clip 级键；其余键（含旧 {@code keyframes}）一律报错。 */
    private static final List<String> CLIP_KEYS = List.of(
            "start_time", "duration", "objects", "loop", "loop_count", "loop_mode");

    /** 参考系切换：这些 step 对象自身的变化点必须和 {@code position.x/y/z} 关键帧对齐。 */
    private static final List<String> SPACE_SWITCH_FIELDS = List.of(
            "position_mode", "position_space", "position_origin",
            "position_origin_freeze", "facing_source");

    /** 只有在 {@code position_space=base} 时才改变参考系的载荷/冻结开关。 */
    private static final List<String> BASE_SWITCH_FIELDS = List.of(
            "facing_target", "facing_target_freeze", "facing_entity",
            "look_at_freeze", "yaw_base_from_freeze", "yaw_base_to_freeze");

    /** {@code layer_type=image} 允许的素材扩展名（只决定运行时实现，不要求 ffmpeg 在场）。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "gif", "mp4", "webm", "mkv");

    private ObjectValidator() {
    }

    /**
     * 校验一个携带 {@code objects} 的片段。
     *
     * @param type 轨道类型；{@code null}（未知轨道类型）只做 clip 级检查
     * @param clip 片段 JSON
     * @param clipPath 片段路径（用于 issue 前缀，如 {@code timeline.tracks[0].clips[1]}）
     * @param issues 收集目标
     */
    public static void validate(TrackType type, JsonObject clip, String clipPath, List<String> issues) {
        String prefix = clipPath == null || clipPath.isEmpty() ? "" : clipPath + ": ";
        checkClipFields(type, clip, prefix, issues);
        rejectPingpong(type, clip, prefix, issues);

        JsonElement objectsElement = clip.get("objects");
        if (objectsElement == null || !objectsElement.isJsonObject()) {
            issues.add(prefix + "objects 必须是对象");
            return;
        }
        if (type == null) {
            return;
        }
        new Check(type, objectsElement.getAsJsonObject(), prefix, issues).run();
    }

    // ======================================================================
    // clip 级：键白名单 + 播放控制字段类型
    // ======================================================================

    private static void checkClipFields(TrackType type, JsonObject clip, String prefix, List<String> issues) {
        for (String key : clip.keySet()) {
            if (!CLIP_KEYS.contains(key)) {
                issues.add(prefix + "新格式片段只允许 start_time/duration/objects/loop/loop_count/loop_mode，发现 " + key);
            }
        }
        JsonElement loop = clip.get("loop");
        boolean looping = false;
        if (loop != null) {
            if (!loop.isJsonPrimitive() || !loop.getAsJsonPrimitive().isBoolean()) {
                issues.add(prefix + "loop 必须是布尔值");
            } else {
                looping = loop.getAsBoolean();
            }
        }
        Integer count = null;
        JsonElement loopCount = clip.get("loop_count");
        if (loopCount != null) {
            count = intValue(loopCount);
            if (count == null) {
                issues.add(prefix + "loop_count 必须是整数");
            } else if (count == 0) {
                issues.add(prefix + "loop_count 不允许为 0（-1=无限循环，正整数=循环次数）");
            } else if (count < -1) {
                issues.add(prefix + "loop_count 不能小于 -1（-1=无限循环，正整数=循环次数）");
            }
        }
        JsonElement loopMode = clip.get("loop_mode");
        String mode = null;
        if (loopMode != null) {
            mode = loopModeString(loopMode);
            if (mode == null) {
                issues.add(prefix + "loop_mode 必须是 repeat 或 pingpong");
            }
        }
        if (count != null && count != 0 && count >= -1 && !looping) {
            issues.add(prefix + "loop=false 时 loop_count 不生效：需要 loop=true 才进入执行循环");
        }
        if ("pingpong".equals(mode) && (type == null || isVisualLoopTrack(type))) {
            if (!looping) {
                issues.add(prefix + "loop_mode=pingpong 需要 loop=true（否则按 repeat 播放）");
            }
            if (count != null && count == 1) {
                issues.add(prefix + "loop_mode=pingpong 与 loop_count=1 无往复效果（至少需要 2 个周期）");
            }
        }
        if (clip.has("duration") && (looping || count != null || mode != null)) {
            JsonElement duration = clip.get("duration");
            if (duration.isJsonPrimitive() && duration.getAsJsonPrimitive().isNumber()
                    && duration.getAsDouble() < 0d) {
                issues.add(prefix + "duration<0（无限时长）与循环参数冲突：无限片段不需要 loop/loop_count/loop_mode");
            }
        }
    }

    /** 视觉轨：pingpong 的时间映射只对这几类轨道有意义（其余轨道由 {@link #rejectPingpong} 报错）。 */
    private static boolean isVisualLoopTrack(TrackType type) {
        return type == TrackType.CAMERA || type == TrackType.OVERLAY
                || type == TrackType.ADJUST || type == TrackType.LETTERBOX;
    }

    private static void rejectPingpong(TrackType type, JsonObject clip, String prefix, List<String> issues) {
        if (type != TrackType.AUDIO && type != TrackType.EVENT && type != TrackType.MOD_EVENT) {
            return;
        }
        if ("pingpong".equals(loopModeString(clip.get("loop_mode")))) {
            issues.add(prefix + "loop_mode: " + type.name() + " 轨道不支持 pingpong");
        }
    }

    /** {@code loop_mode} 的字符串值；非字符串或未知值返回 {@code null}。 */
    private static String loopModeString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = element.getAsString();
        return "repeat".equals(text) || "pingpong".equals(text) ? text : null;
    }

    private static Integer intValue(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double number = element.getAsDouble();
        if (!Double.isFinite(number) || number != Math.floor(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            return null;
        }
        return (int) number;
    }

    private static boolean isNonEmptyArray(JsonElement element) {
        return element != null && element.isJsonArray() && element.getAsJsonArray().size() > 0;
    }

    // ======================================================================
    // 单片段校验状态
    // ======================================================================

    private static final class Check {

        private final TrackType type;
        private final JsonObject objects;
        private final String prefix;
        private final List<String> issues;
        private final Map<String, ObjectDef> defs;
        /** 已出现且形态为对象的属性；缺失的路径不登记。 */
        private final Map<String, Prop> props = new LinkedHashMap<>();

        Check(TrackType type, JsonObject objects, String prefix, List<String> issues) {
            this.type = type;
            this.objects = objects;
            this.prefix = prefix;
            this.issues = issues;
            this.defs = SchemaRegistry.getObjects(type);
        }

        void run() {
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                checkProperty(entry.getKey(), entry.getValue());
            }
            checkRequiresAndRequired();
            if (type == TrackType.CAMERA) {
                checkCamera();
            } else if (type == TrackType.OVERLAY) {
                checkOverlay();
            }
        }

        // ------------------------------------------------------------------
        // 属性形态 / 类型 / keyframe
        // ------------------------------------------------------------------

        private void checkProperty(String path, JsonElement element) {
            ObjectDef def = PropertyPath.isValid(path) ? defs.get(path) : null;
            if (def == null) {
                issues.add(prefix + "未知对象路径: " + path);
                return;
            }
            if (element == null || !element.isJsonObject()) {
                issues.add(prefix + path + ": 需要对象 {value?, keyframes?}");
                return;
            }
            JsonObject object = element.getAsJsonObject();
            for (String member : object.keySet()) {
                if (!"value".equals(member) && !"keyframes".equals(member)) {
                    issues.add(prefix + path + ": 未知成员 " + member);
                }
            }
            boolean hasValue = object.has("value");
            boolean hasKeyframes = object.has("keyframes");
            if (!hasValue && !hasKeyframes) {
                issues.add(prefix + path + ": 需要对象 {value?, keyframes?}");
            }
            switch (def.mode()) {
                case VALUE -> {
                    if (hasKeyframes) {
                        issues.add(prefix + path + ": mode=value 不允许 keyframes");
                    }
                }
                case EVENT -> {
                    if (!hasKeyframes || !isNonEmptyArray(object.get("keyframes"))) {
                        issues.add(prefix + path + ": mode=event 必须有 keyframes");
                    }
                }
                case ANIMATED -> {
                    // value / keyframes 均可，keyframes 允许空数组
                }
            }

            Prop prop = new Prop(def);
            props.put(path, prop);
            if (hasValue) {
                prop.hasValue = true;
                prop.value = object.get("value");
                checkTyped(def, prop.value, path, path + ".value");
            }
            if (hasKeyframes && def.mode() != PropertyMode.VALUE) {
                checkKeyframes(def, object.get("keyframes"), path, prop);
            }
        }

        private void checkKeyframes(ObjectDef def, JsonElement element, String path, Prop prop) {
            if (element == null || !element.isJsonArray()) {
                issues.add(prefix + path + ".keyframes: 需要数组");
                return;
            }
            JsonArray frames = element.getAsJsonArray();
            float previousTime = 0f;
            boolean hasPrevious = false;
            for (int i = 0; i < frames.size(); i++) {
                JsonElement frameElement = frames.get(i);
                String framePath = path + ".keyframes[" + i + "]";
                if (frameElement == null || !frameElement.isJsonObject()) {
                    issues.add(prefix + framePath + ": 需要对象 {time, value}");
                    continue;
                }
                JsonObject frame = frameElement.getAsJsonObject();
                for (String member : frame.keySet()) {
                    if (!"time".equals(member) && !"value".equals(member)) {
                        issues.add(prefix + framePath + ": 未知成员 " + member);
                    }
                }
                Float time = finiteFloat(frame.get("time"));
                if (time == null) {
                    issues.add(prefix + framePath + ".time: 必须为有限数字");
                } else {
                    if (time < 0f) {
                        issues.add(prefix + framePath + ".time: 不能为负数");
                    }
                    if (hasPrevious && time <= previousTime) {
                        issues.add(prefix + framePath + ".time: 关键帧时间必须单调递增，前一帧: " + fmt(previousTime));
                    }
                    previousTime = time;
                    hasPrevious = true;
                }
                JsonElement value = frame.get("value");
                checkTyped(def, value, path, framePath + ".value");
                if (time != null && time >= 0f && isAscending(prop, time)) {
                    prop.frames.add(new Frame(time, value));
                }
            }
        }

        private static boolean isAscending(Prop prop, float time) {
            return prop.frames.isEmpty() || time > prop.frames.get(prop.frames.size() - 1).time();
        }

        private void checkTyped(ObjectDef def, JsonElement value, String propertyPath, String fieldPath) {
            Object parsed;
            try {
                parsed = ObjectCodec.fromJson(def.type(), value,
                        def.type() == PropertyType.EVENT_RECORD ? propertyPath : fieldPath);
            } catch (ObjectFormatException e) {
                issues.add(prefix + e.getMessage());
                return;
            }
            if (def.type() == PropertyType.ENUM) {
                if (!def.enumValues().contains(parsed)) {
                    issues.add(prefix + fieldPath + ": 不在枚举内（允许: "
                            + String.join(", ", def.enumValues()) + "）");
                }
                return;
            }
            if (parsed instanceof Float number) {
                checkRange(def, number.doubleValue(), fieldPath);
                if ("zoom".equals(def.path()) && number <= 0f) {
                    issues.add(prefix + fieldPath + ": 缩放必须为正");
                }
            }
        }

        private void checkRange(ObjectDef def, double number, String fieldPath) {
            Double min = def.min();
            Double max = def.max();
            if (min == null && max == null) {
                return;
            }
            if ((min != null && number < min) || (max != null && number > max)) {
                issues.add(prefix + fieldPath + ": 超出范围 ["
                        + (min == null ? "-∞" : fmt(min)) + ", " + (max == null ? "+∞" : fmt(max)) + "]");
            }
        }

        // ------------------------------------------------------------------
        // requires / required
        // ------------------------------------------------------------------

        private void checkRequiresAndRequired() {
            for (ObjectDef def : defs.values()) {
                Prop prop = props.get(def.path());
                boolean present = objects.has(def.path());
                boolean satisfied = true;
                if (def.requires() != null) {
                    Boolean result = evalRequires(def, def.requires());
                    if (result == null) {
                        continue; // 语法非法：已报 issue，不再按前置条件推断
                    }
                    satisfied = result;
                }
                if (present && !satisfied) {
                    issues.add(prefix + def.path() + ": 写了但前置条件不满足（requires=" + def.requires() + "）");
                }
                if (satisfied && def.required() && !hasSource(def, prop)) {
                    issues.add(prefix + def.path() + ": 必填对象没有值/关键帧/默认值");
                }
            }
        }

        private static boolean hasSource(ObjectDef def, Prop prop) {
            if (prop != null && (prop.hasValue || !prop.frames.isEmpty())) {
                return true;
            }
            return def.defaultValue() != null;
        }

        /**
         * 解析 {@code path=value} / {@code path!=value}；返回 {@code null} = 语法非法（已报 issue）。
         */
        private Boolean evalRequires(ObjectDef def, String expression) {
            String text = expression.trim();
            int index = text.indexOf("!=");
            boolean negated = index >= 0;
            if (!negated) {
                index = text.indexOf('=');
            }
            if (index <= 0) {
                return requiresSyntaxIssue(def, expression);
            }
            String referenced = text.substring(0, index).trim();
            String literal = text.substring(index + (negated ? 2 : 1)).trim();
            if (literal.isEmpty() || !PropertyPath.isValid(referenced)) {
                return requiresSyntaxIssue(def, expression);
            }
            String actual = staticValue(referenced);
            boolean equal = actual != null && actual.equals(literal);
            return negated ? !equal : equal;
        }

        private Boolean requiresSyntaxIssue(ObjectDef def, String expression) {
            issues.add(prefix + def.path() + ": requires 表达式非法（只支持 path=value / path!=value）: "
                    + expression);
            return null;
        }

        /** 被引用字段的 t=0 有效值：keyframes 首帧 > value > default。 */
        private String staticValue(String path) {
            Prop prop = props.get(path);
            if (prop != null) {
                if (!prop.frames.isEmpty()) {
                    return token(prop.frames.get(0).value());
                }
                if (prop.hasValue) {
                    return token(prop.value);
                }
            }
            ObjectDef def = defs.get(path);
            return def == null ? null : defaultValueToken(def);
        }

        // ------------------------------------------------------------------
        // CAMERA：点源 / 参考系 / 朝向
        // ------------------------------------------------------------------

        private void checkCamera() {
            String mode = valueOf("position_mode");
            String space = valueOf("position_space");
            String origin = valueOf("position_origin");
            int originKind = PositionData.originKindOf(origin);
            boolean base = "base".equals(space);
            boolean absolute = "absolute".equals(mode);

            if (absolute) {
                if (!origin.isEmpty()) {
                    issues.add(prefix + "position_mode=absolute 时 position_origin 必须为空");
                }
                if (base) {
                    issues.add(prefix + "position_mode=absolute 不允许 position_space=base");
                }
            }

            String facingSource = valueOf("facing_source");
            if (facingSource.isEmpty()) {
                facingSource = "auto";
            }
            String facingTarget = valueOf("facing_target");
            String facingEntity = valueOf("facing_entity");

            if (base) {
                switch (facingSource) {
                    case "auto" -> {
                        if (!facingTarget.isEmpty() && !facingEntity.isEmpty()) {
                            issues.add(prefix + "facing_source=auto 时 facing_target 与 facing_entity 不能同时非空");
                        }
                    }
                    case "entity" -> {
                        if (facingEntity.isEmpty() && !isEntityOrigin(originKind, base)) {
                            issues.add(prefix + "facing_source=entity 需要实体来源（facing_entity 或实体原点）");
                        }
                    }
                    case "line" -> {
                        if (facingTarget.isEmpty()) {
                            issues.add(prefix + "facing_source=line 需要 facing_target");
                        }
                    }
                    case "world" -> {
                        if (!facingTarget.isEmpty() || !facingEntity.isEmpty()) {
                            issues.add(prefix + "facing_source=world 不使用 facing_target/facing_entity，已忽略");
                        }
                    }
                    default -> {
                        // enum 已校验；未知值不再做方向源推断
                    }
                }
            } else {
                for (String path : List.of("facing_source", "facing_target", "facing_entity")) {
                    if (objects.has(path)) {
                        issues.add(prefix + path + ": 写了但 position_space=world 不读取（已忽略）");
                    }
                }
            }

            checkFreezes(originKind, base, facingSource, facingTarget);
            checkSwitches();
        }

        private static boolean isEntityOrigin(int originKind, boolean base) {
            return originKind == PositionData.ORIGIN_SELECTOR
                    || originKind == PositionData.ORIGIN_PLAYER
                    || (originKind == PositionData.NO_ORIGIN && base);
        }

        private void checkFreezes(int originKind, boolean base, String facingSource, String facingTarget) {
            if (objects.has("position_origin_freeze")) {
                boolean dynamic = originKind == PositionData.ORIGIN_SELECTOR
                        || (originKind == PositionData.NO_ORIGIN && base);
                if (!dynamic) {
                    issues.add(prefix + "position_origin_freeze: 写了但当前点源不是实体选择器，不读取（已忽略）");
                }
            }
            if (objects.has("facing_target_freeze")) {
                boolean read = base && ("line".equals(facingSource)
                        || ("auto".equals(facingSource) && !facingTarget.isEmpty()));
                if (!read) {
                    issues.add(prefix + "facing_target_freeze: 写了但方向源不是 line，不读取（已忽略）");
                }
            }
            if (objects.has("look_at_freeze") && !"entity".equals(valueOf("look_at"))) {
                issues.add(prefix + "look_at_freeze: 写了但 look_at 不是 entity，不读取（已忽略）");
            }
            if (objects.has("yaw_base_from_freeze") && !isEntitySelector("yaw_base_from")) {
                issues.add(prefix + "yaw_base_from_freeze: 写了但端点不是实体选择器，不读取（已忽略）");
            }
            if (objects.has("yaw_base_to_freeze") && !isEntitySelector("yaw_base_to")) {
                issues.add(prefix + "yaw_base_to_freeze: 写了但端点不是实体选择器，不读取（已忽略）");
            }
        }

        private boolean isEntitySelector(String path) {
            return PositionData.originKindOf(valueOf(path)) == PositionData.ORIGIN_SELECTOR;
        }

        /**
         * 参考系切换对齐：step 对象的变化点必须同时存在 {@code position.x/y/z} 关键帧。
         * t=0 的初始值不算“变化”。
         */
        private void checkSwitches() {
            Map<Float, String> changes = new LinkedHashMap<>();
            for (String path : SPACE_SWITCH_FIELDS) {
                collectChanges(path, changes);
            }
            for (String path : BASE_SWITCH_FIELDS) {
                Prop prop = props.get(path);
                if (prop == null) {
                    continue;
                }
                String previous = prop.initialToken();
                for (Frame frame : prop.frames) {
                    String token = token(frame.value());
                    if (frame.time() <= 0f) {
                        previous = token;
                        continue;
                    }
                    if (!token.equals(previous)) {
                        if ("base".equals(spaceAt(frame.time()))) {
                            changes.putIfAbsent(frame.time(), path);
                        }
                        previous = token;
                    }
                }
            }
            for (Map.Entry<Float, String> entry : changes.entrySet()) {
                float time = entry.getKey();
                if (!hasKeyframeAt("position.x", time)
                        || !hasKeyframeAt("position.y", time)
                        || !hasKeyframeAt("position.z", time)) {
                    issues.add(prefix + entry.getValue()
                            + ": 参考系切换缺少 position.x/y/z 关键帧（time=" + fmt(time) + "）");
                }
            }
        }

        private void collectChanges(String path, Map<Float, String> changes) {
            Prop prop = props.get(path);
            if (prop == null) {
                return;
            }
            String previous = prop.initialToken();
            for (Frame frame : prop.frames) {
                String token = token(frame.value());
                if (frame.time() <= 0f) {
                    previous = token;
                    continue;
                }
                if (!token.equals(previous)) {
                    changes.putIfAbsent(frame.time(), path);
                    previous = token;
                }
            }
        }

        private String spaceAt(float time) {
            Prop prop = props.get("position_space");
            if (prop == null) {
                String value = staticValue("position_space");
                return value == null ? "world" : value;
            }
            String value = prop.initialToken();
            for (Frame frame : prop.frames) {
                if (frame.time() <= time) {
                    value = token(frame.value());
                } else {
                    break;
                }
            }
            return value == null ? "world" : value;
        }

        private boolean hasKeyframeAt(String path, float time) {
            Prop prop = props.get(path);
            if (prop == null) {
                return false;
            }
            for (Frame frame : prop.frames) {
                if (frame.time() == time) {
                    return true;
                }
            }
            return false;
        }

        // ------------------------------------------------------------------
        // OVERLAY：appliesTo / image 扩展名
        // ------------------------------------------------------------------

        private void checkOverlay() {
            String layerType = valueOf("layer_type");
            if (layerType.isEmpty()) {
                layerType = "fade";
            }
            for (ObjectDef def : defs.values()) {
                if (def.appliesTo().isEmpty() || !objects.has(def.path())) {
                    continue;
                }
                if (!def.appliesTo().contains(layerType)) {
                    issues.add(prefix + def.path() + ": 不适用于 layer_type=" + layerType);
                }
            }
            if ("image".equals(layerType)) {
                String path = valueOf("path");
                if (!path.isEmpty() && !IMAGE_EXTENSIONS.contains(extensionOf(path))) {
                    issues.add(prefix + "path: image 素材扩展名必须是 .png/.gif/.mp4/.webm/.mkv（当前: "
                            + path + "）");
                }
            }
        }

        private static String extensionOf(String path) {
            int dot = path.lastIndexOf('.');
            return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        // ------------------------------------------------------------------
        // 取值工具
        // ------------------------------------------------------------------

        /** 对象的 t=0 有效值；无法求值返回空串。 */
        private String valueOf(String path) {
            String value = staticValue(path);
            return value == null ? "" : value;
        }
    }

    // ======================================================================
    // 数据结构
    // ======================================================================

    /** 一个已出现的对象属性：静态值与已通过形态检查的关键帧。 */
    private static final class Prop {

        private final ObjectDef def;
        private boolean hasValue;
        private JsonElement value;
        private final List<Frame> frames = new ArrayList<>();

        Prop(ObjectDef def) {
            this.def = def;
        }

        /** t=0 有效值：首帧 > value > default。 */
        String initialToken() {
            if (!frames.isEmpty()) {
                return token(frames.get(0).value());
            }
            if (hasValue) {
                return token(value);
            }
            return defaultValueToken(def);
        }
    }

    private record Frame(float time, JsonElement value) {
    }

    private static String defaultValueToken(ObjectDef def) {
        Object fallback = def.defaultValue();
        if (fallback == null) {
            return null;
        }
        if (fallback instanceof Number number) {
            return fmt(number.doubleValue());
        }
        return String.valueOf(fallback);
    }

    /** JSON 值的比较用字符串形态；数字归一化，避免 {@code 1} 与 {@code 1.0} 不等。 */
    private static String token(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return fmt(primitive.getAsDouble());
            }
            return primitive.getAsString();
        }
        return element.toString();
    }

    private static Float finiteFloat(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double number = element.getAsDouble();
        if (!Double.isFinite(number) || !Float.isFinite((float) number)) {
            return null;
        }
        return (float) number;
    }

    private static String fmt(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
