package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AUDIO 轨道播放器 — 通过 LWJGL OpenAL 驱动多音源 OGG 播放。
 * <p>
 * 每个 Clip 对应一个 {@link CinematicAudioInstance}，在 clip 激活时创建并播放。
 * 支持关键帧插值（volume/x/y/z）、淡入/淡出、空间位置（relative/absolute）。
 */
public class AudioTrackPlayer implements TrackPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Audio");

    private final List<Clip> clips;
    private final Vec3 originPos;
    private final Map<Clip, CinematicAudioInstance> instances = new HashMap<>();
    private int lastClipIndex = -1;

    /** 记录每个 clip 的当前已触发时间，用于检测 clip 边界（active→inactive）。 */
    private final Set<Clip> previouslyActive = new HashSet<>();


    public AudioTrackPlayer(TimelineTrack track, Vec3 originPos) {
        this.clips = track.getClips();
        this.originPos = originPos;
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        Clip activeClip = findActiveClip(globalTime);

        // Handle clip transition: previously active clips that are no longer active
        Iterator<Map.Entry<Clip, CinematicAudioInstance>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Clip, CinematicAudioInstance> entry = it.next();
            Clip clip = entry.getKey();
            CinematicAudioInstance inst = entry.getValue();

            if (clip == activeClip) {
                // Still active — update interpolation
                updateClipInstance(clip, inst, globalTime);
            } else {
                // Was active, now inactive — fade out and cleanup
                float fadeOut = clip.getFadeOut();
                float clipEnd = clip.getStartTime() + clip.getDuration();
                float elapsedSinceEnd = globalTime - clipEnd;

                if (fadeOut > 0f && elapsedSinceEnd < fadeOut && elapsedSinceEnd >= 0f) {
                    // During fade-out period
                    float fadeFactor = 1f - (elapsedSinceEnd / fadeOut);
                    fadeFactor = Math.max(0f, Math.min(1f, fadeFactor));
                    Keyframe lastKf = getLastKeyframe(clip);
                    float baseVol = lastKf != null ? lastKf.getFloat("volume", clip.getVolume()) : clip.getVolume();
                    float musicVol = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC);
                    inst.setVolume(baseVol * fadeFactor * musicVol);
                    inst.update();
                } else {
                    // Fade out complete or no fade — stop and cleanup
                    inst.cleanup();
                    it.remove();
                    previouslyActive.remove(clip);
                }
            }
        }
        if (activeClip == null) {
            lastClipIndex = -1;
            return;
        }

        // 持续压制 MC 背景音乐（每帧停一次，MusicManager 就不会启动新曲）
        Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);

        // New clip became active
        if (!instances.containsKey(activeClip)) {
            startClipInstance(activeClip);
        }

        int clipIdx = clips.indexOf(activeClip);
        lastClipIndex = clipIdx;
        previouslyActive.add(activeClip);
    }

    @Override
    public void onStop() {
        for (CinematicAudioInstance inst : instances.values()) {
            inst.cleanup();
        }
        instances.clear();
        previouslyActive.clear();
        lastClipIndex = -1;
    }

    private void startClipInstance(Clip clip) {
        String sound = clip.getSound();
        if (sound == null || sound.isEmpty()) {
            LOGGER.warn("AUDIO clip at time {} has no sound field, skipping", clip.getStartTime());
            return;
        }

        // Validate fade times against clip duration
        float dur = clip.getDuration();
        float fadeIn = clip.getFadeIn();
        float fadeOut = clip.getFadeOut();
        if (dur > 0f) {
            if (fadeIn + fadeOut > dur) {
                LOGGER.warn("AUDIO clip '{}' fade_in+fade_out ({}+{}) exceeds duration ({}), skipping",
                        sound, fadeIn, fadeOut, dur);
                return;
            }
        }

        CinematicAudioInstance inst = new CinematicAudioInstance(
                sound, clip.getSource(), clip.isLoop(), clip.getAudioPitch());

        if (!inst.isValid()) {
            LOGGER.error("Failed to create audio instance for: {}", sound);
            return;
        }

        // Set initial attenuation
        inst.setAttenuation(clip.getAttenuation());

        // Set initial volume (fade_in starts at 0, multiplied by MC music volume)
        float musicVol = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC);
        float initialVol = fadeIn > 0f ? 0f : clip.getVolume() * musicVol;
        inst.setVolume(initialVol);

        // Set initial position
        Vec3 pos = getInterpolatedPosition(clip, 0f);
        if ("relative".equals(clip.getAudioPositionMode())) {
            pos = originPos.add(pos);
        }
        inst.setPosition(pos);

        inst.play();
        instances.put(clip, inst);
    }

    private void updateClipInstance(Clip clip, CinematicAudioInstance inst, float globalTime) {
        float localTime = clipTime(clip, globalTime);
        float dur = clip.getDuration();

        // Interpolate keyframe values
        float interpolatedVolume = interpolateFloat(clip, localTime, "volume", clip.getVolume());
        float ix = interpolateFloat(clip, localTime, "x", 0f);
        float iy = interpolateFloat(clip, localTime, "y", 0f);
        float iz = interpolateFloat(clip, localTime, "z", 0f);

        // Compute fade factor
        float fadeFactor = 1f;
        float clipEnd = clip.getStartTime() + dur;
        float elapsed = localTime;
        float remaining = dur - elapsed;

        float fadeIn = clip.getFadeIn();
        if (fadeIn > 0f && elapsed < fadeIn) {
            fadeFactor = elapsed / fadeIn;
        }

        float fadeOut = clip.getFadeOut();
        if (fadeOut > 0f && remaining < fadeOut) {
            fadeFactor = remaining / fadeOut;
        }

        fadeFactor = Math.max(0f, Math.min(1f, fadeFactor));

        // Apply effective volume (multiplied by MC music volume slider)
        float musicVol = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC);
        float effectiveVolume = interpolatedVolume * fadeFactor * musicVol;
        inst.setVolume(effectiveVolume);

        // Update position
        Vec3 pos = new Vec3(ix, iy, iz);
        if ("relative".equals(clip.getAudioPositionMode())) {
            pos = originPos.add(pos);
        }
        inst.setPosition(pos);

        inst.update();
    }

    /**
     * 线性插值单个 float 关键帧值。
     * 模式同 LetterboxTrackPlayer 的 aspect_ratio 插值。
     */
    private float interpolateFloat(Clip clip, float localTime, String key, float defaultValue) {
        List<Keyframe> kfs = clip.getKeyframes();
        if (kfs == null || kfs.isEmpty()) {
            return defaultValue;
        }

        if (kfs.size() < 2) {
            return kfs.get(0).getFloat(key, defaultValue);
        }

        // Find surrounding keyframes
        Keyframe from = kfs.get(0);
        Keyframe to = kfs.get(kfs.size() - 1);
        boolean found = false;

        for (int i = 0; i < kfs.size() - 1; i++) {
            if (localTime >= kfs.get(i).getTime() && localTime <= kfs.get(i + 1).getTime()) {
                from = kfs.get(i);
                to = kfs.get(i + 1);
                found = true;
                break;
            }
        }

        if (!found && localTime < kfs.get(0).getTime()) {
            return kfs.get(0).getFloat(key, defaultValue);
        }
        if (!found && localTime > kfs.get(kfs.size() - 1).getTime()) {
            return kfs.get(kfs.size() - 1).getFloat(key, defaultValue);
        }

        float t = (to.getTime() - from.getTime() > 0.001f)
                ? (localTime - from.getTime()) / (to.getTime() - from.getTime()) : 0f;
        t = Math.max(0f, Math.min(1f, t));

        float fromVal = from.getFloat(key, defaultValue);
        float toVal = to.getFloat(key, defaultValue);
        return fromVal + (toVal - fromVal) * t;
    }

    /**
     * 获取插值后的位置 Vec3。
     */
    private Vec3 getInterpolatedPosition(Clip clip, float localTime) {
        float x = interpolateFloat(clip, localTime, "x", 0f);
        float y = interpolateFloat(clip, localTime, "y", 0f);
        float z = interpolateFloat(clip, localTime, "z", 0f);
        return new Vec3(x, y, z);
    }

    /**
     * 获取 clip 的最后一个关键帧（用于 fade-out 取最终 volume）。
     */
    private static Keyframe getLastKeyframe(Clip clip) {
        List<Keyframe> kfs = clip.getKeyframes();
        if (kfs == null || kfs.isEmpty()) return null;
        return kfs.get(kfs.size() - 1);
    }

    private Clip findActiveClip(float globalTime) {
        for (Clip clip : clips) {
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

    private static float clipTime(Clip clip, float globalTime) {
        return Math.max(0f, Math.min(clip.getDuration(), globalTime - clip.getStartTime()));
    }
}
