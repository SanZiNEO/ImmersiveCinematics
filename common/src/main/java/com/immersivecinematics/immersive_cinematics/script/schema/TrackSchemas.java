package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.TrackType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各轨道类型的 Java 字段元数据（0.3.5 第5轮 5B）。
 * <p>
 * 数据源为 Java 字段元数据；顺序即编辑器渲染顺序。
 */
public final class TrackSchemas {

    private TrackSchemas() {}

    public static Map<TrackType, TrackTypeSchema> all() {
        Map<TrackType, TrackTypeSchema> map = new LinkedHashMap<>();
        map.put(TrackType.CAMERA, camera());
        map.put(TrackType.LETTERBOX, letterbox());
        map.put(TrackType.AUDIO, audio());
        map.put(TrackType.EVENT, event());
        map.put(TrackType.MOD_EVENT, modEvent());
        map.put(TrackType.OVERLAY, overlay());
        map.put(TrackType.ADJUST, adjust());
        return map;
    }

    private static TrackTypeSchema camera() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("transition", new FieldDef("enum", "cut", false, List.of("cut", "morph")));
        clips.put("transition_duration", new FieldDef("float", 0.5f));
        clips.put("curve", new FieldDef("bezier_curve", null));
        clips.put("dimension", new FieldDef("string", ""));
        clips.put("loop", new FieldDef("bool", false));
        clips.put("loop_count", new FieldDef("int", -1));
        clips.put("loop_mode", new FieldDef("enum", "repeat", false, List.of("repeat", "pingpong")));
        clips.put("cam_breath_enabled", new FieldDef("bool", false));
        clips.put("cam_breath_intensity", new FieldDef("float", 0.05f));
        clips.put("cam_breath_seed", new FieldDef("int", 0));
        clips.put("cam_breath_type", new FieldDef("enum", "perlin", false,
                List.of("perlin", "perlin_axis", "sine", "trauma")));
        clips.put("cam_breath_speed", new FieldDef("float", 1.0f));
        clips.put("cam_breath_trauma", new FieldDef("float", 1.0f));
        clips.put("cam_breath_decay", new FieldDef("float", 0.5f));
        clips.put("orient", new FieldDef("enum", "manual", false, List.of("manual", "tangent")));
        clips.put("yaw_offset", new FieldDef("float", 0f));
        clips.put("pitch_offset", new FieldDef("float", 0f));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("position", new FieldDef("position", null, true));
        kfs.put("position_mode", new FieldDef("enum", "relative", false, List.of("relative", "absolute")));
        // 基准坐标系偏移的基准点来源（fwd/up/right 用）：留空 = follow 实体 / 玩家
        kfs.put("facing_origin", new FieldDef("string", ""));
        kfs.put("facing_origin_x", new FieldDef("float", 0f));
        kfs.put("facing_origin_y", new FieldDef("float", 0f));
        kfs.put("facing_origin_z", new FieldDef("float", 0f));
        // 基准朝向来源：留空 = 基准点自身朝向；填选择器 = 基准点 → 该目标的连线方向
        kfs.put("facing_target", new FieldDef("string", ""));
        kfs.put("follow", new FieldDef("enum", "none", false, List.of("none", "entity")));
        kfs.put("follow_selector", new FieldDef("string", "@p"));
        kfs.put("look_at", new FieldDef("enum", "none", false, List.of("none", "coordinate", "entity")));
        kfs.put("look_at_selector", new FieldDef("string", "@p"));
        kfs.put("look_at_target_x", new FieldDef("float", null));
        kfs.put("look_at_target_y", new FieldDef("float", null));
        kfs.put("look_at_target_z", new FieldDef("float", null));
        kfs.put("look_at_target_structure", new FieldDef("string", ""));
        kfs.put("look_at_target", new FieldDef("map", null));
        // 选择器目标锁定策略（作用于该关键帧所有 selector 字段）
        kfs.put("selector_refresh", new FieldDef("float", 1.0f));
        kfs.put("selector_switch_while_alive", new FieldDef("bool", true));
        // 切换间隔：两次真实切换之间的最小间隔（与扫描频率无关）；缺省 = selector_refresh
        kfs.put("selector_switch_interval", new FieldDef("float", 1.0f));
        kfs.put("selector_switch_smooth", new FieldDef("float", 0.0f));
        kfs.put("yaw_base", new FieldDef("enum", "world", false, List.of("world", "entity", "line")));
        kfs.put("pitch_base", new FieldDef("enum", "world", false, List.of("world", "entity", "line")));
        kfs.put("yaw_base_selector", new FieldDef("string", "@p"));
        kfs.put("yaw_base_from", new FieldDef("string", ""));
        kfs.put("yaw_base_to", new FieldDef("string", ""));
        kfs.put("yaw", new FieldDef("float", 0));
        kfs.put("pitch", new FieldDef("float", 0));
        kfs.put("roll", new FieldDef("float", 0));
        kfs.put("fov", new FieldDef("float", 70));
        kfs.put("zoom", new FieldDef("float", 1.0f));
        // 合成参数（0.3.6 camera-composition）：挂在本 CAMERA clip 的关键帧上，随关键帧插值
        // （渲染侧由 client/lane/ScriptLaneDriver 逐帧消费）；此处登记字段，供编辑器表单与校验使用。
        kfs.put("opacity", new FieldDef("float", 1.0f));
        kfs.put("dest", new FieldDef("map", null));
        kfs.put("source", new FieldDef("map", null));

        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema letterbox() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("aspect_ratio", new FieldDef("float", 2.35f));
        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema audio() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("sound", new FieldDef("string", null, true));
        clips.put("source", new FieldDef("enum", "file", false, List.of("file", "minecraft")));
        clips.put("volume", new FieldDef("float", 1.0f));
        clips.put("pitch", new FieldDef("float", 1.0f));
        clips.put("loop", new FieldDef("bool", false));
        clips.put("fade_in", new FieldDef("float", 0));
        clips.put("fade_out", new FieldDef("float", 0));
        clips.put("attenuation", new FieldDef("enum", "linear", false, List.of("none", "linear", "inverse")));
        clips.put("position_mode", new FieldDef("enum", "relative", false, List.of("relative", "absolute")));
        clips.put("category", new FieldDef("enum", "music", false, List.of("music", "ambient")));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("volume", new FieldDef("float", 1.0f));
        kfs.put("x", new FieldDef("float", 0));
        kfs.put("y", new FieldDef("float", 0));
        kfs.put("z", new FieldDef("float", 0));

        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema event() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("event_type", new FieldDef("string", "command"));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("event_type", new FieldDef("string", "command"));
        kfs.put("command", new FieldDef("string", ""));
        kfs.put("position", new FieldDef("map", null));

        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema modEvent() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("event_type", new FieldDef("string", null, true));
        clips.put("data", new FieldDef("map", null));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("event_type", new FieldDef("string", "mod_event"));
        kfs.put("data", new FieldDef("map", null));

        return new TrackTypeSchema(clips, kfs);
    }

    private static TrackTypeSchema overlay() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("layer_type", new FieldDef("enum", "fade", true, List.of("fade", "image", "subtitle")));
        clips.put("color", new FieldDef("string", "#000000"));
        clips.put("path", new FieldDef("string", ""));
        clips.put("text", new FieldDef("string", ""));
        // 顺序：统一默认 10（0.3.6 起单一取值；letterbox 的 z = 0 是内置常量，不在脚本口径内）
        clips.put("z_index", new FieldDef("int", 10));

        // 统一参数字段表（0.3.6，定稿见 plans/0.3.6/variable-frame.md §3.1）——全部是关键帧字段
        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        // 位置：参考画布归一化（0~1，元素中心；不钳制，可越界）
        kfs.put("x", new FieldDef("float", 0.5f));
        kfs.put("y", new FieldDef("float", 0.5f));
        // 锚点：元素自身归一化（0 = 左/上缘，1 = 右/下缘），缩放绕点
        kfs.put("anchor_x", new FieldDef("float", 0.5f));
        kfs.put("anchor_y", new FieldDef("float", 0.5f));
        // 缩放：相对逐类基准尺寸（image = 原图像素 ÷ 参考分辨率；subtitle = 当前字号下文字块）
        kfs.put("scale_x", new FieldDef("float", 1.0f));
        kfs.put("scale_y", new FieldDef("float", 1.0f));
        // 取材：素材归一化 {x,y,w,h}，缺省整幅（仅 image 消费）
        kfs.put("source", new FieldDef("map", null));
        // 适配：fit / fill / stretch，缺省 fit（离散枚举 → 步进取值）
        kfs.put("fit", new FieldDef("enum", "fit", false, List.of("fit", "fill", "stretch")));
        // 不透明度：缺省 1.0 = 不透明（fade/letterbox 也有效）
        kfs.put("opacity", new FieldDef("float", 1.0f));
        // 字幕专用：字号倍数（1 = 原版 9px），改变文字块基准尺寸，与 scale_x/y 叠加
        kfs.put("font_scale", new FieldDef("float", 1));

        return new TrackTypeSchema(clips, kfs);
    }

    /**
     * ADJUST 轨道（画面颜色调整，0.3.6：master 标量组 + lane 级调整 + RGB 复合曲线 + 每通道曲线）。
     *
     * <p>17 个标量通道全部是<b>关键帧字段</b>，缺省全 0 = 无效果
     * （关键帧把通道写回 0 就是该项淡出，不需要 enabled 开关）。</p>
     *
     * <p><b>clip 级字段 = 作用域 + 曲线</b>（不随时间变，故挂 clip）：
     * {@code scope}（{@code master} 缺省 / {@code lane}）与 {@code lane}（{@code scope=lane} 时的目标相机轨序号：
     * 0 起、按 timeline 中 CAMERA 轨出现顺序）；{@code curve} = RGB 复合曲线、{@code r_curve} / {@code g_curve} /
     * {@code b_curve} = 每通道曲线（R / G / B 各一条），都是控制点数组
     * {@code [[x,y], ...]}（x 严格递增、各 0~1、≥2 点；结构字段，缺省 = 无曲线——与 CAMERA 轨同名的
     * {@code curve}（{@code bezier_curve}）按轨道类型分派，互不影响）。master = 作用于合成输出（最终显示画面）；
     * lane = 作用于该相机轨的画面，在该 lane 渲染完成之后、合成之前
     * （见 {@code plans/0.3.6/screen-color-adjust.md} 步骤 5、「增量：RGB 复合曲线（形态 b）」与
     * 「增量：每通道曲线（R / G / B）」）。</p>
     *
     * <p>顺序 = 着色器操作栈顺序（{@code assets/minecraft/shaders/core/ic_color_adjust.fsh}）
     * = {@code ColorAdjustParams} 的分量顺序；区间由 {@code ScriptValidator} 校验。
     * <b>本表顺序是硬约定</b>：三处（schema / params / shader）逐项对应，增删通道必须同步。</p>
     */
    private static TrackTypeSchema adjust() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        clips.put("scope", new FieldDef("enum", "master", false, List.of("master", "lane")));
        clips.put("lane", new FieldDef("int", 0));
        // 曲线组（形态 b）：曲线定义一次（clip 级结构字段），关键帧只控各自强度
        clips.put("curve", new FieldDef("color_curve", null));
        clips.put("r_curve", new FieldDef("color_curve", null));
        clips.put("g_curve", new FieldDef("color_curve", null));
        clips.put("b_curve", new FieldDef("color_curve", null));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        // 基础校色（复合 RGB）
        kfs.put("exposure", new FieldDef("float", 0f));       // -5 ~ 5（EV 档，×2^EV）
        kfs.put("contrast", new FieldDef("float", 0f));       // -1 ~ 1（以中灰 0.5 为轴）
        kfs.put("highlights", new FieldDef("float", 0f));     // -1 ~ 1（亮部）
        kfs.put("shadows", new FieldDef("float", 0f));        // -1 ~ 1（暗部）
        kfs.put("whites", new FieldDef("float", 0f));         // -1 ~ 1（白场端点）
        kfs.put("blacks", new FieldDef("float", 0f));         // -1 ~ 1（黑场端点）
        // 完整 HSL（H / S / L 三通道；操作栈顺序 = hue 旋转 → 饱和度 / 自然饱和度 → lightness）
        kfs.put("hue", new FieldDef("float", 0f));            // -1 ~ 1（色相旋转：±1 = ±180°）
        kfs.put("saturation", new FieldDef("float", 0f));     // -1 ~ 1（-1 = 全灰、1 = 双倍）
        kfs.put("vibrance", new FieldDef("float", 0f));       // -1 ~ 1（自然饱和度）
        kfs.put("lightness", new FieldDef("float", 0f));      // -1 ~ 1（HSL 的 L：正 = 向白推、负 = 向黑压）
        // 白平衡
        kfs.put("temperature", new FieldDef("float", 0f));    // -1 ~ 1（正 = 暖 / 偏红）
        kfs.put("tint", new FieldDef("float", 0f));           // -1 ~ 1（正 = 品红、负 = 绿）
        // RGBA 通道拆分（R/G/B 每通道系数；操作栈位置：色温/色调之后、钳制 [0,1] 之前）
        kfs.put("red", new FieldDef("float", 0f));            // -1 ~ 1（红通道乘性系数：-1 = 归零、-0.5 = 减半、+1 = 双倍）
        kfs.put("green", new FieldDef("float", 0f));          // -1 ~ 1（绿通道，同上）
        kfs.put("blue", new FieldDef("float", 0f));           // -1 ~ 1（蓝通道，同上）
        // 曲线组（形态 b）：曲线混合强度（曲线本身是 clip 级字段 curve / r_curve / g_curve / b_curve）
        kfs.put("curve_strength", new FieldDef("float", 1f));   // 0 ~ 1（缺省 1 = 曲线全量生效；无曲线时忽略）
        kfs.put("r_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（R 每通道曲线，同上）
        kfs.put("g_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（G 每通道曲线，同上）
        kfs.put("b_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（B 每通道曲线，同上）
        // 风格化（本身即强度）
        kfs.put("grayscale", new FieldDef("float", 0f));      // 0 ~ 1（灰度混合量）
        kfs.put("invert", new FieldDef("float", 0f));         // 0 ~ 1（反相混合量）

        return new TrackTypeSchema(clips, kfs);
    }

}
