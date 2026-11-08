package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.object.ObjectCallpoints;
import com.immersivecinematics.immersive_cinematics.spi.FieldDef;
import com.immersivecinematics.immersive_cinematics.spi.Interp;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;
import com.immersivecinematics.immersive_cinematics.spi.PropertyMode;
import com.immersivecinematics.immersive_cinematics.spi.PropertyType;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 各轨道类型的 Java 字段元数据：数据源即 Java 字段表，顺序 = 编辑器渲染顺序。
 *
 * 每轨只登记对象模型（{@code objects}）与七轨共用的播放控制（{@link TrackTypeSchema#playControl()}）。
 */
public final class TrackSchemas {

    private TrackSchemas() {}

    public static Map<TrackTypeId, TrackTypeSchema> all() {
        Map<TrackTypeId, TrackTypeSchema> map = new LinkedHashMap<>();
        map.put(TrackTypeId.CAMERA, camera());
        map.put(TrackTypeId.LETTERBOX, letterbox());
        map.put(TrackTypeId.AUDIO, audio());
        map.put(TrackTypeId.EVENT, event());
        map.put(TrackTypeId.MOD_EVENT, modEvent());
        map.put(TrackTypeId.OVERLAY, overlay());
        map.put(TrackTypeId.ADJUST, adjust());
        return map;
    }


    /**
     * CAMERA 轨道（相机运动 + 合成 + 自带调色）：对象声明见 {@link #cameraObjects()}——位置 / 朝向点源、
     * 合成参数、遮罩几何、10 条调色曲线与 57 个调色通道（与 ADJUST 同名同缺省），作用于本片段产出的 lane，
     * 在该 lane 渲染完成之后、合成之前生效。{@code blend_mode} / {@code blend_amount} 属 master 专用（ADJUST），
     * 写在相机片段上会被 validator 拦下；整体画面的调色走 ADJUST 轨。
     */
    private static TrackTypeSchema camera() {
        return new TrackTypeSchema(cameraObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema letterbox() {
        return new TrackTypeSchema(letterboxObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema audio() {
        return new TrackTypeSchema(audioObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema event() {
        return new TrackTypeSchema(eventObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema modEvent() {
        return new TrackTypeSchema(modEventObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema overlay() {
        return new TrackTypeSchema(overlayObjects(), PLAY_CONTROL);
    }

    /**
     * ADJUST 轨道（整体画面调色）：字段顺序 = 着色器操作栈顺序，schema / {@code ColorAdjustParams} / fsh 三处逐项对应，
     * 增删通道必须同步（区间由 {@code ScriptValidator} 校验）。47 个标量关键帧通道缺省全 0 = 无效果，写回 0 即该项淡出
     * （无需 enabled 开关）；mix_rr~mix_bb 全 0 = 单位阵、lift/gamma/gain 九个与六色带十二个全 0 = 整步跳过（逐位恒等）。
     * clip 级曲线 / LUT 只定义一次、关键帧只控强度（0~1，缺省 1）；本轨作用于合成输出（master），lane 级调色写在 CAMERA 片段。
     * blend_mode（缺省 normal = 直替换）+ blend_amount（0~1，缺省 1）在全部步骤之后做层级混合，只动 RGB、alpha 直通。
     */
    private static TrackTypeSchema adjust() {
        return new TrackTypeSchema(adjustObjects(), PLAY_CONTROL);
    }

    // ------------------------------------------------------------------
    // I-1 对象模型声明：七轨 objects + 共用 playControl
    // ------------------------------------------------------------------

    /** 七轨共用的播放控制字段（顺序固定）：start_time / duration / loop / loop_count / loop_mode。 */
    private static final Map<String, FieldDef> PLAY_CONTROL = playControl();

    private static Map<String, FieldDef> playControl() {
        Map<String, FieldDef> fields = new LinkedHashMap<>();
        fields.put("start_time", new FieldDef("float", null, true, List.of(), "playback"));
        fields.put("duration", new FieldDef("float", null, true, List.of(), "playback"));
        fields.put("loop", new FieldDef("bool", false, false, List.of(), "playback"));
        fields.put("loop_count", new FieldDef("int", -1, false, List.of(), "playback"));
        fields.put("loop_mode", new FieldDef("enum", "repeat", false, List.of("repeat", "pingpong"), "playback"));
        return Collections.unmodifiableMap(fields);
    }

    private static ObjectDef.Builder animated(String path, PropertyType type, Interp interp, Object def) {
        return ObjectDef.builder(path, PropertyMode.ANIMATED, type).interp(interp).def(def);
    }

    private static ObjectDef.Builder value(String path, PropertyType type, Object def) {
        return ObjectDef.builder(path, PropertyMode.VALUE, type).def(def);
    }

    private static void add(Map<String, ObjectDef> objects, ObjectDef.Builder builder) {
        ObjectDef def = builder.build();
        objects.put(def.path(), def);
    }

    private static Map<String, ObjectDef> cameraObjects() {
        Map<String, ObjectDef> objects = new LinkedHashMap<>();

        // 位置：点源 + 偏移。组级 step 参数与 position.x/y/z 平级；group 供切换对齐校验。
        add(objects, animated("position.x", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("position").group("position"));
        add(objects, animated("position.y", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("position").group("position"));
        add(objects, animated("position.z", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("position").group("position"));
        add(objects, animated("position_mode", PropertyType.ENUM, Interp.STEP, "relative")
                .enumValues("relative", "absolute").section("position").group("position"));
        add(objects, animated("position_space", PropertyType.ENUM, Interp.STEP, "world")
                .enumValues("world", "base").section("position").group("position"));
        add(objects, animated("position_origin", PropertyType.REF, Interp.STEP, "")
                .section("position").group("position"));
        add(objects, animated("position_origin_x", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("position").group("position"));
        add(objects, animated("position_origin_y", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("position").group("position"));
        add(objects, animated("position_origin_z", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("position").group("position"));
        add(objects, animated("position_origin_freeze", PropertyType.BOOL, Interp.STEP, false)
                .section("position").group("position"));

        // 方向源（position_space=base 的基准系）
        add(objects, animated("facing_source", PropertyType.ENUM, Interp.STEP, "auto")
                .enumValues("auto", "world", "entity", "line").section("position").group("position"));
        add(objects, animated("facing_target", PropertyType.REF, Interp.STEP, "")
                .section("position").group("position"));
        add(objects, animated("facing_target_x", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("position").group("position"));
        add(objects, animated("facing_target_y", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("position").group("position"));
        add(objects, animated("facing_target_z", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("position").group("position"));
        add(objects, animated("facing_target_offset.x", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("position").group("position"));
        add(objects, animated("facing_target_offset.y", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("position").group("position"));
        add(objects, animated("facing_target_offset.z", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("position").group("position"));
        add(objects, animated("facing_target_offset.space", PropertyType.ENUM, Interp.STEP, "world")
                .enumValues("world", "base").section("position").group("position"));
        add(objects, animated("facing_target_freeze", PropertyType.BOOL, Interp.STEP, false)
                .section("position").group("position"));
        add(objects, animated("facing_entity", PropertyType.REF, Interp.STEP, "")
                .section("position").group("position"));

        // 朝向：三轴角度（显式 angle）、方向锁、基准与 look_at
        add(objects, animated("yaw", PropertyType.FLOAT, Interp.ANGLE, 0f)
                .unit("degree").section("orientation").group("orientation"));
        add(objects, animated("pitch", PropertyType.FLOAT, Interp.ANGLE, 0f)
                .unit("degree").section("orientation").group("orientation"));
        add(objects, animated("roll", PropertyType.FLOAT, Interp.ANGLE, 0f)
                .unit("degree").section("orientation").group("orientation"));
        add(objects, animated("yaw_lock", PropertyType.NULLABLE_FLOAT, Interp.ANGLE, null)
                .unit("degree").section("orientation").group("orientation"));
        add(objects, animated("pitch_lock", PropertyType.NULLABLE_FLOAT, Interp.ANGLE, null)
                .unit("degree").section("orientation").group("orientation"));
        add(objects, animated("yaw_base", PropertyType.ENUM, Interp.STEP, "world")
                .enumValues("world", "entity", "line").section("orientation").group("orientation"));
        add(objects, animated("pitch_base", PropertyType.ENUM, Interp.STEP, "world")
                .enumValues("world", "entity", "line").section("orientation").group("orientation"));
        add(objects, animated("yaw_base_selector", PropertyType.REF, Interp.STEP, "@p")
                .section("orientation").group("orientation"));
        add(objects, animated("yaw_base_from", PropertyType.REF, Interp.STEP, "")
                .section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_from_x", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_from_y", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_from_z", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_from_offset.x", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_from_offset.y", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_from_offset.z", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_from_offset.space", PropertyType.ENUM, Interp.STEP, "world")
                .enumValues("world", "base").section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_from_freeze", PropertyType.BOOL, Interp.STEP, false)
                .section("orientation").group("yaw_base_from"));
        add(objects, animated("yaw_base_to", PropertyType.REF, Interp.STEP, "")
                .section("orientation").group("yaw_base_to"));
        add(objects, animated("yaw_base_to_x", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("yaw_base_to"));
        add(objects, animated("yaw_base_to_y", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("yaw_base_to"));
        add(objects, animated("yaw_base_to_z", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("yaw_base_to"));
        add(objects, animated("yaw_base_to_offset.x", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("yaw_base_to"));
        add(objects, animated("yaw_base_to_offset.y", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("yaw_base_to"));
        add(objects, animated("yaw_base_to_offset.z", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("yaw_base_to"));
        add(objects, animated("yaw_base_to_offset.space", PropertyType.ENUM, Interp.STEP, "world")
                .enumValues("world", "base").section("orientation").group("yaw_base_to"));
        add(objects, animated("yaw_base_to_freeze", PropertyType.BOOL, Interp.STEP, false)
                .section("orientation").group("yaw_base_to"));
        add(objects, animated("look_at", PropertyType.ENUM, Interp.STEP, "none")
                .enumValues("none", "player", "coordinate", "entity", "block")
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_selector", PropertyType.REF, Interp.STEP, "@p")
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_part.x", PropertyType.FLOAT, Interp.LINEAR, 50f)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_part.y", PropertyType.FLOAT, Interp.LINEAR, 50f)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_part.z", PropertyType.FLOAT, Interp.LINEAR, 50f)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_target_x", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_target_y", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_target_z", PropertyType.FLOAT, Interp.LINEAR, null)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_target_structure", PropertyType.STRING, Interp.STEP, "")
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_target_block", PropertyType.STRING, Interp.STEP, "")
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_offset.x", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_offset.y", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_offset.z", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .section("orientation").group("look_at"));
        add(objects, animated("look_at_offset.space", PropertyType.ENUM, Interp.STEP, "world")
                .enumValues("world", "base").section("orientation").group("look_at"));
        add(objects, animated("look_at_freeze", PropertyType.BOOL, Interp.STEP, false)
                .section("orientation").group("look_at"));
        add(objects, value("orient", PropertyType.ENUM, "manual")
                .enumValues("manual", "tangent").section("orientation").group("orientation"));
        add(objects, animated("yaw_offset", PropertyType.FLOAT, Interp.ANGLE, 0f)
                .unit("degree").requires("orient=tangent")
                .section("orientation").group("orientation"));
        add(objects, animated("pitch_offset", PropertyType.FLOAT, Interp.ANGLE, 0f)
                .unit("degree").requires("orient=tangent")
                .section("orientation").group("orientation"));

        // 光学与 lane 合成
        add(objects, animated("fov", PropertyType.FLOAT, Interp.LINEAR, 70f).section("camera"));
        add(objects, animated("zoom", PropertyType.FLOAT, Interp.GEOMETRIC, 1f).section("camera"));
        add(objects, animated("opacity", PropertyType.FLOAT, Interp.LINEAR, 1f).section("composite"));
        add(objects, animated("dest.x", PropertyType.FLOAT, Interp.LINEAR, 0f).section("composite"));
        add(objects, animated("dest.y", PropertyType.FLOAT, Interp.LINEAR, 0f).section("composite"));
        add(objects, animated("dest.w", PropertyType.FLOAT, Interp.LINEAR, 1f).section("composite"));
        add(objects, animated("dest.h", PropertyType.FLOAT, Interp.LINEAR, 1f).section("composite"));
        add(objects, animated("source.x", PropertyType.FLOAT, Interp.LINEAR, 0f).section("composite"));
        add(objects, animated("source.y", PropertyType.FLOAT, Interp.LINEAR, 0f).section("composite"));
        add(objects, animated("source.w", PropertyType.FLOAT, Interp.LINEAR, 1f).section("composite"));
        add(objects, animated("source.h", PropertyType.FLOAT, Interp.LINEAR, 1f).section("composite"));
        add(objects, animated("mask_x", PropertyType.FLOAT, Interp.LINEAR, 0.5f).section("composite"));
        add(objects, animated("mask_y", PropertyType.FLOAT, Interp.LINEAR, 0.5f).section("composite"));
        add(objects, animated("mask_width", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .min(0).section("composite"));
        add(objects, animated("mask_height", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .min(0).section("composite"));
        add(objects, animated("mask_radius", PropertyType.FLOAT, Interp.LINEAR, 0.5f)
                .min(0).section("composite"));
        add(objects, value("mask_shape", PropertyType.ENUM, "full")
                .enumValues("full", "rect", "circle").section("composite"));
        add(objects, value("dimension", PropertyType.STRING, "").section("composite"));

        // lane 级调色：57 通道，名字/缺省与 camera() 的 kfs 完全一致
        for (String channel : CAMERA_COLOR_CHANNELS) {
            add(objects, animated(channel, PropertyType.FLOAT, Interp.LINEAR, channelDefault(channel))
                    .min(channelMin(channel)).max(channelMax(channel)).section("color"));
        }

        // 调色曲线（value-only 结构）：曲线定义一次、不随时间变，顺序 = 旧 clip 级声明顺序
        for (String curve : List.of("rgb_curve", "r_curve", "g_curve", "b_curve",
                "hv_h_curve", "hv_s_curve", "hv_l_curve", "lv_s_curve", "sv_s_curve", "sv_l_curve")) {
            add(objects, value(curve, PropertyType.STRUCT, null).section("color"));
        }

        // 相机参数运动模型（value-only 结构）
        for (String channel : List.of("motion_position", "motion_yaw", "motion_pitch",
                "motion_roll", "motion_fov", "motion_zoom")) {
            add(objects, value(channel, PropertyType.STRUCT, null).section("motion"));
        }

        // 手持呼吸（value-only）
        add(objects, value("cam_breath_enabled", PropertyType.BOOL, false).section("breath"));
        add(objects, value("cam_breath_intensity", PropertyType.FLOAT, 0.05f).section("breath"));
        add(objects, value("cam_breath_seed", PropertyType.INT, 0).section("breath"));
        add(objects, value("cam_breath_type", PropertyType.ENUM, "perlin")
                .enumValues("perlin", "perlin_axis", "sine", "trauma").section("breath"));
        add(objects, value("cam_breath_speed", PropertyType.FLOAT, 1f).section("breath"));
        add(objects, value("cam_breath_trauma", PropertyType.FLOAT, 1f).section("breath"));
        add(objects, value("cam_breath_decay", PropertyType.FLOAT, 0.5f).section("breath"));

        // 过渡（value-only）
        add(objects, value("transition", PropertyType.ENUM, "cut")
                .enumValues("cut", "morph").section("transition"));
        add(objects, value("transition_duration", PropertyType.FLOAT, 0.5f)
                .min(0).section("transition"));
        add(objects, value("transition_curve", PropertyType.STRUCT, null).section("transition"));
        add(objects, value("transition_params", PropertyType.STRUCT, null).section("transition"));
        add(objects, value("transition_interrupt", PropertyType.ENUM, "blend")
                .enumValues("blend", "snap").section("transition"));

        // 覆盖链 Modifier（value-only）
        add(objects, value("modifier", PropertyType.STRUCT, null).section("modifier"));
        add(objects, value("suppress_modifiers", PropertyType.BOOL, false).section("modifier"));

        // 选择器策略（通用 + 7 个调用点覆盖）
        add(objects, animated("selector_refresh", PropertyType.FLOAT, Interp.STEP, 1f).section("selector"));
        add(objects, animated("selector_switch_while_alive", PropertyType.BOOL, Interp.STEP, true)
                .section("selector"));
        add(objects, animated("selector_switch_interval", PropertyType.FLOAT, Interp.STEP, 1f)
                .section("selector"));
        add(objects, animated("selector_switch_smooth", PropertyType.FLOAT, Interp.STEP, 0f)
                .section("selector"));
        add(objects, animated("selector_anchor", PropertyType.ENUM, Interp.STEP, "camera")
                .enumValues("camera", "player", "target", "origin").section("selector"));
        add(objects, animated("selector_pick", PropertyType.ENUM, Interp.STEP, "first")
                .enumValues("first", "nearest", "alive").section("selector"));
        for (String callpoint : ObjectCallpoints.NEW_CALLPOINTS) {
            add(objects, animated(ObjectCallpoints.field("selector_refresh", callpoint),
                    PropertyType.FLOAT, Interp.STEP, null).section("selector"));
            add(objects, animated(ObjectCallpoints.field("selector_switch_while_alive", callpoint),
                    PropertyType.BOOL, Interp.STEP, null).section("selector"));
            add(objects, animated(ObjectCallpoints.field("selector_switch_interval", callpoint),
                    PropertyType.FLOAT, Interp.STEP, null).section("selector"));
            add(objects, animated(ObjectCallpoints.field("selector_switch_smooth", callpoint),
                    PropertyType.FLOAT, Interp.STEP, null).section("selector"));
            add(objects, animated(ObjectCallpoints.field("selector_anchor", callpoint),
                    PropertyType.ENUM, Interp.STEP, null)
                    .enumValues("camera", "player", "target", "origin").section("selector"));
            add(objects, animated(ObjectCallpoints.field("selector_pick", callpoint),
                    PropertyType.ENUM, Interp.STEP, null)
                    .enumValues("first", "nearest", "alive").section("selector"));
        }

        return objects;
    }

    private static Map<String, ObjectDef> letterboxObjects() {
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        add(objects, animated("aspect_ratio", PropertyType.FLOAT, Interp.LINEAR, 2.35f)
                .min(0).section("letterbox"));
        add(objects, animated("opacity", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .min(0).max(1).section("letterbox"));
        return objects;
    }

    private static Map<String, ObjectDef> audioObjects() {
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        add(objects, animated("volume", PropertyType.FLOAT, Interp.LINEAR, 1f).section("audio"));
        add(objects, animated("x", PropertyType.FLOAT, Interp.LINEAR, 0f).section("audio"));
        add(objects, animated("y", PropertyType.FLOAT, Interp.LINEAR, 0f).section("audio"));
        add(objects, animated("z", PropertyType.FLOAT, Interp.LINEAR, 0f).section("audio"));

        add(objects, value("sound", PropertyType.STRING, null).required(true).section("audio"));
        add(objects, value("source", PropertyType.ENUM, "file")
                .enumValues("file", "minecraft").section("audio"));
        add(objects, value("pitch", PropertyType.FLOAT, 1f).section("audio"));
        add(objects, value("file_loop", PropertyType.BOOL, false).section("audio"));
        add(objects, value("fade_in", PropertyType.FLOAT, 0f).min(0).section("audio"));
        add(objects, value("fade_out", PropertyType.FLOAT, 0f).min(0).section("audio"));
        add(objects, value("attenuation", PropertyType.ENUM, "linear")
                .enumValues("none", "linear").section("audio"));
        add(objects, value("position_mode", PropertyType.ENUM, "relative")
                .enumValues("relative", "absolute").section("audio"));
        add(objects, value("category", PropertyType.ENUM, "music")
                .enumValues("music", "ambient").section("audio"));
        return objects;
    }

    private static Map<String, ObjectDef> eventObjects() {
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        add(objects, ObjectDef.builder("event", PropertyMode.EVENT, PropertyType.EVENT_RECORD)
                .section("event"));
        add(objects, value("event_type", PropertyType.STRING, "command").section("event"));
        return objects;
    }

    private static Map<String, ObjectDef> modEventObjects() {
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        add(objects, ObjectDef.builder("mod_event", PropertyMode.EVENT, PropertyType.EVENT_RECORD)
                .section("event"));
        add(objects, value("event_type", PropertyType.STRING, "mod_event").section("event"));
        add(objects, value("data", PropertyType.STRUCT, null).section("event"));
        return objects;
    }

    private static Map<String, ObjectDef> overlayObjects() {
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        add(objects, animated("x", PropertyType.FLOAT, Interp.LINEAR, 0.5f).section("layout"));
        add(objects, animated("y", PropertyType.FLOAT, Interp.LINEAR, 0.5f).section("layout"));
        add(objects, animated("anchor_x", PropertyType.FLOAT, Interp.LINEAR, 0.5f).section("layout"));
        add(objects, animated("anchor_y", PropertyType.FLOAT, Interp.LINEAR, 0.5f).section("layout"));
        add(objects, animated("scale_x", PropertyType.FLOAT, Interp.LINEAR, 1f).section("layout"));
        add(objects, animated("scale_y", PropertyType.FLOAT, Interp.LINEAR, 1f).section("layout"));
        add(objects, animated("source.x", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .appliesTo("image").section("source"));
        add(objects, animated("source.y", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .appliesTo("image").section("source"));
        add(objects, animated("source.w", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .appliesTo("image").section("source"));
        add(objects, animated("source.h", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .appliesTo("image").section("source"));
        add(objects, animated("opacity", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .min(0).max(1).section("composite"));
        add(objects, animated("font_scale", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .appliesTo("subtitle").section("text"));
        add(objects, animated("color.r", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .appliesTo("fade").section("color"));
        add(objects, animated("color.g", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .appliesTo("fade").section("color"));
        add(objects, animated("color.b", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .appliesTo("fade").section("color"));
        add(objects, animated("mask_x", PropertyType.FLOAT, Interp.LINEAR, 0.5f)
                .appliesTo("fade").section("mask"));
        add(objects, animated("mask_y", PropertyType.FLOAT, Interp.LINEAR, 0.5f)
                .appliesTo("fade").section("mask"));
        add(objects, animated("mask_width", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .min(0).appliesTo("fade").section("mask"));
        add(objects, animated("mask_height", PropertyType.FLOAT, Interp.LINEAR, 1f)
                .min(0).appliesTo("fade").section("mask"));
        add(objects, animated("mask_radius", PropertyType.FLOAT, Interp.LINEAR, 0.5f)
                .min(0).appliesTo("fade").section("mask"));
        add(objects, animated("gradient_angle", PropertyType.FLOAT, Interp.LINEAR, 0f)
                .appliesTo("fade").section("gradient"));
        add(objects, animated("gradient_center_x", PropertyType.FLOAT, Interp.LINEAR, 0.5f)
                .appliesTo("fade").section("gradient"));
        add(objects, animated("gradient_center_y", PropertyType.FLOAT, Interp.LINEAR, 0.5f)
                .appliesTo("fade").section("gradient"));
        add(objects, animated("gradient_radius", PropertyType.FLOAT, Interp.LINEAR, 0.5f)
                .min(0).appliesTo("fade").section("gradient"));

        add(objects, value("layer_type", PropertyType.ENUM, "fade")
                .enumValues("fade", "image", "subtitle").required(true).section("layer"));
        add(objects, value("color", PropertyType.STRING, "#000000").section("layer"));
        add(objects, value("gradient", PropertyType.STRUCT, null)
                .appliesTo("fade").section("layer"));
        add(objects, value("mask_shape", PropertyType.ENUM, "full")
                .enumValues("full", "rect", "circle").appliesTo("fade").section("layer"));
        add(objects, value("blend_mode", PropertyType.ENUM, "normal")
                .enumValues("normal", "multiply", "screen", "soft_light", "overlay")
                .appliesTo("fade").section("layer"));
        add(objects, value("path", PropertyType.STRING, "").section("layer"));
        add(objects, value("text", PropertyType.STRING, "").section("layer"));
        add(objects, value("z_index", PropertyType.INT, 10).section("layer"));
        return objects;
    }

    private static Map<String, ObjectDef> adjustObjects() {
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        for (String channel : ADJUST_CHANNELS) {
            add(objects, animated(channel, PropertyType.FLOAT, Interp.LINEAR, channelDefault(channel))
                    .min(channelMin(channel)).max(channelMax(channel)).section("color"));
        }

        for (String curve : List.of("rgb_curve", "r_curve", "g_curve", "b_curve",
                "hv_h_curve", "hv_s_curve", "hv_l_curve", "lv_s_curve", "sv_s_curve", "sv_l_curve")) {
            add(objects, value(curve, PropertyType.STRUCT, null).section("curve"));
        }
        add(objects, value("lut", PropertyType.STRING, null).section("lut"));
        add(objects, value("lut_input_gamma", PropertyType.FLOAT, 1f).min(0).section("lut"));
        add(objects, value("blend_mode", PropertyType.ENUM, "normal")
                .enumValues("normal", "multiply", "screen", "soft_light", "overlay").section("blend"));
        return objects;
    }

    /** CAMERA 自带调色通道（57 = 47 标量 + 10 曲线强度；顺序 = 旧 kfs 登记顺序）。 */
    private static final List<String> CAMERA_COLOR_CHANNELS = List.of(
            "exposure", "contrast", "highlights", "shadows", "whites", "blacks",
            "hue", "saturation", "vibrance", "lightness",
            "temperature", "tint",
            "red", "green", "blue",
            "mix_rr", "mix_rg", "mix_rb", "mix_gr", "mix_gg", "mix_gb", "mix_br", "mix_bg", "mix_bb",
            "curve_strength", "r_curve_strength", "g_curve_strength", "b_curve_strength",
            "lift_r", "lift_g", "lift_b", "gamma_r", "gamma_g", "gamma_b", "gain_r", "gain_g", "gain_b",
            "hv_h_strength", "hv_s_strength", "hv_l_strength", "lv_s_strength", "sv_s_strength", "sv_l_strength",
            "hue_red", "sat_red", "hue_yellow", "sat_yellow", "hue_green", "sat_green",
            "hue_cyan", "sat_cyan", "hue_blue", "sat_blue", "hue_magenta", "sat_magenta",
            "grayscale", "invert");

    /** ADJUST 通道（59 = CAMERA 57 + lut_strength + blend_amount；顺序 = 旧 kfs 登记顺序）。 */
    private static final List<String> ADJUST_CHANNELS = List.of(
            "exposure", "contrast", "highlights", "shadows", "whites", "blacks",
            "hue", "saturation", "vibrance", "lightness",
            "temperature", "tint",
            "red", "green", "blue",
            "mix_rr", "mix_rg", "mix_rb", "mix_gr", "mix_gg", "mix_gb", "mix_br", "mix_bg", "mix_bb",
            "curve_strength", "r_curve_strength", "g_curve_strength", "b_curve_strength",
            "lift_r", "lift_g", "lift_b", "gamma_r", "gamma_g", "gamma_b", "gain_r", "gain_g", "gain_b",
            "lut_strength",
            "hv_h_strength", "hv_s_strength", "hv_l_strength", "lv_s_strength", "sv_s_strength", "sv_l_strength",
            "hue_red", "sat_red", "hue_yellow", "sat_yellow", "hue_green", "sat_green",
            "hue_cyan", "sat_cyan", "hue_blue", "sat_blue", "hue_magenta", "sat_magenta",
            "grayscale", "invert", "blend_amount");

    /** 缺省 1 的强度类通道（ADJUST 含 lut_strength / blend_amount）。 */
    private static final Set<String> DEFAULT_ONE_CHANNELS = Set.of(
            "curve_strength", "r_curve_strength", "g_curve_strength", "b_curve_strength",
            "hv_h_strength", "hv_s_strength", "hv_l_strength", "lv_s_strength", "sv_s_strength", "sv_l_strength",
            "lut_strength", "blend_amount");

    /** 取值范围 [0,1] 的通道（强度、风格化与层级混合）。 */
    private static final Set<String> ZERO_ONE_CHANNELS = Set.of(
            "curve_strength", "r_curve_strength", "g_curve_strength", "b_curve_strength",
            "hv_h_strength", "hv_s_strength", "hv_l_strength", "lv_s_strength", "sv_s_strength", "sv_l_strength",
            "lut_strength", "blend_amount", "grayscale", "invert");

    private static Object channelDefault(String channel) {
        return DEFAULT_ONE_CHANNELS.contains(channel) ? 1f : 0f;
    }

    private static float channelMin(String channel) {
        if ("exposure".equals(channel)) {
            return -5f;
        }
        return ZERO_ONE_CHANNELS.contains(channel) ? 0f : -1f;
    }

    private static float channelMax(String channel) {
        return "exposure".equals(channel) ? 5f : 1f;
    }

}
