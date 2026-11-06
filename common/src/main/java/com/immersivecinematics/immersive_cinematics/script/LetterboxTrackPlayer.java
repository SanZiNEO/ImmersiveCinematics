package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.CanvasTransform;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.overlay.LetterboxLayer;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectSlotLayout;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import com.immersivecinematics.immersive_cinematics.script.track.TrackDriver;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.List;

public class LetterboxTrackPlayer implements TrackDriver {

    /**
     * 画幅比缺省（同 {@code TrackSchemas.letterbox()} 的 {@code aspect_ratio} 声明）：
     * 片段未声明该通道时用。
     */
    private static final float DEFAULT_ASPECT_RATIO = 2.35f;

    private static final ObjectSlotLayout LAYOUT = ObjectSlotLayout.of(TrackTypeId.LETTERBOX);
    private static final int SLOT_ASPECT_RATIO = LAYOUT.slotOf("aspect_ratio");
    private static final int SLOT_OPACITY = LAYOUT.slotOf("opacity");

    private final ScriptPlayer scriptPlayer;
    private final int trackIndex;
    private final OverlayManager overlayManager;

    /** 采样装配层：按片段对象身份缓存采样器与复用缓冲。 */
    private final ClipRuntimeCache runtimeCache = new ClipRuntimeCache(TrackTypeId.LETTERBOX);

    /**
     * 本实例的黑边层（per-instance：多实例各自黑边互不覆盖）——构造时建层入 Manager，
     * {@link #onStop()} 移除；归属 token = 本实例的播放器（渐出 / 动画判定按实例过滤）。
     */
    private final LetterboxLayer letterbox;

    public LetterboxTrackPlayer(ScriptPlayer scriptPlayer, OverlayManager overlayManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.trackIndex = trackIndex;
        this.overlayManager = overlayManager;
        this.letterbox = new LetterboxLayer(scriptPlayer.owner());
        overlayManager.addLayer(letterbox);
    }

    /** 动态数据源：replaceScript 后自动用新数据，无需重建轨道 */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
    }

    @Override
    public boolean isActiveAt(double globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(double globalTime) {
        LetterboxLayer letterbox = this.letterbox;
        Clip activeClip = findActiveClip(globalTime);

        if (activeClip == null) {
            // clip 空档：画幅比归 0（一条黑边都不画），不透明度一并复位到缺省 1 —— 不残留前一片段的透明度
            letterbox.setAspectRatio(0.0f);
            letterbox.setOpacity(CanvasTransform.DEFAULT_OPACITY);
            return;
        }

        // 两个通道都在片段本地时间处求值：一个 SampledClip 提供两个对象槽位（未声明 / 未采样 → 缺省）。
        double localTime = scriptPlayer.executionLocalTime(activeClip, globalTime);
        LetterboxParams params = compute(activeClip, localTime, runtimeCache);
        letterbox.setAspectRatio(params.aspectRatio());
        letterbox.setOpacity(params.opacity());
    }

    /**
     * 装配：从 {@link SampledClip} 读 {@code aspect_ratio} / {@code opacity}。
     *
     * @param clip      片段
     * @param localTime 执行局部时间
     * @param cache     该片段的采样缓存
     */
    public static LetterboxParams compute(Clip clip, double localTime, ClipRuntimeCache cache) {
        SampledClip sample = cache.sample(clip, localTime);
        float aspectRatio = SLOT_ASPECT_RATIO >= 0 && sample.present(SLOT_ASPECT_RATIO)
                ? sample.f(SLOT_ASPECT_RATIO)
                : DEFAULT_ASPECT_RATIO;
        float opacity = SLOT_OPACITY >= 0 && sample.present(SLOT_OPACITY)
                ? sample.f(SLOT_OPACITY)
                : CanvasTransform.DEFAULT_OPACITY;
        return new LetterboxParams(aspectRatio, opacity);
    }

    /** 一帧的 LETTERBOX 装配结果（不可变）。 */
    public record LetterboxParams(float aspectRatio, float opacity) {
    }

    @Override
    public void onStop() {
        // per-instance：本层随本轨道退场移除，其他实例的黑边不受影响
        overlayManager.removeLayer(letterbox);
        runtimeCache.clear();
    }

    @Override
    public void onScriptReplaced() {
        // 片段对象身份变化：丢弃采样缓存，下次访问按新片段重建
        runtimeCache.clear();
    }

    private Clip findActiveClip(double globalTime) {
        for (Clip clip : clips()) {
            if (scriptPlayer.isClipActive(clip, globalTime)) return clip;
        }
        return null;
    }
}
