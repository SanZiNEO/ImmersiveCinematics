package com.immersivecinematics.immersive_cinematics.api;

import java.util.List;

/**
 * 播放控制门面（对外稳定面）：按脚本 id 播放 / 停止 / 查询 / 监听本机（客户端）的过场播放。
 *
 * <p><b>线程契约</b>：仅客户端主线程（tick 与渲染同线程）；跨线程调用方须自行 marshal 到主线程。
 *
 * <p><b>脚本来源</b>：{@link #play} 只播本机已收到的脚本（{@link #scripts()} 列出的那一份），
 * 服务端不会在调用时临时下发；需要"先下发再播放"请用服务端 {@link ServerPlaybackApi#playFor}。
 *
 * <p><b>实例 id</b>：{@code instanceId} 是服务端播放请求的播放实例 id；{@code null} / 空串 = 匿名实例
 * （同一脚本的全部匿名请求共用退化键 {@code (scriptId, "")}）。
 *
 * <p><b>稳定性</b>：本包（{@code api}）内的类型与方法<b>只增不删</b>，语义不变；破坏性变更只允许发生在
 * {@code spi} 包与内部实现。
 */
public interface PlaybackApi {

    /**
     * 播放本机已缓存的脚本。
     *
     * <p>码值映射（底层 {@code PlaybackLifecycle.playScript} 的返回值，{@code 0} 在此统一折为 {@code -1}）：
     * <ul>
     *   <li>{@code 1} = 已开始播放（同脚本可打断实例被替换时也是 {@code 1}）</li>
     *   <li>{@code 2} = 已排队等待（同脚本在播实例不可打断，或在播集合含 exclusive）</li>
     *   <li>{@code -1} = 未受理：脚本未缓存，或客户端不在世界被拒（底层 {@code 0}）</li>
     * </ul>
     *
     * @param scriptId   脚本 id（{@code null} / 空白 = 未受理）
     * @param instanceId 播放实例 id；{@code null} / 空串 = 匿名实例
     * @return {@code 1} 已开始 / {@code 2} 已排队 / {@code -1} 未受理
     */
    int play(String scriptId, String instanceId);

    /**
     * 停止某脚本的播放实例（非顶层同样生效）。
     *
     * @param scriptId   脚本 id
     * @param instanceId 播放实例 id；{@code null} / 空串 = 该脚本任意实例
     * @return {@code false} = 无匹配在播实例（不会取消排队项）
     */
    boolean stop(String scriptId, String instanceId);

    /** 立即停用全部活跃实例（不渐出、不接播）。 */
    void stopAll();

    /** 是否有脚本模式实例在播。 */
    boolean isPlaying();

    /**
     * 本机已收到的脚本（客户端缓存，由服务端下发的播放 / 重载包写入）。
     *
     * <p>只含"收到过"的脚本，不代表正在播放，也不含排队项；返回不可变快照，按 id 升序。
     */
    List<ScriptInfo> scripts();

    /**
     * 注册播放监听器（客户端主线程回调）。
     *
     * <p>重复注册同一实例只保留一份；{@code null} 被忽略。
     */
    void addListener(PlaybackListener listener);

    /**
     * 移除播放监听器。
     *
     * @return {@code true} = 确有该监听器被移除
     */
    boolean removeListener(PlaybackListener listener);

    /** 当前实现（客户端相机门面单例）。 */
    static PlaybackApi get() {
        return Holder.INSTANCE;
    }

    /** 持有单例；仅供 {@link #get()} 内部使用，不要直接引用。 */
    final class Holder {
        static final PlaybackApi INSTANCE =
                com.immersivecinematics.immersive_cinematics.camera.CameraManager.INSTANCE;

        private Holder() {}
    }
}
