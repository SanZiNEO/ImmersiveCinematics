package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放实例注册表：活跃实例列表 + 实例查询 + 帧级相机 clip 缓存。
 *
 * 列表顺序 = 启动顺序（帧驱动按顺序遍历，后写入者覆盖先写入者 → 后来者居上），顶层 = 最后一个；
 * 不变量：预览实例恒为末位（{@link #insertBefore} 把游戏实例插在它之前）。
 * 同脚本单实例只看游戏实例（{@link #instancePlaying(String)}，预览另算）；跨脚本到达裁决按播放模式
 * 查在播集合（{@link #hasGameInstancePlaying(ScriptMeta.PlayMode)}）；控制原语按脚本 / 实例 id
 * 定位（{@link #instanceOf(String, String)} / {@link #instancesOf(String)}，同样排除预览）。
 *
 * 帧缓存（{@link #hasActiveCameraClip()} / {@link #topInstanceHasActiveCameraClip()}）由帧驱动每帧
 * 同一时点写入一次；前者 = 全部实例并集，后者 = 顶层实例自身（听者门控口径）。
 * 缓存只在帧驱动写入：无活跃实例时帧驱动不跑、缓存不刷新，消费方必须以 {@link #isActive()} /
 * 快照非空为准。
 *
 * 线程：仅客户端主线程（渲染线程）。{@link #instances()} 的只读视图随增删实时变化，帧内遍历需自行
 * 防并发修改；查询路径零分配（{@link #instanceBehaviors()} 除外，只在实例增删时调用）。
 */
final class PlaybackRegistry {

    /** 活跃播放实例，按启动顺序（= 叠放顺序）排列。 */
    private final List<PlaybackInstance> instances = new ArrayList<>();

    /** {@link #instances} 的只读视图（避免每次取用都包装一遍；与底层列表同步反映增删）。 */
    private final List<PlaybackInstance> instancesView = Collections.unmodifiableList(instances);

    /** 全部实例本帧活跃 CAMERA clip 的并集 —— 帧级缓存，避免多个 Mixin 调用点每帧重复扫描。 */
    private boolean cachedHasActiveCameraClip = false;

    /** 顶层实例自身本帧是否有活跃 CAMERA clip（与并集同帧同点计算）。 */
    private boolean cachedTopInstanceHasActiveCameraClip = false;

    /** 全部活跃实例，按启动顺序排列（先启动在前，顶层 = 最后一个）——只读视图。 */
    List<PlaybackInstance> instances() {
        return instancesView;
    }

    /** 是否无活跃实例。 */
    boolean isEmpty() {
        return instances.isEmpty();
    }

    /** 是否有活跃实例。 */
    boolean isActive() {
        return !instances.isEmpty();
    }

    /** 活跃实例数。 */
    int size() {
        return instances.size();
    }

    /** 顶层实例 = 启动最晚的活跃实例（列表最后一个）；空表为 {@code null}。 */
    PlaybackInstance topInstance() {
        return instances.isEmpty() ? null : instances.get(instances.size() - 1);
    }

    /** 追加到末位（启动顺序 = 叠放顺序）。 */
    void addLast(PlaybackInstance instance) {
        instances.add(instance);
    }

    /** 插在 {@code anchor} 之前；{@code anchor} 为 {@code null} 或不在表内 = 追加末位。 */
    void insertBefore(PlaybackInstance anchor, PlaybackInstance instance) {
        int at = anchor == null ? -1 : instances.indexOf(anchor);
        instances.add(at >= 0 ? at : instances.size(), instance);
    }

    /** 出列：后续读取不再看到本实例。 */
    void remove(PlaybackInstance instance) {
        instances.remove(instance);
    }

    /** 本表当前是否仍持有该实例（帧内遍历副本时用于跳过本帧更早退出的实例）。 */
    boolean contains(PlaybackInstance instance) {
        return instances.contains(instance);
    }

    /**
     * 正在播放 {@code scriptId} 的游戏实例；无则 {@code null}。
     * 预览实例不参与——预览与游戏实例互不替换，游戏同脚本请求 / 队列可播判定都只看游戏实例。
     *
     * @param scriptId 脚本 id；{@code null} = 无匹配
     */
    PlaybackInstance instancePlaying(String scriptId) {
        if (scriptId == null) return null;
        for (PlaybackInstance instance : instances) {
            if (!instance.isPreview() && instance.player().isPlaying()
                    && scriptId.equals(instance.scriptId())) return instance;
        }
        return null;
    }

    /**
     * 按脚本 + 播放实例 id 定位活跃实例（控制原语：按 id 停非顶层）。
     * {@code instanceId} 空串 = 该脚本的任意实例（返回首个）；非空则要求实例 id 相等。
     *
     * @param scriptId   脚本 id；{@code null} = 无匹配
     * @param instanceId 播放实例 id；空串 / {@code null} = 任意
     * @return 首个匹配的实例；无匹配返回 {@code null}
     */
    PlaybackInstance instanceOf(String scriptId, String instanceId) {
        if (scriptId == null) return null;
        boolean anyInstance = instanceId == null || instanceId.isEmpty();
        for (PlaybackInstance instance : instances) {
            // 预览不参与游戏控制原语：服务端定向 stop 不得命中编辑器预览
            if (instance.isPreview() || !instance.player().isPlaying()
                    || !scriptId.equals(instance.scriptId())) continue;
            if (anyInstance || instanceId.equals(instance.instanceId())) return instance;
        }
        return null;
    }

    /**
     * 全部正在播放 {@code scriptId} 的游戏实例（启动顺序；预览实例另算，不在此列）。
     * 同脚本同玩家保持单实例，故常态至多一个。
     *
     * @param scriptId 脚本 id；{@code null} = 无匹配（返回空列表）
     */
    List<PlaybackInstance> instancesOf(String scriptId) {
        if (scriptId == null) return Collections.emptyList();
        List<PlaybackInstance> matched = new ArrayList<>();
        for (PlaybackInstance instance : instances) {
            if (!instance.isPreview() && instance.player().isPlaying()
                    && scriptId.equals(instance.scriptId())) matched.add(instance);
        }
        return matched;
    }

    /**
     * 是否存在「非预览实例 + 播放模式 = {@code mode}」的在播实例（跨脚本到达裁决的唯一查询面）。
     * 在播 = 仍在实例表中（含渐出中与 {@code hold_at_end} 驻留实例——两者都还在渲染 / 占位）；
     * 预览实例恒不参与（预览通道不套用脚本行为，也不与游戏实例争夺播放关系）。
     *
     * @param mode 目标播放模式；{@code null} = 无匹配
     */
    boolean hasGameInstancePlaying(ScriptMeta.PlayMode mode) {
        if (mode == null) return false;
        for (int i = 0; i < instances.size(); i++) {
            PlaybackInstance instance = instances.get(i);
            if (!instance.isPreview() && instance.playMode() == mode) return true;
        }
        return false;
    }

    /**
     * 全部非预览实例的行为快照（顺序 = 列表顺序）——运行时控制并集
     * （{@code CinematicController.recomputeUnion}）的输入；预览实例不参与（预览通道不套用脚本行为）。
     */
    List<ScriptMeta.RuntimeBehavior> instanceBehaviors() {
        List<ScriptMeta.RuntimeBehavior> behaviors = new ArrayList<>(instances.size());
        for (PlaybackInstance instance : instances) {
            if (instance.isPreview()) continue;
            behaviors.add(instance.behavior());
        }
        return behaviors;
    }

    /** 任一非预览实例声明 {@code pause_when_game_paused}；无活跃实例时为 {@code false}。 */
    boolean isAnyPauseWhenGamePaused() {
        for (PlaybackInstance instance : instances) {
            if (!instance.isPreview() && instance.isPauseWhenGamePaused()) return true;
        }
        return false;
    }

    /** 顶层实例是否可跳过；无活跃实例时为 {@code false}（不显示提示）。 */
    boolean isTopInstanceSkippable() {
        PlaybackInstance top = topInstance();
        return top != null && top.isSkippable();
    }

    /** 顶层实例的脚本 id（无活跃实例时 {@code "<none>"}）。 */
    String activeScriptId() {
        PlaybackInstance instance = topInstance();
        return instance != null ? instance.scriptId() : "<none>";
    }

    /** 顶层实例的播放器是否在播放（脚本播放模式）。 */
    boolean isScriptMode() {
        PlaybackInstance instance = topInstance();
        return instance != null && instance.player().isPlaying();
    }

    /** 全部实例本帧的活跃 CAMERA clip 并集。 */
    boolean hasActiveCameraClip() {
        return cachedHasActiveCameraClip;
    }

    /** 顶层实例本帧自身是否有活跃 CAMERA clip（听者门控口径）。 */
    boolean topInstanceHasActiveCameraClip() {
        return cachedTopInstanceHasActiveCameraClip;
    }

    /** 写入本帧两份帧缓存（帧驱动每帧一次）。 */
    void setFrameCaches(boolean anyActiveCameraClip, boolean topInstanceActiveCameraClip) {
        cachedHasActiveCameraClip = anyActiveCameraClip;
        cachedTopInstanceHasActiveCameraClip = topInstanceActiveCameraClip;
    }
}
