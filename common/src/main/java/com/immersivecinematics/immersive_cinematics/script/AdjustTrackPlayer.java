package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.client.post.MasterColorAdjust;

import java.util.List;

/**
 * ADJUST 轨道播放器 — 画面颜色调整（master，第一批：标量组 15 通道 = 12 标量 + R/G/B 每通道系数）。
 *
 * <h2>职责</h2>
 * 每渲染帧找到本轨道当前活跃的 clip，把 15 个标量通道在<b>片段本地时间</b>处插值，
 * 发布给 {@link MasterColorAdjust}（渲染侧在合成输出上开一次全屏 pass 消费它，见
 * {@code client/post/ColorAdjustPass}）。无活跃 clip 时<b>不发布</b> —— 渲染侧取不到参数就不动画面。
 * <p>同帧多条 ADJUST 轨道：后发布者生效（轨道层级靠后的覆盖靠前的）；没活跃 clip 的轨道不参与
 * （不会把别的轨道的发布抹掉）。参数全为缺省（0）时发布被规整为「无调整」，同样不影响画面。</p>
 *
 * <h2>数据口径</h2>
 * 15 个通道全部是<b>关键帧字段</b>（没有 clip 级字段、没有 clip 级简写）：
 * {@code exposure / contrast / highlights / shadows / whites / blacks / saturation / vibrance /
 * temperature / tint / red / green / blue / grayscale / invert}，缺省全 0 = 无效果
 * （字段名 / 范围 / 公式见 {@code docs/SCRIPT_FORMAT.md} §10 与 {@code TrackSchemas.adjust()}）。
 * 插值走 {@link KeyframeInterpolator#interpolateChannel}（匀速线性），与其它轨道的标量通道同一口径。
 *
 * <h2>分层位置</h2>
 * 本版本只有 <b>master</b>（作用于合成输出 = 最终显示画面，不含 GUI）。lane 级调整与调整层
 * （作用于其下各层）是后续批次，见 plans/0.3.6/screen-color-adjust.md §7。
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
            // 无活跃片段：本帧不发布（不是「发布空值」）——渲染侧取不到参数自然不调色。
            // 这里刻意不 clear()：同帧还有别的 ADJUST 轨道时，清空会把它的发布一起抹掉
            // （多条轨道 = 后发布者生效，而不是「后一条没生效就取消前一条」）。
            return;
        }
        MasterColorAdjust.INSTANCE.publish(sample(clip.getKeyframes(), clipTime(clip, globalTime)));
    }

    @Override
    public void onStop() {
        MasterColorAdjust.INSTANCE.clear();
    }

    /** 15 个标量通道在片段本地时间处的取值（顺序 = {@link ColorAdjustParams} 分量顺序 = shader 操作栈顺序）。 */
    private static ColorAdjustParams sample(List<Keyframe> keyframes, float localTime) {
        return new ColorAdjustParams(
                channel(keyframes, localTime, "exposure"),
                channel(keyframes, localTime, "contrast"),
                channel(keyframes, localTime, "highlights"),
                channel(keyframes, localTime, "shadows"),
                channel(keyframes, localTime, "whites"),
                channel(keyframes, localTime, "blacks"),
                channel(keyframes, localTime, "saturation"),
                channel(keyframes, localTime, "vibrance"),
                channel(keyframes, localTime, "temperature"),
                channel(keyframes, localTime, "tint"),
                channel(keyframes, localTime, "red"),
                channel(keyframes, localTime, "green"),
                channel(keyframes, localTime, "blue"),
                channel(keyframes, localTime, "grayscale"),
                channel(keyframes, localTime, "invert"));
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
