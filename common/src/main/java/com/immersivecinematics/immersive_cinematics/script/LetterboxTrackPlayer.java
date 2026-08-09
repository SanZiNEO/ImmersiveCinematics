package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.overlay.LetterboxLayer;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;

import java.util.List;

public class LetterboxTrackPlayer implements TrackPlayer {

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final OverlayManager overlayManager;
    private int lastClipIdx = -1;

    public LetterboxTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, OverlayManager overlayManager) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.overlayManager = overlayManager;
    }

    /** 组 A：动态数据源（replaceScript 后自动用新数据，零重建） */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(type);
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        LetterboxLayer letterbox = overlayManager.getLetterboxLayer();
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
            ratio = from.getAspectRatio() + (to.getAspectRatio() - from.getAspectRatio()) * t;
        }

        if (clipIdx != lastClipIdx || Math.abs(letterbox.getAspectRatio() - ratio) > 0.001f) {
            letterbox.setAspectRatio(ratio);
        }
        lastClipIdx = clipIdx;
    }

    @Override
    public void onStop() {
        overlayManager.getLetterboxLayer().setAspectRatio(0.0f);
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
