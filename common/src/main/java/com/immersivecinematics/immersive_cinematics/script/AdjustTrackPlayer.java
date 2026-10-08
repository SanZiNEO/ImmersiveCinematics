package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.client.post.MasterColorAdjust;

import java.util.List;

/**
 * ADJUST 轨道播放器 — 画面颜色调整（0.3.6：master 26 通道 + lane 级调整 + RGB 通道混合器
 * + RGB 复合曲线 + 每通道曲线 + 六条 hue 曲线）。
 *
 * <h2>职责</h2>
 * 每渲染帧找到本轨道当前活跃的 clip，把 26 个标量通道 + 十条曲线强度在<b>片段本地时间</b>处插值，按 clip 的
 * {@code scope} 分流（见下）。无活跃 clip 时<b>本帧不参与</b> —— 渲染侧拿不到参数就不动画面。
 * <p>同帧多条 ADJUST 轨道：后发布者生效（轨道层级靠后的覆盖靠前的）；没活跃 clip 的轨道不参与
 * （不会把别的轨道的发布抹掉）。参数全为缺省（0）时被规整为「无调整」，同样不影响画面。</p>
 *
 * <h2>作用域（clip 级 {@code scope} / {@code lane}）</h2>
 * <ul>
 *   <li>{@code master}（缺省）→ 现行为不变：发布给 {@link MasterColorAdjust}，渲染侧在<b>合成输出</b>上
 *       开一次全屏 pass（{@code client/post/ColorAdjustPass}）。</li>
 *   <li>{@code lane} → <b>不发布 master</b>：把本帧参数存进 {@link #laneParams()}（连同
 *       {@link #laneTarget()} = 目标相机轨序号，0 起、按时间轴中 CAMERA 轨出现顺序——与
 *       {@link LaneFrame#cameraTrackIndex()} 同一编号），由 {@link ScriptPlayer#collectCameraLanes()}
 *       归集到对应相机轨的每条 lane 上，渲染侧在<b>该 lane 渲染完成之后、合成之前</b>开 pass
 *       （{@code client/lane/LaneRenderer#renderLane} + {@code ColorAdjustPass#applyTo}）。</li>
 * </ul>
 * 同一相机轨被多条 lane 级 ADJUST 指向 → 按轨道顺序后者生效（与 master 同口径）；
 * 目标序号越界在 {@code ScriptValidator} 里就被拦下。
 *
 * <h2>数据口径</h2>
 * 17 个通道全部是<b>关键帧字段</b>：{@code exposure / contrast / highlights / shadows / whites /
 * blacks / hue / saturation / vibrance / lightness / temperature / tint / red / green / blue /
 * grayscale / invert}，
 * 缺省全 0 = 无效果（字段名 / 范围 / 公式见 {@code docs/SCRIPT_FORMAT.md} §10 与
 * {@code TrackSchemas.adjust()}）。插值走 {@link KeyframeInterpolator#interpolateChannel}（匀速线性），
 * 与其它轨道的标量通道同一口径。作用域（{@code scope} / {@code lane}）是 clip 级字段，不随时间变。
 *
 * <h2>曲线组（形态 b）：复合曲线 + 每通道曲线</h2>
 * clip 级字段 {@code curve}（RGB 复合曲线）与 {@code r_curve} / {@code g_curve} / {@code b_curve}（每通道曲线）
 * 都是控制点数组（结构字段、缺省 = 无曲线），解析成 {@link ColorCurve}，
 * 各自的 256 点 LUT 在 CPU 侧采样好、随 clip 缓存（逐帧拿到同一个数组，渲染侧只在换曲线时重传纹理）；
 * 关键帧只控各自的混合强度 {@code curve_strength} / {@code r_curve_strength} / {@code g_curve_strength} /
 * {@code b_curve_strength}（0~1，<b>缺省 1</b> = 曲线全量生效，写回 0 = 曲线淡出）。
 * 无对应曲线时该强度恒为 0、渲染侧那一步跳过。
 *
 * <h2>六条 hue 曲线（DaVinci 曲线页口径）</h2>
 * clip 级字段 {@code hv_h_curve} / {@code hv_s_curve} / {@code hv_l_curve} / {@code lv_s_curve} /
 * {@code sv_s_curve} / {@code sv_l_curve}（HvH / HvS / HvL、LvS / SvS / SvL）同样是控制点数组，
 * 与上面四条走同一套采样 / 缓存 / 强度口径（强度缺省 1，无曲线时忽略）；差别只在键与目标：
 * 键 = 进入 HSL 块时的 hue / 亮度 / 饱和度，目标 = hue / 饱和度 / 亮度，在 HSL 块内按固定顺序生效
 * （详见 {@code ColorAdjustParams} 与 {@code docs/SCRIPT_FORMAT.md} §10）。
 *
 * <h2>默认零差异</h2>
 * 没有 ADJUST 轨道 / 没有活跃 clip / 参数全为缺省 → 不发布、不归集 → 渲染侧一次 GL 调用都不做
 * （lane 级还会保持「lane FBO 直接进合成」的原路径）。
 */
public class AdjustTrackPlayer implements TrackPlayer {

    private final ScriptPlayer scriptPlayer;
    private final int trackIndex;

    /** 本帧 lane 级调整的目标相机轨序号；{@code -1} = 本帧不做 lane 级调整（master 或无活跃 clip）。 */
    private int laneTarget = -1;

    /** 本帧 lane 级调整参数；{@code null} = 本帧该轨道不参与 lane 级调整（恒等参数也归一化为 null）。 */
    private ColorAdjustParams laneParams;

    public AdjustTrackPlayer(ScriptPlayer scriptPlayer, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.trackIndex = trackIndex;
    }

    /** 本帧 lane 级调整的目标相机轨序号（{@code -1} = 不参与）；由 {@link ScriptPlayer} 归集。 */
    public int laneTarget() {
        return laneTarget;
    }

    /** 本帧 lane 级调整参数（{@code null} = 不参与）；由 {@link ScriptPlayer} 归集。 */
    public ColorAdjustParams laneParams() {
        return laneParams;
    }

    /** 组 A：动态数据源（replaceScript 后自动用新数据，零重建） */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        Clip clip = findActiveClip(globalTime);
        if (clip == null) {
            // 无活跃片段：本帧不参与（不是「发布空值」）——渲染侧取不到参数自然不调色。
            // 这里刻意不 clear()：同帧还有别的 ADJUST 轨道时，清空会把它的发布一起抹掉
            // （多条轨道 = 后发布者生效，而不是「后一条没生效就取消前一条」）。
            laneTarget = -1;
            laneParams = null;
            return;
        }
        ColorAdjustParams params = sample(clip, clipTime(clip, globalTime));
        if ("lane".equals(clip.getString("scope", "master"))) {
            // lane 级：不发布 master，只把本帧参数交给 ScriptPlayer 归集（按目标相机轨定位）。
            // 恒等参数归一化为「不参与」——渲染侧因此保持 lane FBO 直接进合成的原路径。
            int target = clip.getInt("lane", -1);
            laneTarget = target >= 0 ? target : -1;
            laneParams = (target >= 0 && !params.isIdentity()) ? params : null;
        } else {
            laneTarget = -1;
            laneParams = null;
            MasterColorAdjust.INSTANCE.publish(params);
        }
    }

    @Override
    public void onStop() {
        laneTarget = -1;
        laneParams = null;
        MasterColorAdjust.INSTANCE.clear();
    }

    /**
     * 本片段在<b>片段本地时间</b>处的全部取值（顺序 = {@link ColorAdjustParams} 分量顺序 = shader 操作栈顺序）。
     *
     * <p>26 个标量通道走 {@link KeyframeInterpolator#interpolateChannel}（缺省 0 = 无效果）；
     * 曲线组（形态 b）的十条曲线都是 clip 级字段（{@code curve} / {@code r_curve} / {@code g_curve} /
     * {@code b_curve} 与六条 hue 曲线 {@code hv_h_curve} ~ {@code sv_l_curve}，不随时间变）：
     * LUT 由 {@link ColorCurve} 对象持有（按 clip 缓存，逐帧拿到同一个数组），
     * 此处只把各自的强度在本地时间处插值——<b>强度缺省 1</b>：写了曲线就是全量生效，
     * 关键帧把强度写回 0 即曲线淡出；无曲线时该强度置 0（那一步不生效）。</p>
     */
    private static ColorAdjustParams sample(Clip clip, float localTime) {
        List<Keyframe> keyframes = clip.getKeyframes();
        float[] curveLut = lut(clip.getColorCurve());
        float[] rCurveLut = lut(clip.getRCurve());
        float[] gCurveLut = lut(clip.getGCurve());
        float[] bCurveLut = lut(clip.getBCurve());
        float[] hvHLut = lut(clip.getHvHCurve());
        float[] hvSLut = lut(clip.getHvSCurve());
        float[] hvLLut = lut(clip.getHvLCurve());
        float[] lvSLut = lut(clip.getLvSCurve());
        float[] svSLut = lut(clip.getSvSCurve());
        float[] svLLut = lut(clip.getSvLCurve());
        return new ColorAdjustParams(
                channel(keyframes, localTime, "exposure"),
                channel(keyframes, localTime, "contrast"),
                channel(keyframes, localTime, "highlights"),
                channel(keyframes, localTime, "shadows"),
                channel(keyframes, localTime, "whites"),
                channel(keyframes, localTime, "blacks"),
                channel(keyframes, localTime, "hue"),
                channel(keyframes, localTime, "saturation"),
                channel(keyframes, localTime, "vibrance"),
                channel(keyframes, localTime, "lightness"),
                channel(keyframes, localTime, "temperature"),
                channel(keyframes, localTime, "tint"),
                channel(keyframes, localTime, "red"),
                channel(keyframes, localTime, "green"),
                channel(keyframes, localTime, "blue"),
                channel(keyframes, localTime, "mix_rr"),
                channel(keyframes, localTime, "mix_rg"),
                channel(keyframes, localTime, "mix_rb"),
                channel(keyframes, localTime, "mix_gr"),
                channel(keyframes, localTime, "mix_gg"),
                channel(keyframes, localTime, "mix_gb"),
                channel(keyframes, localTime, "mix_br"),
                channel(keyframes, localTime, "mix_bg"),
                channel(keyframes, localTime, "mix_bb"),
                curveLut, rCurveLut, gCurveLut, bCurveLut,
                strength(keyframes, localTime, "curve_strength", curveLut),
                strength(keyframes, localTime, "r_curve_strength", rCurveLut),
                strength(keyframes, localTime, "g_curve_strength", gCurveLut),
                strength(keyframes, localTime, "b_curve_strength", bCurveLut),
                hvHLut, hvSLut, hvLLut, lvSLut, svSLut, svLLut,
                strength(keyframes, localTime, "hv_h_strength", hvHLut),
                strength(keyframes, localTime, "hv_s_strength", hvSLut),
                strength(keyframes, localTime, "hv_l_strength", hvLLut),
                strength(keyframes, localTime, "lv_s_strength", lvSLut),
                strength(keyframes, localTime, "sv_s_strength", svSLut),
                strength(keyframes, localTime, "sv_l_strength", svLLut),
                channel(keyframes, localTime, "grayscale"),
                channel(keyframes, localTime, "invert"));
    }

    /** clip 级曲线 → 它的 256 点 LUT（{@code null} = 本片段没有那条曲线）；LUT 随曲线对象按 clip 缓存。 */
    private static float[] lut(ColorCurve curve) {
        return curve != null ? curve.lut() : null;
    }

    /** 曲线强度：没有对应曲线 → 0（该步不生效）；有曲线 → 关键帧插值，缺省 1 = 全量生效。 */
    private static float strength(List<Keyframe> keyframes, float localTime, String key, float[] lut) {
        return lut == null ? 0.0F : KeyframeInterpolator.interpolateChannel(keyframes, localTime, key, 1.0F);
    }

    /** 单通道取值：缺省 0 = 无效果（关键帧没写该字段就是不做这项调整）。 */
    private static float channel(List<Keyframe> keyframes, float localTime, String key) {
        return KeyframeInterpolator.interpolateChannel(keyframes, localTime, key, 0.0F);
    }

    private float clipTime(Clip clip, float globalTime) {
        return Math.max(0f, Math.min(clip.getDuration(), globalTime - clip.getStartTime()));
    }

    private Clip findActiveClip(float globalTime) {
        for (Clip clip : clips()) {
            boolean isActive;
            if (clip.getDuration() < 0f) {
                isActive = globalTime >= clip.getStartTime();
            } else {
                float clipEnd = clip.getStartTime() + clip.getDuration();
                isActive = globalTime >= clip.getStartTime() && globalTime < clipEnd;
            }
            if (isActive) return clip;
        }
        return null;
    }
}
