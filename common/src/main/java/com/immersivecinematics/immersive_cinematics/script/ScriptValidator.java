package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraChannels;
import com.immersivecinematics.immersive_cinematics.camera.source.PointSource;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionMode;
import com.immersivecinematics.immersive_cinematics.math.ValueDomain;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import com.immersivecinematics.immersive_cinematics.script.schema.MathModelRegistry;
import com.immersivecinematics.immersive_cinematics.selector.SelectorSchema;
import com.immersivecinematics.immersive_cinematics.trigger.server.ListenStrategy;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.server.prereq.PrerequisiteRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 脚本静态校验器 — 供 /icinematics validate 命令使用。
 * <p>
 * 与 {@link ScriptParser} 不同：解析器"报错即停"（运行时），本校验器**收集所有问题**继续扫描，
 * 一次输出完整问题清单（结构错误 / 字段缺失 / 语义错误 / 缺省字段提示）。
 * 面向"AI 仅凭参考指南写脚本"的场景：拿到清单即可逐条修正。
 */
public final class ScriptValidator {

    private static final String[] CAMERA_KF_FIELDS = {"yaw", "pitch", "roll", "fov", "zoom"};

    /** 相机片段运动模型字段（clip 级；缺省 null = 该通道不接模型 = 逐位不改变现状）。 */
    private static final String[] MOTION_FIELDS = {
            "motion_position", "motion_yaw", "motion_pitch", "motion_roll", "motion_fov", "motion_zoom"};
    private static final String[] KNOWN_TYPES =
            {"CAMERA", "LETTERBOX", "AUDIO", "EVENT", "MOD_EVENT", "OVERLAY", "ADJUST"};

    /** {@code meta.base_resolution} 的 w / h 上限（像素）：覆盖 8K 宽，超出视为笔误。 */
    private static final int BASE_RESOLUTION_MAX = 16384;

    /**
     * 调色曲线字段（clip 级结构字段，0.3.6 起 ADJUST 轨与 CAMERA 片段共用同一套）：
     * {@code rgb_curve} = RGB 复合曲线；{@code r_curve} / {@code g_curve} / {@code b_curve} = 每通道曲线；
     * 后六条 = DaVinci 曲线页口径的 hue 曲线（HvH / HvS / HvL、LvS / SvS / SvL）。
     */
    private static final String[] COLOR_CURVE_FIELDS = {
            "rgb_curve", "r_curve", "g_curve", "b_curve",
            "hv_h_curve", "hv_s_curve", "hv_l_curve", "lv_s_curve", "sv_s_curve", "sv_l_curve"};

    /**
     * 层级混合模式（clip 级 {@code blend_mode} 的合法值；顺序 = shader 的 {@code BlendMode} uniform
     * 编码 {@code 0 ~ 4}，与 {@code client/post/ColorAdjustParams} 的 {@code BLEND_*} 常量一一对应）。
     * <p>缺省 {@code normal} = 直替换 = 现状；混合模式作用于调整层（= ADJUST 轨）的输出与基画面，
     * 只写在 ADJUST 轨（master）的片段上——相机片段上写会被拦下（同 {@code lut} 口径）。</p>
     */
    private static final String[] BLEND_MODES =
            {"normal", "multiply", "screen", "soft_light", "overlay"};

    /**
     * 调色关键帧标量通道 → 合法区间（顺序与
     * {@code TrackSchemas.adjust()} / {@code ColorAdjustParams} / {@code ic_color_adjust.fsh} 一致）。
     * <p>0.3.6 起 ADJUST 轨与 CAMERA 片段共用同一套通道（见 {@link #checkAdjustFields}）。</p>
     * <p>标量通道缺省 0 = 无效果（{@code curve_strength} / {@code r_curve_strength} / {@code g_curve_strength} /
     * {@code b_curve_strength} 与六条 hue 曲线的强度 {@code hv_h_strength} ~ {@code sv_l_strength}
     * 例外：缺省 1 = 曲线全量生效）。层级混合的 {@code blend_amount}（0 ~ 1，缺省 1）不在这里——
     * 它与 clip 级 {@code blend_mode} 一起由 {@link #checkBlendFields} 校验（ADJUST 轨专用）。</p>
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
            // 长度 / 格式硬限制（与 ScriptParser#parseMeta 同一口径——校验器放过的写法解析期会直接拒载脚本）
            if (meta.has("id") && meta.get("id").isJsonPrimitive() && meta.get("id").getAsJsonPrimitive().isString()) {
                String id = meta.get("id").getAsString();
                if (!id.isEmpty() && !id.matches("^[a-zA-Z0-9_]{1,32}$")) {
                    issues.add("meta.id 必须匹配 ^[a-zA-Z0-9_]{1,32}$（1~32 位字母 / 数字 / 下划线），实际: " + id);
                }
            }
            checkMaxLength(meta, "meta", "name", 50, issues);
            checkMaxLength(meta, "meta", "author", 30, issues);
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

        // ===== meta.base_resolution：编辑基准分辨率（分辨率转义系数 k 的分母）=====
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            checkBaseResolution(root.getAsJsonObject("meta"), issues);
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
                if (!Float.isFinite(td)) {
                    issues.add("timeline.total_duration 不是有限数字（NaN / Infinity）");
                } else if (td == 0f) issues.add("timeline.total_duration 不允许为 0");
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
                    try {
                        start = clip.get("start_time").getAsFloat();
                        if (!Float.isFinite(start)) issues.add(cp + ".start_time 不是有限数字（NaN / Infinity）");
                    }
                    catch (Exception e) { issues.add(cp + ".start_time 不是数字"); }
                }
                if (!clip.has("duration")) issues.add(cp + ".duration 缺失");
                else {
                    try {
                        float d = clip.get("duration").getAsFloat();
                        if (!Float.isFinite(d)) {
                            issues.add(cp + ".duration 不是有限数字（NaN / Infinity）");
                        } else if (d == 0f) {
                            issues.add(cp + ".duration 不允许为 0（正数=定长，负数=无限）");
                        }
                        dur = d;
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
                    // 相机参数运动模型（0.3.6）：clip 级 motion_* 六字段
                    checkMotionModels(clip, cp, issues);
                    // 覆盖链 Modifier（0.3.6）：clip 级 modifier 声明 + suppress_modifiers 标志
                    checkModifier(clip, cp, issues);
                    // 过渡声明（0.3.6）：clip 级分参数 / 权重曲线 / 打断（只对 transition=morph 生效）
                    checkTransition(clip, cp, issues);
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
                    // 混合模式作用于调整层（= ADJUST 轨）的输出与基画面：相机片段（lane 级）不参与，直接拦下
                    if (clip.has("blend_mode")) {
                        issues.add(cp + ".blend_mode 不支持（混合模式作用于调整层 = ADJUST 轨的输出与基画面，"
                                + "只写在 ADJUST 轨的片段上）：相机片段请删掉该字段，或把 blend_mode 移到 ADJUST 轨");
                    }
                }
                // 覆盖链 Modifier 只作用于主相机状态：只写在 CAMERA 片段上（其余轨道上写 = 不生效）
                checkModifierTrackScope(clip, cp, type, issues);
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
                    // 层级混合（混合模式作用于调整层）：clip 级 blend_mode 枚举——只在 ADJUST 轨
                    checkBlendFields(clip, cp, null, null, issues);
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
                                if (!Float.isFinite(t)) {
                                    // 非有限时间：与 0 / 前帧的比较全为 false，必须单独拦下
                                    issues.add(kp + ".time 不是有限数字（NaN / Infinity）");
                                } else {
                                    if (t < 0f) issues.add(kp + ".time 不能为负数: " + t);
                                    if (t < prevT) issues.add(kp + ".time 不单调（" + t + " < 前一个 " + prevT + "），关键帧时间必须递增");
                                    prevT = t;
                                }
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
                            checkEnum(kf, kp, "look_at", issues, "none", "player", "coordinate", "entity", "block");
                            checkAxisLock(kf, kp, "yaw_lock", issues);
                            checkAxisLock(kf, kp, "pitch_lock", issues);
                            checkRemovedRelativeOrigin(kf, kp, issues);
                            checkRemovedUpAxis(kf, kp, issues);
                            checkRemovedLookAtTarget(kf, kp, issues);
                            String follow = kf.has("follow") ? kf.get("follow").getAsString() : "none";
                            String lookAt = kf.has("look_at") ? kf.get("look_at").getAsString() : "none";
                            checkStringField(kf, kp, "follow_selector", issues,
                                    "选择器字符串，如 @p / @e[type=minecraft:iron_golem]");
                            if ("entity".equals(follow) && !kf.has("follow_selector")) {
                                issues.add(kp + ".follow_selector 缺失（follow=entity 时指定目标，如 @p / @e[type=minecraft:iron_golem]），默认 @p");
                            }
                            if ("entity".equals(follow) && !kf.has("position")) {
                                issues.add(kp + ".position 缺失（follow=entity 时 position 的 dx/dy/dz 即相对实体脚底的偏移）");
                            }
                            checkStringField(kf, kp, "look_at_selector", issues,
                                    "选择器字符串，如 @p / @e[type=minecraft:iron_golem]");
                            if ("entity".equals(lookAt) && !kf.has("look_at_selector")) {
                                issues.add(kp + ".look_at_selector 缺失（look_at=entity 时指定目标），默认 @p");
                            }
                            checkStringField(kf, kp, "look_at_target_structure", issues, "结构 id，如 minecraft:village");
                            checkLookAtTargetCoords(kf, kp, lookAt, issues);
                            if ("block".equals(lookAt)) {
                                if (!kf.has("look_at_target_block")) {
                                    issues.add(kp + ".look_at_target_block 缺失（look_at=block 时指定方块点源，"
                                            + "写法 block:<方块 id>[:<半径>]）");
                                } else {
                                    checkBlockPointSource(kf, kp, "look_at_target_block", issues);
                                }
                            }
                            // 部位百分比只属于实体来源：实体包围盒内的取点（坐标 / 结构 / 方块没有部位）
                            if (kf.has("look_at_part") && !"entity".equals(lookAt)) {
                                issues.add(kp + ".look_at_part 只对 look_at=entity 有效（部位 = 实体包围盒内按每轴百分比取点）："
                                        + "当前 look_at=" + lookAt + "，坐标 / 结构 / 方块来源直接改坐标微调");
                            } else if ("entity".equals(lookAt)) {
                                checkLookAtPart(kf, kp, issues);
                            }
                            // 选择器策略字段（通用 + 调用点专属）：类型 / 范围校验
                            checkSelectorPolicy(kf, kp, issues);
                            // 选择器锚点字段（通用 + 调用点专属）：取值枚举校验
                            checkSelectorAnchor(kf, kp, issues);
                            // 选择器择一字段（通用 + 调用点专属）：取值枚举校验
                            checkSelectorPick(kf, kp, issues);
                            checkStringField(kf, kp, "yaw_base_selector", issues,
                                    "选择器字符串（yaw_base / pitch_base = entity 时的目标）");
                            // 点源字段（位置点源 / 连线端点 / 基准坐标系前轴目标）：字符串形态 + coordinate 配套坐标（都写在关键帧上）
                            checkPointSourceField(kf, kp, "facing_origin", issues);
                            checkPointSourceField(kf, kp, "yaw_base_from", issues);
                            checkPointSourceField(kf, kp, "yaw_base_to", issues);
                            checkPointSourceField(kf, kp, "facing_target", issues);
                            // 点源偏移（世界轴 / 基准坐标系二选一；见 camera/source/PointSource.Offset）
                            checkPointOffset(kf, kp, "yaw_base_from_offset", issues);
                            checkPointOffset(kf, kp, "yaw_base_to_offset", issues);
                            checkPointOffset(kf, kp, "facing_target_offset", issues);
                            checkPointOffset(kf, kp, "look_at_offset", issues);
                            if (kf.has("facing_origin_offset")) {
                                issues.add(kp + ".facing_origin_offset 不存在：位置点源的偏移写在 position 对象里"
                                        + "（dx/dy/dz 世界轴 / fwd/up/right 基准坐标系）");
                            }
                            // 点源时态（live = 逐帧重取 / snapshot = 首次取点后冻结）：只对实体选择器形态生效，
                            // 静态来源写 = 无效果（提示级；未知枚举值报错，运行期仍回落 live）
                            checkPointSourceTime(kf, kp, "facing_origin", issues);
                            checkPointSourceTime(kf, kp, "facing_target", issues);
                            checkPointSourceTime(kf, kp, "yaw_base_from", issues);
                            checkPointSourceTime(kf, kp, "yaw_base_to", issues);
                            checkLookAtTime(kf, kp, lookAt, issues);
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
                            // 混合模式 / 强度是调整层（= ADJUST 轨）的层级混合参数：相机片段关键帧同样拦下
                            if (kf.has("blend_amount")) {
                                issues.add(kp + ".blend_amount 不支持（混合强度只随 ADJUST 轨的 blend_mode 一起写在关键帧上）："
                                        + "相机片段请删掉该字段，或把 blend_mode / blend_amount 移到 ADJUST 轨");
                            }
                        }

                        // ADJUST 关键帧：57 个通道的取值区间（47 个标量缺省 0 = 无效果、10 个曲线强度缺省 1；写回 0 = 该项淡出）
                        // + LUT 混合强度 lut_strength（0~1，缺省 1）+ 层级混合强度 blend_amount（0~1，缺省 1）
                        if ("ADJUST".equalsIgnoreCase(type)) {
                            checkAdjustFields(null, null, kf, kp, issues);
                            checkLutFields(null, null, kf, kp, issues);
                            checkBlendFields(null, null, kf, kp, issues);
                        }

                        // OVERLAY 关键帧：fit 已移除（元素框由素材派生，形状由 scale_x/scale_y 表达）
                        if ("OVERLAY".equalsIgnoreCase(type)) {
                            checkRemovedFit(kf, kp, issues);
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
        if (!Float.isFinite(v)) {
            // NaN / ±Infinity：与任何区间比较都为 false，必须单独拦下（否则校验器放过、运行期直达 shader）
            issues.add(path + "." + key + " 不是有限数字（NaN / Infinity；范围 " + min + " ~ " + max + "）：" + v);
            return;
        }
        if (v < min || v > max) {
            issues.add(path + "." + key + " 超出范围 " + min + " ~ " + max + "：" + v);
        }
    }

    /**
     * 校验调色字段（0.3.6 起 ADJUST 轨与 CAMERA 片段的调色字段集完全一致；ADJUST 作用于整体画面，
     * CAMERA 片段作用于该相机轨的 lane）：
     * <ul>
     *   <li>clip 级：10 条调色曲线的结构（{@link #COLOR_CURVE_FIELDS}，控制点数组）；</li>
     *   <li>关键帧级：57 个通道的取值区间（{@link #ADJUST_CHANNELS} = 47 个标量 + 10 个曲线强度）。</li>
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
     * 校验相机片段的运动模型定义（{@code motion_*} 六字段，clip 级）：定义对象形态、{@code type} 已注册、
     * 参数名已知、嵌套深度 / 节点数上限（与 {@code MathModelRegistry} 同源常量）、组合域一致
     * （结构键 {@code domain} 顶层由通道域覆盖、组合内子定义缺省继承父定义，见 {@link #checkMathModelDomain}）。
     * 只记 issue 不阻断加载——运行期由 {@code MathModelRegistry} 按同一口径回落 {@code linear}（= 无效果）。
     */
    private static void checkMotionModels(JsonObject clip, String cp, List<String> issues) {
        for (String field : MOTION_FIELDS) {
            if (!clip.has(field)) continue;
            checkModelDefinition(clip.get(field), cp + "." + field,
                    MathModelRegistry.channelDomain(field).key(), "缺省 = 该通道不接模型", issues);
        }
    }

    /**
     * 校验一个模型定义字段（{@code motion_*} / 过渡权重曲线）：对象形态、嵌套深度 / 节点数上限
     * （与 {@code MathModelRegistry} 同源常量）、定义树逐节点（{@code type} 已注册 / 参数名已知 / 组合域一致）。
     *
     * @param value          字段值
     * @param path           字段路径（报错定位）
     * @param expectedDomain 继承域（顶层 = 通道值域；结构键 {@code domain} 缺省继承它）
     * @param hint           缺省语义提示（拼进对象形态错误里）
     */
    private static void checkModelDefinition(JsonElement value, String path, String expectedDomain,
                                             String hint, List<String> issues) {
        if (!value.isJsonObject()) {
            issues.add(path + " 需要对象 {type, ...}（数学模型定义；" + hint + "）");
            return;
        }
        int[] measure = {0, 0};
        measureModel(value.getAsJsonObject(), 1, measure);
        if (measure[0] > MathModelRegistry.MODEL_MAX_NODES || measure[1] > MathModelRegistry.MODEL_MAX_DEPTH) {
            issues.add(path + " 模型定义超限（节点 " + measure[0] + " / 上限 "
                    + MathModelRegistry.MODEL_MAX_NODES + "，深度 " + measure[1] + " / 上限 "
                    + MathModelRegistry.MODEL_MAX_DEPTH + "）：运行时整链回落 linear");
        }
        checkMathModelNode(value.getAsJsonObject(), path, expectedDomain, true, issues);
    }

    /**
     * 校验单个模型定义节点（递归下钻 {@code model} / {@code model_list} 参数）：
     * {@code expectedDomain} = 继承域（顶层 = 通道值域）；{@code type} 缺失 / 未注册时无法确定参数表，不再下钻。
     *
     * @param root {@code true} = 字段顶层定义（{@code domain} 被解析期通道域覆盖，见 {@link #checkMathModelDomain}）；
     *             {@code false} = 组合内子定义（{@code domain} 参与组合域一致性判定）
     */
    private static void checkMathModelNode(JsonObject def, String path, String expectedDomain, boolean root,
                                           List<String> issues) {
        JsonElement typeElement = def.get("type");
        if (typeElement == null || !typeElement.isJsonPrimitive() || !typeElement.getAsJsonPrimitive().isString()) {
            issues.add(path + ".type 缺失（需要字符串注册名，可选: "
                    + String.join(" / ", MathModelRegistry.types()) + "；运行时回落 linear）");
            return;
        }
        String type = typeElement.getAsString();
        if (!MathModelRegistry.types().contains(type)) {
            issues.add(path + ".type 未注册: " + type + "（可选: "
                    + String.join(" / ", MathModelRegistry.types()) + "；运行时回落 linear）");
            return;
        }
        String domain = checkMathModelDomain(def, path, expectedDomain, root, issues);
        Map<String, FieldDef> params = MathModelRegistry.params(type);
        for (Map.Entry<String, JsonElement> entry : def.entrySet()) {
            String key = entry.getKey();
            if ("type".equals(key) || "domain".equals(key)) continue;
            FieldDef paramDef = params.get(key);
            if (paramDef == null) {
                issues.add(path + "." + key + " 未知参数（type = " + type + "；可用: "
                        + String.join(" / ", params.keySet()) + "；运行时忽略）");
                continue;
            }
            checkMathModelParam(paramDef.type(), entry.getValue(), path + "." + key, domain, issues);
        }
    }

    /**
     * 校验 {@code domain} 结构键：未知值域 / 与继承域不一致各记一条 issue；返回生效域。
     *
     * 不一致的语义分两层（与 {@code ScriptParser.parseMathModel} 的解析口径一致）：字段顶层定义的
     * {@code domain} 在解析期被通道域覆盖（声明值被忽略，运行期按通道域跑、不回落）；只有组合内子定义
     * 才是组合域不匹配（运行期该子模型回落 {@code linear}）。
     *
     * @param root {@code true} = 字段顶层定义
     */
    private static String checkMathModelDomain(JsonObject def, String path, String expectedDomain, boolean root,
                                               List<String> issues) {
        JsonElement element = def.get("domain");
        if (element == null || element.isJsonNull()) return expectedDomain;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            issues.add(path + ".domain 需要字符串（scalar / angle / vector / color；缺省继承所在通道 / 父定义）");
            return expectedDomain;
        }
        String declared = element.getAsString();
        if (ValueDomain.byKey(declared) == null) {
            issues.add(path + ".domain 未知值域: " + declared + "（合法：scalar / angle / vector / color）");
            return expectedDomain;
        }
        if (!declared.equals(expectedDomain)) {
            if (root) {
                issues.add(path + ".domain 与通道域不一致：顶层 domain 由通道域覆盖（运行期按 " + expectedDomain
                        + " 跑，声明的 " + declared + " 被忽略，不回落 linear）——仅组合内子定义的 domain 不匹配才回落 linear");
            } else {
                issues.add(path + ".domain 组合域不匹配：父定义 " + expectedDomain + "，此处 " + declared
                        + "（运行时该子模型回落 linear）");
            }
        }
        return declared;
    }

    /** 校验一个参数值：{@code model} = 子定义对象、{@code model_list} = 子定义数组；其余类型由解析期把关。 */
    private static void checkMathModelParam(String paramType, JsonElement value, String path, String domain,
                                            List<String> issues) {
        switch (paramType) {
            case "model" -> {
                if (!value.isJsonObject()) {
                    issues.add(path + " 需要对象（子模型定义）");
                    return;
                }
                checkMathModelNode(value.getAsJsonObject(), path, domain, false, issues);
            }
            case "model_list" -> {
                if (!value.isJsonArray()) {
                    issues.add(path + " 需要数组（子模型定义数组）");
                    return;
                }
                JsonArray array = value.getAsJsonArray();
                if (array.size() == 0) {
                    issues.add(path + " 为空数组（空链 = 恒等，无效果）");
                }
                for (int i = 0; i < array.size(); i++) {
                    if (!array.get(i).isJsonObject()) {
                        issues.add(path + "[" + i + "] 需要对象（子模型定义）");
                        continue;
                    }
                    checkMathModelNode(array.get(i).getAsJsonObject(), path + "[" + i + "]", domain, false, issues);
                }
            }
            default -> { }
        }
    }

    /** 统计模型定义树的节点数与最大深度（深度含根 = 1）：{@code acc[0]} = 节点数、{@code acc[1]} = 最大深度。 */
    private static void measureModel(JsonObject def, int depth, int[] acc) {
        acc[0]++;
        if (depth > acc[1]) acc[1] = depth;
        for (Map.Entry<String, JsonElement> entry : def.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                measureModel(value.getAsJsonObject(), depth + 1, acc);
            } else if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonObject()) measureModel(item.getAsJsonObject(), depth + 1, acc);
                }
            }
        }
    }

    /**
     * 校验覆盖链 Modifier 字段（CAMERA 片段专用）：clip 级 {@code modifier} 声明与 {@code suppress_modifiers}
     * 标志。字段语义见 {@code docs/SCRIPT_FORMAT.md} §「覆盖链 Modifier」；与 {@code ScriptParser#parseClipModifier}
     * 同一口径（校验拦下的写法解析期也会拒绝）。
     */
    private static void checkModifier(JsonObject clip, String cp, List<String> issues) {
        if (clip.has("suppress_modifiers")) {
            JsonElement e = clip.get("suppress_modifiers");
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
                issues.add(cp + ".suppress_modifiers 需要布尔值（true = 本片段活跃时跳过 Modifier 链；缺省 false）");
            }
        }
        if (!clip.has("modifier")) return;
        String mp = cp + ".modifier";
        JsonElement e = clip.get("modifier");
        if (!e.isJsonObject()) {
            issues.add(mp + " 需要对象 {channels, mode?, priority?, <通道值>}");
            return;
        }
        JsonObject modifier = e.getAsJsonObject();
        int mask = checkModifierChannels(modifier, mp, issues);
        checkModifierMode(modifier, mp, issues);
        checkModifierPriority(modifier, mp, issues);
        for (String name : CameraChannels.NAMES) {
            boolean declared = (mask & CameraChannels.bitOf(name)) != 0;
            if (!declared) {
                if (modifier.has(name)) {
                    issues.add(mp + "." + name + " 未在 channels 中声明（该通道不生效——要么加进 channels，"
                            + "要么删掉该键）");
                }
                continue;
            }
            if (!modifier.has(name)) {
                issues.add(mp + "." + name + " 缺失（channels 已声明该通道，需要它的值）");
                continue;
            }
            checkModifierValue(modifier.get(name), mp + "." + name, name, issues);
        }
        for (String key : modifier.keySet()) {
            if ("channels".equals(key) || "mode".equals(key) || "priority".equals(key)) continue;
            if (CameraChannels.bitOf(key) != 0) continue;
            issues.add(mp + "." + key + " 未知键（结构键: channels / mode / priority；值键: 通道名 "
                    + CameraChannels.namesHint() + "）");
        }
    }

    /** 校验 {@code channels}（生效通道名数组）：非空、六通道名子集、不重复；返回已声明通道的掩码。 */
    private static int checkModifierChannels(JsonObject modifier, String mp, List<String> issues) {
        String hint = CameraChannels.namesHint();
        JsonElement e = modifier.get("channels");
        if (e == null) {
            issues.add(mp + ".channels 缺失（需要通道名数组，至少一个: " + hint + "）");
            return 0;
        }
        if (!e.isJsonArray()) {
            issues.add(mp + ".channels 需要数组（通道名: " + hint + "）");
            return 0;
        }
        JsonArray channels = e.getAsJsonArray();
        if (channels.isEmpty()) {
            issues.add(mp + ".channels 为空（至少声明一个生效通道: " + hint + "）");
            return 0;
        }
        int mask = 0;
        for (int i = 0; i < channels.size(); i++) {
            JsonElement channel = channels.get(i);
            String cp = mp + ".channels[" + i + "]";
            if (!channel.isJsonPrimitive() || !channel.getAsJsonPrimitive().isString()) {
                issues.add(cp + " 需要字符串（通道名: " + hint + "）");
                continue;
            }
            String name = channel.getAsString();
            int bit = CameraChannels.bitOf(name);
            if (bit == 0) {
                issues.add(cp + " 未知通道: " + name + "（可选: " + hint + "）");
            } else if ((mask & bit) != 0) {
                issues.add(cp + " 重复通道: " + name);
            } else {
                mask |= bit;
            }
        }
        return mask;
    }

    /** 校验 {@code mode}：{@code add} / {@code multiply} / {@code replace}；缺省 = 逐通道缺省。 */
    private static void checkModifierMode(JsonObject modifier, String mp, List<String> issues) {
        if (!modifier.has("mode")) return;
        JsonElement e = modifier.get("mode");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(mp + ".mode 需要字符串（add / multiply / replace；缺省 = 逐通道缺省）");
            return;
        }
        String mode = e.getAsString();
        if (!"add".equals(mode) && !"multiply".equals(mode) && !"replace".equals(mode)) {
            issues.add(mp + ".mode 未知合成模式: " + mode
                    + "（可选: add / multiply / replace；缺省 = 逐通道缺省）");
        }
    }

    /** 校验 {@code priority}：整数（应用序升序，小者先应用；缺省 0）。 */
    private static void checkModifierPriority(JsonObject modifier, String mp, List<String> issues) {
        if (!modifier.has("priority")) return;
        JsonElement e = modifier.get("priority");
        if (!isNumber(e)) {
            issues.add(mp + ".priority 需要整数（应用序：升序，小者先应用；缺省 0）");
            return;
        }
        double value = e.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            issues.add(mp + ".priority 需要整数（应用序：升序，小者先应用；缺省 0）：" + value);
        }
    }

    /** 校验一个已声明通道的值：{@code position} = 对象 {@code {x, y, z}}；其余五个 = 有限数字。 */
    private static void checkModifierValue(JsonElement e, String path, String name, List<String> issues) {
        if (CameraChannels.POSITION == CameraChannels.bitOf(name)) {
            if (!e.isJsonObject()) {
                issues.add(path + " 需要对象 {x, y, z}（position 是整通道一位，三轴都要写）");
                return;
            }
            JsonObject position = e.getAsJsonObject();
            for (String axis : position.keySet()) {
                if (!"x".equals(axis) && !"y".equals(axis) && !"z".equals(axis)) {
                    issues.add(path + "." + axis + " 未知键（position 只认 x / y / z）");
                }
            }
            for (String axis : new String[]{"x", "y", "z"}) {
                JsonElement component = position.get(axis);
                if (component == null) {
                    issues.add(path + "." + axis + " 缺失（position 的三轴 x / y / z 都要写）");
                } else if (!isNumber(component) || !Double.isFinite(component.getAsDouble())) {
                    issues.add(path + "." + axis + " 需要有限数字（世界坐标，方块）");
                }
            }
            return;
        }
        if (!isNumber(e) || !Float.isFinite(e.getAsFloat())) {
            issues.add(path + " 需要有限数字（REPLACE = 绝对值、ADD = 增量、MULTIPLY = 倍率）");
        }
    }

    /**
     * 校验相机片段的过渡声明（clip 级 {@code transition_curve} / {@code transition_params} /
     * {@code transition_interrupt}）：分参数形态（通道名 / {@code mode} / {@code duration} / {@code curve}）、
     * 权重曲线模型定义（与 {@code motion_*} 同一口径）、打断枚举。字段语义见
     * {@code docs/SCRIPT_FORMAT.md} §「过渡（transition）」；与 {@code ScriptParser#parseTransitionParams}
     * 同一口径（校验拦下的写法解析期也会拒绝）。
     */
    private static void checkTransition(JsonObject clip, String cp, List<String> issues) {
        boolean declared = clip.has("transition_curve") || clip.has("transition_params")
                || clip.has("transition_interrupt");
        if (declared && !"morph".equals(transitionName(clip))) {
            issues.add(cp + " 过渡声明（transition_curve / transition_params / transition_interrupt）只对"
                    + " transition=morph 生效：把 transition 改成 morph，或删掉这些字段");
        }
        if (clip.has("transition_curve")) {
            checkTransitionCurve(clip.get("transition_curve"), cp + ".transition_curve", issues);
        }
        if (clip.has("transition_interrupt")) {
            checkEnum(clip, cp, "transition_interrupt", issues, "blend", "snap");
        }
        if (!clip.has("transition_params")) return;
        String pp = cp + ".transition_params";
        JsonElement e = clip.get("transition_params");
        if (!e.isJsonObject()) {
            issues.add(pp + " 需要对象 {<通道>: {mode?, duration?, curve?}}（通道名: "
                    + CameraChannels.namesHint() + "）");
            return;
        }
        for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
            String path = pp + "." + entry.getKey();
            if (CameraChannels.bitOf(entry.getKey()) == 0) {
                issues.add(path + " 未知通道（可用: " + CameraChannels.namesHint() + "）");
                continue;
            }
            if (!entry.getValue().isJsonObject()) {
                issues.add(path + " 需要对象 {mode?, duration?, curve?}");
                continue;
            }
            for (Map.Entry<String, JsonElement> key : entry.getValue().getAsJsonObject().entrySet()) {
                switch (key.getKey()) {
                    case "mode" -> checkTransitionMode(key.getValue(), path + ".mode", issues);
                    case "duration" -> checkTransitionDuration(key.getValue(), path + ".duration", issues);
                    case "curve" -> checkTransitionCurve(key.getValue(), path + ".curve", issues);
                    default -> issues.add(path + "." + key.getKey() + " 未知键（结构键: mode / duration / curve）");
                }
            }
        }
    }

    /** 片段的过渡名（{@code transition} 字段；缺失 / 非字符串 = 缺省 {@code cut}）。 */
    private static String transitionName(JsonObject clip) {
        JsonElement e = clip.get("transition");
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "cut";
    }

    /** 过渡通道模式：脚本用名 {@code blend} / {@code hold} / {@code cut}（缺省 {@code blend}）。 */
    private static void checkTransitionMode(JsonElement value, String path, List<String> issues) {
        String name = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
        if (TransitionMode.byName(name) == null) {
            issues.add(path + " 需要 blend / hold / cut（缺省 blend）");
        }
    }

    /** 过渡通道时长：有限正数（秒）；不做过渡请用 {@code mode: "cut"}。 */
    private static void checkTransitionDuration(JsonElement value, String path, List<String> issues) {
        if (!isNumber(value)) {
            issues.add(path + " 需要数字（秒，> 0）");
            return;
        }
        float duration = value.getAsFloat();
        if (!Float.isFinite(duration) || duration <= 0f) {
            issues.add(path + " 需要有限正数（秒）；不做过渡请用 mode: \"cut\"");
        }
    }

    /** 过渡权重曲线：模型定义对象（SCALAR 域；缺省 = 线性斜坡）。 */
    private static void checkTransitionCurve(JsonElement value, String path, List<String> issues) {
        checkModelDefinition(value, path, ValueDomain.SCALAR.key(), "缺省 = 线性斜坡", issues);
    }

    /** 覆盖链 Modifier / 过渡声明字段只写在 CAMERA 片段上：其余轨道上写 = 不生效（与 {@code lut} 的轨道口径同款）。 */
    private static void checkModifierTrackScope(JsonObject clip, String cp, String type, List<String> issues) {
        if ("CAMERA".equalsIgnoreCase(type)) return;
        for (String field : new String[]{"modifier", "suppress_modifiers"}) {
            if (clip.has(field)) {
                issues.add(cp + "." + field + " 不支持（覆盖链 Modifier 只作用于主相机状态）："
                        + "该字段只写在 CAMERA 轨的片段上");
            }
        }
        for (String field : new String[]{"transition_curve", "transition_params", "transition_interrupt"}) {
            if (clip.has(field)) {
                issues.add(cp + "." + field + " 不支持（过渡声明只写在 CAMERA 轨的片段上）");
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
     * 校验层级混合字段（<b>ADJUST 轨专用</b>：混合模式作用于<b>调整层</b>（= ADJUST 轨）的输出与
     * 基画面（{@code Sampler0} 原图）的层级混合，逐 lane / 相机片段不参与）：
     * <ul>
     *   <li>clip 级 {@code blend_mode}：枚举 {@code normal} / {@code multiply} / {@code screen} /
     *       {@code soft_light} / {@code overlay}（缺省 {@code normal} = 直替换 = 现状，
     *       见 {@link #checkBlendMode}）；</li>
     *   <li>关键帧级 {@code blend_amount}：混合强度 0 ~ 1（缺省 1 = 全量生效，写回 0 = 调整层整体透明）。</li>
     * </ul>
     * <p>{@code clip} / {@code kf} 的用法同 {@link #checkAdjustFields}（另一侧传 {@code null}）。</p>
     */
    private static void checkBlendFields(JsonObject clip, String cp, JsonObject kf, String kp,
                                         List<String> issues) {
        if (clip != null && clip.has("blend_mode")) {
            checkBlendMode(clip.get("blend_mode"), cp + ".blend_mode", issues);
        }
        if (kf != null) {
            checkRange(kf, kp, "blend_amount", issues, 0f, 1f);
        }
    }

    /**
     * 校验 clip 级 {@code blend_mode}（混合模式）：字符串、合法值 = {@code normal} / {@code multiply} /
     * {@code screen} / {@code soft_light} / {@code overlay}（缺省 {@code normal} = 直替换 = 现状）。
     * <p>与 {@code ScriptParser#parseBlendMode} 同一口径——校验拦下的写法解析期也会拒绝。
     * 字段缺省时跳过（缺省 {@code normal} 生效）。</p>
     */
    private static void checkBlendMode(JsonElement e, String path, List<String> issues) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + " 需要字符串（normal / multiply / screen / soft_light / overlay；缺省 normal）");
            return;
        }
        String mode = e.getAsString();
        for (String allowed : BLEND_MODES) {
            if (allowed.equals(mode)) {
                return;
            }
        }
        issues.add(path + " 未知值: " + mode + "（可选: "
                + String.join(" / ", BLEND_MODES) + "）");
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
        if (!Float.isFinite(gamma)) {
            // +Infinity 也落在这里：`Inf > 0` 为 true，只靠「大于 0」拦不住
            issues.add(path + " 必须大于 0 的有限数字（NaN / Infinity；缺省 1 = 不变换）：" + gamma);
        } else if (gamma != 1.0F && !(gamma > 0.0F)) {
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
        } else if (".".equals(name) || "..".equals(name)) {
            issues.add(path + " 文件名非法：" + name);
        }
    }

    /**
     * 校验 {@code meta.base_resolution}（编辑基准分辨率，分辨率转义系数 k 的分母）：
     * 必须是对象且含正整数 {@code w} / {@code h}（{@code > 0}、整数、上限 {@link #BASE_RESOLUTION_MAX}），
     * 违规 → 脚本被拒。字段缺省 / JSON null（= 1920×1080）时跳过。
     */
    private static void checkBaseResolution(JsonObject meta, List<String> issues) {
        if (!meta.has("base_resolution") || meta.get("base_resolution").isJsonNull()) return;
        JsonElement e = meta.get("base_resolution");
        if (!e.isJsonObject()) {
            issues.add("meta.base_resolution 需要对象 {w, h}（像素，正整数；缺省 1920×1080）");
            return;
        }
        JsonObject obj = e.getAsJsonObject();
        checkBaseResolutionComponent(obj, "w", issues);
        checkBaseResolutionComponent(obj, "h", issues);
    }

    /** 校验 {@code meta.base_resolution} 的一个分量：正整数且 ≤ {@link #BASE_RESOLUTION_MAX}。 */
    private static void checkBaseResolutionComponent(JsonObject obj, String key, List<String> issues) {
        String path = "meta.base_resolution." + key;
        if (!obj.has(key)) {
            issues.add(path + " 缺失（base_resolution 需要正整数 w 与 h；缺省 1920×1080）");
            return;
        }
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            issues.add(path + " 不是数字（需要正整数，缺省 1920×1080）");
            return;
        }
        double d = e.getAsDouble();
        if (!Double.isFinite(d) || d != Math.floor(d)) {
            issues.add(path + " 不是整数：" + e);
            return;
        }
        if (d <= 0 || d > BASE_RESOLUTION_MAX) {
            issues.add(path + " 超出范围 1 ~ " + BASE_RESOLUTION_MAX + "：" + e);
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
     * 拒绝已移除的 {@code fit} 关键帧字段（OVERLAY 轨）：元素框由素材派生，形状由
     * {@code scale_x} / {@code scale_y} 表达，没有独立的框可适配。
     */
    private static void checkRemovedFit(JsonObject kf, String kp, List<String> issues) {
        if (kf.has("fit")) {
            issues.add(kp + ".fit 已移除（0.3.6 起）：形状由 scale_x/scale_y 表达");
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
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                // 非有限控制点：与 0~1 及「严格递增」的比较全为 false，必须单独拦下
                issues.add(pp + " 不是有限数字（NaN / Infinity）：" + x + ", " + y);
                continue;
            }
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
        if (!Float.isFinite(v)) {
            issues.add(path + "." + key + " 不是有限数字（NaN / Infinity；范围 0~1）：" + v);
            return;
        }
        if (v < 0f || v > 1f) {
            issues.add(path + "." + key + " 超出范围 0~1: " + v);
        }
    }

    /**
     * 校验选择器目标锁定策略字段（CAMERA 关键帧）：通用字段与调用点专属字段
     * （{@code <字段>_<调用点>}）同口径——刷新 / 切换间隔 / 切换平滑为非负有限秒数，
     * 存活期切换为布尔。字段缺省 = 回落（不报错）；未知调用点后缀不在名单内 = 不校验。
     */
    private static void checkSelectorPolicy(JsonObject kf, String path, List<String> issues) {
        checkSelectorSeconds(kf, path, "selector_refresh", issues);
        checkSelectorBool(kf, path, "selector_switch_while_alive", issues);
        checkSelectorSeconds(kf, path, "selector_switch_interval", issues);
        checkSelectorSeconds(kf, path, "selector_switch_smooth", issues);
        for (String callpoint : SelectorSchema.CALLPOINTS) {
            checkSelectorSeconds(kf, path, SelectorSchema.callpointField("selector_refresh", callpoint), issues);
            checkSelectorBool(kf, path,
                    SelectorSchema.callpointField("selector_switch_while_alive", callpoint), issues);
            checkSelectorSeconds(kf, path,
                    SelectorSchema.callpointField("selector_switch_interval", callpoint), issues);
            checkSelectorSeconds(kf, path,
                    SelectorSchema.callpointField("selector_switch_smooth", callpoint), issues);
        }
    }

    /**
     * 校验选择器锚点字段（CAMERA 关键帧）：通用 {@code selector_anchor} 与调用点专属
     * {@code selector_anchor_<调用点>} 同口径——取值须为 {@link SelectorSchema#ANCHORS} 之一。
     * 字段缺省 = 回落（不报错）；未知调用点后缀不在名单内 = 不校验。
     */
    private static void checkSelectorAnchor(JsonObject kf, String path, List<String> issues) {
        checkSelectorAnchorValue(kf, path, "selector_anchor", issues);
        for (String callpoint : SelectorSchema.CALLPOINTS) {
            checkSelectorAnchorValue(kf, path, SelectorSchema.callpointField("selector_anchor", callpoint), issues);
        }
    }

    /** 单个锚点字段：非字符串 / 四值集之外的取值都报错；字段缺省时跳过（回落通用字段 / 缺省 camera）。 */
    private static void checkSelectorAnchorValue(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        String allowed = String.join(" / ", SelectorSchema.ANCHORS);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + "." + key + " 不是字符串（可选: " + allowed + "）");
            return;
        }
        String v = e.getAsString();
        for (String a : SelectorSchema.ANCHORS) {
            if (a.equals(v)) return;
        }
        issues.add(path + "." + key + " 未知值: " + v + "（可选: " + allowed + "）");
    }

    /**
     * 校验选择器择一字段（CAMERA 关键帧）：通用 {@code selector_pick} 与调用点专属
     * {@code selector_pick_<调用点>} 同口径——取值须为 {@link SelectorSchema#PICKS} 之一。
     * 字段缺省 = 回落（不报错）；未知调用点后缀不在名单内 = 不校验。
     */
    private static void checkSelectorPick(JsonObject kf, String path, List<String> issues) {
        checkSelectorPickValue(kf, path, "selector_pick", issues);
        for (String callpoint : SelectorSchema.CALLPOINTS) {
            checkSelectorPickValue(kf, path, SelectorSchema.callpointField("selector_pick", callpoint), issues);
        }
    }

    /** 单个择一字段：非字符串 / 三值集之外的取值都报错；字段缺省时跳过（回落通用字段 / 缺省 first）。 */
    private static void checkSelectorPickValue(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        String allowed = String.join(" / ", SelectorSchema.PICKS);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + "." + key + " 不是字符串（可选: " + allowed + "）");
            return;
        }
        String v = e.getAsString();
        for (String p : SelectorSchema.PICKS) {
            if (p.equals(v)) return;
        }
        issues.add(path + "." + key + " 未知值: " + v + "（可选: " + allowed + "）");
    }

    /**
     * 校验点源偏移字段 {@code <字段>_offset}（对象形态）：{@code dx/dy/dz}（世界轴，缺省表达空间）或
     * {@code fwd/up/right}（基准坐标系）二选一（混写报错），已给出的分量须为有限数字（单位方块）。
     * 基准坐标系偏移需要本关键帧建立基准坐标系（见 {@link #hasFrame}），否则该偏移不生效。
     */
    private static void checkPointOffset(JsonObject kf, String path, String field, List<String> issues) {
        if (!kf.has(field)) return;
        String pp = path + "." + field;
        JsonElement e = kf.get(field);
        if (!e.isJsonObject()) {
            issues.add(pp + " 应为对象（世界轴 {dx,dy,dz} 或基准坐标系 {fwd,up,right}）");
            return;
        }
        JsonObject o = e.getAsJsonObject();
        boolean world = o.has("dx") || o.has("dy") || o.has("dz");
        boolean base = o.has("fwd") || o.has("up") || o.has("right");
        if (world && base) {
            issues.add(pp + " 不能混用世界轴（dx/dy/dz）与基准坐标系（fwd/up/right）分量");
            return;
        }
        for (String axis : base ? new String[]{"fwd", "up", "right"} : new String[]{"dx", "dy", "dz"}) {
            if (!o.has(axis)) continue;
            JsonElement c = o.get(axis);
            if (!isNumber(c) || !Float.isFinite(c.getAsFloat())) {
                issues.add(pp + "." + axis + " 不是有限数字（偏移量，单位方块）");
            }
        }
        if (base && !hasFrame(kf)) {
            issues.add(pp + " 用基准坐标系（fwd/up/right）表达，但本关键帧没有基准坐标系"
                    + "（需写 facing_origin / facing_target，或位置用 fwd/up/right）——该偏移不生效");
        }
    }

    /**
     * 本关键帧是否建立基准坐标系：写了 {@code facing_origin} / {@code facing_target}，
     * 或位置用基准坐标系偏移（{@code fwd}/{@code up}/{@code right}）。
     */
    private static boolean hasFrame(JsonObject kf) {
        if (kf.has("facing_origin") || kf.has("facing_target")) return true;
        JsonElement pos = kf.get("position");
        if (pos == null || !pos.isJsonObject()) return false;
        JsonObject p = pos.getAsJsonObject();
        return p.has("fwd") || p.has("up") || p.has("right");
    }

    /**
     * 拒绝已移除的位置基准字段（{@code position.relative_origin} 与配套坐标）：0.3.6 起位置基准统一用
     * 关键帧字段 {@code facing_origin}（五形态），两种偏移表达空间共用同一位置点源。
     */
    private static void checkRemovedRelativeOrigin(JsonObject kf, String path, List<String> issues) {
        JsonElement pos = kf.get("position");
        if (pos == null || !pos.isJsonObject()) return;
        JsonObject p = pos.getAsJsonObject();
        for (String field : new String[]{"relative_origin", "relative_origin_x", "relative_origin_y",
                "relative_origin_z"}) {
            if (p.has(field)) {
                issues.add(path + ".position." + field + " 已移除（0.3.6 起）：位置基准统一用关键帧字段 facing_origin"
                        + "（写法见 docs/SCRIPT_FORMAT.md §「点源」）");
            }
        }
    }

    /**
     * 拒绝已移除的垂直面基准字段 {@code position.up_axis}：0.3.6 起基准坐标系的上轴固定为世界竖直
     * （前轴 / 右轴取水平面内分量），方向侧每轴跟来源 / 锁值改用 {@code yaw_lock} / {@code pitch_lock}。
     */
    private static void checkRemovedUpAxis(JsonObject kf, String path, List<String> issues) {
        JsonElement pos = kf.get("position");
        if (pos == null || !pos.isJsonObject()) return;
        if (pos.getAsJsonObject().has("up_axis")) {
            issues.add(path + ".position.up_axis 已移除（0.3.6 起）：基准坐标系的上轴固定为世界竖直"
                    + "（前轴 / 右轴取水平面内分量）；方向侧每轴跟来源 / 锁值改用 yaw_lock / pitch_lock");
        }
    }

    /**
     * 校验方向锁字段（{@code yaw_lock} / {@code pitch_lock}）：取值须为有限数字（该轴世界角锁值，单位度）；
     * 字段缺省 = 不锁（跟方向源），不报错。
     */
    private static void checkAxisLock(JsonObject kf, String path, String key, List<String> issues) {
        if (!kf.has(key)) return;
        JsonElement e = kf.get(key);
        if (!isNumber(e) || !Float.isFinite(e.getAsFloat())) {
            issues.add(path + "." + key + " 不是有限数字（方向锁 = 该轴世界角锁值，单位度；不写 = 跟方向源）");
        }
    }

    /**
     * 拒绝已移除的注视目标对象 {@code look_at_target}（含 {@code relative_to} / 对象内偏移）：
     * 0.3.6 起注视目标统一为点源 + 偏移。
     */
    private static void checkRemovedLookAtTarget(JsonObject kf, String path, List<String> issues) {
        if (kf.has("look_at_target")) {
            issues.add(path + ".look_at_target 已移除（0.3.6 起）：注视目标统一为点源 + 偏移——实体用"
                    + " look_at_selector（部位百分比 look_at_part）、坐标用 look_at_target_x/y/z、结构用"
                    + " look_at_target_structure、方块用 look_at_target_block，偏移用 look_at_offset"
                    + "（世界轴 dx/dy/dz / 基准坐标系 fwd/up/right）");
        }
    }

    /**
     * 校验点源字段（CAMERA 关键帧的连线端点 / 基准朝向目标）：取值须为字符串，形态与判据见
     * {@code camera/source/PointSource}（{@code player} / {@code coordinate} / {@code block:id[:radius]} /
     * {@code @选择器} / {@code uuid:…} / 结构 id）。{@code "coordinate"} 形态必须带 {@code <字段>_x/_y/_z}
     * 三个数字——缺省会被当作 (0,0,0)，静默把点源钉在世界原点。字段缺省 / 空串 = 无点源（不报错）。
     */
    private static void checkPointSourceField(JsonObject kf, String path, String field, List<String> issues) {
        if (!kf.has(field)) return;
        JsonElement e = kf.get(field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + "." + field
                    + " 不是字符串（点源写法：player / coordinate / block:id[:radius] / @选择器 / uuid:… / 结构 id）");
            return;
        }
        if (!"coordinate".equals(e.getAsString())) return;
        for (String axis : new String[]{"x", "y", "z"}) {
            String key = field + "_" + axis;
            if (!kf.has(key) || !isNumber(kf.get(key))) {
                issues.add(path + "." + key + " 缺失或不是数字（" + field + " = \"coordinate\" 时必须给出点源坐标）");
            }
        }
    }

    /**
     * 校验点源时态字段 {@code <字段>_time}：{@code live}（缺省）= 逐帧重取，{@code snapshot} = 首次解析到的点冻结；
     * 未知值报错（运行期按缺省回落 live，脚本仍可播，与其余枚举校验同口径）。时态只对实体选择器形态生效：
     * 坐标 / 方块 / 结构 / 玩家激活位置天生恒定，写了 = 无效果（提示级，参照 exit_buffer 无效提示）。
     */
    private static void checkPointSourceTime(JsonObject kf, String path, String field, List<String> issues) {
        String key = field + "_time";
        if (!kf.has(key)) return;
        checkEnum(kf, path, key, issues, "live", "snapshot");
        JsonElement e = kf.get(field);
        String form = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
        if (PointSource.kindOf(form) != PointSource.Kind.SELECTOR) {
            issues.add(path + "." + key + " 无效果（" + field + " 的来源天生恒定，时态只对实体选择器形态生效）");
        }
    }

    /**
     * 校验注视点时态字段 {@code look_at_time}：形态判据是 {@code look_at} 枚举（不是点源形态字符串），故单列一份。
     * 只有 {@code look_at = entity}（实体选择器）可切时态，其余形态（含 {@code none}）恒定 = 写了无效果（提示级）。
     *
     * @param lookAt 该关键帧的 {@code look_at} 取值（缺省 {@code none}）
     */
    private static void checkLookAtTime(JsonObject kf, String path, String lookAt, List<String> issues) {
        if (!kf.has("look_at_time")) return;
        checkEnum(kf, path, "look_at_time", issues, "live", "snapshot");
        if (!"entity".equals(lookAt)) {
            issues.add(path + ".look_at_time 无效果（look_at=" + lookAt + " 的来源天生恒定，时态只对 entity 生效）");
        }
    }

    /**
     * 校验字符串字段（选择器 / 结构名等形态字段）：取值须为字符串，缺省时跳过（回落缺省值）。
     * 非字符串会在运行期被 {@code Keyframe.getString} 静默回落缺省——在此拦下，避免静默降级。
     */
    private static void checkStringField(JsonObject obj, String path, String key, List<String> issues, String usage) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + "." + key + " 不是字符串（" + usage + "）");
        }
    }

    /**
     * 校验 {@code look_at = coordinate} 的注视坐标：{@code look_at_target_x/y/z} 三个都要给、且为有限数字
     * （非数字会被 {@code Keyframe.getFloat} 静默回落缺省 x=0 / y=64 / z=0）。
     * 写了 {@code look_at_target_structure} 时结构中心优先、坐标不参与，不校验。
     */
    private static void checkLookAtTargetCoords(JsonObject kf, String path, String lookAt, List<String> issues) {
        if (!"coordinate".equals(lookAt) || kf.has("look_at_target_structure")) return;
        for (String axis : new String[]{"x", "y", "z"}) {
            String key = "look_at_target_" + axis;
            if (!kf.has(key)) {
                issues.add(path + ".look_at_target_x/y/z 缺失（look_at=coordinate 时指定坐标，"
                        + "或 look_at_target_structure 结构名）");
                return;
            }
            if (!isNumber(kf.get(key)) || !Float.isFinite(kf.get(key).getAsFloat())) {
                issues.add(path + "." + key + " 不是有限数字（look_at=coordinate 的注视坐标）");
            }
        }
    }

    /**
     * 校验方块点源字段（{@code look_at_target_block}）：取值须为 {@code block:<方块 id>[:<半径>]} 字符串，
     * 形态判据与 {@code camera/source/PointSource} 同源（{@link PositionData#originKindOf}）。
     */
    private static void checkBlockPointSource(JsonObject kf, String path, String field, List<String> issues) {
        JsonElement e = kf.get(field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            issues.add(path + "." + field + " 不是字符串（方块点源写法：block:<方块 id>[:<半径>]）");
            return;
        }
        if (PositionData.originKindOf(e.getAsString()) != PositionData.ORIGIN_BLOCK) {
            issues.add(path + "." + field + " 不是方块点源: " + e.getAsString()
                    + "（写法 block:<方块 id>[:<半径>]，半径缺省 " + PositionData.DEFAULT_BLOCK_RADIUS + " 格）");
        }
    }

    /**
     * 校验部位百分比对象 {@code look_at_part}：须为对象 {@code {x,y,z}}，已给出的分量须为有限数字
     * （每轴 0 ~ 100，缺省分量 50 = 包围盒中心）。越界不报错——运行时钳制在包围盒内
     * （见 {@code camera/source/PointSource.EntityPoint}）。
     */
    private static void checkLookAtPart(JsonObject kf, String path, List<String> issues) {
        if (!kf.has("look_at_part")) return;
        String pp = path + ".look_at_part";
        JsonElement e = kf.get("look_at_part");
        if (!e.isJsonObject()) {
            issues.add(pp + " 应为对象 {x,y,z}（实体包围盒内的部位百分比，每轴 0 ~ 100，缺省 50 = 中心）");
            return;
        }
        JsonObject part = e.getAsJsonObject();
        for (String axis : new String[]{"x", "y", "z"}) {
            if (!part.has(axis)) continue;
            JsonElement c = part.get(axis);
            if (!isNumber(c)) {
                issues.add(pp + "." + axis + " 不是数字（部位百分比，每轴 0 ~ 100）");
                continue;
            }
            float v = c.getAsFloat();
            if (!Float.isFinite(v)) {
                issues.add(pp + "." + axis + " 不是有限数字（部位百分比，每轴 0 ~ 100）: " + v);
            }
        }
    }

    /** 非负有限秒数；字段缺省时跳过（回落通用字段 / 缺省策略）。 */
    private static void checkSelectorSeconds(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        if (!isNumber(e)) {
            issues.add(path + "." + key + " 不是数字（单位秒，≥ 0）");
            return;
        }
        float v = e.getAsFloat();
        if (!Float.isFinite(v)) {
            issues.add(path + "." + key + " 不是有限数字（NaN / Infinity）：" + v);
            return;
        }
        if (v < 0f) {
            issues.add(path + "." + key + " 不能为负数（单位秒）: " + v);
        }
    }

    /** 布尔值；字段缺省时跳过（回落通用字段 / 缺省策略）。 */
    private static void checkSelectorBool(JsonObject obj, String path, String key, List<String> issues) {
        if (!obj.has(key)) return;
        JsonElement e = obj.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            issues.add(path + "." + key + " 应为布尔值（true / false）");
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
            if (!Float.isFinite(v)) {
                issues.add(rp + "." + c + " 不是有限数字（NaN / Infinity；范围 0~1）：" + v);
                continue;
            }
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
     * 校验 meta.triggers 的 type / conditions 结构：未知类型、conditions 非对象；facing 的四个角度字段
     * （yaw1/pitch1/yaw2/pitch2 为数字、pitch 端点 ±90）；all_of / any 的 list 结构与子类型（轮询类触发器 /
     * 已注册前置条件 / 嵌套组合器，层数上限 {@link Evaluators#MAX_COMBINATION_DEPTH} 含顶层，事件类不可用）；
     * death 的场景组字段类型（dimension / biome / position / radius / corner1 / corner2）；
     * facing / all_of / any 上无效的 exit_buffer 提示，death 上无效的 on_enter / exit_buffer 提示。
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
                    validateCombinationConditions(conditions, tp + ".conditions", type, 1,
                            selfId, knownScriptIds, issues);
                } else if ("death".equals(type)) {
                    validateDeathConditions(conditions, tp + ".conditions", issues);
                }
            }
            if (("facing".equals(type) || "all_of".equals(type) || "any".equals(type))
                    && isPositiveNumber(t.get("exit_buffer"))) {
                issues.add(tp + ".exit_buffer 对 " + type + " 无效（没有可外扩的空间条件），将被忽略");
            }
            if ("death".equals(type)) {
                if (isPositiveNumber(t.get("exit_buffer"))) {
                    issues.add(tp + ".exit_buffer 对 death 无效（事件驱动触发器不参与进出区域判定），将被忽略");
                }
                if (t.has("on_enter") && t.get("on_enter").isJsonPrimitive()
                        && t.get("on_enter").getAsJsonPrimitive().isBoolean()
                        && t.get("on_enter").getAsBoolean()) {
                    issues.add(tp + ".on_enter 对 death 无效（事件驱动触发器没有进入/离开语义，"
                            + "而进入状态不会复位）：写了等于只触发一次，建议改用 repeatable 控制");
                }
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
            if (!Float.isFinite(p1) || !Float.isFinite(p2)) {
                issues.add(p + " pitch 端点不是有限数字（NaN / Infinity），该条件将永不满足");
            } else if (p1 < -90f || p1 > 90f || p2 < -90f || p2 > 90f) {
                issues.add(p + " pitch 端点超出 -90~90（原版 pitch 范围），该条件将永不满足");
            }
        }
    }

    /**
     * death 条件校验（场景组，字段全部可选）：dimension / biome 必须是字符串，position / corner1 / corner2
     * 必须是对象（形如 {@code { "x": ..., "y": ..., "z": ... } }），radius 必须是数字。写错类型 = 该条件
     * 永不满足（求值侧按类型不符直接判 false），故报错而非静默忽略。
     */
    private static void validateDeathConditions(JsonObject c, String p, List<String> issues) {
        for (String key : new String[]{"dimension", "biome"}) {
            if (c.has(key) && (!c.get(key).isJsonPrimitive() || !c.get(key).getAsJsonPrimitive().isString())) {
                issues.add(p + "." + key + " 必须是字符串（场景条件按死亡时刻记录判定，如 \"minecraft:the_nether\"）");
            }
        }
        for (String key : new String[]{"position", "corner1", "corner2"}) {
            if (c.has(key) && !c.get(key).isJsonObject()) {
                issues.add(p + "." + key + " 必须是对象（形如 { \"x\": ..., \"y\": ..., \"z\": ... }）");
            }
        }
        if (c.has("radius") && (!c.get("radius").isJsonPrimitive()
                || !c.get("radius").getAsJsonPrimitive().isNumber())) {
            issues.add(p + ".radius 必须是数字（配合 position，缺省 0）");
        }
    }

    /**
     * 校验组合器（all_of / any）的 conditions.list（递归，支持嵌套）：list 必须是非空数组，元素为对象且带非空
     * 字符串 type；子类型可以是已注册的轮询类触发器、已注册前置条件（锁存）或另一个组合器（嵌套）；事件类拒绝。
     * 嵌套层数 = 本层 + 1（顶层 = 1），超过 {@link Evaluators#MAX_COMBINATION_DEPTH} 报错并停止下钻。
     *
     * @param depth 本组合器所处层数，顶层 = 1（与求值侧同一常量口径）
     */
    private static void validateCombinationConditions(JsonObject c, String p, String combinator, int depth,
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
            boolean badConditions = sub.has("conditions") && !sub.get("conditions").isJsonObject();
            if (badConditions) {
                issues.add(sp + ".conditions 必须是对象");
            }
            if ("all_of".equals(subType) || "any".equals(subType)) {
                if (depth + 1 > Evaluators.MAX_COMBINATION_DEPTH) {
                    issues.add(sp + " 嵌套超过 " + Evaluators.MAX_COMBINATION_DEPTH + " 层（组合器最大深度，含顶层）: "
                            + subType);
                    continue;
                }
                if (badConditions) continue;   // conditions 结构已报错，无法继续下钻
                JsonObject nested = sub.has("conditions") ? sub.getAsJsonObject("conditions") : new JsonObject();
                validateCombinationConditions(nested, sp + ".conditions", subType, depth + 1,
                        selfId, knownScriptIds, issues);
                continue;
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

    /**
     * 校验字符串字段的最大长度（按 UTF-16 码元数，与 {@code ScriptParser#parseMeta} 同一口径：
     * {@code meta.name} ≤ 50、{@code meta.author} ≤ 30）；字段缺省 / 非字符串时跳过。
     */
    private static void checkMaxLength(JsonObject obj, String path, String key, int max, List<String> issues) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) return;
        int len = obj.get(key).getAsString().length();
        if (len > max) {
            issues.add(path + "." + key + " 最长 " + max + " 字符，实际: " + len);
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
