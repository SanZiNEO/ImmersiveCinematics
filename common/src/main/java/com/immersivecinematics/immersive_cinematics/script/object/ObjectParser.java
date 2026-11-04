package com.immersivecinematics.immersive_cinematics.script.object;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.schema.SchemaRegistry;
import com.immersivecinematics.immersive_cinematics.spi.Interp;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;
import com.immersivecinematics.immersive_cinematics.spi.PropertyMode;
import com.immersivecinematics.immersive_cinematics.spi.PropertyType;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 新格式片段 {@code objects} 入口的解析：点分路径 → {@link SampledProperty}。
 *
 * <p>只接受新字段模型：片段级键限于 {@code start_time} / {@code duration} / {@code objects} /
 * {@code loop} / {@code loop_count} / {@code loop_mode}；属性对象只允许 {@code value} / {@code keyframes}；
 * 路径必须是 {@link SchemaRegistry} 已登记的 {@link ObjectDef}，不做旧字段名别名、不做 {@code keyframes} 回退合并。
 * 所有错误以 {@link ObjectFormatException} 抛出，消息带片段内的字段路径（如
 * {@code timeline.tracks[0].clips[0].objects.yaw.keyframes[1].time}）。</p>
 */
public final class ObjectParser {

    /** 新格式片段允许的 clip 级键；其余（尤其旧 {@code keyframes}）一律拒绝。 */
    private static final Set<String> CLIP_KEYS =
            Set.of("start_time", "duration", "objects", "loop", "loop_count", "loop_mode", "id", "exit");

    /** 片段对象集合的键。 */
    private static final String OBJECTS = "objects";

    private ObjectParser() {
    }

    /**
     * 解析一个片段的 {@code objects}。
     *
     * @param clip 片段 JSON（必须含 {@code objects}）
     * @param clipPath 片段的字段路径（用于错误信息，如 {@code timeline.tracks[0].clips[0]}）
     * @param type 轨道类型（决定对象声明表）
     * @return 路径 → 采样属性；未声明的路径不会出现
     * @throws ObjectFormatException 片段级键非法、路径未知、形态/类型/时间不合法或必填对象无值
     */
    public static ClipObjects parse(JsonObject clip, String clipPath, TrackTypeId type) {
        for (Map.Entry<String, JsonElement> entry : clip.entrySet()) {
            String key = entry.getKey();
            if (!CLIP_KEYS.contains(key)) {
                throw new ObjectFormatException(clipPath + "." + key,
                        "新格式片段只允许 start_time/duration/objects/loop/loop_count/loop_mode/id/exit，发现 " + key);
            }
        }

        JsonElement objectsElement = clip.get(OBJECTS);
        if (objectsElement == null || !objectsElement.isJsonObject()) {
            throw new ObjectFormatException(clipPath + "." + OBJECTS, "需要对象（点分路径 → 属性对象）");
        }
        JsonObject objectsObject = objectsElement.getAsJsonObject();

        float duration = durationOf(clip);
        boolean infinite = duration < 0f;

        Map<String, SampledProperty> properties = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : objectsObject.entrySet()) {
            String path = entry.getKey();
            if (!PropertyPath.isValid(path)) {
                throw new ObjectFormatException(clipPath + "." + OBJECTS + "." + path,
                        "非法属性路径（段名需匹配 [a-z][a-z0-9_]*，以 . 分隔）：" + path);
            }
            ObjectDef def = SchemaRegistry.getObject(type, path);
            if (def == null) {
                throw new ObjectFormatException(clipPath + "." + OBJECTS, "未知对象路径: " + path);
            }
            properties.put(path, parseProperty(path, entry.getValue(), clipPath, def, duration, infinite));
        }
        return new ClipObjects(properties);
    }

    /** 片段时长；调用方已用 {@code requireFloat} 读过，这里的缺省只为直接调用时的防御。 */
    private static float durationOf(JsonObject clip) {
        JsonElement element = clip.get("duration");
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0f;
        }
        return element.getAsFloat();
    }

    private static SampledProperty parseProperty(String path, JsonElement json, String clipPath,
                                                 ObjectDef def, float duration, boolean infinite) {
        String propertyPath = clipPath + "." + OBJECTS + "." + path;
        if (json == null || !json.isJsonObject()) {
            throw new ObjectFormatException(propertyPath, "需要对象 {value?, keyframes?}");
        }
        JsonObject property = json.getAsJsonObject();

        for (String key : property.keySet()) {
            if (!"value".equals(key) && !"keyframes".equals(key)) {
                throw new ObjectFormatException(propertyPath, "未知成员 " + key);
            }
        }
        boolean hasValue = property.has("value");
        boolean hasKeyframes = property.has("keyframes");
        if (!hasValue && !hasKeyframes) {
            throw new ObjectFormatException(propertyPath, "需要对象 {value?, keyframes?}");
        }
        if (def.mode() == PropertyMode.VALUE && hasKeyframes) {
            throw new ObjectFormatException(propertyPath, "mode=value 不允许 keyframes");
        }

        Object value = hasValue
                ? fromJson(def, property.get("value"), propertyPath, propertyPath + ".value")
                : null;
        List<ObjectKeyframe> keyframes = hasKeyframes
                ? parseKeyframes(path, property.get("keyframes"), propertyPath, def)
                : List.of();

        if (def.mode() == PropertyMode.EVENT && keyframes.isEmpty()) {
            throw new ObjectFormatException(propertyPath, "mode=event 必须有 keyframes");
        }
        if (def.required() && !hasValue && !hasEffectiveKeyframe(keyframes, duration, infinite)
                && def.defaultValue() == null) {
            throw new ObjectFormatException(propertyPath, "必填对象没有值/关键帧/默认值");
        }

        return new SampledProperty(path, def.mode(), def.type(), interpOf(def), value, keyframes);
    }

    private static List<ObjectKeyframe> parseKeyframes(String path, JsonElement json, String propertyPath,
                                                       ObjectDef def) {
        if (json == null || !json.isJsonArray()) {
            throw new ObjectFormatException(propertyPath + ".keyframes", "需要数组");
        }
        JsonArray array = json.getAsJsonArray();
        List<ObjectKeyframe> keyframes = new ArrayList<>(array.size());
        float previous = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < array.size(); i++) {
            String keyframePath = propertyPath + ".keyframes[" + i + "]";
            JsonElement item = array.get(i);
            if (item == null || !item.isJsonObject()) {
                throw new ObjectFormatException(keyframePath, "需要对象 {time, value}");
            }
            JsonObject keyframe = item.getAsJsonObject();
            for (String key : keyframe.keySet()) {
                if (!"time".equals(key) && !"value".equals(key)) {
                    throw new ObjectFormatException(keyframePath, "未知成员 " + key);
                }
            }
            float time = readTime(keyframe.get("time"), keyframePath);
            if (time < 0f) {
                throw new ObjectFormatException(keyframePath + ".time", "不能为负数");
            }
            if (i > 0 && time <= previous) {
                throw new ObjectFormatException(keyframePath + ".time",
                        "关键帧时间必须单调递增，前一帧: " + previous);
            }
            previous = time;
            Object value = fromJson(def, keyframe.get("value"), propertyPath, keyframePath + ".value");
            keyframes.add(new ObjectKeyframe(time, value));
        }
        return keyframes;
    }

    /** 关键帧时间：必须是有限数字（{@code float} 也必须有限）。 */
    private static float readTime(JsonElement json, String keyframePath) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) {
            throw new ObjectFormatException(keyframePath + ".time", "必须为有限数字");
        }
        double number = json.getAsDouble();
        float time = (float) number;
        if (!Double.isFinite(number) || !Float.isFinite(time)) {
            throw new ObjectFormatException(keyframePath + ".time", "必须为有限数字");
        }
        return time;
    }

    /**
     * 值转换：事件记录的类型判定依赖路径末段（{@code event} / {@code mod_event}），
     * 故事件记录使用属性路径本身，其余类型用 {@code .value}（错误信息带完整字段路径）。
     */
    private static Object fromJson(ObjectDef def, JsonElement json, String propertyPath, String valuePath) {
        String codecPath = def.type() == PropertyType.EVENT_RECORD ? propertyPath : valuePath;
        return ObjectCodec.fromJson(def.type(), json, codecPath);
    }

    /**
     * 插值方式：value/event 恒 {@code none}；animated 取声明值，缺省按类型/路径/单位推导
     * （{@link Interp#infer}）。声明为 {@code none} 的 animated 视为未声明。
     */
    private static Interp interpOf(ObjectDef def) {
        if (def.mode() != PropertyMode.ANIMATED) {
            return Interp.NONE;
        }
        Interp declared = def.interp();
        if (declared != null && declared != Interp.NONE) {
            return declared;
        }
        return Interp.infer(def.type(), def.path(), def.unit());
    }

    /** 是否存在范围内关键帧（有限片段 {@code time <= duration}；无限执行全部有效）。 */
    private static boolean hasEffectiveKeyframe(List<ObjectKeyframe> keyframes, float duration, boolean infinite) {
        for (ObjectKeyframe keyframe : keyframes) {
            if (infinite || keyframe.time() <= duration) {
                return true;
            }
        }
        return false;
    }
}
