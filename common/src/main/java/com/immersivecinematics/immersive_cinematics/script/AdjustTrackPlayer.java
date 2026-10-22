package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.MasterColorAdjust;

import java.util.List;

/**
 * ADJUST 轨道播放器 — 整体画面调色（master）。
 *
 * 每渲染帧取本轨当前活跃 clip，在片段本地时间处插值 47 个标量通道 + 10 条曲线强度
 * （采样见 {@link ColorAdjustSampler}），按所属播放实例发布给 {@link MasterColorAdjust}。
 * 无活跃 clip = 本帧不发布（渲染侧拿不到参数就不动画面）；同实例多条 ADJUST 轨后发布者生效，
 * 不同实例各占一层，叠加顺序见 {@link MasterColorAdjust}。
 *
 * ADJUST 轨只作用于整体画面（合成输出上的全屏 pass）；lane 级调色挂在相机片段自己的字段上，
 * 由 {@link ColorAdjustSampler} 在每条 lane 的片段本地时间处采样。两条路径都只携带 RGB：
 * 调色只动 RGB，透明度一律在合成之后由合成层调控。
 *
 * 字段名 / 范围 / 缺省 / 层级混合见 {@code ColorAdjustParams} 与 {@code TrackSchemas.adjust()}，
 * 操作栈步骤与像素级公式见 {@code ic_color_adjust.fsh}。
 */
public class AdjustTrackPlayer implements TrackPlayer {

    private final ScriptPlayer scriptPlayer;
    private final int trackIndex;

    public AdjustTrackPlayer(ScriptPlayer scriptPlayer, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.trackIndex = trackIndex;
    }

    /** 动态数据源：replaceScript 后自动用新数据，零重建。 */
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
            // 无活跃片段：本帧不发布（不是发布空值）——渲染侧取不到参数自然不调色。
            // 刻意不 clear()：同帧别的 ADJUST 轨道的发布不能被抹掉（多条轨道 = 后发布者生效）。
            return;
        }
        // 恒等参数由 publish 归一化为「无调整」（渲染侧一次 GL 调用都不做）。
        // 实例标识 = 本轨所属的 ScriptPlayer：同实例多条 ADJUST 轨互相覆盖，不同实例各占一层。
        MasterColorAdjust.INSTANCE.publish(scriptPlayer,
                ColorAdjustSampler.sample(clip, clipTime(clip, globalTime)));
    }

    @Override
    public void onStop() {
        // 只清本实例的一层：同帧别的实例的发布不受影响（多实例并行播放）
        MasterColorAdjust.INSTANCE.clear(scriptPlayer);
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
