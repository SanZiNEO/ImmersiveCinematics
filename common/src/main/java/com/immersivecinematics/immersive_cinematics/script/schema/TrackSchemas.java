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

    /**
     * CAMERA 轨道（相机运动 + 合成 + 自带调色）。
     * <p>
     * <b>相机片段自带调色</b>（0.3.6：每个 lane / 图层各自持有调整）：clip 级 10 条曲线
     * （{@code rgb_curve} 复合 + {@code r_curve} / {@code g_curve} / {@code b_curve} 每通道 +
     * {@code hv_h_curve} / {@code hv_s_curve} / {@code hv_l_curve} / {@code lv_s_curve} /
     * {@code sv_s_curve} / {@code sv_l_curve} 六条 hue 曲线）+ 关键帧 57 个调色通道
     * （与 ADJUST 轨同名同缺省；ADJUST 轨另有 {@code blend_mode} / {@code blend_amount}
     * 两个层级混合字段，属 master 专用，相机片段上写会被 validator 拦下）。调色作用于该相机轨产出的 lane，在该 lane
     * <b>渲染完成之后、合成之前</b>生效（与 {@code dest} / {@code source} / {@code opacity} 同层，
     * 见 {@code plans/0.3.6/screen-color-adjust.md} 步骤 1、{@code plans/0.3.6/camera-composition.md}）。
     * 整体画面的调色走 ADJUST 轨（master）。</p>
     */
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
        // 调色：每个相机片段直接携带自己的调色（clip 级 10 条曲线；关键帧 45 通道控强度 / 标量）
        // 曲线组（形态 b）：曲线定义一次（clip 级结构字段），关键帧只控各自强度
        clips.put("rgb_curve", new FieldDef("color_curve", null));
        clips.put("r_curve", new FieldDef("color_curve", null));
        clips.put("g_curve", new FieldDef("color_curve", null));
        clips.put("b_curve", new FieldDef("color_curve", null));
        // 六条 hue 曲线（DaVinci 曲线页；键 = hue 或 sat / lum，在 HSL 块内生效）
        clips.put("hv_h_curve", new FieldDef("color_curve", null));   // hue → hue
        clips.put("hv_s_curve", new FieldDef("color_curve", null));   // hue → saturation
        clips.put("hv_l_curve", new FieldDef("color_curve", null));   // hue → luminance
        clips.put("lv_s_curve", new FieldDef("color_curve", null));   // luminance → saturation
        clips.put("sv_s_curve", new FieldDef("color_curve", null));   // saturation → saturation
        clips.put("sv_l_curve", new FieldDef("color_curve", null));   // saturation → luminance

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
        // 选择器目标锁定策略：通用字段 = 该关键帧所有调用点的缺省回落（语义见 EntityTargetResolver.SelectorPolicy）。
        // 调用点专属字段名 = <字段>_<调用点>（调用点名见 EntityTargetResolver.SELECTOR_CALLPOINTS），
        // 缺省 null = 回落通用字段；不写任何调用点字段 = 旧行为（只用通用字段）。
        kfs.put("selector_refresh", new FieldDef("float", 1.0f));
        kfs.put("selector_switch_while_alive", new FieldDef("bool", true));
        // 切换间隔：两次真实切换之间的最小间隔（与扫描频率无关）；缺省 = selector_refresh
        kfs.put("selector_switch_interval", new FieldDef("float", 1.0f));
        kfs.put("selector_switch_smooth", new FieldDef("float", 0.0f));
        // 选择器锚点：就近排序（sort=nearest）的参考点，也是服务端解析请求的原点。
        // camera（缺省）= 相机位置（旧行为）；player = 玩家脚底；target = 该调用点已解析锁的实体脚底
        // （未解析到目标时回落 camera）；origin = position.relative_origin 点源坐标（固定坐标 / 结构中心 /
        // 方块中心 / 玩家激活位置 / 实体选择器，与位置基准同一解析；position 缺失或点源解析失败回落 camera）。
        // 取值与回落链见 EntityTargetResolver.selectorAnchor。
        kfs.put("selector_anchor", new FieldDef("string", "camera"));
        // 候选择一策略：first（缺省）= 现状行为（本地 @e 就近取首个可用，服务端按回传序取首个可用）；
        // nearest = 客户端可用候选内取距锚点最近；alive = 按候选返回序取首个存活。
        // 取值与回落链见 EntityTargetResolver.selectorPick。
        kfs.put("selector_pick", new FieldDef("string", "first"));
        // 调用点专属覆盖（8 个调用点 × 6 个选择器字段）；名单须与 EntityTargetResolver.SELECTOR_CALLPOINTS 一致
        for (String callpoint : new String[]{"follow", "look_at", "look_at_target", "yaw_base",
                "yaw_base_from", "yaw_base_to", "facing_origin", "facing_target"}) {
            kfs.put("selector_refresh_" + callpoint, new FieldDef("float", null));
            kfs.put("selector_switch_while_alive_" + callpoint, new FieldDef("bool", null));
            kfs.put("selector_switch_interval_" + callpoint, new FieldDef("float", null));
            kfs.put("selector_switch_smooth_" + callpoint, new FieldDef("float", null));
            kfs.put("selector_anchor_" + callpoint, new FieldDef("string", null));
            kfs.put("selector_pick_" + callpoint, new FieldDef("string", null));
        }
        // 朝向基准来源（措辞口径：身体朝向 = 实体身体水平角 yBodyRot；视线 = 实体 yRot + xRot）。
        // yaw_base = entity 取「实体身体朝向」水平角；pitch_base = entity 取「实体视线」俯仰。
        kfs.put("yaw_base", new FieldDef("enum", "world", false, List.of("world", "entity", "line")));
        kfs.put("pitch_base", new FieldDef("enum", "world", false, List.of("world", "entity", "line")));
        // yaw_base/pitch_base = entity 时的实体选择器（yaw 取身体朝向水平角、pitch 取视线俯仰）
        kfs.put("yaw_base_selector", new FieldDef("string", "@p"));
        // line 基准的两个端点；退化连线（零长度 / 纯垂直）水平角未定义，运行时被拒（回退基准 0 = world）
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

        // 调色：每个相机片段直接携带自己的调色；作用于该相机轨的 lane（渲染完、合成前）
        // 下列 57 个字段与 ADJUST 轨同名同缺省（47 个标量通道缺省 0 + 10 个曲线强度缺省 1）。
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
        // RGB 通道混合器（PS 式 3×3 矩阵；矩阵 = 单位阵 + 参数矩阵，缺省全 0 = 单位阵 = 无效果）
        kfs.put("mix_rr", new FieldDef("float", 0f));         // -1 ~ 1（输出 R ← 输入 R 的额外系数，叠加在单位阵上）
        kfs.put("mix_rg", new FieldDef("float", 0f));         // -1 ~ 1（输出 R ← 输入 G）
        kfs.put("mix_rb", new FieldDef("float", 0f));         // -1 ~ 1（输出 R ← 输入 B）
        kfs.put("mix_gr", new FieldDef("float", 0f));         // -1 ~ 1（输出 G ← 输入 R）
        kfs.put("mix_gg", new FieldDef("float", 0f));         // -1 ~ 1（输出 G ← 输入 G）
        kfs.put("mix_gb", new FieldDef("float", 0f));         // -1 ~ 1（输出 G ← 输入 B）
        kfs.put("mix_br", new FieldDef("float", 0f));         // -1 ~ 1（输出 B ← 输入 R）
        kfs.put("mix_bg", new FieldDef("float", 0f));         // -1 ~ 1（输出 B ← 输入 G）
        kfs.put("mix_bb", new FieldDef("float", 0f));         // -1 ~ 1（输出 B ← 输入 B）
        // 曲线组（形态 b）：曲线混合强度（曲线本身是 clip 级字段 rgb_curve / r_curve / g_curve / b_curve）
        kfs.put("curve_strength", new FieldDef("float", 1f));   // 0 ~ 1（缺省 1 = 曲线全量生效；无曲线时忽略）
        kfs.put("r_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（R 每通道曲线，同上）
        kfs.put("g_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（G 每通道曲线，同上）
        kfs.put("b_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（B 每通道曲线，同上）
        // Lift / Gamma / Gain 色轮（三组逐通道色轮；九个全 0 = 整步跳过）
        kfs.put("lift_r", new FieldDef("float", 0f));         // -1 ~ 1（R 阴影：正 = 抬阴影、负 = 压黑）
        kfs.put("lift_g", new FieldDef("float", 0f));         // -1 ~ 1（G 阴影，同上）
        kfs.put("lift_b", new FieldDef("float", 0f));         // -1 ~ 1（B 阴影，同上）
        kfs.put("gamma_r", new FieldDef("float", 0f));        // -1 ~ 1（R 中间调：正 = 提亮、负 = 压暗）
        kfs.put("gamma_g", new FieldDef("float", 0f));        // -1 ~ 1（G 中间调，同上）
        kfs.put("gamma_b", new FieldDef("float", 0f));        // -1 ~ 1（B 中间调，同上）
        kfs.put("gain_r", new FieldDef("float", 0f));         // -1 ~ 1（R 高光：乘性增益 = 1 + 值）
        kfs.put("gain_g", new FieldDef("float", 0f));         // -1 ~ 1（G 高光，同上）
        kfs.put("gain_b", new FieldDef("float", 0f));         // -1 ~ 1（B 高光，同上）
        // 六条 hue 曲线的混合强度（曲线本身是 clip 级字段 hv_h_curve / hv_s_curve / ... / sv_l_curve）
        kfs.put("hv_h_strength", new FieldDef("float", 1f));    // 0 ~ 1（HvH：hue → hue，缺省 1；无曲线时忽略）
        kfs.put("hv_s_strength", new FieldDef("float", 1f));    // 0 ~ 1（HvS：hue → 饱和度）
        kfs.put("hv_l_strength", new FieldDef("float", 1f));    // 0 ~ 1（HvL：hue → 亮度）
        kfs.put("lv_s_strength", new FieldDef("float", 1f));    // 0 ~ 1（LvS：亮度 → 饱和度）
        kfs.put("sv_s_strength", new FieldDef("float", 1f));    // 0 ~ 1（SvS：饱和度 → 饱和度）
        kfs.put("sv_l_strength", new FieldDef("float", 1f));    // 0 ~ 1（SvL：饱和度 → 亮度）
        // PS 式六色带微调（Hue / Sat 分色带；第 17 步 = HSL 块之后、灰度之前）：
        // 六条固定色带（红 0° / 黄 60° / 绿 120° / 青 180° / 蓝 240° / 品红 300°）各一对
        // hue（±1 = ±180° 旋转）/ sat（乘性 (1 + Δsat)）；带权重 = 升余弦锥形（±30° 支撑）
        kfs.put("hue_red", new FieldDef("float", 0f));        // -1 ~ 1（红带色相旋转，缺省 0 = 无效果）
        kfs.put("sat_red", new FieldDef("float", 0f));        // -1 ~ 1（红带饱和度增量：sat *= (1 + 值)）
        kfs.put("hue_yellow", new FieldDef("float", 0f));     // -1 ~ 1（黄带色相旋转）
        kfs.put("sat_yellow", new FieldDef("float", 0f));     // -1 ~ 1（黄带饱和度增量）
        kfs.put("hue_green", new FieldDef("float", 0f));      // -1 ~ 1（绿带色相旋转）
        kfs.put("sat_green", new FieldDef("float", 0f));      // -1 ~ 1（绿带饱和度增量）
        kfs.put("hue_cyan", new FieldDef("float", 0f));       // -1 ~ 1（青带色相旋转）
        kfs.put("sat_cyan", new FieldDef("float", 0f));       // -1 ~ 1（青带饱和度增量）
        kfs.put("hue_blue", new FieldDef("float", 0f));       // -1 ~ 1（蓝带色相旋转）
        kfs.put("sat_blue", new FieldDef("float", 0f));       // -1 ~ 1（蓝带饱和度增量）
        kfs.put("hue_magenta", new FieldDef("float", 0f));    // -1 ~ 1（品红带色相旋转）
        kfs.put("sat_magenta", new FieldDef("float", 0f));    // -1 ~ 1（品红带饱和度增量）
        // 风格化（本身即强度）
        kfs.put("grayscale", new FieldDef("float", 0f));      // 0 ~ 1（灰度混合量）
        kfs.put("invert", new FieldDef("float", 0f));         // 0 ~ 1（反相混合量）

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

        // 统一参数字段表（0.3.6，定稿见 plans/0.3.6/variable-frame.md §4.2）——全部是关键帧字段
        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        // 位置：窗口归一化（0~1，元素中心；不钳制，可越界）
        kfs.put("x", new FieldDef("float", 0.5f));
        kfs.put("y", new FieldDef("float", 0.5f));
        // 锚点：元素自身归一化（0 = 左/上缘，1 = 右/下缘），缩放绕点
        kfs.put("anchor_x", new FieldDef("float", 0.5f));
        kfs.put("anchor_y", new FieldDef("float", 0.5f));
        // 缩放：相对素材原始像素尺寸的倍数（1 = 素材 1:1 像素）
        kfs.put("scale_x", new FieldDef("float", 1.0f));
        kfs.put("scale_y", new FieldDef("float", 1.0f));
        // 取材：素材归一化 {x,y,w,h}（原点 = 素材左上角），缺省整幅（仅 image 消费）
        kfs.put("source", new FieldDef("map", null));
        // 不透明度：缺省 1.0 = 不透明（fade/letterbox 也有效）
        kfs.put("opacity", new FieldDef("float", 1.0f));
        // 字幕专用：字号倍数（1 = 原版 9px），改变文字块基准尺寸，与 scale_x/y 叠加
        kfs.put("font_scale", new FieldDef("float", 1));

        return new TrackTypeSchema(clips, kfs);
    }

    /**
     * ADJUST 轨道（画面颜色调整，0.3.6：master 标量组 + lane 级调整 + RGB 通道混合器 + RGB 复合曲线
     * + 每通道曲线 + 六条 hue 曲线 + Lift / Gamma / Gain 色轮 + PS 式六色带微调 + 层级混合模式）。
     *
     * <p>47 个标量通道全部是<b>关键帧字段</b>，缺省全 0 = 无效果
     * （关键帧把通道写回 0 就是该项淡出，不需要 enabled 开关）。其中
     * {@code mix_rr} ~ {@code mix_bb} 九个构成 <b>RGB 通道混合器</b>（PS 式 3×3 矩阵）：
     * 实际矩阵 = 单位阵 + 参数矩阵（{@code out.r = (1+mix_rr)·r + mix_rg·g + mix_rb·b}，g / b 行同式），
     * 九个全 0 = 单位阵 = 逐位恒等；{@code lift_*} / {@code gamma_*} / {@code gain_*} 九个构成
     * <b>Lift / Gamma / Gain 色轮</b>（三组逐通道）：{@code c' = c + lift·(1-c)}、
     * {@code c' = pow(max(c,0), exp2(-gamma))}、{@code c' = c·(1+gain)}，
     * 九个全 0 = 整步跳过（逐位恒等）。{@code hue_red} / {@code sat_red} ~ {@code hue_magenta} /
     * {@code sat_magenta} 十二个构成 <b>PS 式六色带微调</b>（Hue / Sat 分色带；六条固定色带各撑 ±30°、
     * 升余弦权重，先旋色相再改饱和，十二个全 0 = 整步跳过；第 17 步 = HSL 块之后、灰度之前）。</p>
     *
     * <p><b>ADJUST 轨 = 整体画面（master）；lane 级调色 = 相机片段字段</b>：
     * 本轨只作用于合成输出（最终显示画面），没有 scope / lane 字段；作用于某条相机轨产出的 lane
     * （渲染完成之后、合成之前）的调色，直接写在该 CAMERA 片段上（见 {@code camera()}）。
     * clip 级字段 = 曲线 + LUT 文件名（都不随时间变，故挂 clip）：{@code lut} = LUT 文件名
     * （{@code resource/} 目录下的 {@code .cube}，关键帧只控 {@code lut_strength} 混合强度，
     * 栈位 = LGG 之后、HSL 块之前）、{@code lut_input_gamma} = LUT 输入域适配的幂指数
     * （缺省 1 = 不变换，必须 > 0；无 LUT 时忽略）、{@code rgb_curve} = RGB 复合曲线、{@code r_curve} /
     * {@code g_curve} / {@code b_curve} = 每通道曲线（R / G / B 各一条）、{@code hv_h_curve} /
     * {@code hv_s_curve} / {@code hv_l_curve} / {@code lv_s_curve} / {@code sv_s_curve} / {@code sv_l_curve} =
     * 六条 hue 曲线（DaVinci 曲线页口径：HvH / HvS / HvL、LvS / SvS / SvL），都是控制点数组
     * {@code [[x,y], ...]}（x 严格递增、各 0~1、≥2 点；结构字段，缺省 = 无曲线——与 CAMERA 轨同名的
     * {@code curve}（{@code bezier_curve}）按轨道类型分派，互不影响）。master = 作用于合成输出（最终显示画面）
     * （见 {@code plans/0.3.6/screen-color-adjust.md} 步骤 5、「增量：RGB 通道混合器」、
     * 「增量：RGB 复合曲线（形态 b）」、「增量：每通道曲线（R / G / B）」、「增量：六条 hue 曲线」
     * 「增量：Lift / Gamma / Gain 色轮」、「增量：LUT」、「增量：PS 式六色带微调」
     * 与「增量：混合模式」）。</p>
     *
     * <p><b>层级混合（第 20 步：混合模式作用于调整层）</b>：clip 级 {@code blend_mode}
     * （枚举 {@code normal} / {@code multiply} / {@code screen} / {@code soft_light} / {@code overlay}，
     * 缺省 {@code normal} = 直替换 = 现状）+ 关键帧 {@code blend_amount}（0 ~ 1，缺省 1 = 全量生效）
     * 把<b>调整层的输出</b>（上面 19 步算出的 {@code c_adj}）与<b>基画面</b>（{@code Sampler0} 原图
     * {@code c_base}）按混合模式整体混合：{@code c = mix(c_base, blend(c_base, c_adj, mode),
     * clamp(blend_amount, 0, 1))}——<b>不是操作栈步骤</b>，而是全部步骤之后对整幅画面的层级混合
     * （调整层 vs 底下的画面）；{@code blend_amount} 写回 0 = 调整层整体透明（输出 = 基画面）。
     * 只动 RGB、alpha 逐位直通。只有 <b>ADJUST 轨（master）</b>带这两个字段（相机片段上写会被
     * validator 直接拦下，同 {@code lut} 口径）。</p>
     *
     * <p>顺序 = 着色器操作栈顺序（{@code assets/minecraft/shaders/core/ic_color_adjust.fsh}）
     * = {@code ColorAdjustParams} 的分量顺序；区间由 {@code ScriptValidator} 校验。
     * <b>本表顺序是硬约定</b>：三处（schema / params / shader）逐项对应，增删通道必须同步。</p>
     */
    private static TrackTypeSchema adjust() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        // 曲线组（形态 b）：曲线定义一次（clip 级结构字段），关键帧只控各自强度
        clips.put("rgb_curve", new FieldDef("color_curve", null));
        clips.put("r_curve", new FieldDef("color_curve", null));
        clips.put("g_curve", new FieldDef("color_curve", null));
        clips.put("b_curve", new FieldDef("color_curve", null));
        // 六条 hue 曲线（DaVinci 曲线页；键 = hue 或 sat / lum，在 HSL 块内生效）
        clips.put("hv_h_curve", new FieldDef("color_curve", null));   // hue → hue
        clips.put("hv_s_curve", new FieldDef("color_curve", null));   // hue → saturation
        clips.put("hv_l_curve", new FieldDef("color_curve", null));   // hue → luminance
        clips.put("lv_s_curve", new FieldDef("color_curve", null));   // luminance → saturation
        clips.put("sv_s_curve", new FieldDef("color_curve", null));   // saturation → saturation
        clips.put("sv_l_curve", new FieldDef("color_curve", null));   // saturation → luminance
        // LUT（clip 级文件名，resource/ 目录下的 .cube；缺省 null = 本片段不做 LUT）；
        // 关键帧只控 lut_strength，栈位 = LGG 之后、HSL 块之前
        clips.put("lut", new FieldDef("string", null));
        // LUT 输入域适配（clip 级幂指数，缺省 1 = 不变换，必须 > 0）：查表前
        // v = pow(clamp(c, 0, 1), lut_input_gamma)，以 v 为四面体查表坐标——现成 .cube 按特定素材 /
        // 色彩空间调成时，用它把游戏画面（sRGB）映射回表假设的输入域；无 LUT 时忽略
        clips.put("lut_input_gamma", new FieldDef("float", 1f));
        // 混合模式（clip 级枚举，缺省 "normal" = 直替换 = 现状）：调整层输出与基画面（Sampler0 原图）
        // 的层级混合口径——不是操作栈步骤，而是在全部 19 步算完之后整体施加
        // （c = mix(c_base, blend(c_base, c_adj, mode), blend_amount)）；只有 ADJUST 轨（master）
        // 带该字段（相机片段上写会被 validator 直接拦下，同 lut 口径）
        clips.put("blend_mode", new FieldDef("enum", "normal", false,
                List.of("normal", "multiply", "screen", "soft_light", "overlay")));

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
        // RGB 通道混合器（PS 式 3×3 矩阵；矩阵 = 单位阵 + 参数矩阵，缺省全 0 = 单位阵 = 无效果）
        // 行 = 输出通道、列 = 输入通道：out.r = (1+mix_rr)·r + mix_rg·g + mix_rb·b，g / b 行同式。
        // 操作栈位置：R/G/B 通道系数之后、复合曲线之前（故排在这里，与 fsh 栈顺序一致）
        kfs.put("mix_rr", new FieldDef("float", 0f));         // -1 ~ 1（输出 R ← 输入 R 的额外系数，叠加在单位阵上）
        kfs.put("mix_rg", new FieldDef("float", 0f));         // -1 ~ 1（输出 R ← 输入 G）
        kfs.put("mix_rb", new FieldDef("float", 0f));         // -1 ~ 1（输出 R ← 输入 B）
        kfs.put("mix_gr", new FieldDef("float", 0f));         // -1 ~ 1（输出 G ← 输入 R）
        kfs.put("mix_gg", new FieldDef("float", 0f));         // -1 ~ 1（输出 G ← 输入 G）
        kfs.put("mix_gb", new FieldDef("float", 0f));         // -1 ~ 1（输出 G ← 输入 B）
        kfs.put("mix_br", new FieldDef("float", 0f));         // -1 ~ 1（输出 B ← 输入 R）
        kfs.put("mix_bg", new FieldDef("float", 0f));         // -1 ~ 1（输出 B ← 输入 G）
        kfs.put("mix_bb", new FieldDef("float", 0f));         // -1 ~ 1（输出 B ← 输入 B）
        // 曲线组（形态 b）：曲线混合强度（曲线本身是 clip 级字段 rgb_curve / r_curve / g_curve / b_curve）
        kfs.put("curve_strength", new FieldDef("float", 1f));   // 0 ~ 1（缺省 1 = 曲线全量生效；无曲线时忽略）
        kfs.put("r_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（R 每通道曲线，同上）
        kfs.put("g_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（G 每通道曲线，同上）
        kfs.put("b_curve_strength", new FieldDef("float", 1f)); // 0 ~ 1（B 每通道曲线，同上）
        // Lift / Gamma / Gain 色轮（三组逐通道色轮；操作栈位置 = 每通道曲线之后、钳制 [0,1] 之前 → 第 10 步，
        // 故排在这里，与 fsh 栈顺序一致）：
        //   Lift（阴影） c' = c + lift·(1-c)（正 = 抬阴影、负 = 压黑；c = 1 不动）
        //   Gamma（中间调）c' = pow(max(c,0), exp2(-gamma))（0 = 指数 1 恒等；正 = 中间调提亮、负 = 压暗）
        //   Gain（高光）  c' = c·(1+gain)（正 = 乘性提亮、负 = 压暗）
        // 九个全 0 = 整步跳过（逐位恒等）。
        kfs.put("lift_r", new FieldDef("float", 0f));         // -1 ~ 1（R 阴影：正 = 抬阴影、负 = 压黑）
        kfs.put("lift_g", new FieldDef("float", 0f));         // -1 ~ 1（G 阴影，同上）
        kfs.put("lift_b", new FieldDef("float", 0f));         // -1 ~ 1（B 阴影，同上）
        kfs.put("gamma_r", new FieldDef("float", 0f));        // -1 ~ 1（R 中间调：正 = 提亮、负 = 压暗）
        kfs.put("gamma_g", new FieldDef("float", 0f));        // -1 ~ 1（G 中间调，同上）
        kfs.put("gamma_b", new FieldDef("float", 0f));        // -1 ~ 1（B 中间调，同上）
        kfs.put("gain_r", new FieldDef("float", 0f));         // -1 ~ 1（R 高光：乘性增益 = 1 + 值）
        kfs.put("gain_g", new FieldDef("float", 0f));         // -1 ~ 1（G 高光，同上）
        kfs.put("gain_b", new FieldDef("float", 0f));         // -1 ~ 1（B 高光，同上）
        // LUT 混合强度（LUT 本身是 clip 级字段 lut，第 11 步；缺省 1 = 全量生效、写回 0 = 淡出）
        kfs.put("lut_strength", new FieldDef("float", 1f));   // 0 ~ 1（无 LUT 时忽略）
        // 六条 hue 曲线的混合强度（曲线本身是 clip 级字段 hv_h_curve / hv_s_curve / ... / sv_l_curve）
        kfs.put("hv_h_strength", new FieldDef("float", 1f));    // 0 ~ 1（HvH：hue → hue，缺省 1；无曲线时忽略）
        kfs.put("hv_s_strength", new FieldDef("float", 1f));    // 0 ~ 1（HvS：hue → 饱和度）
        kfs.put("hv_l_strength", new FieldDef("float", 1f));    // 0 ~ 1（HvL：hue → 亮度）
        kfs.put("lv_s_strength", new FieldDef("float", 1f));    // 0 ~ 1（LvS：亮度 → 饱和度）
        kfs.put("sv_s_strength", new FieldDef("float", 1f));    // 0 ~ 1（SvS：饱和度 → 饱和度）
        kfs.put("sv_l_strength", new FieldDef("float", 1f));    // 0 ~ 1（SvL：饱和度 → 亮度）
        // PS 式六色带微调（Hue / Sat 分色带；第 17 步 = HSL 块之后、灰度之前）：
        // 六条固定色带（红 0° / 黄 60° / 绿 120° / 青 180° / 蓝 240° / 品红 300°）各一对
        // hue（±1 = ±180° 旋转）/ sat（乘性 (1 + Δsat)）；带权重 = 升余弦锥形（±30° 支撑）
        kfs.put("hue_red", new FieldDef("float", 0f));        // -1 ~ 1（红带色相旋转，缺省 0 = 无效果）
        kfs.put("sat_red", new FieldDef("float", 0f));        // -1 ~ 1（红带饱和度增量：sat *= (1 + 值)）
        kfs.put("hue_yellow", new FieldDef("float", 0f));     // -1 ~ 1（黄带色相旋转）
        kfs.put("sat_yellow", new FieldDef("float", 0f));     // -1 ~ 1（黄带饱和度增量）
        kfs.put("hue_green", new FieldDef("float", 0f));      // -1 ~ 1（绿带色相旋转）
        kfs.put("sat_green", new FieldDef("float", 0f));      // -1 ~ 1（绿带饱和度增量）
        kfs.put("hue_cyan", new FieldDef("float", 0f));       // -1 ~ 1（青带色相旋转）
        kfs.put("sat_cyan", new FieldDef("float", 0f));       // -1 ~ 1（青带饱和度增量）
        kfs.put("hue_blue", new FieldDef("float", 0f));       // -1 ~ 1（蓝带色相旋转）
        kfs.put("sat_blue", new FieldDef("float", 0f));       // -1 ~ 1（蓝带饱和度增量）
        kfs.put("hue_magenta", new FieldDef("float", 0f));    // -1 ~ 1（品红带色相旋转）
        kfs.put("sat_magenta", new FieldDef("float", 0f));    // -1 ~ 1（品红带饱和度增量）
        // 风格化（本身即强度）
        kfs.put("grayscale", new FieldDef("float", 0f));      // 0 ~ 1（灰度混合量）
        kfs.put("invert", new FieldDef("float", 0f));         // 0 ~ 1（反相混合量）
        // 层级混合强度（第 20 步 = 全部操作栈步骤之后整体施加）：0 ~ 1，缺省 1 = 全量生效；
        // 写回 0 = 调整层整体透明（输出 = 基画面 = 恒等）；只有 ADJUST 轨（master）带该字段
        kfs.put("blend_amount", new FieldDef("float", 1f));

        return new TrackTypeSchema(clips, kfs);
    }

}
