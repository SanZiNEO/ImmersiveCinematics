package com.immersivecinematics.immersive_cinematics.api;

import com.immersivecinematics.immersive_cinematics.script.CameraLaneComposite;

/**
 * 相机只读查询面（对外稳定面）：过场是否接管相机、本帧是否有活跃 CAMERA 片段、统一状态快照、
 * 顶层 lane 的合成参数、覆盖链胜出的 Base 层标识。
 *
 * 全部方法都是无副作用查询：{@link #getCameraState()} 取当前帧相机状态链输出，{@link #isActive()} 读活跃实例表、
 * {@link #hasActiveCameraClip()} 读帧级缓存、{@link #getLaneComposite()} 读本帧 lane 快照、
 * {@link #getActiveBaseProvider()} 读覆盖链上次求值的选择结果——后两者都**不触发**新的求值。
 * 稳定性：本包（{@code api}）内的类型与方法**只增不删**，语义不变；破坏性变更只允许发生在
 * {@code spi} 包与内部实现。本接口当前只开放只读查询——播放控制见 {@code PlaybackApi}，
 * 扩展注册见 {@code spi} 门面（见 {@code plans/0.3.6/H-other/public-api.md}）。
 * 线程契约：仅客户端主线程（tick 与渲染同线程）；跨线程调用方须自行 marshal 到主线程。
 * 取得实现：客户端相机门面单例（{@code CameraManager.INSTANCE}）。
 */
public interface CameraApi {

    /** 相机是否被过场接管（= 有活跃播放实例）；{@code false} = 原版相机照常。 */
    boolean isActive();

    /** 全部活跃实例本帧是否有活跃 CAMERA 片段（帧级缓存，同一帧内多次读取一致）。 */
    boolean hasActiveCameraClip();

    /** 统一相机状态快照（本帧最新值）；无活跃相机时为 {@code null}。 */
    CameraState getCameraState();

    /**
     * 顶层实例本帧最上层 CAMERA lane 的合成参数（opacity / dest / source / mask）；无活跃相机片段返回 {@code null}。
     *
     * <p>只读：复用本帧已有的 lane 快照（{@code CameraTrackPlayer.getLaneSnapshots()}），不重新求值相机、
     * 不新建集合；同一帧内与 {@link #getCameraState()} 来自同一批快照，结果一致。
     * 无活跃 lane 时返回 {@code null}。</p>
     *
     * <p>「最上层」= 跨轨拼接顺序的最后一个 lane（轨道注册顺序 + 轨道内 clip 顺序），与渲染叠放次序一致。</p>
     */
    CameraLaneComposite getLaneComposite();

    /**
     * 覆盖链本帧胜出的 Base 层标识（诊断用）；无胜出者返回 {@code null}。
     *
     * <p>只读：取自覆盖链上一次求值的选择结果（不重新选择、不重复求值）；本帧尚未求值或无活跃 Base 时为
     * {@code null}。标识由 {@link BaseCameraProvider#providerId()} 给出（默认实现类全限定名），
     * 仅供诊断 / 日志，不得据此做行为分支。</p>
     */
    String getActiveBaseProvider();
}
