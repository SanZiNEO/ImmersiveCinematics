package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectSlotLayout;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import com.immersivecinematics.immersive_cinematics.script.track.TrackDriver;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AUDIO 轨道播放器：实例为 {@link CinematicAudioInstance}（AbstractTickableSoundInstance），
 * 空间 / 衰减 / 分类音量 / 暂停全部交给原版 SoundEngine。
 * 每个 Clip 对应一个实例，clip 激活时创建并 play；规则动作 {@code play_sound} 走一次性声音
 * （{@link #playOneShot}），与 clip 实例分开装在 {@link #oneShots} 里（不属任何 clip），由
 * {@link #pruneOneShots} / {@link #stopOneShots} / {@link #onStop} 管理生命周期。
 * 声音引擎出口收在 {@link SoundSink} 后（探针可注入桩）。
 */
public class AudioTrackPlayer implements TrackDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/Audio");

    /** 生产出口：原版 SoundEngine（全局暂停 / 恢复也走它，见 {@link #pauseAll}）。 */
    private static final SoundSink ENGINE_SINK = new SoundSink() {
        @Override
        public void play(SoundInstance instance) {
            Minecraft.getInstance().getSoundManager().play(instance);
        }

        @Override
        public void stop(SoundInstance instance) {
            Minecraft.getInstance().getSoundManager().stop(instance);
        }

        @Override
        public boolean isActive(SoundInstance instance) {
            return Minecraft.getInstance().getSoundManager().isActive(instance);
        }
    };

    /** 声音引擎出口（play / stop / isActive 三个动作；无头探针注入桩即可验证一次性声音的生命周期）。 */
    public interface SoundSink {
        void play(SoundInstance instance);

        void stop(SoundInstance instance);

        boolean isActive(SoundInstance instance);
    }

    private final ScriptPlayer scriptPlayer;
    private final int trackIndex;
    private final Vec3 originPos;
    private final Map<Clip, CinematicAudioInstance> instances = new HashMap<>();
    /** 创建失败的音频 clip：跳过并只提示一次，避免每帧刷异常 */
    private final Set<Clip> failedClips = new HashSet<>();
    private int lastClipIndex = -1;

    /** AUDIO 对象槽位（volume/x/y/z）：类加载时按名字解析一次，帧内直接读数组。 */
    private static final int SLOT_VOLUME = audioSlot("volume");
    private static final int SLOT_X = audioSlot("x");
    private static final int SLOT_Y = audioSlot("y");
    private static final int SLOT_Z = audioSlot("z");

    /** 片段的采样缓存（按对象身份复用采样器 / 缓冲 / value 视图）。 */
    private final ClipRuntimeCache runtime = new ClipRuntimeCache(TrackTypeId.AUDIO);

    /**
     * 规则动作 {@code play_sound} 的一次性声音：独立于按 clip 键的 {@link #instances}——
     * 不属任何 clip，随实例生命周期清理（{@link #pruneOneShots} 帧内回收已播完的、{@link #onStop} 全停清空、
     * {@link #stopOneShots} 挂起时停本实例的一次性声音）。
     */
    private final List<SoundInstance> oneShots = new ArrayList<>();

    /** 声音引擎出口（原版 {@code SoundManager} 的薄封装）；探针可注入桩（无头环境无 SoundEngine）。 */
    private SoundSink sink = ENGINE_SINK;

    /** 暂停状态（对齐 MC SoundEngine 的全局暂停语义） */
    private boolean paused = false;

    private final Set<Clip> previouslyActive = new HashSet<>();

    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
    }

    public AudioTrackPlayer(ScriptPlayer scriptPlayer, Vec3 originPos, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.trackIndex = trackIndex;
        this.originPos = originPos;
    }

    @Override
    public boolean isActiveAt(double globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(double globalTime) {
        // 精准压制 vanilla 背景音乐（只停 MusicManager 曲目，不影响我们自己的 SoundEngine 实例）
        Minecraft.getInstance().getMusicManager().stopPlaying();

        // 暂停时不创建实例、不更新、不启动任何声音
        if (paused) return;
        Clip activeClip = findActiveClip(globalTime);
        List<Clip> clips = clips();

        Iterator<Map.Entry<Clip, CinematicAudioInstance>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Clip, CinematicAudioInstance> entry = it.next();
            Clip clip = entry.getKey();
            CinematicAudioInstance inst = entry.getValue();

            if (clip == activeClip) {
                if (inst.isFailed()) {
                    // 解码失败：本帧回收并记入 failedClips，后续帧不再重建/重试
                    inst.cleanup();
                    it.remove();
                    failedClips.add(clip);
                    previouslyActive.remove(clip);
                    continue;
                }
                updateClipInstance(clip, inst, globalTime);
            } else {
                if (clip.isEffectivelyInfinite()) {
                    // 无限执行没有有限窗口末端：不淡出，直接回收
                    inst.cleanup();
                    it.remove();
                    previouslyActive.remove(clip);
                    continue;
                }
                double fadeOut = fadeOutOf(clip);
                double clipEnd = scriptPlayer.finiteWindowEnd(clip);
                double elapsedSinceEnd = globalTime - clipEnd;

                if (fadeOut > 0.0 && elapsedSinceEnd < fadeOut && elapsedSinceEnd >= 0.0) {
                    double fadeFactor = 1.0 - (elapsedSinceEnd / fadeOut);
                    fadeFactor = Math.max(0.0, Math.min(1.0, fadeFactor));
                    float baseVol = volumeAt(clip, clip.getDuration());
                    inst.setVolume((float) (baseVol * fadeFactor));
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
            startClipInstance(activeClip, scriptPlayer.executionLocalTime(activeClip, globalTime));
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
        stopOneShots();
        previouslyActive.clear();
        failedClips.clear();
        runtime.clear();
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
                if (java.util.Objects.equals(soundOf(nc), soundOf(old))
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
        failedClips.clear();
        lastClipIndex = -1;
        previouslyActive.clear();
        // 片段对象已换：采样器 / 缓冲 / value 视图全部失效（重映射用过的旧片段条目一并丢弃）
        runtime.clear();
    }

    /** 幂等暂停：走原版 SoundEngine 全局 pause（本实现无 per-instance 暂停语义） */
    public void pauseAll() {
        if (!paused) {
            Minecraft.getInstance().getSoundManager().pause();
        }
        paused = true;
    }

    /** 幂等恢复：走原版 SoundEngine 全局 resume */
    public void resumeAll() {
        if (paused) {
            Minecraft.getInstance().getSoundManager().resume();
        }
        paused = false;
    }

    // ========== 一次性声音（规则动作 play_sound） ==========

    /**
     * 播一条一次性声音（{@code play_sound} 动作）：相对听者、无衰减的本机声音实例，记进 {@link #oneShots}
     * 容器（不属任何 clip，不进 {@link #instances}）。
     *
     * @param sound    音效事件（已解析；调用方负责未知 id 的日志）
     * @param category 声音分类（分类音量 / 静音随原版走）
     * @param volume   音量（1 = 原值）
     * @param pitch    音调（1 = 原值）
     */
    public void playOneShot(SoundEvent sound, SoundSource category, float volume, float pitch) {
        if (sound == null) return;
        playOneShot(sound.getLocation(), category, volume, pitch);
    }

    /**
     * 按音效 id 播一条一次性声音（同一实现的 id 入口；规则动作已在上游把 id 解析成 {@link SoundEvent}）。
     */
    void playOneShot(ResourceLocation soundId, SoundSource category, float volume, float pitch) {
        if (soundId == null) return;
        SoundInstance instance = new SimpleSoundInstance(soundId, category, volume, pitch,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                0.0, 0.0, 0.0, true);
        oneShots.add(instance);
        sink.play(instance);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("one-shot start: sound={} category={} active={}",
                    soundId, category, oneShots.size());
        }
    }

    /** 帧内回收已播完的一次性声音（SoundEngine 不再持有即移除；索引倒序遍历，零新增分配）。 */
    public void pruneOneShots() {
        for (int i = oneShots.size() - 1; i >= 0; i--) {
            if (!sink.isActive(oneShots.get(i))) {
                oneShots.remove(i);
            }
        }
    }

    /**
     * 停本实例的一次性声音并清空容器（规则挂起 / 停播；幂等）。
     * 只动本实例的一次性声音：音轨实例（{@link #instances}）走全局 SoundEngine 暂停链路
     * （{@link #pauseAll} = 全局 pause，非 per-instance），一次性声音没有「续播」语义，挂起即停。
     */
    public void stopOneShots() {
        if (oneShots.isEmpty()) return;
        for (int i = 0; i < oneShots.size(); i++) {
            sink.stop(oneShots.get(i));
        }
        oneShots.clear();
    }

    private void startClipInstance(Clip clip, double localTime) {
        ClipValueView values = runtime.values(clip);
        String sound = values.s("sound", "");
        if (sound == null || sound.isEmpty()) {
            // 只报一次并记住，避免每帧重试刷日志
            if (failedClips.add(clip)) {
                LOGGER.warn("AUDIO clip at time {} has no sound object, skipping", clip.getStartTime());
            }
            return;
        }

        if (failedClips.contains(clip)) return;

        double dur = clip.getDuration();
        double fadeIn = values.f("fade_in", 0f);
        double fadeOut = values.f("fade_out", 0f);
        if (dur > 0.0 && fadeIn + fadeOut > dur) {
            LOGGER.warn("AUDIO clip '{}' fade_in+fade_out ({}+{}) exceeds duration ({}), skipping",
                    sound, fadeIn, fadeOut, dur);
            return;
        }

        SoundSource category = parseCategory(values.s("category", "music"));
        CinematicAudioInstance inst;
        try {
            inst = new CinematicAudioInstance(
                    sound,
                    values.s("source", "file"),
                    values.b("file_loop", false),
                    values.f("pitch", 1f),
                    category);
        } catch (RuntimeException e) {
            // 缺失/无法加载的音频：只报一次并跳过该 clip，不再每帧重试
            LOGGER.error("AUDIO clip failed to start: {} ({})", sound, e.getMessage());
            failedClips.add(clip);
            return;
        }

        if (!inst.isValid()) {
            LOGGER.error("Failed to create audio instance for: {}", sound);
            return;
        }

        // 按当前执行局部时间采样，位置/音量与中段起播一致（不再假设从 0 开始）
        SampledClip sample = runtime.sample(clip, localTime);
        double fadeFactor = fadeEnvelope(localTime, dur, clip.isEffectivelyInfinite(), fadeIn, fadeOut);
        inst.setVolume((float) (audioValue(sample, SLOT_VOLUME, 1f) * fadeFactor));

        // 两类语义：music=背景音（非空间，强制无衰减/relative）；ambient=环境音（空间，按 position_mode+attenuation）
        if (category == SoundSource.MUSIC) {
            inst.setRelative(true);
            inst.setAttenuation("none");
            inst.setPosition(Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.position() : Vec3.ZERO);
        } else {
            Vec3 offset = new Vec3(audioValue(sample, SLOT_X, 0f), audioValue(sample, SLOT_Y, 0f),
                    audioValue(sample, SLOT_Z, 0f));
            // 相对模式也允许空间衰减：声音在世界坐标 = 玩家 + offset，由原版按听者距离衰减
            inst.setAttenuation(values.s("attenuation", "linear"));
            inst.setPosition(resolveAudioPosition(values.s("position_mode", "relative"), offset));
        }

        inst.play();
        instances.put(clip, inst);
        if (Config.debugLogging) {
            LOGGER.info("audio-start clip={} instance={} sound={} local={}",
                    clip.getClipId(), System.identityHashCode(inst), sound, String.format("%.3f", localTime));
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("audio start: sound={} category={} instances={} paused={}", sound, category, instances.size(), paused);
        }
    }

    private void updateClipInstance(Clip clip, CinematicAudioInstance inst, double globalTime) {
        double localTime = scriptPlayer.executionLocalTime(clip, globalTime);
        double dur = clip.getDuration();

        // volume/x/y/z 来自 SampledClip；infinite 用执行语义（无限执行循环也不淡出）
        ClipValueView values = runtime.values(clip);
        SampledClip sample = runtime.sample(clip, localTime);
        double fadeIn = values.f("fade_in", 0f);
        double fadeOut = values.f("fade_out", 0f);
        double fadeFactor = fadeEnvelope(localTime, dur, clip.isEffectivelyInfinite(), fadeIn, fadeOut);
        float vol = (float) (audioValue(sample, SLOT_VOLUME, 1f) * fadeFactor);
        inst.setPosition(resolveAudioPosition(values.s("position_mode", "relative"),
                new Vec3(audioValue(sample, SLOT_X, 0f), audioValue(sample, SLOT_Y, 0f),
                        audioValue(sample, SLOT_Z, 0f))));
        // 外部解码未就绪：内部只记待办（静音等待）；就绪且未在播时按当前执行时间起播
        inst.ensurePlaying(localTime, vol);
        inst.update();
    }

    /** fade 包络：fadeIn 起播渐入，fadeOut 收尾渐出（fadeOut 覆盖 fadeIn）。 */
    private static double fadeEnvelope(double localTime, double dur, boolean infinite,
                                       double fadeIn, double fadeOut) {
        double factor = 1.0;
        if (fadeIn > 0.0 && localTime < fadeIn) {
            factor = localTime / fadeIn;
        }
        if (!infinite) {
            double remaining = dur - localTime;
            if (fadeOut > 0.0 && remaining < fadeOut) {
                factor = remaining / fadeOut;
            }
        }
        return Math.max(0.0, Math.min(1.0, factor));
    }

    private Vec3 resolveAudioPosition(String positionMode, Vec3 offset) {
        if ("relative".equals(positionMode)) {
            // 相对模式锚点=当前真实听者：listener=camera 时是镜头，listener=player 时是玩家
            return AudioListenerController.getListenerPosition().add(offset);
        }
        return offset;
    }

    /** 当前音量：取对象采样（未声明 → 缺省 1）。 */
    private float volumeAt(Clip clip, double localTime) {
        return audioValue(runtime.sample(clip, localTime), SLOT_VOLUME, 1f);
    }

    /** 槽位读取：作者声明过用采样值，否则回落 schema 缺省（未声明槽位的数值无意义）。 */
    private static float audioValue(SampledClip sample, int slot, float fallback) {
        return sample.present(slot) ? sample.f(slot) : fallback;
    }

    /** 片段的声音 id（value-only 对象）。 */
    private String soundOf(Clip clip) {
        return runtime.values(clip).s("sound", "");
    }

    /** 片段的 fade_in（value-only 对象）。 */
    private float fadeInOf(Clip clip) {
        return runtime.values(clip).f("fade_in", 0f);
    }

    /** 片段的 fade_out（value-only 对象）。 */
    private float fadeOutOf(Clip clip) {
        return runtime.values(clip).f("fade_out", 0f);
    }

    private static int audioSlot(String path) {
        int slot = ObjectSlotLayout.of(TrackTypeId.AUDIO).slotOf(path);
        if (slot < 0) {
            throw new IllegalStateException("AUDIO 缺少对象声明: " + path);
        }
        return slot;
    }

    private Clip findActiveClip(double globalTime) {
        for (Clip clip : clips()) {
            if (scriptPlayer.isClipActive(clip, globalTime)) return clip;
        }
        return null;
    }

    private static SoundSource parseCategory(String category) {
        if ("ambient".equalsIgnoreCase(category)) return SoundSource.AMBIENT;
        // 背景音（music 及任何其它/旧值）统一归 MUSIC——只保留两类，不写兼容
        return SoundSource.MUSIC;
    }

    /** 编辑器定位：按目标时间把活动 clip 实例重定位（暂停态只 seek，播放态 seek 后重启），其余实例清理 */
    public void repositionAudio(double globalTime) {
        Clip activeClip = findActiveClip(globalTime);
        Iterator<Map.Entry<Clip, CinematicAudioInstance>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Clip, CinematicAudioInstance> e = it.next();
            Clip clip = e.getKey();
            CinematicAudioInstance inst = e.getValue();
            if (clip == activeClip) {
                double local = scriptPlayer.executionLocalTime(clip, globalTime);
                float vol = volumeAt(clip, local);
                if (paused) {
                    inst.seekTo(local);
                    inst.setVolume(vol);
                } else {
                    inst.syncToTime(local, vol, fadeInOf(clip));
                }
            } else {
                inst.cleanup();
                it.remove();
            }
        }
        if (activeClip != null && !instances.containsKey(activeClip)) {
            double local = scriptPlayer.executionLocalTime(activeClip, globalTime);
            startClipInstance(activeClip, local);
            CinematicAudioInstance inst = instances.get(activeClip);
            if (inst != null) {
                float vol = volumeAt(activeClip, local);
                if (paused) {
                    inst.pause();
                    inst.seekTo(local);
                    inst.setVolume(vol);
                } else {
                    inst.syncToTime(local, vol, fadeInOf(activeClip));
                }
            }
        }
        previouslyActive.clear();
    }
}
