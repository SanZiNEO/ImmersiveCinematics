package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放实例注册表：活跃实例列表 + 实例查询 + 帧级相机 clip 缓存。
 *
 * 列表顺序 = 启动顺序（帧驱动按顺序遍历，后写入者覆盖先写入者 → 后来者居上），
 * 顶层实例 = 列表最后一个。不变量：预览实例恒为末位（{@link #insertBefore} 把游戏实例插在它之前），
 * 因此预览激活时顶层 = 预览实例。同脚本同玩家保持单实例：{@link #instancePlaying(String)}。
 *
 * 帧缓存（{@link #hasActiveCameraClip()} / {@link #topInstanceHasActiveCameraClip()}）由帧驱动
 * 每帧同一时点写入一次，供帧内多个消费方读取；无活跃实例时由帧驱动清零。两份缓存的差别只在多实例：
 * 前者 = 全部实例并集，后者 = 顶层实例自身（听者门控口径）。
 *
 * 线程：仅客户端主线程（渲染线程）。{@link #instances()} 的只读视图随增删实时变化，
 * 帧内遍历需自行防并发修改；查询路径零分配（{@link #instanceBehaviors()} 除外，只在实例增删时调用）。
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
     * 正在播放 {@code scriptId} 的活跃实例；无则 {@code null}。
     *
     * @param scriptId 脚本 id；{@code null} = 无匹配
     */
    PlaybackInstance instancePlaying(String scriptId) {
        if (scriptId == null) return null;
        for (PlaybackInstance instance : instances) {
            if (instance.player().isPlaying() && scriptId.equals(instance.scriptId())) return instance;
        }
        return null;
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

    /** 清零两份帧缓存（无活跃实例时）。 */
    void clearFrameCaches() {
        cachedHasActiveCameraClip = false;
        cachedTopInstanceHasActiveCameraClip = false;
    }
}
