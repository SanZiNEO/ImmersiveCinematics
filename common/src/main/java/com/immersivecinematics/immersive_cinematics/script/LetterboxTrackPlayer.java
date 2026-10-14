package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.overlay.LetterboxLayer;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;

import java.util.List;

public class LetterboxTrackPlayer implements TrackPlayer {

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;
    private final OverlayManager overlayManager;

    /**
     * 本实例的黑边层（per-instance：多实例各自黑边互不覆盖，设计 §6-2）——构造时建层入 Manager，
     * {@link #onStop()} 移除；归属 token = 本实例的播放器（渐出 / 动画判定按实例过滤）。
     */
    private final LetterboxLayer letterbox;

    private int lastClipIdx = -1;

    public LetterboxTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, OverlayManager overlayManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.trackIndex = trackIndex;
        this.overlayManager = overlayManager;
        this.letterbox = new LetterboxLayer(scriptPlayer.owner());
        overlayManager.addLayer(letterbox);
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
        LetterboxLayer letterbox = this.letterbox;
        List<Clip> clips = clips();
        Clip activeClip = findActiveClip(globalTime);

        if (activeClip == null) {
            letterbox.setAspectRatio(0.0f);
            lastClipIdx = -1;
            return;
        }

        int clipIdx = clips.indexOf(activeClip);
        float localTime = clipTime(activeClip, globalTime);
        List<Keyframe> kfs = activeClip.getKeyframes();

        if (kfs == null || kfs.isEmpty()) {
            lastClipIdx = clipIdx;
            return;
        }

        float ratio;
        if (kfs.size() < 2) {
            ratio = kfs.get(0).getAspectRatio();
        } else {
            Keyframe from = kfs.get(0), to = kfs.get(kfs.size() - 1);
            for (int i = 0; i < kfs.size() - 1; i++) {
                if (localTime >= kfs.get(i).getTime() && localTime <= kfs.get(i + 1).getTime()) {
                    from = kfs.get(i);
                    to = kfs.get(i + 1);
                    break;
                }
            }
            float t = (to.getTime() - from.getTime() > 0.001f)
                    ? (localTime - from.getTime()) / (to.getTime() - from.getTime()) : 0f;
            t = Math.max(0f, Math.min(1f, t));
            // E3：smoothstep（3t²-2t³）缓入缓出，letterbox 出入场变平滑
            t = t * t * (3f - 2f * t);
            ratio = from.getAspectRatio() + (to.getAspectRatio() - from.getAspectRatio()) * t;
        }

        if (clipIdx != lastClipIdx || Math.abs(letterbox.getAspectRatio() - ratio) > 0.001f) {
            letterbox.setAspectRatio(ratio);
        }
        lastClipIdx = clipIdx;
    }

    @Override
    public void onStop() {
        // per-instance：本层随本轨道退场移除（不再共写全局单例，其他实例黑边不受影响）
        overlayManager.removeLayer(letterbox);
    }

    /** 组 A：数据替换后复位 clip 索引状态 */
    @Override
    public void onScriptReplaced() {
        lastClipIdx = -1;
    }

    private float clipTime(Clip clip, float globalTime) {
        return Math.max(0f, Math.min(clip.getDuration(), globalTime - clip.getStartTime()));
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
