package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.MasterColorAdjust;

import java.util.List;

/**
 * ADJUST 轨道播放器 — 画面颜色调整（0.3.6：master 35 通道 + RGB 通道混合器
 * + RGB 复合曲线 + 每通道曲线 + 六条 hue 曲线 + Lift / Gamma / Gain 色轮）。
 *
 * <h2>职责</h2>
 * 每渲染帧找到本轨道当前活跃的 clip，把 35 个标量通道 + 十条曲线强度在<b>片段本地时间</b>处插值
 * （采样见 {@link ColorAdjustSampler}），发布给 {@link MasterColorAdjust}，作用于<b>整体画面</b>。
 * 无活跃 clip 时<b>本帧不参与</b> —— 渲染侧拿不到参数就不动画面。
 * <p>同帧多条 ADJUST 轨道：后发布者生效（轨道层级靠后的覆盖靠前的）；没活跃 clip 的轨道不参与
 * （不会把别的轨道的发布抹掉）。参数全为缺省（0）时被规整为「无调整」，同样不影响画面。</p>
 *
 * <h2>作用范围（ADJUST 轨 = 整体画面 master）</h2>
 * ADJUST 轨只作用于<b>整体画面</b>：渲染侧在<b>合成输出</b>上开一次全屏 pass
 * （{@code client/post/ColorAdjustPass}）。
 * <p>lane 级调色<b>不</b>由 ADJUST 轨承担，而是挂在<b>相机片段</b>自己的调色字段上：由
 * {@link ColorAdjustSampler} 在每条 lane 的相机片段本地时间处采样（见
 * {@link ScriptPlayer#collectCameraLanes()}），渲染侧在该 lane 渲染完成之后、合成之前开 pass
 * （{@code client/lane/LaneRenderer#renderLane} + {@code ColorAdjustPass#applyTo}）。</p>
 * <p>两条路径都<b>只携带 RGB</b>（{@code ColorAdjustParams} 里没有 alpha / opacity 参数）：
 * 调色只动 RGB、alpha 逐位直通；透明度一律在<b>合成之后</b>由合成层调控。</p>
 *
 * <h2>数据口径</h2>
 * 35 个标量通道全部是<b>关键帧字段</b>：{@code exposure / contrast / highlights / shadows / whites /
 * blacks / hue / saturation / vibrance / lightness / temperature / tint / red / green / blue /
 * mix_rr ~ mix_bb（九个）/ lift_r ~ gain_b（九个）/ grayscale / invert}，
 * 缺省全 0 = 无效果（字段名 / 范围 / 公式见 {@code docs/SCRIPT_FORMAT.md} §10 与
 * {@code TrackSchemas.adjust()}）。插值走 {@link KeyframeInterpolator#interpolateChannel}（匀速线性），
 * 与其它轨道的标量通道同一口径。
 *
 * <h2>Lift / Gamma / Gain 色轮</h2>
 * {@code lift_r} / {@code lift_g} / {@code lift_b}（阴影）、{@code gamma_r} / {@code gamma_g} / {@code gamma_b}
 * （中间调）、{@code gain_r} / {@code gain_g} / {@code gain_b}（高光）九个同样是关键帧字段，
 * 各 {@code -1 ~ 1}、缺省 {@code 0} = 无效果；逐通道公式
 * {@code c' = c + lift·(1-c)}、{@code c' = pow(max(c,0), exp2(-gamma))}、{@code c' = c·(1+gain)}，
 * 在着色器里位于<b>每通道曲线之后、钳制 {@code [0,1]} 之前</b>（九个全 0 时整步跳过）。
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
 * 没有 ADJUST 轨道 / 没有活跃 clip / 参数全为缺省 → 不发布 → 渲染侧一次 GL 调用都不做。
 */
public class AdjustTrackPlayer implements TrackPlayer {

    private final ScriptPlayer scriptPlayer;
    private final int trackIndex;

    public AdjustTrackPlayer(ScriptPlayer scriptPlayer, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.trackIndex = trackIndex;
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
            return;
        }
        // 恒等参数由 publish 归一化为「无调整」（渲染侧因此一次 GL 调用都不做）。
        MasterColorAdjust.INSTANCE.publish(ColorAdjustSampler.sample(clip, clipTime(clip, globalTime)));
    }

    @Override
    public void onStop() {
        MasterColorAdjust.INSTANCE.clear();
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
