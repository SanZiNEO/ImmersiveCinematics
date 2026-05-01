package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.overlay.LetterboxLayer;

import java.util.List;

/**
 * Letterbox 轨道播放器 — 从 ScriptPlayer.processLetterboxTrack() 抽取
 * <p>
 * 职责：
 * <ul>
 *   <li>定位当前活跃的 LetterboxClip</li>
 *   <li>设置 LetterboxLayer 的 aspectRatio / fadeIn / fadeOut</li>
 *   <li>当无活跃 clip 时触发 fade-out</li>
 * </ul>
 */
public class LetterboxTrackPlayer implements TrackPlayer {

    private final List<LetterboxClip> clips;
    private final OverlayManager overlayManager;

    public LetterboxTrackPlayer(TimelineTrack track, OverlayManager overlayManager) {
        this.clips = track.getLetterboxClips();
        this.overlayManager = overlayManager;
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        LetterboxLayer letterbox = overlayManager.getLetterboxLayer();

        LetterboxClip activeClip = findActiveClip(globalTime);

        if (activeClip != null && activeClip.isEnabled()) {
            // letterbox clip 活跃：确保 letterbox 层可见
            // LetterboxLayer 的 fade-in/fade-out 由自身的 tick() 动画驱动
            // 只需设置 aspectRatio（如果当前 HIDDEN 会自动触发 FADE_IN）
            if (letterbox.getAspectRatio() != activeClip.getAspectRatio()) {
                letterbox.setFadeIn(activeClip.getFadeIn());
                letterbox.setFadeOut(activeClip.getFadeOut());
                letterbox.setAspectRatio(activeClip.getAspectRatio());
            }
        } else if (activeClip == null) {
            // 不在任何 letterbox clip 中：触发 fade-out
            letterbox.startFadeOut();
        }
    }

    @Override
    public void onStop() {
        // 停止时触发 fade-out
        overlayManager.getLetterboxLayer().startFadeOut();
    }

    /**
     * 找到当前全局时间所在的 letterbox clip
     * <p>
     * 支持无限时长 clip（duration=-1）
     */
    private LetterboxClip findActiveClip(float globalTime) {
        for (LetterboxClip clip : clips) {
            boolean isActive;
            if (clip.getDuration() < 0) {
                // 无限时长 clip：一旦进入就永远活跃
                isActive = globalTime >= clip.getStartTime();
            } else {
                float clipEnd = clip.getStartTime() + clip.getDuration();
                isActive = globalTime >= clip.getStartTime() && globalTime < clipEnd;
            }
            if (isActive) {
                return clip;
            }
        }
        return null;
    }
}
