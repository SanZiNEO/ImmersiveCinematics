package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.client.post.MasterColorAdjust;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.script.track.TrackDriver;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

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
public class AdjustTrackPlayer implements TrackDriver {

    private final ScriptPlayer scriptPlayer;
    private final int trackIndex;
    /** 新格式片段的采样缓存（旧格式片段不走这里，仍由 {@link ColorAdjustSampler#sample} 扫关键帧）。 */
    private final ClipRuntimeCache runtime = new ClipRuntimeCache(TrackTypeId.ADJUST);
    /** 曲线解析缓存：曲线是 value-only 结构，按 clip 只解析一次。 */
    private final ColorAdjustSampler.CurveCache curves = new ColorAdjustSampler.CurveCache();

    public AdjustTrackPlayer(ScriptPlayer scriptPlayer, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.trackIndex = trackIndex;
    }

    /** 动态数据源：replaceScript 后自动用新数据，零重建。 */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
    }

    @Override
    public boolean isActiveAt(double globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(double globalTime) {
        Clip clip = findActiveClip(globalTime);
        if (clip == null) {
            // 无活跃片段：本帧不发布（不是发布空值）——渲染侧取不到参数自然不调色。
            // 刻意不 clear()：同帧别的 ADJUST 轨道的发布不能被抹掉（多条轨道 = 后发布者生效）。
            return;
        }
        // 恒等参数由 publish 归一化为「无调整」（渲染侧一次 GL 调用都不做）。
        // 实例标识 = 本轨所属的 ScriptPlayer：同实例多条 ADJUST 轨互相覆盖，不同实例各占一层。
        double localTime = scriptPlayer.executionLocalTime(clip, globalTime);
        // 59 通道来自对象模型（旧格式关键帧扫描已随格式删除）。
        ColorAdjustParams params = ColorAdjustSampler.sampleObjects(clip, runtime.sample(clip, localTime),
                runtime.values(clip), curves);
        MasterColorAdjust.INSTANCE.publish(scriptPlayer, params);
    }

    @Override
    public void onStop() {
        runtime.clear();
        curves.clear();
        // 只清本实例的一层：同帧别的实例的发布不受影响（多实例并行播放）
        MasterColorAdjust.INSTANCE.clear(scriptPlayer);
    }

    @Override
    public void onScriptReplaced() {
        // 片段对象已换：采样器 / 缓冲 / 曲线解析结果全部失效
        runtime.clear();
        curves.clear();
    }

    private Clip findActiveClip(double globalTime) {
        for (Clip clip : clips()) {
            if (scriptPlayer.isClipActive(clip, globalTime)) return clip;
        }
        return null;
    }
}
