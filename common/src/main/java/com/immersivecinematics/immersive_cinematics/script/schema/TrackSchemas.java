package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.ClipRule;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectCallpoints;
import com.immersivecinematics.immersive_cinematics.selector.SelectorSchema;
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
 * clip 字段 = 片段级（不随关键帧变化）；keyframe 字段 = 逐关键帧插值。两者按 {@link TrackTypeSchema} 成对登记。
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
     * 全轨道 clip 共有的字段（逐轨道显式登记，不做隐式合并）：{@code all()} 里七个 builder 各调一次。
     *
     * {@code rules} = 判定规则数组，任意轨道的片段都可挂（判定源与轨道无关，校验器不拦）；形态见 {@link ClipRule}。
     */
    private static void putCommonClipFields(Map<String, FieldDef> clips) {
        clips.put("rules", new FieldDef("rules", null, false, List.of(), "rules"));
    }

    /**
     * CAMERA 轨道（相机运动 + 合成 + 自带调色）：clip 级 10 条曲线 + 关键帧 57 个调色通道（与 ADJUST 同名同缺省），
     * 作用于本片段产出的 lane，在该 lane 渲染完成之后、合成之前生效（与 {@code dest} / {@code source} /
     * {@code opacity} 同层）。{@code blend_mode} / {@code blend_amount} 属 master 专用（ADJUST），写在相机片段上
     * 会被 validator 拦下；整体画面的调色走 ADJUST 轨。
     */
    private static TrackTypeSchema camera() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        putCommonClipFields(clips);
        // 过渡（clip 级）：缺省 cut = 直接切；transition_curve / transition_params / transition_interrupt
        // 分别声明权重曲线 / 分参数 / 打断策略，缺省 = 全六通道同权重线性斜坡
        clips.put("transition", new FieldDef("enum", "cut", false, List.of("cut", "morph"), "transition"));
        clips.put("transition_duration", new FieldDef("float", 0.5f, false, List.of(), "transition"));
        clips.put("transition_curve", new FieldDef("model", null, false, List.of(), "transition"));
        clips.put("transition_params", new FieldDef("transition_params", null, false, List.of(), "transition"));
        clips.put("transition_interrupt", new FieldDef("enum", "blend", false, List.of("blend", "snap"), "transition"));
        // 路径曲线（clip 级）：按 type 选路径表达式，形态 / 参数表见 {@link PathCurveRegistry}
        clips.put("curve", new FieldDef("path_curve", null));
        clips.put("dimension", new FieldDef("string", ""));
        // lane 合成遮罩形状：只属于 CAMERA（OVERLAY 的 fade 另有同名字段，语义相同但作用于颜色层）。
        // 几何通道 mask_x / mask_y / mask_width / mask_height / mask_radius 是关键帧字段，见下方 kfs。
        clips.put("mask_shape", new FieldDef("enum", "full", false, List.of("full", "rect", "circle")));
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
        // 相机参数运动模型（clip 级；缺省 null = 该通道不接模型 = 逐位不改变现状）：值 = 模型定义对象
        // {type, ...}；类型 / 参数 schema 见 {@link MathModelRegistry}
        clips.put("motion_position", new FieldDef("model", null, false, List.of(), "motion"));
        clips.put("motion_yaw", new FieldDef("model", null, false, List.of(), "motion"));
        clips.put("motion_pitch", new FieldDef("model", null, false, List.of(), "motion"));
        clips.put("motion_roll", new FieldDef("model", null, false, List.of(), "motion"));
        clips.put("motion_fov", new FieldDef("model", null, false, List.of(), "motion"));
        clips.put("motion_zoom", new FieldDef("model", null, false, List.of(), "motion"));
        // 覆盖链 Modifier（clip 级）：本片段声明一条静态 Modifier（掩码 / 合成模式 / 应用序 / 每通道值），
        // 形态与校验见 ScriptParser#parseClipModifier
        clips.put("modifier", new FieldDef("modifier", null, false, List.of(), "modifier"));
        // 本片段活跃时跳过 Modifier 链（缺省 false = 不跳过）
        clips.put("suppress_modifiers", new FieldDef("bool", false, false, List.of(), "modifier"));
        clips.put("orient", new FieldDef("enum", "manual", false, List.of("manual", "tangent")));
        clips.put("yaw_offset", new FieldDef("float", 0f));
        clips.put("pitch_offset", new FieldDef("float", 0f));
        // 调色（clip 级）：曲线定义一次，关键帧只控各自强度；曲线不随时间变，故挂 clip
        // 曲线组：复合 RGB 曲线 + 每通道曲线
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
        // 基准坐标系（base 表达空间的参照系）：原点 = 位置点源 facing_origin（留空 = 世界轴偏移用玩家激活位置、
        // 基准坐标系偏移用玩家实体 @p）；前轴 = facing_target（点源，留空 = 位置点源自身朝向）
        kfs.put("facing_origin", new FieldDef("string", ""));
        kfs.put("facing_origin_x", new FieldDef("float", 0f));
        kfs.put("facing_origin_y", new FieldDef("float", 0f));
        kfs.put("facing_origin_z", new FieldDef("float", 0f));
        // 点源时态（live = 逐帧重取 / snapshot = 首次解析到的点冻结；seek 重取、loop 折返延续、morph 两侧各持）：
        // 只对实体选择器形态生效，静态来源（坐标 / 方块 / 结构 / 玩家激活位置）写 = 无效果（validator 提示）
        kfs.put("facing_origin_time", new FieldDef("enum", "live", false, List.of("live", "snapshot")));
        // 基准坐标系前轴目标：留空 = 位置点源自身朝向；否则 = 位置点源 → 该目标的连线方向（点源，见
        // camera/source/PointSource；"coordinate" 形态的坐标取 facing_target_x/y/z）；
        // 偏移 facing_target_offset
        kfs.put("facing_target", new FieldDef("string", ""));
        kfs.put("facing_target_x", new FieldDef("float", null));
        kfs.put("facing_target_y", new FieldDef("float", null));
        kfs.put("facing_target_z", new FieldDef("float", null));
        // 点源偏移（{dx,dy,dz} 世界轴 / {fwd,up,right} 基准坐标系；见 camera/source/PointSource.Offset）
        kfs.put("facing_target_offset", new FieldDef("map", null));
        kfs.put("facing_target_time", new FieldDef("enum", "live", false, List.of("live", "snapshot")));
        kfs.put("follow", new FieldDef("enum", "none", false, List.of("none", "entity")));
        kfs.put("follow_selector", new FieldDef("string", "@p"));
        kfs.put("look_at", new FieldDef("enum", "none", false, List.of("none", "player", "coordinate", "entity", "block")));
        kfs.put("look_at_selector", new FieldDef("string", "@p"));
        // 部位百分比：实体包围盒内按每轴百分比取点（0 ~ 100，缺省 50 = 中心）；只对 look_at=entity 有效
        kfs.put("look_at_part", new FieldDef("map", null));
        kfs.put("look_at_target_x", new FieldDef("float", null));
        kfs.put("look_at_target_y", new FieldDef("float", null));
        kfs.put("look_at_target_z", new FieldDef("float", null));
        kfs.put("look_at_target_structure", new FieldDef("string", ""));
        // 方块点源（block:<方块 id>[:<半径>]）：look_at=block 时的注视目标，取就近搜索到的方块中心
        kfs.put("look_at_target_block", new FieldDef("string", ""));
        // 注视点偏移（{dx,dy,dz} 世界轴 / {fwd,up,right} 基准坐标系）：叠加在来源取点之后
        kfs.put("look_at_offset", new FieldDef("map", null));
        // 注视点时态：只对 look_at=entity 生效（其余形态恒定）
        kfs.put("look_at_time", new FieldDef("enum", "live", false, List.of("live", "snapshot")));
        // 选择器目标锁定策略：通用字段 = 该关键帧所有调用点的缺省回落（语义见 selector/SelectorPolicy）。
        // 调用点专属字段名 = <字段>_<调用点>（调用点名见 SelectorSchema.CALLPOINTS），
        // 缺省 null = 回落通用字段；不写任何调用点字段 = 旧行为（只用通用字段）。
        kfs.put("selector_refresh", new FieldDef("float", 1.0f));
        kfs.put("selector_switch_while_alive", new FieldDef("bool", true));
        // 切换间隔：两次真实切换之间的最小间隔（与扫描频率无关）；缺省 = selector_refresh
        kfs.put("selector_switch_interval", new FieldDef("float", 1.0f));
        kfs.put("selector_switch_smooth", new FieldDef("float", 0.0f));
        // 选择器锚点：就近排序（sort=nearest）的参考点，也是服务端解析请求的原点。
        // camera（缺省）= 相机位置（旧行为）；player = 玩家脚底；target = 该调用点已解析锁的实体脚底
        // （未解析到目标时回落 camera）；origin = position 的位置侧点源坐标（形态见 camera/source/PointSource；
        // position 缺失或点源解析失败回落 camera）。取值与回落链见 EntityTargetResolver.selectorAnchor。
        kfs.put("selector_anchor", new FieldDef("string", "camera"));
        // 候选择一策略：first（缺省）= 现状行为（本地 @e 就近取首个可用，服务端按回传序取首个可用）；
        // nearest 取客户端可用候选内距锚点最近者；alive 按候选返回序取首个存活。
        // 取值与回落链见 EntityTargetResolver.selectorPick。
        kfs.put("selector_pick", new FieldDef("string", "first"));
        // 调用点专属覆盖（7 个调用点 × 6 个选择器字段）；名单与字段名模式见 SelectorSchema
        for (String callpoint : SelectorSchema.CALLPOINTS) {
            kfs.put(SelectorSchema.callpointField("selector_refresh", callpoint), new FieldDef("float", null));
            kfs.put(SelectorSchema.callpointField("selector_switch_while_alive", callpoint),
                    new FieldDef("bool", null));
            kfs.put(SelectorSchema.callpointField("selector_switch_interval", callpoint),
                    new FieldDef("float", null));
            kfs.put(SelectorSchema.callpointField("selector_switch_smooth", callpoint),
                    new FieldDef("float", null));
            kfs.put(SelectorSchema.callpointField("selector_anchor", callpoint), new FieldDef("string", null));
            kfs.put(SelectorSchema.callpointField("selector_pick", callpoint), new FieldDef("string", null));
        }
        // 朝向基准来源（措辞口径：身体朝向 = 实体身体水平角 yBodyRot；视线 = 实体 yRot + xRot）。
        // yaw_base 取 entity 的「实体身体朝向」水平角；pitch_base 取「实体视线」俯仰。
        kfs.put("yaw_base", new FieldDef("enum", "world", false, List.of("world", "entity", "line")));
        kfs.put("pitch_base", new FieldDef("enum", "world", false, List.of("world", "entity", "line")));
        // yaw_base/pitch_base = entity 时的实体选择器（yaw 取身体朝向水平角、pitch 取视线俯仰）
        kfs.put("yaw_base_selector", new FieldDef("string", "@p"));
        // line 基准的两个端点：各是完整点源（见 camera/source/PointSource；"coordinate" 形态的坐标取 <字段>_x/_y/_z，
        // 偏移取 <字段>_offset）；退化连线（零长度 / 纯垂直）水平角未定义，运行时被拒（回退基准 0 = world）
        kfs.put("yaw_base_from", new FieldDef("string", ""));
        kfs.put("yaw_base_from_x", new FieldDef("float", null));
        kfs.put("yaw_base_from_y", new FieldDef("float", null));
        kfs.put("yaw_base_from_z", new FieldDef("float", null));
        kfs.put("yaw_base_from_offset", new FieldDef("map", null));
        kfs.put("yaw_base_from_time", new FieldDef("enum", "live", false, List.of("live", "snapshot")));
        kfs.put("yaw_base_to", new FieldDef("string", ""));
        kfs.put("yaw_base_to_x", new FieldDef("float", null));
        kfs.put("yaw_base_to_y", new FieldDef("float", null));
        kfs.put("yaw_base_to_z", new FieldDef("float", null));
        kfs.put("yaw_base_to_offset", new FieldDef("map", null));
        kfs.put("yaw_base_to_time", new FieldDef("enum", "live", false, List.of("live", "snapshot")));
        kfs.put("yaw", new FieldDef("float", 0));
        kfs.put("pitch", new FieldDef("float", 0));
        // 方向锁（每轴一把，缺省 null = 不锁，跟 yaw_base / pitch_base 的方向源）：
        // 锁值 = 该轴世界角绝对值（度），替换该轴基准；关键帧 yaw / pitch 偏移仍叠加；
        // look_at != none 时静态侧整体不参与（锁与偏移都不生效）
        kfs.put("yaw_lock", new FieldDef("float", null));
        kfs.put("pitch_lock", new FieldDef("float", null));
        kfs.put("roll", new FieldDef("float", 0));
        kfs.put("fov", new FieldDef("float", 70));
        kfs.put("zoom", new FieldDef("float", 1.0f));
        // 合成参数：挂在本 CAMERA clip 的关键帧上，随关键帧插值
        // （渲染侧由 client/lane/ScriptLaneDriver 逐帧消费）；此处登记字段，供编辑器表单与校验使用。
        kfs.put("opacity", new FieldDef("float", 1.0f));
        kfs.put("dest", new FieldDef("map", null));
        kfs.put("source", new FieldDef("map", null));
        // lane 合成遮罩几何（形状是 clip 级 mask_shape）：窗口比例中心 / 宽高 / 短边半径，
        // 随关键帧线性插值，由 client/lane/LaneMask 逐帧采样（语义 = fade 局部几何，作用对象 = lane 整幅 RGB）。
        // full 不消费这些通道；rect 读 x/y/width/height；circle 读 x/y/radius。
        kfs.put("mask_x", new FieldDef("float", 0.5f));
        kfs.put("mask_y", new FieldDef("float", 0.5f));
        kfs.put("mask_width", new FieldDef("float", 1.0f));
        kfs.put("mask_height", new FieldDef("float", 1.0f));
        kfs.put("mask_radius", new FieldDef("float", 0.5f));

        // 调色：每个相机片段自带调色，作用于该相机轨的 lane（渲染完、合成前）；下列 57 个字段与 ADJUST
        // 同名同缺省（47 个标量通道缺省 0 + 10 个曲线强度缺省 1）。
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
        // PS 式六色带微调（六条固定色带各撑 ±30°、升余弦权重；十二个全 0 = 整步跳过；栈位 = HSL 块之后、灰度之前）
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

        return new TrackTypeSchema(clips, kfs, cameraObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema letterbox() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        putCommonClipFields(clips);
        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("aspect_ratio", new FieldDef("float", 2.35f));
        // 黑边不透明度（0~1，缺省 1 = 纯黑边）：与覆盖层同名通道同口径（LetterboxLayer#setOpacity），
        // 随关键帧线性插值
        kfs.put("opacity", new FieldDef("float", 1.0f));
        return new TrackTypeSchema(clips, kfs, letterboxObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema audio() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        putCommonClipFields(clips);
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

        return new TrackTypeSchema(clips, kfs, audioObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema event() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        putCommonClipFields(clips);
        clips.put("event_type", new FieldDef("string", "command"));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("event_type", new FieldDef("string", "command"));
        kfs.put("command", new FieldDef("string", ""));
        kfs.put("position", new FieldDef("map", null));

        return new TrackTypeSchema(clips, kfs, eventObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema modEvent() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        putCommonClipFields(clips);
        clips.put("event_type", new FieldDef("string", null, true));
        clips.put("data", new FieldDef("map", null));

        Map<String, FieldDef> kfs = new LinkedHashMap<>();
        kfs.put("event_type", new FieldDef("string", "mod_event"));
        kfs.put("data", new FieldDef("map", null));

        return new TrackTypeSchema(clips, kfs, modEventObjects(), PLAY_CONTROL);
    }

    private static TrackTypeSchema overlay() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        putCommonClipFields(clips);
        clips.put("layer_type", new FieldDef("enum", "fade", true, List.of("fade", "image", "subtitle")));
        clips.put("color", new FieldDef("string", "#000000"));
        // fade 高级颜色遮罩：只属于 layer_type=fade；image / subtitle 上写会被
        // ScriptParser / ScriptValidator 明确拒绝（不扩规则动作字段，也不影响旧 fade 默认表现）
        // gradient 形如 {type: linear|radial, stops: [{offset, color}, ...]}（结构数据，解析期一次校验）
        clips.put("gradient", new FieldDef("map", null));
        clips.put("mask_shape", new FieldDef("enum", "full", false, List.of("full", "rect", "circle")));
        clips.put("blend_mode", new FieldDef("enum", "normal", false,
                List.of("normal", "multiply", "screen", "soft_light", "overlay")));
        clips.put("path", new FieldDef("string", ""));
        clips.put("text", new FieldDef("string", ""));
        // 顺序：统一默认 10（letterbox 的 z = 0 是内置常量，不在脚本口径内）
        clips.put("z_index", new FieldDef("int", 10));

        // 统一参数字段表——全部是关键帧字段
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
        // fade 高级颜色遮罩关键帧通道：只属于 layer_type=fade；image / subtitle 上写会被
        // ScriptParser / ScriptValidator 明确拒绝。几何通道缺省 = 旧 fade 的全窗口覆盖。
        // color 缺省 null = 回落 clip 级 color（不是固定黑色）
        kfs.put("color", new FieldDef("string", null));
        // 覆盖区域中心（窗口比例，可越界）
        kfs.put("mask_x", new FieldDef("float", 0.5f));
        kfs.put("mask_y", new FieldDef("float", 0.5f));
        // rect 宽高 / 窗口比例（非负，不设人为上限；零面积不绘制）
        kfs.put("mask_width", new FieldDef("float", 1.0f));
        kfs.put("mask_height", new FieldDef("float", 1.0f));
        // circle 半径 / 窗口较短边（非负；0 不绘制）
        kfs.put("mask_radius", new FieldDef("float", 0.5f));
        // linear 方向角（0 = 向右，90 = 向下）；radial 中心（窗口比例，可越界）与半径（短边比例，必须 > 0）
        kfs.put("gradient_angle", new FieldDef("float", 0f));
        kfs.put("gradient_center_x", new FieldDef("float", 0.5f));
        kfs.put("gradient_center_y", new FieldDef("float", 0.5f));
        kfs.put("gradient_radius", new FieldDef("float", 0.5f));
        // 字幕专用：字号倍数（1 = 原版 9px），改变文字块基准尺寸，与 scale_x/y 叠加
        kfs.put("font_scale", new FieldDef("float", 1));

        return new TrackTypeSchema(clips, kfs, overlayObjects(), PLAY_CONTROL);
    }

    /**
     * ADJUST 轨道（整体画面调色）：字段顺序 = 着色器操作栈顺序，schema / {@code ColorAdjustParams} / fsh 三处逐项对应，
     * 增删通道必须同步（区间由 {@code ScriptValidator} 校验）。47 个标量关键帧通道缺省全 0 = 无效果，写回 0 即该项淡出
     * （无需 enabled 开关）；mix_rr~mix_bb 全 0 = 单位阵、lift/gamma/gain 九个与六色带十二个全 0 = 整步跳过（逐位恒等）。
     * clip 级曲线 / LUT 只定义一次、关键帧只控强度（0~1，缺省 1）；本轨作用于合成输出（master），lane 级调色写在 CAMERA 片段。
     * blend_mode（缺省 normal = 直替换）+ blend_amount（0~1，缺省 1）在全部步骤之后做层级混合，只动 RGB、alpha 直通。
     */
    private static TrackTypeSchema adjust() {
        Map<String, FieldDef> clips = new LinkedHashMap<>();
        putCommonClipFields(clips);
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
        // 先取 v = pow(clamp(c, 0, 1), lut_input_gamma)，以 v 为四面体查表坐标——现成 .cube 按特定素材 /
        // 色彩空间调成时，用它把游戏画面（sRGB）映射回表假设的输入域；无 LUT 时忽略
        clips.put("lut_input_gamma", new FieldDef("float", 1f));
        // 混合模式（clip 级枚举，缺省 "normal" = 直替换）：c = mix(c_base, blend(c_base, c_adj, mode), blend_amount)，
        // 全部 19 步算完后整体施加；只有 ADJUST 轨（master）带该字段（相机片段上写会被 validator 拦下）
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
        // RGB 通道混合器（单位阵 + 参数矩阵，九个全 0 = 逐位恒等；行 = 输出、列 = 输入；栈位 = R/G/B 系数之后、复合曲线之前）
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
        // Lift / Gamma / Gain 色轮（c' = c + lift·(1-c) / pow(max(c,0), exp2(-gamma)) / c·(1+gain)；
        // 九个全 0 = 整步跳过；栈位 = 每通道曲线之后、钳制 [0,1] 之前）
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
        // PS 式六色带微调（六条固定色带各撑 ±30°、升余弦权重；十二个全 0 = 整步跳过；栈位 = HSL 块之后、灰度之前）
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
        // 层级混合强度（第 20 步 = 全部步骤之后整体施加）：0 ~ 1，缺省 1 = 全量生效，写回 0 = 输出 = 基画面
        kfs.put("blend_amount", new FieldDef("float", 1f));

        return new TrackTypeSchema(clips, kfs, adjustObjects(), PLAY_CONTROL);
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
                .enumValues("none", "linear", "inverse").section("audio"));
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
