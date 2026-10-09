package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.trigger.server.ListenStrategy;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.prereq.PrerequisiteRegistry;

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
    private static final String[] KNOWN_TYPES =
            {"CAMERA", "LETTERBOX", "AUDIO", "EVENT", "MOD_EVENT", "OVERLAY", "ADJUST"};

    /**
     * 调色曲线字段（clip 级结构字段，0.3.6 起 ADJUST 轨与 CAMERA 片段共用同一套）：
     * {@code rgb_curve} = RGB 复合曲线；{@code r_curve} / {@code g_curve} / {@code b_curve} = 每通道曲线；
     * 后六条 = DaVinci 曲线页口径的 hue 曲线（HvH / HvS / HvL、LvS / SvS / SvL）。
     */
    private static final String[] COLOR_CURVE_FIELDS = {
            "rgb_curve", "r_curve", "g_curve", "b_curve",
            "hv_h_curve", "hv_s_curve", "hv_l_curve", "lv_s_curve", "sv_s_curve", "sv_l_curve"};

    /**
     * 调色关键帧标量通道 → 合法区间（顺序与
     * {@code TrackSchemas.adjust()} / {@code ColorAdjustParams} / {@code ic_color_adjust.fsh} 一致）。
     * <p>0.3.6 起 ADJUST 轨与 CAMERA 片段共用同一套通道（见 {@link #checkAdjustFields}）。</p>
     * <p>标量通道缺省 0 = 无效果（{@code curve_strength} / {@code r_curve_strength} / {@code g_curve_strength} /
     * {@code b_curve_strength} 与六条 hue 曲线的强度 {@code hv_h_strength} ~ {@code sv_l_strength}
     * 例外：缺省 1 = 曲线全量生效）。</p>
     */
    private static final List<ChannelRange> ADJUST_CHANNELS = List.of(
            new ChannelRange("exposure", -5f, 5f),
            new ChannelRange("contrast", -1f, 1f),
            new ChannelRange("highlights", -1f, 1f),
            new ChannelRange("shadows", -1f, 1f),
            new ChannelRange("whites", -1f, 1f),
            new ChannelRange("blacks", -1f, 1f),
            new ChannelRange("hue", -1f, 1f),
            new ChannelRange("saturation", -1f, 1f),
            new ChannelRange("vibrance", -1f, 1f),
            new ChannelRange("lightness", -1f, 1f),
            new ChannelRange("temperature", -1f, 1f),
            new ChannelRange("tint", -1f, 1f),
            new ChannelRange("red", -1f, 1f),
            new ChannelRange("green", -1f, 1f),
            new ChannelRange("blue", -1f, 1f),
            new ChannelRange("mix_rr", -1f, 1f),
            new ChannelRange("mix_rg", -1f, 1f),
            new ChannelRange("mix_rb", -1f, 1f),
            new ChannelRange("mix_gr", -1f, 1f),
            new ChannelRange("mix_gg", -1f, 1f),
            new ChannelRange("mix_gb", -1f, 1f),
            new ChannelRange("mix_br", -1f, 1f),
            new ChannelRange("mix_bg", -1f, 1f),
            new ChannelRange("mix_bb", -1f, 1f),
            new ChannelRange("curve_strength", 0f, 1f),
            new ChannelRange("r_curve_strength", 0f, 1f),
            new ChannelRange("g_curve_strength", 0f, 1f),
            new ChannelRange("b_curve_strength", 0f, 1f),
            new ChannelRange("lift_r", -1f, 1f),
            new ChannelRange("lift_g", -1f, 1f),
            new ChannelRange("lift_b", -1f, 1f),
            new ChannelRange("gamma_r", -1f, 1f),
            new ChannelRange("gamma_g", -1f, 1f),
            new ChannelRange("gamma_b", -1f, 1f),
            new ChannelRange("gain_r", -1f, 1f),
            new ChannelRange("gain_g", -1f, 1f),
            new ChannelRange("gain_b", -1f, 1f),
            new ChannelRange("hv_h_strength", 0f, 1f),
            new ChannelRange("hv_s_strength", 0f, 1f),
            new ChannelRange("hv_l_strength", 0f, 1f),
            new ChannelRange("lv_s_strength", 0f, 1f),
            new ChannelRange("sv_s_strength", 0f, 1f),
            new ChannelRange("sv_l_strength", 0f, 1f),
            new ChannelRange("hue_red", -1f, 1f),
            new ChannelRange("sat_red", -1f, 1f),
            new ChannelRange("hue_yellow", -1f, 1f),
            new ChannelRange("sat_yellow", -1f, 1f),
            new ChannelRange("hue_green", -1f, 1f),
            new ChannelRange("sat_green", -1f, 1f),
            new ChannelRange("hue_cyan", -1f, 1f),
            new ChannelRange("sat_cyan", -1f, 1f),
            new ChannelRange("hue_blue", -1f, 1f),
            new ChannelRange("sat_blue", -1f, 1f),
            new ChannelRange("hue_magenta", -1f, 1f),
            new ChannelRange("sat_magenta", -1f, 1f),
            new ChannelRange("grayscale", 0f, 1f),
            new ChannelRange("invert", 0f, 1f));

    /** 一个 ADJUST 通道的合法取值区间（{@code min} ~ {@code max}，闭区间）。 */
    private record ChannelRange(String field, float min, float max) {}

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

        // ===== meta 宏观循环（macro_loop）=====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("meta");
            // macro_loop_count：-1 = 无限重复；正整数 N = 从 a 到 b 重复 N 圈后自然结束
            if (meta.has("macro_loop_count")) {
                try {
                    int count = meta.get("macro_loop_count").getAsInt();
                    if (count == 0) {
                        issues.add("meta.macro_loop_count 不允许为 0（1 = 只播一遍，不重复），运行时按 1 处理");
                    } else if (count < -1) {
                        issues.add("meta.macro_loop_count 只能为 -1（无限重复）或正整数，实际: " + count
                                + "，运行时按 -1 处理");
                    }
                } catch (Exception e) {
                    issues.add("meta.macro_loop_count 不是整数");
                }
            }
            boolean macroLoop = meta.has("macro_loop") && meta.get("macro_loop").isJsonPrimitive()
                    && meta.get("macro_loop").getAsJsonPrimitive().isBoolean()
                    && meta.get("macro_loop").getAsBoolean();
            if (macroLoop) {
                boolean holdAtEnd = meta.has("hold_at_end") && meta.get("hold_at_end").isJsonPrimitive()
                        && meta.get("hold_at_end").getAsJsonPrimitive().isBoolean()
                        && meta.get("hold_at_end").getAsBoolean();
                if (holdAtEnd) {
                    issues.add("meta.macro_loop 与 meta.hold_at_end 同时开启（互斥）：无限次数"
                            + "（macro_loop_count=-1，缺省）下脚本不自然结束，hold_at_end 不会生效，请二选一");
                }
                String mode = meta.has("macro_loop_mode") && meta.get("macro_loop_mode").isJsonPrimitive()
                        ? meta.get("macro_loop_mode").getAsString() : "repeat";
                if ("pingpong".equals(mode)) {
                    issues.add("meta.macro_loop_mode=pingpong 第一版未实现：按 repeat 播放");
                } else if (!"repeat".equals(mode)) {
                    issues.add("meta.macro_loop_mode 未知值: " + mode + "（可选: repeat / pingpong）");
                }
            }
        }

        // ===== meta.triggers：前置依赖（requires）+ 类型/条件结构校验 =====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            validateTriggerRequires(root.getAsJsonObject("meta"), "meta", knownScriptIds, issues);
            validateTriggerConditions(root.getAsJsonObject("meta"), "meta", knownScriptIds, issues);
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
                    issues.add(tp + ".type 未知类型: " + type
                            + "（可选: CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT / OVERLAY / ADJUST）");
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

                if (clip.has("interpolation")) {
                    issues.add(cp + ".interpolation 已移除（0.3.6 起）：运行时统一匀速线性插值——删掉该字段；"
                            + "缓动由编辑器把速度曲线烘焙成显式关键帧（脚本里存的是补出的关键帧），运行时不求值");
                }
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
                    // 调色（0.3.6）：本相机片段自带调色，作用于该相机轨的 lane——字段集与 ADJUST 轨完全一致
                    checkRemovedScopeLane(clip, cp, issues);
                    checkAdjustFields(clip, cp, null, null, issues);
                    // LUT 是整体画面（master）处理，只挂在 ADJUST 轨：相机片段上写 lut 不会生效，直接拦下
                    if (clip.has("lut")) {
                        issues.add(cp + ".lut 不支持（LUT 是对整体画面的处理，只写在 ADJUST 轨的片段上）："
                                + "相机片段请改用逐 lane 调色字段，或把 lut 移到 ADJUST 轨");
                    }
                    if (clip.has("lut_strength")) {
                        issues.add(cp + ".lut_strength 不支持（LUT 强度只随 ADJUST 轨的 lut 一起写在关键帧上）："
                                + "相机片段请删掉该字段，或把 lut / lut_strength 移到 ADJUST 轨");
                    }
                    if (clip.has("lut_input_gamma")) {
                        issues.add(cp + ".lut_input_gamma 不支持（LUT 输入域适配只随 ADJUST 轨的 lut 一起写在片段上）："
                                + "相机片段请删掉该字段，或把 lut / lut_input_gamma 移到 ADJUST 轨");
                    }
                }
                if ("OVERLAY".equalsIgnoreCase(type)) {
                    checkEnum(clip, cp, "layer_type", issues, "fade", "image", "subtitle");
                    // layer_type 缺省时运行期按 "fade" 处理（OverlayTrackPlayer.createLayer）
                    String layerType = clip.has("layer_type") && clip.get("layer_type").isJsonPrimitive()
                            ? clip.get("layer_type").getAsString() : "fade";
                    if ("image".equals(layerType) && !clip.has("path")) {
                        issues.add(cp + ".path 缺失（layer_type=image 需要图片文件名，只支持 PNG）");
                    }
                    if ("fade".equals(layerType)) {
                        checkHexColor(clip, cp, "color", issues);
                    }
                }
                if ("ADJUST".equalsIgnoreCase(type)) {
                    // ADJUST 轨只作用于整体画面（master）：lane 级调色已改为 CAMERA 片段字段
                    checkRemovedScopeLane(clip, cp, issues);
                    checkAdjustFields(clip, cp, null, null, issues);
                    // LUT（整体画面烘焙）：clip 级 lut 文件名 + 关键帧级 lut_strength——只在 ADJUST 轨
                    checkLutFields(clip, cp, null, null, issues);
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
                            issues.add(cp + " 无限循环（loop=true + loop_count=-1）后仍有其他片段：永不结束的片段 = 时间轴终点，其后的片段不会播放");
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
                            // ===== 合成参数（0.3.6 camera-composition）：opacity / dest / source =====
                            checkUnitFloat(kf, kp, "opacity", issues);
                            checkRect(kf, kp, "dest", issues);
                            checkRect(kf, kp, "source", issues);
                            // 调色（0.3.6）：本相机片段关键帧的 57 个调色通道区间
                            checkAdjustFields(null, null, kf, kp, issues);
                            // LUT 是整体画面（master）处理：相机片段关键帧写 lut_strength 同样不生效，直接拦下
                            if (kf.has("lut_strength")) {
                                issues.add(kp + ".lut_strength 不支持（LUT 强度只随 ADJUST 轨的 lut 一起写在关键帧上）："
                                        + "相机片段请删掉该字段，或把 lut / lut_strength 移到 ADJUST 轨");
                            }
                            if (kf.has("lut_input_gamma")) {
                                issues.add(kp + ".lut_input_gamma 不支持（LUT 输入域适配只随 ADJUST 轨的 lut 一起写在片段上）："
                                        + "相机片段请删掉该字段，或把 lut / lut_input_gamma 移到 ADJUST 轨");
                            }
                        }

                        // ADJUST 关键帧：47 个标量通道的取值区间（不写 = 缺省 0 = 无效果——十条曲线强度缺省 1；写回 0 = 该项淡出）
                        // + LUT 混合强度 lut_strength（0~1，缺省 1）
                        if ("ADJUST".equalsIgnoreCase(type)) {
                            checkAdjustFields(null, null, kf, kp, issues);
                            checkLutFields(null, null, kf, kp, issues);
                        }
                    }
                }

                // 片段时间允许重叠（0.3.6 lane 模型）：叠化 = 上一个 clip 末尾帧复制延长（hold 等值关键帧）
                // + 下一个 clip 首帧复制延长，两冻结区时间交叉、opacity 关键帧交叉（scene-transition §3.2）；
                // 重叠区按「轨道层级 → 轨道内 clip 顺序」分层（后者在上，camera-composition §1）。
                // 故此处不再拒绝同轨道重叠——旧「一个时间点只能有一个相机状态」的单相机约束已随 lane 模型作废。
                float end = start + dur;
                prevContentEnd = Math.max(prevContentEnd, end);
            }
            if (prevContentEnd > 0f) {
                // 预留：可在此处对比 total_duration 与内容末尾
            }
        }
        return issues;
    }

    /**
     * 校验数值字段的取值区间（闭区间）；字段缺省时跳过（缺省值生效）。
     * <p>与 {@link #checkUnitFloat} 同族，只是区间由调用方给定（ADJUST 轨道的各通道区间不同）。</p>
     */
    private static void checkRange(JsonObject obj, String path, String key, List<String> issues,
                                   float min, float max) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            issues.add(path + "." + key + " 不是数字（范围 " + min + " ~ " + max + "）");
            return;
        }
        float v = e.getAsFloat();
        if (v < min || v > max) {
            issues.add(path + "." + key + " 超出范围 " + min + " ~ " + max + "：" + v);
        }
    }

    /**
     * 校验调色字段（0.3.6 起 ADJUST 轨与 CAMERA 片段的调色字段集完全一致；ADJUST 作用于整体画面，
     * CAMERA 片段作用于该相机轨的 lane）：
     * <ul>
     *   <li>clip 级：10 条调色曲线的结构（{@link #COLOR_CURVE_FIELDS}，控制点数组）；</li>
     *   <li>关键帧级：47 个标量通道的取值区间（{@link #ADJUST_CHANNELS}）。</li>
     * </ul>
     * <p>{@code clip} 与 {@code kf} 分别对应两种上下文，调用方按当前层级传非空值、另一侧传 {@code null}
     * （路径前缀 {@code cp} / {@code kp} 随之，缺省字段一律跳过、由缺省值生效）。</p>
     */
    private static void checkAdjustFields(JsonObject clip, String cp, JsonObject kf, String kp,
                                          List<String> issues) {
        if (clip != null) {
            for (String field : COLOR_CURVE_FIELDS) {
                if (clip.has(field)) {
                    checkColorCurve(clip.get(field), cp + "." + field, issues);
                }
            }
        }
        if (kf != null) {
            for (ChannelRange range : ADJUST_CHANNELS) {
                checkRange(kf, kp, range.field(), issues, range.min(), range.max());
            }
        }
    }

    /**
     * 校验 LUT 字段（<b>ADJUST 轨专用</b>：LUT 是对整体画面（master）的烘焙，逐 lane / 相机片段不参与）：
     * <ul>
     *   <li>clip 级 {@code lut}：LUT 文件名（字符串、非空、不含路径分隔符，见 {@link #checkLutFile}）；</li>
     *   <li>clip 级 {@code lut_input_gamma}：输入域适配的幂指数（数字、{@code > 0}，缺省 1 = 不变换，
     *       见 {@link #checkLutInputGamma}）；</li>
     *   <li>关键帧级 {@code lut_strength}：混合强度 0 ~ 1（缺省 1 = 全量生效，写回 0 = 淡出）。</li>
     * </ul>
     * <p>{@code clip} / {@code kf} 的用法同 {@link #checkAdjustFields}（另一侧传 {@code null}）。</p>
     */
    private static void checkLutFields(JsonObject clip, String cp, JsonObject kf, String kp,
                                       List<String> issues) {
        if (clip != null) {
            if (clip.has("lut")) {
                checkLutFile(clip.get("lut"), cp + ".lut", issues);
            }
            if (clip.has("lut_input_gamma")) {
                checkLutInputGamma(clip.get("lut_input_gamma"), cp + ".lut_input_gamma", issues);
            }
        }
        if (kf != null) {
            checkRange(kf, kp, "lut_strength", issues, 0f, 1f);
        }
    }

    /**
     * 校验 clip 级 {@code lut_input_gamma}（LUT 输入域适配的幂指数）：数字、必须 {@code > 0}
     * （缺省 1 = 不变换，恒合法；{@code NaN} 也落在这里被拒）。
     * <p>与 {@code ScriptParser#parseLutInputGamma} 同一口径——校验拦下的写法解析期也会拒绝。
     * 字段缺省时跳过（缺省 1 生效）；{@code lut} 缺省时不额外报错（{@code lut == null} 时本字段被忽略）。</p>
     */
    private static void checkLutInputGamma(JsonElement e, String path, List<String> issues) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            issues.add(path + " 需要数字（必须大于 0；缺省 1 = 不变换）");
            return;
        }
        float gamma = e.getAsFloat();
        if (gamma != 1.0F && !(gamma > 0.0F)) {
            issues.add(path + " 必须大于 0（缺省 1 = 不变换）：" + gamma);
        }
    }

    /**
     * 校验 clip 级 {@code lut}（LUT 文件名）：字符串、非空、不含路径分隔符（{@code /} / {@code \} / {@code :}）。
     * <p>与 {@code ScriptParser#parseLutFile} 同一口径——校验拦下的写法解析期也会拒绝。
     * 文件是否存在此处不查（校验器无游戏目录上下文；缺失由 {@code CubeLutLoader} 记日志、按「无 LUT」继续）。</p>
     */
    private static void checkLutFile(JsonElement e, String path, List<String> issues) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + " 需要字符串（resource/ 目录下的 .cube 文件名）");
            return;
        }
        String name = e.getAsString();
        if (name.isBlank()) {
            issues.add(path + " 文件名不能为空");
        } else if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            issues.add(path + " 文件名不能包含路径分隔符（LUT 只取 resource/ 目录下的文件）：" + name);
        }
    }

    /**
     * 拒绝已移除的 {@code scope} / {@code lane} 字段（ADJUST 与 CAMERA 两种轨道的 clip 都拦）：
     * 0.3.6 起 lane 级调色改为相机片段字段——调色直接写在 CAMERA 片段上，ADJUST 轨只管整体画面（master）。
     */
    private static void checkRemovedScopeLane(JsonObject clip, String cp, List<String> issues) {
        if (clip.has("scope")) {
            issues.add(cp + ".scope 已移除（0.3.6 起 lane 级调色改为相机片段字段）："
                    + "调色直接写在 CAMERA 片段上；ADJUST 轨只管整体画面（master）");
        }
        if (clip.has("lane")) {
            issues.add(cp + ".lane 已移除（0.3.6 起 lane 级调色改为相机片段字段）："
                    + "调色直接写在 CAMERA 片段上；ADJUST 轨只管整体画面（master）");
        }
    }

    /**
     * 校验调色曲线（clip 级结构字段 {@link #COLOR_CURVE_FIELDS}，如 {@code rgb_curve} / {@code r_curve} /
     * {@code g_curve} / {@code b_curve} / 六条 hue 曲线）：
     * 控制点数组 {@code [[x,y], ...]}——至少 2 点、每点 2 个数字、{@code x} 严格递增、{@code x} / {@code y} 各 0~1
     * （与 {@code ScriptParser#parseColorCurve} 同一口径：校验拦下的写法解析期也会拒绝）。
     */
    private static void checkColorCurve(JsonElement e, String path, List<String> issues) {
        if (!e.isJsonArray()) {
            issues.add(path + " 需要控制点数组 [[x,y], ...]");
            return;
        }
        JsonArray arr = e.getAsJsonArray();
        if (arr.size() < 2) {
            issues.add(path + " 至少需要 2 个控制点，实际: " + arr.size());
            return;
        }
        float prevX = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < arr.size(); i++) {
            String pp = path + "[" + i + "]";
            JsonElement pe = arr.get(i);
            if (!pe.isJsonArray() || pe.getAsJsonArray().size() != 2
                    || !isNumber(pe.getAsJsonArray().get(0)) || !isNumber(pe.getAsJsonArray().get(1))) {
                issues.add(pp + " 需要 [x, y] 两个数字");
                continue;
            }
            float x = pe.getAsJsonArray().get(0).getAsFloat();
            float y = pe.getAsJsonArray().get(1).getAsFloat();
            if (x < 0f || x > 1f || y < 0f || y > 1f) {
                issues.add(pp + " 超出 0~1：" + x + ", " + y);
            }
            if (x <= prevX) {
                issues.add(pp + " 控制点 x 必须严格递增（前一个 x = " + prevX + "，当前 = " + x + "）");
            }
            prevX = x;
        }
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    /**
     * 校验归一化浮点字段（如合成参数 opacity）：必须是数字且在 0~1；字段缺省时跳过（默认值生效）。
     */
    private static void checkUnitFloat(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            issues.add(path + "." + key + " 不是数字（范围 0~1）");
            return;
        }
        float v = e.getAsFloat();
        if (v < 0f || v > 1f) {
            issues.add(path + "." + key + " 超出范围 0~1: " + v);
        }
    }

    /**
     * 校验归一化矩形对象 {x,y,w,h}（各分量 0~1，如合成参数 dest/source）；
     * 字段缺省时跳过（默认全屏/全幅）。
     */
    private static void checkRect(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key)) return;
        String rp = path + "." + key;
        JsonElement e = obj.get(key);
        if (!e.isJsonObject()) {
            issues.add(rp + " 应为对象 {x,y,w,h}（归一化矩形，各分量 0~1）");
            return;
        }
        JsonObject rect = e.getAsJsonObject();
        for (String c : new String[]{"x", "y", "w", "h"}) {
            if (!rect.has(c) || !rect.get(c).isJsonPrimitive() || !rect.get(c).getAsJsonPrimitive().isNumber()) {
                issues.add(rp + "." + c + " 缺失或不是数字（矩形需要 x/y/w/h）");
                continue;
            }
            float v = rect.get(c).getAsFloat();
            if (v < 0f || v > 1f) {
                issues.add(rp + "." + c + " 超出范围 0~1: " + v);
            }
        }
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
     * 校验 fade 覆盖层的 color 十六进制格式。
     * <p>
     * 规则与运行期 {@code FadeLayer.setColor} 的文档契约一致：#RRGGBB 六位十六进制，
     * 前缀 {@code #} 可省，大小写均可（{@code Integer.parseInt(_, 16)} 接受 a-f）。
     * 字段缺省时跳过——运行期默认回退黑色（不告警），仅在作者显式写了非法色时提示。
     */
    private static void checkHexColor(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + "." + key + " 应为字符串颜色（#RRGGBB 六位十六进制，如 \"#000000\"）");
            return;
        }
        String v = e.getAsString();
        String hex = v.startsWith("#") ? v.substring(1) : v;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            issues.add(path + "." + key + " 颜色格式非法: \"" + v + "\"（需 #RRGGBB 六位十六进制，如 \"#000000\"）");
        }
    }

    /**
     * 校验 meta.triggers 的 requires 前置条件：
     * - 结构性：requires 必须是数组，元素可以是字符串脚本 id 或对象型前置条件；
     * - 字符串（旧语法）：按 script_played 前置条件校验脚本存在/自引用；
     * - 对象型：内置 script_played / script_started / script_completed 需要有效的 script 字段。
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
                issues.add(tp + ".requires 必须是数组");
                continue;
            }
            JsonArray reqs = t.getAsJsonArray("requires");
            for (int j = 0; j < reqs.size(); j++) {
                JsonElement re = reqs.get(j);
                String rp = tp + ".requires[" + j + "]";
                if (re.isJsonPrimitive() && re.getAsJsonPrimitive().isString()) {
                    String reqId = re.getAsString();
                    if (reqId.isEmpty()) {
                        issues.add(rp + " 前置脚本 id 不能为空");
                        continue;
                    }
                    if (selfId != null && reqId.equals(selfId)) {
                        issues.add(rp + " 自引用自身脚本 '" + reqId + "'（可能永不解锁）");
                    }
                    if (knownScriptIds != null && !knownScriptIds.contains(reqId)) {
                        issues.add(rp + " 指向不存在的脚本 '" + reqId + "'（该触发器将永不触发）");
                    }
                } else if (re.isJsonObject()) {
                    // 对象型前置条件：type / script 字段与跨脚本引用校验（与组合器子条件共用同一套规则）
                    JsonObject reqObj = re.getAsJsonObject();
                    validatePrerequisiteRef(reqObj, reqObj, rp, selfId, knownScriptIds, issues);
                } else {
                    issues.add(rp + " 必须是字符串脚本 id 或对象型前置条件");
                }
            }
        }
    }

    /**
     * 校验 meta.triggers 的 type / conditions 结构（0.3.6：facing / all_of / any 三个新类型）：
     * - 未知触发器类型；conditions 不是对象；
     * - facing：yaw1/pitch1/yaw2/pitch2 必须是数字；pitch 端点必须在 -90~90；
     * - all_of / any：list 必须是非空数组，元素为 { type, conditions }；子类型必须是已注册的轮询类
     *   触发器或已注册的前置条件（script_played / script_started / script_completed，锁存语义）；
     *   事件类不可用——会带来“很久以前发生过也算”的误判；两者都不允许嵌套组合器；
     * - facing / all_of / any 上的 exit_buffer 不会生效（无空间外扩），给出提示。
     */
    private static void validateTriggerConditions(JsonObject meta, String path,
                                                  Collection<String> knownScriptIds, List<String> issues) {
        if (!meta.has("triggers") || !meta.get("triggers").isJsonArray()) return;
        String selfId = meta.has("id") && meta.get("id").isJsonPrimitive()
                ? meta.get("id").getAsString() : null;
        JsonArray triggers = meta.getAsJsonArray("triggers");
        for (int i = 0; i < triggers.size(); i++) {
            JsonElement te = triggers.get(i);
            if (!te.isJsonObject()) continue;
            JsonObject t = te.getAsJsonObject();
            String tp = path + ".triggers[" + i + "]";
            String type = t.has("type") && t.get("type").isJsonPrimitive() ? t.get("type").getAsString() : "";
            JsonObject conditions = null;
            if (t.has("conditions")) {
                if (t.get("conditions").isJsonObject()) {
                    conditions = t.getAsJsonObject("conditions");
                } else {
                    issues.add(tp + ".conditions 必须是对象");
                }
            }
            if (type.isEmpty()) continue;
            TriggerType tt = TriggerRegistry.get(type);
            if (tt == null) {
                issues.add(tp + ".type 未知触发器类型: " + type);
                continue;
            }
            if (conditions != null) {
                if ("facing".equals(type)) {
                    validateFacingConditions(conditions, tp + ".conditions", issues);
                } else if ("all_of".equals(type) || "any".equals(type)) {
                    validateCombinationConditions(conditions, tp + ".conditions", type,
                            selfId, knownScriptIds, issues);
                }
            }
            if (("facing".equals(type) || "all_of".equals(type) || "any".equals(type))
                    && isPositiveNumber(t.get("exit_buffer"))) {
                issues.add(tp + ".exit_buffer 对 " + type + " 无效（没有可外扩的空间条件），将被忽略");
            }
        }
    }

    private static void validateFacingConditions(JsonObject c, String p, List<String> issues) {
        for (String key : new String[]{"yaw1", "pitch1", "yaw2", "pitch2"}) {
            if (!c.has(key) || !c.get(key).isJsonPrimitive() || !c.get(key).getAsJsonPrimitive().isNumber()) {
                issues.add(p + "." + key + " 缺失或不是数字（facing 需要 yaw1/pitch1/yaw2/pitch2）");
            }
        }
        if (c.has("pitch1") && c.has("pitch2")
                && c.get("pitch1").isJsonPrimitive() && c.get("pitch2").isJsonPrimitive()
                && c.get("pitch1").getAsJsonPrimitive().isNumber()
                && c.get("pitch2").getAsJsonPrimitive().isNumber()) {
            float p1 = c.get("pitch1").getAsFloat();
            float p2 = c.get("pitch2").getAsFloat();
            if (p1 < -90f || p1 > 90f || p2 < -90f || p2 > 90f) {
                issues.add(p + " pitch 端点超出 -90~90（原版 pitch 范围），该条件将永不满足");
            }
        }
    }

    /**
     * 校验组合器（all_of / any）的 conditions.list：
     * - list 必须是非空数组，元素必须是对象且带非空字符串 type；
     * - 子类型可以是**已注册的轮询类触发器**（瞬时语义），也可以是**已注册的前置条件**
     *   （script_played / script_started / script_completed，锁存语义）；
     * - 事件类触发器（“最近发生过”语义）与嵌套组合器（all_of / any）一律拒绝；
     * - 前置条件子项复用 requires 的同一套字段 / 跨脚本引用校验。
     */
    private static void validateCombinationConditions(JsonObject c, String p, String combinator,
                                                      String selfId, Collection<String> knownScriptIds,
                                                      List<String> issues) {
        if (!c.has("list") || !c.get("list").isJsonArray()) {
            issues.add(p + ".list 缺失或不是数组（" + combinator
                    + " 需要 \"list\": [ { \"type\": ..., \"conditions\": { ... } }, ... ]）");
            return;
        }
        JsonArray list = c.getAsJsonArray("list");
        if (list.isEmpty()) {
            issues.add(p + ".list 不能为空");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            JsonElement e = list.get(i);
            String sp = p + ".list[" + i + "]";
            if (!e.isJsonObject()) {
                issues.add(sp + " 不是对象（应为 { type, conditions }）");
                continue;
            }
            JsonObject sub = e.getAsJsonObject();
            String subType = sub.has("type") && sub.get("type").isJsonPrimitive()
                    && sub.get("type").getAsJsonPrimitive().isString()
                    ? sub.get("type").getAsString() : null;
            if (subType == null || subType.isEmpty()) {
                issues.add(sp + ".type 缺失或不是非空字符串");
                continue;
            }
            if ("all_of".equals(subType) || "any".equals(subType)) {
                issues.add(sp + " 不允许嵌套组合器（" + subType + "，最小版本只支持一层）");
                continue;
            }
            if (sub.has("conditions") && !sub.get("conditions").isJsonObject()) {
                issues.add(sp + ".conditions 必须是对象");
            }
            if (PrerequisiteRegistry.has(subType)) {
                // 前置条件子项（锁存语义）：数据与触发器一致地放在 conditions 里，复用 requires 的同一套字段检查
                JsonObject data = sub.has("conditions") && sub.get("conditions").isJsonObject()
                        ? sub.getAsJsonObject("conditions") : new JsonObject();
                validatePrerequisiteRef(sub, data, sp + ".conditions", selfId, knownScriptIds, issues);
                continue;
            }
            TriggerType tt = TriggerRegistry.get(subType);
            if (tt == null) {
                issues.add(sp + ".type 未知触发器类型，也不是已注册的前置条件: " + subType);
                continue;
            }
            if (tt.getStrategy() != ListenStrategy.POLLING) {
                issues.add(sp + " 事件类触发器不能放进 " + combinator
                        + "（会带来“很久以前发生过也算”的误判）: " + subType);
            }
        }
    }

    /**
     * 前置条件的公共校验（{@code requires} 的对象型写法与组合器子条件共用同一套规则）：
     * type 必需且非空；内置 {@code script_played} / {@code script_started} / {@code script_completed}
     * 的数据对象必须提供 script 字段，并做自引用与跨脚本存在性检查。未知 / 自定义类型不在这里硬报错——
     * 是否允许由调用方决定（requires 允许其他模组注册后使用；组合器子条件只允许已注册类型）。
     *
     * @param typeHolder 带 {@code type} 的对象（requires 元素 / 组合器子条件）
     * @param data       该前置条件的数据对象（requires 元素自身；组合器子条件为 {@code conditions}）
     * @param dataPath   {@code data} 的路径前缀（报错定位用）
     */
    private static void validatePrerequisiteRef(JsonObject typeHolder, JsonObject data, String dataPath,
                                                String selfId, Collection<String> knownScriptIds,
                                                List<String> issues) {
        if (!typeHolder.has("type") || !typeHolder.get("type").isJsonPrimitive()
                || !typeHolder.get("type").getAsJsonPrimitive().isString()) {
            issues.add(dataPath + " 对象型前置条件必须包含字符串 type");
            return;
        }
        String type = typeHolder.get("type").getAsString();
        if (type.isEmpty()) {
            issues.add(dataPath + " 前置条件 type 不能为空");
            return;
        }
        boolean needsScript = type.equals("script_played")
                || type.equals("script_started")
                || type.equals("script_completed");
        if (!needsScript) return;
        if (!data.has("script") || !data.get("script").isJsonPrimitive()
                || !data.get("script").getAsJsonPrimitive().isString()) {
            issues.add(dataPath + ".script 缺失或不是字符串（script_* 前置条件需要 script 字段）");
            return;
        }
        String reqId = data.get("script").getAsString();
        if (reqId.isEmpty()) {
            issues.add(dataPath + ".script 不能为空");
            return;
        }
        if (selfId != null && reqId.equals(selfId)) {
            issues.add(dataPath + ".script 自引用自身脚本 '" + reqId + "'（可能永不解锁或自我循环）");
        }
        if (knownScriptIds != null && !knownScriptIds.contains(reqId)) {
            issues.add(dataPath + ".script 指向不存在的脚本 '" + reqId + "'（该前置条件永不满足）");
        }
    }

    private static boolean isPositiveNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsFloat() > 0f;
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
