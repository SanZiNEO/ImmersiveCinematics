package com.immersivecinematics.immersive_cinematics.script.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.camera.rule.RuleActions;
import com.immersivecinematics.immersive_cinematics.math.path.PathCapability;
import com.immersivecinematics.immersive_cinematics.script.ContinuitySpec;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.TriggerDefinition;
import com.immersivecinematics.immersive_cinematics.script.identity.ClipIdentity;
import com.immersivecinematics.immersive_cinematics.script.path.PathCollectionParser;
import com.immersivecinematics.immersive_cinematics.script.rule.ClipExitPolicyParser;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleCollectionParser;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleControl;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleDefinition;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleReleaseKind;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleSchedule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * 将 Java 字段元数据导出为标准 JSON schema。
 *
 * 以后 WebUI 连接后通过 {@code schema.get} 获取这份数据，由前端动态生成表单；
 * Java 侧始终是唯一 schema 权威，避免两端字段漂移。
 */
public final class SchemaExporter {

    private SchemaExporter() {}

    /** 导出完整 schema（meta / tracks / triggers / models / curveTypes / script_structure 六段）；只转发各注册表，不自行补字段。 */
    public static JsonObject exportAll() {
        JsonObject root = new JsonObject();
        root.add("meta", exportFields(SchemaRegistry.getMetaFields()));

        JsonObject tracks = new JsonObject();
        for (TrackType type : TrackType.values()) {
            TrackTypeSchema schema = SchemaRegistry.get(type);
            JsonObject track = new JsonObject();
            track.add("clips", exportFields(schema.clipFields()));
            track.add("keyframes", exportFields(schema.kfFields()));
            track.add("playControl", exportFields(schema.playControl()));
            track.add("objects", exportObjects(schema.objects()));
            tracks.add(type.name(), track);
        }
        root.add("tracks", tracks);

        // 触发器 schema：类型列表 + 每种类型的 conditions 字段
        JsonObject triggers = new JsonObject();
        JsonArray typeList = new JsonArray();
        for (String t : TriggerSchemas.typeList()) typeList.add(t);
        triggers.add("types", typeList);
        JsonObject conditions = new JsonObject();
        for (Map.Entry<String, Map<String, FieldDef>> e : TriggerSchemas.all().entrySet()) {
            conditions.add(e.getKey(), exportFields(e.getValue()));
        }
        triggers.add("conditions", conditions);
        // 触发器级字段（additive）：id = 脚本内唯一身份；缺省 = type#声明下标
        JsonObject triggerFields = new JsonObject();
        JsonObject triggerId = field("string", false, null,
                "脚本内唯一的触发器身份；缺省 = type#声明下标");
        triggerId.addProperty("pattern", TriggerDefinition.ID_REGEX);
        // exportFields 风格用 doc；同时给出 description 同义键（additive，不改既有键）
        triggerId.addProperty("description", "脚本内唯一的触发器身份；缺省 = type#声明下标");
        put(triggerFields, "id", triggerId);
        // 连续语义（additive）：sweep / dwell / window；缺省 = 关闭（旧点判定路径）
        JsonObject continuityFields = new JsonObject();
        put(continuityFields, "sweep", field("boolean", false, false,
                "把相邻两次采样之间的运动近似成线段 / 最短弧；只对 location / structure / facing 生效"));
        String tickRange = "0~" + ContinuitySpec.MAX_TICKS;
        JsonObject dwell = field("integer", false, 0,
                "停留门槛（tick）：连续处于区域内至少该时长才触发；只对轮询类生效");
        dwell.addProperty("range", tickRange);
        put(continuityFields, "dwell", dwell);
        JsonObject window = field("integer", false, 0,
                "事件类子条件的时间窗口（tick）：all_of / any 内的事件类子条件按窗口内是否发生过求值");
        window.addProperty("range", tickRange);
        put(continuityFields, "window", window);
        put(triggerFields, "continuity",
                objectField(false, continuityFields, "触发器级连续语义；缺省 = 全部关闭"));
        triggers.add("triggerFields", triggerFields);
        root.add("triggers", triggers);

        // 数学模型 schema：type → 参数表（类型顺序 = 注册顺序；编辑器类型下拉 + 参数表单直接消费）
        JsonObject models = new JsonObject();
        for (String type : MathModelRegistry.types()) {
            models.add(type, exportFields(MathModelRegistry.params(type)));
        }
        root.add("models", models);

        // 路径曲线 schema：type →（capabilities + params）
        // 曲线类型顺序 = 注册顺序；编辑器类型下拉 + 参数表单直接消费
        JsonObject curveTypes = new JsonObject();
        for (String type : PathCurveRegistry.types()) {
            JsonObject curve = new JsonObject();
            JsonArray capabilities = new JsonArray();
            for (PathCapability capability : PathCurveRegistry.capabilities(type)) {
                capabilities.add(capability.name());
            }
            curve.add("capabilities", capabilities);
            JsonObject params = new JsonObject();
            for (PathParamDef def : PathCurveRegistry.params(type)) {
                JsonObject param = new JsonObject();
                param.addProperty("kind", def.kind().name());
                Object defVal = def.defaultValue();
                if (defVal == null) {
                    param.add("default", JsonNull.INSTANCE);
                } else if (defVal instanceof Boolean b) {
                    param.addProperty("default", b);
                } else if (defVal instanceof Number n) {
                    param.addProperty("default", n);
                } else {
                    param.addProperty("default", defVal.toString());
                }
                param.addProperty("required", def.required());
                JsonArray enumValues = new JsonArray();
                for (String value : def.enumValues()) {
                    enumValues.add(value);
                }
                param.add("enumValues", enumValues);
                param.addProperty("doc", def.doc());
                params.add(def.name(), param);
            }
            curve.add("params", params);
            curveTypes.add(type, curve);
        }
        root.add("curveTypes", curveTypes);

        // 脚本级结构 schema：clips / rules / rule_bindings / paths + 枚举（编辑器结构面板直接消费）
        root.add("script_structure", scriptStructure());

        return root;
    }

    /**
     * 脚本级结构 schema（{@code ROUND-I-4-MAP} §1.1–§1.7）：片段身份 / 规则定义 / 规则绑定 / 路径集合，
     * 以及动作类型、出口类型、调度类型、事实条件类型四份枚举。
     *
     * <p>本方法只导出结构（字段名 / 类型 / 必填 / 缺省值 / 枚举 / 说明），不解析脚本、不读注册表以外的状态；
     * {@code paths} 的解析归后续轮次，这里先给编辑器一份稳定契约。</p>
     */
    public static JsonObject scriptStructure() {
        JsonObject root = new JsonObject();

        // ===== clips[]：脚本级独立片段（无 start_time，track + duration 必填）=====
        JsonObject clips = new JsonObject();
        put(clips, "id", field("string", false, null,
                "片段 id（" + ClipIdentity.PATTERN + "；被规则 / 路径 / 分支 / 出口引用时必填）"));
        put(clips, "track", field("string", true, null,
                "轨道类型（独立片段必填，决定 objects schema）", trackTypeNames()));
        put(clips, "duration", field("number", true, null,
                "片段时长（秒；正数=有限，负数=无限单次）"));
        put(clips, "objects", field("object", true, null, "采样属性对象（新格式内容）"));
        put(clips, "exit", exitField(false));
        root.add("clips", clips);

        // ===== rules[]：规则定义 =====
        JsonObject rules = new JsonObject();
        put(rules, "id", field("string", true, null,
                "规则 id（" + RuleCollectionParser.ID_PATTERN + "；脚本内唯一）"));
        put(rules, "condition", field("object", true, null,
                "条件树（v1 状态谓词 + all_of/any + clip_fact/rule_fact，见 condition_fact_types）"));
        put(rules, "actions", field("array", true, null, "动作数组（至少一个；类型见 action_types）"));
        JsonObject policy = new JsonObject();
        put(policy, "once", field("boolean", false, RuleDefinition.DEFAULT_ONCE, "只触发一次（缺省 true）"));
        put(policy, "cooldown", field("number", false, RuleDefinition.DEFAULT_COOLDOWN,
                "触发后静默秒数（缺省 0，>= 0）"));
        put(policy, "mode", field("string", false, RuleDefinition.MODE_ENTER,
                "触发方式（缺省 enter）", RuleDefinition.MODES.toArray(new String[0])));
        put(policy, "dwell", field("number", false, RuleDefinition.DEFAULT_DWELL,
                "驻留判定阈值秒数（缺省 0，>= 0）"));
        put(policy, "timeout", field("number", false, RuleDefinition.TIMEOUT_NONE,
                "等待上限秒数（缺省 -1 = 无限等待）"));
        put(policy, "on_timeout", field("array", false, null, "超时动作（缺省空数组）"));
        put(rules, "policy", objectField(false, policy, "触发策略（缺省值见内层字段）"));
        root.add("rules", rules);

        // ===== rule_bindings[]：定义 × 片段 × 时间门 =====
        JsonObject bindings = new JsonObject();
        put(bindings, "id", field("string", true, null,
                "绑定 id（" + RuleCollectionParser.ID_PATTERN + "；脚本内唯一）"));
        put(bindings, "rule", field("string", true, null, "规则定义 id（必须命中 rules[].id）"));
        put(bindings, "clip", field("string", true, null, "宿主片段 id（必须命中片段 id）"));
        JsonObject gate = new JsonObject();
        put(gate, "at", field("number", true, null,
                "时间门（目标片段执行局部秒；有限片段 0 ~ duration，无限片段 >= 0）"));
        put(bindings, "gate", objectField(true, gate, "时间门（必填）"));
        put(bindings, "control", field("string", false, RuleControl.OBSERVE.scriptName(),
                "执行控制（缺省 observe）", RuleControl.SCRIPT_NAMES.toArray(new String[0])));
        put(bindings, "schedule", scheduleField(false));
        put(bindings, "exit", exitField(false));
        root.add("rule_bindings", bindings);

        // ===== paths[]：片段位置关键帧区间上的路径（I4-8 解析 / 校验 / 索引）=====
        JsonObject paths = new JsonObject();
        put(paths, "id", field("string", true, null, "路径 id（脚本内唯一，匹配 ^[A-Za-z0-9_]{1,64}$）"));
        put(paths, "clip", field("string", true, null,
                "目标片段 id（必须是 objects 片段；端点取自其 position.x/y/z 节点时间序列）"));
        put(paths, "from", field("number", true, null,
                "起点位置节点时间（秒，必须命中 position.x/y/z 关键帧时间，from < to）"));
        put(paths, "to", field("number", true, null,
                "终点位置节点时间（秒，必须命中节点时间且与 from 相邻）"));
        put(paths, "axes", field("array", false, null,
                "轴集合（缺省 x/y/z；去重、顺序无关、至少一项）", "x", "y", "z"));
        JsonObject geometry = new JsonObject();
        put(geometry, "type", field("string", true, null,
                "曲线类型（line = linear 的别名）", geometryTypeNames()));
        put(geometry, "control_points", field("array", false, null, "控制点（bezier 等曲线类型使用）"));
        put(paths, "geometry", objectField(true, geometry, "曲线几何"));
        JsonObject timing = new JsonObject();
        put(timing, "type", field("string", false, "linear",
                "时间映射类型（缺省 linear）", "linear", "keyframes"));
        put(timing, "points", field("array", false, null,
                "关键帧点 [{t,s}]（keyframes：两端 0/1、单调不减、至少 2 点）"));
        put(paths, "timing", objectField(false, timing, "时间映射（缺省线性）"));
        root.add("paths", paths);

        // ===== 枚举 =====
        JsonArray actionTypes = new JsonArray();
        for (String type : RuleActions.ACTION_TYPES_V1) actionTypes.add(type);
        root.add("action_types", actionTypes);
        root.add("exit_kinds", stringArray(ClipExitPolicyParser.KIND_NAMES));
        root.add("schedule_kinds", stringArray(RuleReleaseKind.SCRIPT_NAMES));

        JsonObject factTypes = new JsonObject();
        JsonObject clipFact = new JsonObject();
        put(clipFact, "clip", field("string", true, null, "片段 id（必须可解析）"));
        put(clipFact, "fact", field("string", true, null, "事实", "started", "completed"));
        factTypes.add("clip_fact", clipFact);
        JsonObject ruleFact = new JsonObject();
        put(ruleFact, "binding", field("string", true, null, "绑定 id（必须可解析）"));
        put(ruleFact, "fact", field("string", true, null, "事实", "succeeded", "action_completed"));
        factTypes.add("rule_fact", ruleFact);
        root.add("condition_fact_types", factTypes);

        return root;
    }

    /** 单位段：{@code exit} 对象（片段默认出口 / 绑定入口覆盖共用）。 */
    private static JsonObject exitField(boolean required) {
        JsonObject fields = new JsonObject();
        put(fields, "on_complete", field("string", true, null, "完成去向",
                ClipExitPolicyParser.KIND_NAMES.toArray(new String[0])));
        put(fields, "target", field("string", false, null, "目标片段 id（仅 goto，必须可解析）"));
        put(fields, "entry_time", field("number", false, 0.0, "入口局部秒（仅 goto；缺省 0，>= 0）"));
        return objectField(required, fields, "出口策略（片段默认 / 绑定入口覆盖）");
    }

    /** 单位段：{@code schedule} 对象（五种释放条件的具体参数）。 */
    private static JsonObject scheduleField(boolean required) {
        JsonObject fields = new JsonObject();
        put(fields, "on", field("string", true, null, "释放条件（缺省 = 立即提交）",
                RuleReleaseKind.SCRIPT_NAMES.toArray(new String[0])));
        put(fields, "clip", field("string", false, null,
                "目标片段 id（clip_start / clip_complete / local_time 必填）"));
        put(fields, "at", field("number", false, null,
                "目标片段执行局部秒（local_time 必填，>= 0）"));
        put(fields, "seconds", field("number", false, null,
                "判定成功后的真实秒数（delay 必填，>= 0；挂起不冻结）"));
        put(fields, "binding", field("string", false, null, "来源绑定 id（fact 必填，必须可解析）"));
        put(fields, "fact", field("string", false, null, "来源事实（fact 必填）",
                RuleSchedule.FACT_NAMES.toArray(new String[0])));
        return objectField(required, fields, "动作调度（缺省 = 成功后立即提交）");
    }

    /** 字段描述：类型 / 必填 / 缺省值 / 枚举 / 说明。 */
    private static JsonObject field(String type, boolean required, Object defaultValue, String doc,
                                    String... enumValues) {
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        out.addProperty("required", required);
        out.add("default", toJson(defaultValue));
        JsonArray values = new JsonArray();
        for (String value : enumValues) values.add(value);
        out.add("enumValues", values);
        out.addProperty("doc", doc);
        return out;
    }

    /** 对象字段：附带内层字段表。 */
    private static JsonObject objectField(boolean required, JsonObject fields, String doc) {
        JsonObject out = field("object", required, null, doc);
        out.add("fields", fields);
        return out;
    }

    private static void put(JsonObject parent, String name, JsonObject field) {
        parent.add(name, field);
    }

    private static JsonArray stringArray(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static String[] trackTypeNames() {
        TrackType[] types = TrackType.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].name();
        return names;
    }

    /** 路径 geometry 的 type 枚举：{@code line}（别名）在前，其后为注册表类型（去重）。 */
    private static String[] geometryTypeNames() {
        List<String> names = new ArrayList<>();
        names.add(PathCollectionParser.LINE_TYPE);
        for (String type : PathCurveRegistry.types()) {
            if (!names.contains(type)) names.add(type);
        }
        return names.toArray(new String[0]);
    }

    private static JsonObject exportFields(Map<String, FieldDef> fields) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, FieldDef> e : fields.entrySet()) {
            out.add(e.getKey(), exportField(e.getValue()));
        }
        return out;
    }

    private static JsonObject exportField(FieldDef def) {
        JsonObject o = new JsonObject();
        o.addProperty("type", def.type());

        Object defVal = def.defaultValue();
        if (defVal == null) {
            o.add("default", JsonNull.INSTANCE);
        } else if (defVal instanceof Boolean b) {
            o.addProperty("default", b);
        } else if (defVal instanceof Number n) {
            o.addProperty("default", n);
        } else {
            o.addProperty("default", defVal.toString());
        }

        o.addProperty("required", def.required());

        JsonArray vals = new JsonArray();
        for (String v : def.enumValues()) vals.add(v);
        o.add("enumValues", vals);

        o.addProperty("section", def.section());
        return o;
    }

    private static JsonObject exportObjects(Map<String, ObjectDef> objects) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, ObjectDef> entry : objects.entrySet()) {
            out.add(entry.getKey(), exportObject(entry.getValue()));
        }
        return out;
    }

    private static JsonObject exportObject(ObjectDef def) {
        JsonObject o = new JsonObject();
        o.addProperty("path", def.path());
        o.addProperty("mode", scriptName(def.mode()));
        o.addProperty("type", scriptName(def.type()));
        o.addProperty("interp", scriptName(def.interp()));
        o.add("default", toJson(def.defaultValue()));
        o.addProperty("required", def.required());
        JsonArray enumValues = new JsonArray();
        for (String value : def.enumValues()) {
            enumValues.add(value);
        }
        o.add("enumValues", enumValues);
        o.addProperty("nullable", def.nullable());
        o.add("min", def.min() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.min()));
        o.add("max", def.max() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.max()));
        o.add("requires", def.requires() == null
                ? JsonNull.INSTANCE
                : new JsonPrimitive(def.requires()));
        o.add("section", def.section() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.section()));
        o.add("group", def.group() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.group()));
        JsonArray appliesTo = new JsonArray();
        for (String value : def.appliesTo()) {
            appliesTo.add(value);
        }
        o.add("appliesTo", appliesTo);
        o.add("labelKey", def.labelKey() == null
                ? JsonNull.INSTANCE
                : new JsonPrimitive(def.labelKey()));
        o.add("unit", def.unit() == null ? JsonNull.INSTANCE : new JsonPrimitive(def.unit()));
        return o;
    }

    private static String scriptName(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    private static JsonElement toJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return object;
        }
        if (value instanceof Collection<?> collection) {
            JsonArray array = new JsonArray();
            for (Object element : collection) {
                array.add(toJson(element));
            }
            return array;
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
