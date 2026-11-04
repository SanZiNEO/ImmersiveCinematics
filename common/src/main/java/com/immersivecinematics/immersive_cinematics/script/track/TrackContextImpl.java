package com.immersivecinematics.immersive_cinematics.script.track;

import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.object.ClipSampler;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import com.immersivecinematics.immersive_cinematics.spi.TrackContext;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.Map;

/**
 * {@link TrackContext} 的实现：片段采样缓冲 + 注册期编译的路径槽位。
 *
 * <p>每帧由 {@link ExternalTrackDriver} 调 {@link #refresh} 采样一次，随后回调读值只做数组下标访问。
 * 缓冲复用，稳态零分配。非线程安全：只在客户端渲染帧主线程使用。</p>
 */
final class TrackContextImpl implements TrackContext {

    private final Clip clip;
    private final ClipSampler sampler;
    private final SampledClip buffer;
    private final ClipValueView values;
    private final Map<String, Integer> slots;
    private final Object owner;
    private final String scriptId;
    private final String instanceId;

    private double localTime;
    private int executionIndex;

    /**
     * @param id         轨道类型 id（决定路径槽位布局）
     * @param clip       当前片段
     * @param player     所属播放器
     * @param trackIndex 轨道索引（当前实现只按 id 取布局，保留给后续按轨特化的实现）
     */
    TrackContextImpl(TrackTypeId id, Clip clip, ScriptPlayer player, int trackIndex) {
        this.clip = clip;
        this.sampler = new ClipSampler(clip);
        this.buffer = sampler.newBuffer();
        this.values = new ClipValueView(clip);
        this.slots = TrackRegistry.pathSlots(id);
        this.owner = player.owner();
        this.scriptId = player.getScriptId();
        this.instanceId = player.owner() instanceof PlaybackInstance instance ? instance.instanceId() : "";
    }

    /**
     * 在指定执行局部时间重采样本片段。
     *
     * @param localTime      执行局部时间（执行层已归一化）
     * @param executionIndex 本次执行序号
     */
    void refresh(double localTime, int executionIndex) {
        buffer.clear();
        sampler.sample(localTime, buffer);
        this.localTime = localTime;
        this.executionIndex = executionIndex;
    }

    @Override
    public Clip clip() {
        return clip;
    }

    @Override
    public double executionLocalTime() {
        return localTime;
    }

    @Override
    public int executionIndex() {
        return executionIndex;
    }

    @Override
    public Object owner() {
        return owner;
    }

    @Override
    public float f(String path) {
        int slot = slot(path);
        return slot < 0 || !buffer.present(slot) ? 0f : buffer.f(slot);
    }

    @Override
    public boolean present(String path) {
        int slot = slot(path);
        return slot >= 0 && buffer.present(slot);
    }

    @Override
    public int i(String path) {
        int slot = slot(path);
        return slot < 0 || !buffer.present(slot) ? 0 : buffer.i(slot);
    }

    @Override
    public boolean b(String path) {
        int slot = slot(path);
        return slot >= 0 && buffer.present(slot) && buffer.b(slot);
    }

    @Override
    public String s(String path) {
        int slot = slot(path);
        return slot < 0 ? null : buffer.s(slot);
    }

    @Override
    public Object struct(String path) {
        return values.struct(path);
    }

    @Override
    public String scriptId() {
        return scriptId;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    private int slot(String path) {
        if (path == null) {
            return -1;
        }
        Integer slot = slots.get(path);
        return slot != null ? slot : -1;
    }
}
