package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AUDIO 轨道播放器 — 回归原版 SoundEngine（0.3.5 第4轮）。
 * <p>
 * 不再直接操作 OpenAL：实例为 {@link CinematicAudioInstance}（AbstractTickableSoundInstance），
 * 由 SoundEngine 统一管理空间/衰减/分类音量/暂停。每个 Clip 对应一个实例，clip 激活时创建并 play。
 */
public class AudioTrackPlayer implements TrackPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Audio");

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;
    private final Vec3 originPos;
    private final Map<Clip, CinematicAudioInstance> instances = new HashMap<>();
    private int lastClipIndex = -1;

    /** 组 1：暂停状态（对齐 MC SoundEngine） */
    private boolean paused = false;

    private final Set<Clip> previouslyActive = new HashSet<>();

    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
    }

    public AudioTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, Vec3 originPos, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.trackIndex = trackIndex;
        this.originPos = originPos;
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        // 精准压制 vanilla 背景音乐（只停 MusicManager 曲目，不影响我们自己的 SoundEngine 实例）
        Minecraft.getInstance().getMusicManager().stopPlaying();

        // 组 1：暂停时不创建实例、不更新、不启动任何声音
        if (paused) return;
        Clip activeClip = findActiveClip(globalTime);
        List<Clip> clips = clips();

        Iterator<Map.Entry<Clip, CinematicAudioInstance>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Clip, CinematicAudioInstance> entry = it.next();
            Clip clip = entry.getKey();
            CinematicAudioInstance inst = entry.getValue();

            if (clip == activeClip) {
                updateClipInstance(clip, inst, globalTime);
            } else {
                float fadeOut = clip.getFadeOut();
                float clipEnd = clip.getStartTime() + clip.getDuration();
                float elapsedSinceEnd = globalTime - clipEnd;

                if (fadeOut > 0f && elapsedSinceEnd < fadeOut && elapsedSinceEnd >= 0f) {
                    float fadeFactor = 1f - (elapsedSinceEnd / fadeOut);
                    fadeFactor = Math.max(0f, Math.min(1f, fadeFactor));
                    Keyframe lastKf = getLastKeyframe(clip);
                    float baseVol = lastKf != null ? lastKf.getFloat("volume", clip.getVolume()) : clip.getVolume();
                    inst.setVolume(baseVol * fadeFactor);
                    inst.update();
                } else {
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

        if (!instances.containsKey(activeClip)) {
            startClipInstance(activeClip);
        }

        int clipIdx = clips.indexOf(activeClip);
        lastClipIndex = clipIdx;
        previouslyActive.add(activeClip);
    }

    @Override
    public void onStop() {
        paused = false;
        for (CinematicAudioInstance inst : instances.values()) {
            inst.cleanup();
        }
        instances.clear();
        previouslyActive.clear();
        lastClipIndex = -1;
    }

    @Override
    public void onScriptReplaced() {
        List<Clip> newClips = clips();
        Map<Clip, CinematicAudioInstance> remapped = new HashMap<>();
        for (Map.Entry<Clip, CinematicAudioInstance> e : instances.entrySet()) {
            Clip old = e.getKey();
            CinematicAudioInstance inst = e.getValue();
            Clip match = null;
            for (Clip nc : newClips) {
                if (java.util.Objects.equals(nc.getSound(), old.getSound())
                        && Math.abs(nc.getStartTime() - old.getStartTime()) < 0.001f
                        && Math.abs(nc.getDuration() - old.getDuration()) < 0.001f) {
                    match = nc;
                    break;
                }
            }
            if (match != null) {
                remapped.put(match, inst);
            } else {
                inst.cleanup();
            }
        }
        instances.clear();
        instances.putAll(remapped);
        lastClipIndex = -1;
        previouslyActive.clear();
    }

    /** 组 1：幂等暂停 — 走原版 SoundEngine 全局暂停 */
    public void pauseAll() {
        if (!paused) {
            Minecraft.getInstance().getSoundManager().pause();
        }
        paused = true;
    }

    /** 组 1：幂等恢复 — 走原版 SoundEngine 全局恢复 */
    public void resumeAll() {
        if (paused) {
            Minecraft.getInstance().getSoundManager().resume();
        }
        paused = false;
    }

    private void startClipInstance(Clip clip) {
        String sound = clip.getSound();
        if (sound == null || sound.isEmpty()) {
            LOGGER.warn("AUDIO clip at time {} has no sound field, skipping", clip.getStartTime());
            return;
        }

        float dur = clip.getDuration();
        float fadeIn = clip.getFadeIn();
        float fadeOut = clip.getFadeOut();
        if (dur > 0f && fadeIn + fadeOut > dur) {
            LOGGER.warn("AUDIO clip '{}' fade_in+fade_out ({}+{}) exceeds duration ({}), skipping",
                    sound, fadeIn, fadeOut, dur);
            return;
        }

        SoundSource category = parseCategory(clip.getString("category", "music"));
        CinematicAudioInstance inst = new CinematicAudioInstance(
                sound, clip.getSource(), clip.isLoop(), clip.getAudioPitch(), category);

        if (!inst.isValid()) {
            LOGGER.error("Failed to create audio instance for: {}", sound);
            return;
        }

        float initialVol = fadeIn > 0f ? 0f : clip.getVolume();
        inst.setVolume(initialVol);

        // 两类语义：music=背景音（非空间，强制无衰减/relative）；ambient=环境音（空间，按 position_mode+attenuation）
        if (category == SoundSource.MUSIC) {
            inst.setRelative(true);
            inst.setAttenuation("none");
            inst.setPosition(Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.position() : Vec3.ZERO);
        } else {
            Vec3 offset = getInterpolatedPosition(clip, 0f);
            if ("absolute".equals(clip.getAudioPositionMode())) {
                inst.setAttenuation(clip.getAttenuation());
            } else {
                inst.setAttenuation("none");
            }
            inst.setPosition(resolveAudioPosition(clip, offset));
        }

        inst.play();
        instances.put(clip, inst);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("audio start: sound={} category={} instances={} paused={}", sound, category, instances.size(), paused);
        }
    }

    private void updateClipInstance(Clip clip, CinematicAudioInstance inst, float globalTime) {
        float localTime = clipTime(clip, globalTime);
        float dur = clip.getDuration();
        boolean infinite = dur < 0f;

        float interpolatedVolume = interpolateFloat(clip, localTime, "volume", clip.getVolume());
        float ix = interpolateFloat(clip, localTime, "x", clip.getFloat("x", 0f));
        float iy = interpolateFloat(clip, localTime, "y", clip.getFloat("y", 0f));
        float iz = interpolateFloat(clip, localTime, "z", clip.getFloat("z", 0f));

        float fadeFactor = 1f;
        float elapsed = localTime;

        float fadeIn = clip.getFadeIn();
        if (fadeIn > 0f && elapsed < fadeIn) {
            fadeFactor = elapsed / fadeIn;
        }
        if (!infinite) {
            float remaining = dur - elapsed;
            float fadeOut = clip.getFadeOut();
            if (fadeOut > 0f && remaining < fadeOut) {
                fadeFactor = remaining / fadeOut;
            }
        }
        fadeFactor = Math.max(0f, Math.min(1f, fadeFactor));

        inst.setVolume(interpolatedVolume * fadeFactor);
        inst.setPosition(resolveAudioPosition(clip, new Vec3(ix, iy, iz)));
        inst.update();
    }

    private Vec3 resolveAudioPosition(Clip clip, Vec3 offset) {
        if ("relative".equals(clip.getAudioPositionMode())) {
            return Minecraft.getInstance().player.position().add(offset);
        }
        return offset;
    }

    private float interpolateFloat(Clip clip, float localTime, String key, float defaultValue) {
        List<Keyframe> kfs = clip.getKeyframes();
        if (kfs == null || kfs.isEmpty()) {
            return defaultValue;
        }
        if (kfs.size() < 2) {
            return kfs.get(0).getFloat(key, defaultValue);
        }

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

    private Vec3 getInterpolatedPosition(Clip clip, float localTime) {
        return new Vec3(
                interpolateFloat(clip, localTime, "x", clip.getFloat("x", 0f)),
                interpolateFloat(clip, localTime, "y", clip.getFloat("y", 0f)),
                interpolateFloat(clip, localTime, "z", clip.getFloat("z", 0f)));
    }

    private static Keyframe getLastKeyframe(Clip clip) {
        List<Keyframe> kfs = clip.getKeyframes();
        if (kfs == null || kfs.isEmpty()) return null;
        return kfs.get(kfs.size() - 1);
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

    private static float clipTime(Clip clip, float globalTime) {
        float local = globalTime - clip.getStartTime();
        if (clip.getDuration() < 0f) {
            return Math.max(0f, local);
        }
        return Math.max(0f, Math.min(clip.getDuration(), local));
    }

    private static SoundSource parseCategory(String category) {
        if ("ambient".equalsIgnoreCase(category)) return SoundSource.AMBIENT;
        // 背景音（music 及任何其它/旧值）统一归 MUSIC——只保留两类，不写兼容
        return SoundSource.MUSIC;
    }

    /** 编辑器 reposition：seek/sync 精确实现属于第 E 项；当前保留 API 结构 */
    public void repositionAudio(float globalTime) {
        Clip activeClip = findActiveClip(globalTime);
        Iterator<Map.Entry<Clip, CinematicAudioInstance>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Clip, CinematicAudioInstance> e = it.next();
            Clip clip = e.getKey();
            CinematicAudioInstance inst = e.getValue();
            if (clip == activeClip) {
                float local = clipTime(clip, globalTime);
                float vol = interpolateFloat(clip, local, "volume", clip.getVolume());
                if (paused) {
                    inst.seekTo(local);
                    inst.setVolume(vol);
                } else {
                    inst.syncToTime(local, vol, clip.getFadeIn());
                }
            } else {
                inst.cleanup();
                it.remove();
            }
        }
        if (activeClip != null && !instances.containsKey(activeClip)) {
            startClipInstance(activeClip);
            CinematicAudioInstance inst = instances.get(activeClip);
            if (inst != null) {
                float local = clipTime(activeClip, globalTime);
                float vol = interpolateFloat(activeClip, local, "volume", activeClip.getVolume());
                if (paused) {
                    inst.pause();
                    inst.seekTo(local);
                    inst.setVolume(vol);
                } else {
                    inst.syncToTime(local, vol, activeClip.getFadeIn());
                }
            }
        }
        previouslyActive.clear();
    }
}
