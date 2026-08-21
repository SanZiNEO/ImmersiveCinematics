package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 脚本静态校验器 — 供 /icinematics validate 命令使用。
 * <p>
 * 与 {@link ScriptParser} 不同：解析器"报错即停"（运行时），本校验器**收集所有问题**继续扫描，
 * 一次输出完整问题清单（结构错误 / 字段缺失 / 语义错误 / 缺省字段提示）。
 * 面向"AI 仅凭参考指南写脚本"的场景：拿到清单即可逐条修正。
 */
public final class ScriptValidator {

    private static final String[] CAMERA_KF_FIELDS = {"yaw", "pitch", "roll", "fov", "zoom"};
    private static final String[] KNOWN_TYPES = {"CAMERA", "LETTERBOX", "AUDIO", "EVENT", "MOD_EVENT", "OVERLAY"};

    private ScriptValidator() {}

    /**
     * 校验脚本 JSON 文本，返回问题清单（空 = 校验通过）。
     *
     * @param json 脚本文件内容
     * @return 问题列表，每条含路径与说明；无问题返回空列表
     */
    public static List<String> validate(String json) {
        return validate(json, null);
    }

    /**
     * 校验脚本 JSON 文本（可附带已知脚本 id 集合做跨脚本引用检查）。
     *
     * @param json            脚本文件内容
     * @param knownScriptIds  当前已加载的全部脚本 id（null = 不做跨脚本 exists 检查）
     * @return 问题列表，每条含路径与说明；无问题返回空列表
     */
    public static List<String> validate(String json, Collection<String> knownScriptIds) {
        List<String> issues = new ArrayList<>();

        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            issues.add("JSON 解析失败: " + e.getMessage());
            return issues;
        }

        // ===== meta =====
        if (!root.has("meta") || !root.get("meta").isJsonObject()) {
            issues.add("meta 缺失或不是对象（需要 id/name/author/version）");
        } else {
            JsonObject meta = root.getAsJsonObject("meta");
            requireString(meta, "meta", "id", issues);
            requireString(meta, "meta", "name", issues);
            requireString(meta, "meta", "author", issues);
            if (!meta.has("version")) {
                issues.add("meta.version 缺失（当前仅支持版本 3）");
            } else {
                try {
                    if (meta.get("version").getAsInt() != 3) {
                        issues.add("meta.version 必须为 3，实际: " + meta.get("version").getAsInt());
                    }
                } catch (Exception e) {
                    issues.add("meta.version 不是整数");
                }
            }
        }

        // ===== meta.triggers：前置依赖（requires）校验 =====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            validateTriggerRequires(root.getAsJsonObject("meta"), "meta", knownScriptIds, issues);
        }

        // ===== timeline =====
        if (!root.has("timeline") || !root.get("timeline").isJsonObject()) {
            issues.add("timeline 缺失或不是对象");
            return issues;
        }
        JsonObject timeline = root.getAsJsonObject("timeline");
        if (!timeline.has("total_duration")) {
            issues.add("timeline.total_duration 缺失（正数=有限时长，负数=无限）");
        } else {
            try {
                float td = timeline.get("total_duration").getAsFloat();
                if (td == 0f) issues.add("timeline.total_duration 不允许为 0");
            } catch (Exception e) {
                issues.add("timeline.total_duration 不是数字");
            }
        }
        if (!timeline.has("tracks") || !timeline.get("tracks").isJsonArray()) {
            issues.add("timeline.tracks 缺失或不是数组");
            return issues;
        }

        // ===== 逐轨道 =====
        JsonArray tracks = timeline.getAsJsonArray("tracks");
        int clipCount = 0;
        int keyframeCount = 0;
        for (int ti = 0; ti < tracks.size(); ti++) {
            JsonElement te = tracks.get(ti);
            if (!te.isJsonObject()) {
                issues.add("timeline.tracks[" + ti + "] 不是对象");
                continue;
            }
            JsonObject track = te.getAsJsonObject();
            String tp = "timeline.tracks[" + ti + "]";
            String type = null;
            if (!track.has("type")) {
                issues.add(tp + ".type 缺失");
            } else {
                type = track.get("type").getAsString();
                if (!isKnownType(type)) {
                    issues.add(tp + ".type 未知类型: " + type + "（可选: CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT / OVERLAY）");
                }
            }

            if (!track.has("clips") || !track.get("clips").isJsonArray()) {
                if (track.has("keyframes")) {
                    issues.add(tp + ".clips 缺失：发现轨道直接写了 keyframes——正确结构为 "
                            + "{ \"type\": \"...\", \"clips\": [ { \"start_time\": ..., \"duration\": ..., \"keyframes\": [...] } ] }，"
                            + "keyframes 应嵌在 clip 内，不能直接挂在轨道上");
                } else {
                    issues.add(tp + ".clips 缺失或不是数组（轨道需要 clips 数组）");
                }
                continue;
            }

            JsonArray clips = track.getAsJsonArray("clips");
            float prevContentEnd = -Float.MAX_VALUE;
            float prevEnd = -Float.MAX_VALUE;
            for (int ci = 0; ci < clips.size(); ci++) {
                JsonElement ce = clips.get(ci);
                if (!ce.isJsonObject()) {
                    issues.add(tp + ".clips[" + ci + "] 不是对象");
                    continue;
                }
                JsonObject clip = ce.getAsJsonObject();
                String cp = tp + ".clips[" + ci + "]";
                clipCount++;

                float start = 0f, dur = 0f;
                if (!clip.has("start_time")) issues.add(cp + ".start_time 缺失");
                else {
                    try { start = clip.get("start_time").getAsFloat(); }
                    catch (Exception e) { issues.add(cp + ".start_time 不是数字"); }
                }
                if (!clip.has("duration")) issues.add(cp + ".duration 缺失");
                else {
                    try {
                        dur = clip.get("duration").getAsFloat();
                        if (dur == 0f) issues.add(cp + ".duration 不允许为 0（正数=定长，负数=无限）");
                    } catch (Exception e) { issues.add(cp + ".duration 不是数字"); }
                }

                checkEnum(clip, cp, "interpolation", issues, "linear", "smooth");
                checkEnum(clip, cp, "transition", issues, "cut", "morph");
                checkEnum(clip, cp, "orient", issues, "manual", "tangent");
                if ("CAMERA".equalsIgnoreCase(type)) {
                    // 废弃检测：position_mode / cam_tracking_* 已迁移到关键帧级
                    if (clip.has("position_mode") || clip.has("cam_tracking_look_at")
                            || clip.has("cam_tracking_look_target_x") || clip.has("cam_tracking_look_target_y")
                            || clip.has("cam_tracking_look_target_z") || clip.has("cam_tracking_target_selector")
                            || clip.has("cam_tracking_follow") || clip.has("cam_tracking_follow_offset_x")
                            || clip.has("cam_tracking_follow_offset_y") || clip.has("cam_tracking_follow_offset_z")) {
                        issues.add(cp + " 使用了已废弃的 clip 级字段（position_mode / cam_tracking_*）——已迁移到关键帧级："
                                + "position_mode、follow、follow_selector、look_at、look_at_selector、look_at_target_x/y/z 写在关键帧对象里");
                    }
                }
                if ("OVERLAY".equalsIgnoreCase(type)) {
                    checkEnum(clip, cp, "layer_type", issues, "fade", "image", "subtitle", "pip");
                    if ("image".equals(clip.has("layer_type") ? clip.get("layer_type").getAsString() : "") && !clip.has("path")) {
                        issues.add(cp + ".path 缺失（layer_type=image 需要图片文件名，只支持 PNG）");
                    }
                }
                // ===== 循环参数校验（CAMERA）=====
                if ("CAMERA".equalsIgnoreCase(type)) {
                    boolean loop = clip.has("loop") && clip.get("loop").getAsBoolean();
                    if (clip.has("loop_count")) {
                        try {
                            int lc = clip.get("loop_count").getAsInt();
                            if (lc == 0) {
                                issues.add(cp + ".loop_count 不允许为 0（-1=无限循环，正整数=循环次数），运行时将按 1 处理");
                            }
                        } catch (Exception e) {
                            issues.add(cp + ".loop_count 不是整数");
                        }
                    }
                    checkEnum(clip, cp, "loop_mode", issues, "repeat", "pingpong");
                    if (loop) {
                        boolean infinite = !clip.has("loop_count") || clip.get("loop_count").getAsInt() < 0;
                        if (infinite && ci < clips.size() - 1) {
                            issues.add(cp + " 无限循环（loop=true + loop_count=-1）后仍有其他片段：后续片段作为特写覆盖播放，播完回落该循环视角");
                        }
                        if (clip.has("keyframes") && clip.get("keyframes").isJsonArray()) {
                            JsonArray kfs = clip.getAsJsonArray("keyframes");
                            if (kfs.size() >= 2) {
                                try {
                                    float first = kfs.get(0).getAsJsonObject().get("time").getAsFloat();
                                    float last = kfs.get(kfs.size() - 1).getAsJsonObject().get("time").getAsFloat();
                                    float animPeriod = last - first;
                                    if (animPeriod <= 0f) {
                                        issues.add(cp + " 循环周期无效：首末关键帧时间差必须 > 0");
                                    } else if (clip.has("duration") && Math.abs(clip.get("duration").getAsFloat() - animPeriod) > 0.5f) {
                                        issues.add(cp + " duration 与循环周期不匹配（duration=" + clip.get("duration").getAsFloat()
                                                + "，周期=" + animPeriod + "）：有限循环窗口=周期×loop_count，不匹配会造成播放跳变");
                                    }
                                } catch (Exception ignored) {
                                    // 校验期防御读取：关键帧 time 字段异常 → 跳过循环周期校验，该问题会经其它校验项
                                    // （如 "time 不是数字"/"不单调"）单独报出，此处不重复刷屏——保留 catch 避免中断整个收集
                                }
                            } else {
                                issues.add(cp + " loop=true 但关键帧不足 2 个：循环取模不生效（单帧循环 = 固定视角模式，由首帧决定位置/朝向）");
                            }
                        }
                    }
                }

                // 关键帧
                if (!clip.has("keyframes") || !clip.get("keyframes").isJsonArray()) {
                    issues.add(cp + ".keyframes 缺失或不是数组");
                } else {
                    JsonArray kfs = clip.getAsJsonArray("keyframes");
                    if (kfs.size() == 0) {
                        issues.add(cp + ".keyframes 为空（CAMERA clip 至少需要 1 个关键帧）");
                    }
                    float prevT = -1f;
                    for (int ki = 0; ki < kfs.size(); ki++) {
                        JsonElement ke = kfs.get(ki);
                        if (!ke.isJsonObject()) {
                            issues.add(cp + ".keyframes[" + ki + "] 不是对象");
                            continue;
                        }
                        JsonObject kf = ke.getAsJsonObject();
                        String kp = cp + ".keyframes[" + ki + "]";
                        keyframeCount++;

                        if (!kf.has("time")) {
                            issues.add(kp + ".time 缺失");
                        } else {
                            try {
                                float t = kf.get("time").getAsFloat();
                                if (t < 0f) issues.add(kp + ".time 不能为负数: " + t);
                                if (t < prevT) issues.add(kp + ".time 不单调（" + t + " < 前一个 " + prevT + "），关键帧时间必须递增");
                                prevT = t;
                            } catch (Exception e) { issues.add(kp + ".time 不是数字"); }
                        }

                        // position 仅 CAMERA 轨道必需（与 CAMERA_KF_FIELDS 检查同条件）；
                        // letterbox/audio/event/overlay 轨道的字段体系与 camera 不同，不检查
                        if ("CAMERA".equalsIgnoreCase(type)) {
                            if (!kf.has("position")) {
                                issues.add(kp + ".position 缺失（relative 模式: {dx,dy,dz}；absolute 模式: {x,y,z}）");
                            } else if (!kf.get("position").isJsonObject()) {
                                issues.add(kp + ".position 应为对象 {dx,dy,dz} 或 {x,y,z}，实际是 "
                                        + (kf.get("position").isJsonArray() ? "数组（position 不是数组，改用对象写法）" : "其他类型"));
                            } else {
                                JsonObject pos = kf.getAsJsonObject("position");
                                if (pos.size() == 0) issues.add(kp + ".position 为空对象");
                            }
                        }

                        // CAMERA 关键帧缺省字段提示
                        if ("CAMERA".equalsIgnoreCase(type)) {
                            for (String f : CAMERA_KF_FIELDS) {
                                if (!kf.has(f)) issues.add(kp + "." + f + " 缺失，将使用默认值 " + cameraKfDefault(f));
                            }
                            checkEnum(kf, kp, "position_mode", issues, "relative", "absolute");
                            checkEnum(kf, kp, "follow", issues, "none", "entity");
                            checkEnum(kf, kp, "look_at", issues, "none", "coordinate", "entity");
                            String follow = kf.has("follow") ? kf.get("follow").getAsString() : "none";
                            String lookAt = kf.has("look_at") ? kf.get("look_at").getAsString() : "none";
                            if ("entity".equals(follow) && !kf.has("follow_selector")) {
                                issues.add(kp + ".follow_selector 缺失（follow=entity 时指定目标，如 @p / @e[type=minecraft:iron_golem]），默认 @p");
                            }
                            if ("entity".equals(follow) && !kf.has("position")) {
                                issues.add(kp + ".position 缺失（follow=entity 时 position 的 dx/dy/dz 即相对实体脚底的偏移）");
                            }
                            if ("entity".equals(lookAt) && !kf.has("look_at_selector")) {
                                issues.add(kp + ".look_at_selector 缺失（look_at=entity 时指定目标），默认 @p");
                            }
                            if ("coordinate".equals(lookAt)
                                    && !kf.has("look_at_target_structure")
                                    && (!kf.has("look_at_target_x") || !kf.has("look_at_target_y") || !kf.has("look_at_target_z"))) {
                                issues.add(kp + ".look_at_target 缺失（look_at=coordinate 时指定 look_at_target_x/y/z 坐标，或 look_at_target_structure 结构名）");
                            }
                        }
                    }
                }

                // 同轨道重叠（morph 重叠除外：prev 有 transition_duration 时允许 next 起点 = prevEnd − t/2）
                float end = start + dur;
                float prevTransition = 0f;
                if (ci > 0 && clips.get(ci - 1).isJsonObject()) {
                    JsonObject prevClip = clips.get(ci - 1).getAsJsonObject();
                    if ("morph".equals(prevClip.has("transition") ? prevClip.get("transition").getAsString() : "")) {
                        try { prevTransition = prevClip.has("transition_duration") ? prevClip.get("transition_duration").getAsFloat() : 0f; }
                        catch (Exception ignored) {
                            // 校验期防御读取：transition_duration 异常按 0 处理（该问题经 duration/字段校验项单独报出）
                        }
                    }
                    float minStart = prevEnd - prevTransition / 2f;
                    if (start < minStart - 0.001f) {
                        issues.add(cp + " 与上一 clip 重叠（start " + start + " < 上一 clip 末尾 "
                                + (prevEnd - prevTransition / 2f) + "）");
                    }
                }
                prevContentEnd = Math.max(prevContentEnd, end);
                prevEnd = Math.max(prevEnd, end);
            }
            if (prevContentEnd > 0f) {
                // 预留：可在此处对比 total_duration 与内容末尾
            }
        }
        return issues;
    }

    /** 校验枚举字段，未知值时报错并列出合法值 */
    private static void checkEnum(JsonObject obj, String path, String key, List<String> issues, String... allowed) {
        if (!obj.has(key)) return;
        String v = obj.get(key).getAsString();
        for (String a : allowed) {
            if (a.equals(v)) return;
        }
        StringBuilder sb = new StringBuilder(path + "." + key + " 未知值: " + v + "（可选: ");
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(allowed[i]);
        }
        sb.append("）");
        issues.add(sb.toString());
    }

    /**
     * 校验 meta.triggers 的 requires 前置依赖：
     * - 结构性：requires 必须是字符串数组，元素必须是字符串脚本 id；
     * - 自引用：requires 指向脚本自身 → 永不解锁；
     * - 跨脚本：requires 指向不存在的脚本（knownScriptIds 非 null 时）→ 该触发器永不触发。
     */
    private static void validateTriggerRequires(JsonObject meta, String path,
                                                 Collection<String> knownScriptIds, List<String> issues) {
        if (!meta.has("triggers")) return;
        if (!meta.get("triggers").isJsonArray()) {
            issues.add(path + ".triggers 不是数组");
            return;
        }
        JsonArray triggers = meta.getAsJsonArray("triggers");
        String selfId = meta.has("id") && meta.get("id").isJsonPrimitive()
                ? meta.get("id").getAsString() : null;
        for (int i = 0; i < triggers.size(); i++) {
            JsonElement te = triggers.get(i);
            if (!te.isJsonObject()) {
                issues.add(path + ".triggers[" + i + "] 不是对象");
                continue;
            }
            JsonObject t = te.getAsJsonObject();
            String tp = path + ".triggers[" + i + "]";
            if (!t.has("requires")) continue;
            if (!t.get("requires").isJsonArray()) {
                issues.add(tp + ".requires 必须是字符串数组");
                continue;
            }
            JsonArray reqs = t.getAsJsonArray("requires");
            for (int j = 0; j < reqs.size(); j++) {
                JsonElement re = reqs.get(j);
                if (!re.isJsonPrimitive() || !re.getAsJsonPrimitive().isString()) {
                    issues.add(tp + ".requires[" + j + "] 必须是字符串脚本 id");
                    continue;
                }
                String reqId = re.getAsString();
                if (selfId != null && reqId.equals(selfId)) {
                    issues.add(tp + ".requires 自引用自身脚本 '" + reqId + "'（可能永不解锁）");
                }
                if (knownScriptIds != null && !knownScriptIds.contains(reqId)) {
                    issues.add(tp + ".requires 指向不存在的脚本 '" + reqId + "'（该触发器将永不触发）");
                }
            }
        }
    }

    private static boolean isKnownType(String type) {
        for (String t : KNOWN_TYPES) {
            if (t.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private static void requireString(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key) || obj.get(key).getAsString().isEmpty()) {
            issues.add(path + "." + key + " 缺失");
        }
    }

    private static String cameraKfDefault(String field) {
        return switch (field) {
            case "yaw", "pitch", "roll" -> "0";
            case "fov" -> "70";
            default -> "1.0";  // zoom
        };
    }
}
