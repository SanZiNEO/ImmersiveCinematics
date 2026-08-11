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

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;
    private final Vec3 originPos;
    private final Map<Clip, CinematicAudioInstance> instances = new HashMap<>();
    private int lastClipIndex = -1;

    /** 组 1：暂停状态（对齐 MC SoundEngine：暂停时不创建实例、不更新、不启动任何声音） */
    private boolean paused = false;

    /** 记录每个 clip 的当前已触发时间，用于检测 clip 边界（active→inactive）。 */
    private final Set<Clip> previouslyActive = new HashSet<>();

    /** 组 A：动态数据源（replaceScript 后自动用新数据，零重建） */
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
        // 持续压制 MC 背景音乐（每帧停一次，MusicManager 就不会启动新曲）—
        // 放在暂停早退之前：编辑器会话内（含暂停态）始终替换 MC 音乐，保持旧行为。
        Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);

        // 组 1：暂停时不创建实例、不更新、不启动任何声音（对齐 MC SoundEngine tickNonPaused 语义）
        if (paused) return;
        Clip activeClip = findActiveClip(globalTime);
        List<Clip> clips = clips();

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
        paused = false;   // 组 1：停止后复位暂停状态
        for (CinematicAudioInstance inst : instances.values()) {
            inst.cleanup();
        }
        instances.clear();
        previouslyActive.clear();
        lastClipIndex = -1;
    }

    /**
     * 组 A：脚本数据替换（编辑器编辑）后，把旧实例重映射到新 clip 对象——
     * 按 sound+startTime+duration 匹配复用（不重解码、不重建），失配的清理。
     * 随后 CameraManager 会调用 repositionAudio 把实例 seek 到播放头。
     */
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

    /**
     * 组 1：幂等暂停 — 仅在暂停转换时暂停实例（对齐 MC SoundEngine.pause()：只转换时操作源）。
     * 每帧同步只置标志不碰源——否则 PLAYING 源每帧被 alSourcePlay 反复触发 → 周期性从头重启（"滴滴"噪音）。
     */
    public void pauseAll() {
        if (!paused) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("audio pause: instances={}", instances.size());
            }
            for (CinematicAudioInstance inst : instances.values()) {
                inst.pause();
            }
        }
        paused = true;
    }

    /** 组 1：幂等恢复 — 仅在恢复转换时恢复实例（对齐 MC SoundEngine.resume()） */
    public void resumeAll() {
        if (paused) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("audio resume: instances={}", instances.size());
            }
            for (CinematicAudioInstance inst : instances.values()) {
                inst.resume();
            }
        }
        paused = false;
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

        // Set initial attenuation（relative_camera = 播报语义，强制无衰减恒定音量）
        if ("relative_camera".equals(clip.getAudioPositionMode())) {
            inst.setAttenuation("none");
        } else {
            inst.setAttenuation(clip.getAttenuation());
        }

        // Set initial volume (fade_in starts at 0, multiplied by MC music volume)
        float musicVol = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC);
        float initialVol = fadeIn > 0f ? 0f : clip.getVolume() * musicVol;
        inst.setVolume(initialVol);

        // Set initial position（relative = 跟随玩家；relative_camera = 跟随相机；absolute = 世界坐标）
        Vec3 pos = resolveAudioPosition(clip, getInterpolatedPosition(clip, 0f));
        inst.setPosition(pos);

        inst.play();
        instances.put(clip, inst);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("audio start: sound={} instances={} paused={}", sound, instances.size(), paused);
        }
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

        // 组 4：音量链路诊断日志（debug 级）— 确认 eff/interp/fade/music 实际值
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("audio vol: eff={} interp={} fade={} music={} local={}",
                    effectiveVolume, interpolatedVolume, fadeFactor, musicVol, localTime);
            // 输出层诊断：源状态（4112=AL_SOURCE_STATE）/ 播放偏移 / 实际增益 / OpenAL 错误码
            LOGGER.debug("audio state: state={} offset={} gain={} err={}",
                    inst.getSourceState(), inst.getCurrentTime(), inst.getGain(), inst.getOpenAlError());
        }

        // Update position（relative = 每帧玩家当前位置 + 偏移，跟随玩家；relative_camera = 相机位置 + 偏移，跟随镜头播报）
        inst.setPosition(resolveAudioPosition(clip, new Vec3(ix, iy, iz)));

        inst.update();
    }

    /**
     * 音频音源位置求值：
     * position_mode = "relative"（默认）→ 每帧玩家当前位置 + 偏移（跟随玩家，随身声，可走空间衰减）
     * position_mode = "relative_camera" → 每帧相机当前位置 + 偏移（跟随镜头，播报/旁白；
     *               配合强制无衰减 = 恒定音量，镜头飞远也听得到）
     * position_mode = "absolute" → 偏移直接作为世界坐标（音源固定）
     */
    private Vec3 resolveAudioPosition(Clip clip, Vec3 offset) {
        String mode = clip.getAudioPositionMode();
        if ("relative_camera".equals(mode)) {
            return com.immersivecinematics.immersive_cinematics.camera.CameraManager.INSTANCE
                    .getPath().getPosition().add(offset);
        }
        if ("relative".equals(mode)) {
            return Minecraft.getInstance().player.position().add(offset);
        }
        return offset;
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
        return Math.max(0f, Math.min(clip.getDuration(), globalTime - clip.getStartTime()));
    }
    /**
     * Reposition all active audio instances to match a new global time.
     * Used by the editor when the playhead is dragged to a new position.
     */
    public void repositionAudio(float globalTime) {
        Clip activeClip = findActiveClip(globalTime);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("audio reposition: global={} active={} instances={}", globalTime, activeClip != null, instances.size());
        }
        Iterator<Map.Entry<Clip, CinematicAudioInstance>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Clip, CinematicAudioInstance> e = it.next();
            Clip clip = e.getKey();
            CinematicAudioInstance inst = e.getValue();
            if (clip == activeClip) {
                // 组 2：active 实例保留在 map（下一帧 onRenderFrame 正常 update，不重复创建）
                float local = clipTime(clip, globalTime);
                float vol = interpolateFloat(clip, local, "volume", clip.getVolume());
                if (paused) {
                    // 暂停时定位：仅 seek 不播放（用户语义：拖动播放头音频不触发）
                    inst.seekTo(local);
                    inst.setVolume(vol);
                } else {
                    inst.syncToTime(local, vol, clip.getFadeIn());
                }
            } else {
                // 组 2：非 active 实例迭代清理（不再 instances.clear() 泄漏 active 实例）
                inst.cleanup();
                it.remove();
            }
        }
        // 组 2：定位到从未创建实例的 clip（如直接拖入另一 clip 内部）→ 补创建并按暂停态处理
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
