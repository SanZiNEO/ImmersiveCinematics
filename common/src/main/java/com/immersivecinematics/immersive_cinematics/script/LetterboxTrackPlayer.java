package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.CanvasTransform;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.overlay.LetterboxLayer;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;

import java.util.List;

public class LetterboxTrackPlayer implements TrackPlayer {

    /**
     * 画幅比缺省（同 {@code TrackSchemas.letterbox()} / {@link Keyframe#getAspectRatio()}）：
     * 关键帧没写该通道时用。
     */
    private static final float DEFAULT_ASPECT_RATIO = 2.35f;

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;
    private final OverlayManager overlayManager;

    /**
     * 本实例的黑边层（per-instance：多实例各自黑边互不覆盖）——构造时建层入 Manager，
     * {@link #onStop()} 移除；归属 token = 本实例的播放器（渐出 / 动画判定按实例过滤）。
     */
    private final LetterboxLayer letterbox;

    public LetterboxTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, OverlayManager overlayManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
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
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        LetterboxLayer letterbox = this.letterbox;
        Clip activeClip = findActiveClip(globalTime);

        if (activeClip == null) {
            // clip 空档：画幅比归 0（一条黑边都不画），不透明度一并复位到缺省 1 —— 不残留前一片段的透明度
            letterbox.setAspectRatio(0.0f);
            letterbox.setOpacity(CanvasTransform.DEFAULT_OPACITY);
            return;
        }

        // 两个通道都在片段本地时间处按相邻关键帧匀速线性求值（运行时统一线性：缓动由编辑器烘焙成显式关键帧，
        // 运行时不求值）；关键帧为空 / 单帧 / 首帧前 / 末帧后由插值器统一取缺省或边界值。
        List<Keyframe> kfs = activeClip.getKeyframes();
        float localTime = clipTime(activeClip, globalTime);
        letterbox.setAspectRatio(KeyframeInterpolator.interpolateChannel(
                kfs, localTime, "aspect_ratio", DEFAULT_ASPECT_RATIO));
        letterbox.setOpacity(KeyframeInterpolator.interpolateChannel(
                kfs, localTime, "opacity", CanvasTransform.DEFAULT_OPACITY));
    }

    @Override
    public void onStop() {
        // per-instance：本层随本轨道退场移除，其他实例的黑边不受影响
        overlayManager.removeLayer(letterbox);
    }

    private float clipTime(Clip clip, float globalTime) {
        float elapsed = globalTime - clip.getStartTime();
        if (clip.getDuration() < 0f) {
            // 无限片段（duration < 0）：不按 duration 钳制，本地时间照常推进（同 AudioTrackPlayer.clipTime）
            return Math.max(0f, elapsed);
        }
        return Math.max(0f, Math.min(clip.getDuration(), elapsed));
    }

    private Clip findActiveClip(float globalTime) {
        for (Clip clip : clips()) {
            boolean isActive;
            if (clip.getDuration() < 0) {
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
