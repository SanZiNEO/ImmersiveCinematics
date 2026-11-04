package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.api.BaseCameraProvider;
import com.immersivecinematics.immersive_cinematics.api.CameraApi;
import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.api.PlaybackApi;
import com.immersivecinematics.immersive_cinematics.api.PlaybackListener;
import com.immersivecinematics.immersive_cinematics.api.ScriptInfo;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraCoverChain;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraModifier;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.script.CameraLane;
import com.immersivecinematics.immersive_cinematics.script.CameraLaneComposite;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptCache;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.GameClock;
import com.immersivecinematics.immersive_cinematics.util.PreviewClock;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 相机门面：持有统一状态、双时钟与各播放组件，公开方法只转发；只读查询面见 {@link CameraApi}，
 * 对外的播放控制 / 监听面见 {@link PlaybackApi}。
 * 组件分工：{@link PlaybackRegistry} 实例列表、{@link PreviewChannel} 编辑器预览、{@link PlaybackLedger} 网络账本、
 * {@link PlaybackLifecycle} 生命周期与队列、{@link FrameDriver} 帧驱动、{@link ClientEntityProbe} tick / 渲染 pass 诊断；
 * {@link CameraStateHolder} 是六参数的唯一写入缓冲与统一快照来源（快照 = 覆盖链输出：先按优先级选胜出 Base 层，
 * 再按注册表合成 Modifier）。各转发方法的定义 / 缺省 / 边界见对应组件类 javadoc。
 * 线程契约：仅客户端主线程（渲染线程）。
 */
public class CameraManager implements CameraApi, PlaybackApi {

    /** 客户端单例。 */
    public static final CameraManager INSTANCE = new CameraManager();

    // 组件（角色见类 javadoc）；声明顺序 = 构造顺序 = 依赖顺序
    private final CameraStateHolder stateHolder = new CameraStateHolder();
    private final GameClock gameClock = new GameClock();
    private final PreviewClock previewClock = new PreviewClock();
    private final PlaybackRegistry registry = new PlaybackRegistry();
    private final PlaybackLedger ledger = new PlaybackLedger();
    private final PreviewChannel previewChannel = new PreviewChannel(previewClock);
    private final CameraCoverChain coverChain = new CameraCoverChain();
    private final DirectControlBase directControlBase = new DirectControlBase(stateHolder);
    private final PlaybackLifecycle lifecycle = new PlaybackLifecycle(registry, ledger, previewChannel,
            stateHolder, gameClock, previewClock, coverChain, this::refreshCameraState);
    private final FrameDriver frameDriver = new FrameDriver(registry, previewChannel, gameClock, previewClock,
            ledger, lifecycle, directControlBase, this::refreshCameraState);
    private final ClientEntityProbe entityProbe = new ClientEntityProbe(registry);

    private CameraManager() {
        previewChannel.bindLifecycle(lifecycle);
        // 注册顺序 = 同优先级次序（每层各注册一个）；档位表见 BaseCameraProvider
        coverChain.register(new ScriptDirectorBase(registry, stateHolder));
        coverChain.register(directControlBase);
        coverChain.register(new FlightBase(previewChannel, stateHolder));
    }

    // ========== 实例查询（PlaybackRegistry）==========
    /** 活跃播放实例 = 顶层实例（启动最晚者，后启动者覆盖先启动者）；无播放时为 {@code null}。编辑器播放激活时顶层 = 编辑器实例。 */
    public PlaybackInstance activeInstance() { return registry.topInstance(); }
    /** 全部活跃播放实例，按启动顺序排列（先启动在前，顶层 = 最后一个）——只读视图，随增删实时变化。 */
    public List<PlaybackInstance> instances() { return registry.instances(); }
    /** 暂停联动取并集：任一游戏实例声明 {@code pause_when_game_paused} 即 {@code true}（编辑器实例不参与）。 */
    public boolean isAnyPauseWhenGamePaused() { return registry.isAnyPauseWhenGamePaused(); }
    public boolean isTopInstanceSkippable() { return registry.isTopInstanceSkippable(); }
    public String getActiveScriptId() { return registry.activeScriptId(); }
    public boolean isScriptMode() { return registry.isScriptMode(); }
    @Override
    public boolean hasActiveCameraClip() { return registry.hasActiveCameraClip(); }
    /** 顶层实例本帧自身是否有活跃 CAMERA clip —— 听者判定的门控口径（不是全实例并集）。 */
    public boolean topInstanceHasActiveCameraClip() { return registry.topInstanceHasActiveCameraClip(); }
    @Override
    public boolean isActive() { return registry.isActive(); }

    // ========== 播放控制（PlaybackLifecycle）==========
    /** 统一退出入口：作用于顶层实例（跳过键 / 强制退出键等玩家交互面向的就是顶层）。 */
    public boolean requestExit(ExitReason reason) { return lifecycle.requestExit(registry.topInstance(), reason); }

    /**
     * 声明式退出（规则动作 {@code exit}）：作用于触发它的那个实例，映射 {@link ExitReason#NATURAL_END}
     * 但跳过 {@code hold_at_end} 拒绝（声明式优先于末尾驻留）。
     */
    public boolean requestDeclaredExit(PlaybackInstance instance) {
        return lifecycle.requestDeclaredExit(instance);
    }
    /**
     * 播放脚本：跨脚本一律新建实例并行播放；同脚本已有活跃实例时按可打断性「立即替换 / 入队」。
     *
     * @param instanceId 服务端播放请求的播放实例 id；本地来源传空串
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    public int playCinematic(CinematicScript script, String instanceId) {
        return lifecycle.playScript(script, instanceId);
    }
    /** 同 {@link #playCinematic(CinematicScript, String)}，本地来源（播放实例 id 为空串）。 */
    public int playCinematic(CinematicScript script) { return playCinematic(script, ""); }
    /** 强制立即停用全部活跃实例（服务端 stop / 调试强制退出）：不渐出、不接播。 */
    public void forceDeactivate() { lifecycle.forceDeactivate(); }
    /**
     * 按脚本（+可选播放实例 id）停止实例：非顶层同样生效。
     *
     * @param instanceId 播放实例 id；空串 = 该脚本任意实例
     * @return {@code false} = 无匹配实例（调用方转而尝试 {@link #cancelQueued(String)}）
     */
    public boolean stopScriptById(String scriptId, String instanceId) {
        return lifecycle.stopScriptById(scriptId, instanceId);
    }
    /** 取消该脚本的排队项；{@code true} = 确有排队项被移除。 */
    public boolean cancelQueued(String scriptId) { return lifecycle.cancelQueued(scriptId); }
    /** 紧急停止（世界退出/断线）：跳过退场动画直接清理全部实例，防止 OpenAL 音频残留。 */
    public void emergencyStop() { lifecycle.emergencyStop(); }

    // ========== 对外播放 API（PlaybackApi）==========
    /**
     * {@inheritDoc}
     *
     * <p>脚本取自客户端缓存（{@link ClientScriptCache}）；未缓存的 id 直接 {@code -1}。
     * 底层 {@code 0}（被拒绝）在此统一折为 {@code -1}，因此调用方只会看到 {@code 1} / {@code 2} / {@code -1}。
     */
    @Override
    public int play(String scriptId, String instanceId) {
        if (scriptId == null || scriptId.isBlank()) return -1;
        CinematicScript script = ClientScriptCache.getScript(scriptId);
        if (script == null) return -1;
        int result = playCinematic(script, instanceId == null ? "" : instanceId);
        return result == 0 ? -1 : result;
    }

    @Override
    public boolean stop(String scriptId, String instanceId) {
        if (scriptId == null) return false;
        return stopScriptById(scriptId, instanceId == null ? "" : instanceId);
    }

    /** 立即停用全部活跃实例：先快照实例表再逐个停用（停用可能接播队列，避免边遍历边增删）；单个失败不影响其余。 */
    @Override
    public void stopAll() {
        List<PlaybackInstance> snapshot = new ArrayList<>(instances());
        for (int i = 0; i < snapshot.size(); i++) {
            PlaybackInstance instance = snapshot.get(i);
            try {
                stopScriptById(instance.scriptId(), instance.instanceId());
            } catch (RuntimeException e) {
                ErrorLog.logRateLimited("Playback", "stopAll", 5000L,
                        "stopAll 停止实例失败: script=" + instance.scriptId() + " instance=" + instance.instanceId(), e);
            }
        }
    }

    @Override
    public boolean isPlaying() { return isScriptMode(); }

    /** 客户端缓存快照 → 元信息，按 id 升序；返回不可变列表。 */
    @Override
    public List<ScriptInfo> scripts() {
        Map<String, CinematicScript> cached = ClientScriptCache.snapshot();
        List<ScriptInfo> result = new ArrayList<>(cached.size());
        for (CinematicScript script : cached.values()) {
            if (script != null) {
                result.add(ScriptInfo.of(script));
            }
        }
        result.sort(Comparator.comparing(info -> info.id() == null ? "" : info.id()));
        return List.copyOf(result);
    }

    @Override
    public void addListener(PlaybackListener listener) { PlaybackListeners.add(listener); }

    @Override
    public boolean removeListener(PlaybackListener listener) { return PlaybackListeners.remove(listener); }

    // ========== 编辑器播放（PreviewChannel）==========
    public void pushScript(String jsonContent) { previewChannel.pushScript(jsonContent); }
    /** 编辑器定位（seek）：编辑器播放头跳到 {@code seconds} 秒并保持编辑器播放激活、暂停。 */
    public void setTime(double seconds) { previewChannel.setTime(seconds); }
    public void resume() { previewChannel.resume(); }
    public void pause() { previewChannel.pause(); }
    /** 编辑器停止：编辑器播放激活时播放头归零并保持激活，否则走游戏内停止。 */
    public void stop() { previewChannel.stop(); }
    public void exitPreview() { previewChannel.exitPreview(); }
    public void setPreviewDirectControl(boolean on) { previewChannel.setPreviewDirectControl(on); }
    /** 编辑器拖拽直控是否生效（轨道写入据此跳过）。 */
    public boolean isPreviewDirectControl() { return previewChannel.isPreviewDirectControl(); }
    /** 是否处于编辑器播放（玩家移动不由玩家输入驱动）。 */
    public boolean isEditorPlayback() { return previewChannel.isEditorPlayback(); }
    public boolean isEditorPaused() { return previewChannel.isEditorPaused(); }

    // ========== 时钟读数 ==========
    /** 游戏共享虚拟时钟读数（秒；游戏实例的时间轴）。 */
    public double getGameTimeSeconds() { return gameClock.seconds(); }
    /** 编辑器播放头（秒）；编辑器播放未激活时回落游戏共享虚拟时钟。 */
    public double getPreviewTimeSeconds() {
        return previewChannel.isEditorPlayback() ? previewClock.seconds() : gameClock.seconds();
    }

    // ========== 帧与 tick 驱动 ==========
    /** 每渲染帧驱动全部活跃实例（顺序见 {@link FrameDriver}）；调用点 = {@code GameRendererMixin} 的渲染头。 */
    public void onRenderFrame() { frameDriver.frame(); }
    /** 每客户端 tick 驱动诊断（见 {@link ClientEntityProbe}）。 */
    public void tick() { entityProbe.tick(); }
    /**
     * 渲染 pass 诊断采样（{@code LevelRenderer.renderLevel} RETURN）：只读转发给 {@link ClientEntityProbe}。
     * 采样自身受 {@code debugLogging}、活跃实例与 pass 类型门控，关闭时立即返回。
     */
    public void onRenderPass(LevelRenderer renderer, Frustum frustum, Camera camera) {
        entityProbe.onRenderPass(renderer, frustum, camera);
    }

    // ========== 覆盖链注册（对外注册门面转发；仅初始化期） ==========
    /**
     * 登记一层 Base 相机。null 校验与注册时机（冻结）判定在对外门面
     * {@code api.ImmersiveCinematicsApi}；本方法只转发覆盖链。线程 = 客户端主线程。
     */
    public void registerBaseCamera(BaseCameraProvider provider) { coverChain.register(provider); }

    /** 注销一层 Base 相机；未注册过时为无操作。线程 = 客户端主线程。 */
    public void unregisterBaseCamera(BaseCameraProvider provider) { coverChain.unregister(provider); }

    /** 登记一条相机 Modifier；按优先级升序插入。线程 = 客户端主线程。 */
    public void registerCameraModifier(CameraModifier modifier) { coverChain.register(modifier); }

    /** 注销一条相机 Modifier；未注册过时为无操作。线程 = 客户端主线程。 */
    public void unregisterCameraModifier(CameraModifier modifier) { coverChain.unregister(modifier); }

    // ========== 全局相机状态 ==========
    /** 脚本导演写侧：顶层 clip 本帧六参数写入全局写入缓冲 + 同步链上的当前值（不刷新快照）。 */
    public void setScriptState(CameraState state, boolean suppressModifiers) {
        stateHolder.setScriptState(state, suppressModifiers);
    }
    /**
     * 统一只读相机状态快照（本帧最新值）；无活跃相机时为 {@code null}。
     *
     * 刷新点：帧末、停用 / 重启、直控写入口；脚本导演写入只改写入缓冲，由帧末统一重建。
     * 同一帧内多次读取结果一致。
     *
     * <p>这是覆盖链输出 / 顶层实例的全局快照，不是服务端 {@code CameraCenterManager} 的当前维度中心
     * 上报源；后者由 {@code CameraCenterReporter} 按当前维度第一条 lane 选择。
     */
    @Override
    public CameraState getCameraState() { return stateHolder.snapshot(); }

    /**
     * 顶层实例本帧最上层 CAMERA lane 的合成参数（不透明度 / 目标矩形 / 素材矩形 / 遮罩）；无活跃相机片段时为
     * {@code null}。
     *
     * <p>只读：直接读本帧已有的 lane 快照（{@code ScriptPlayer.activeCameraLanes()}，源自各
     * {@code CameraTrackPlayer.getLaneSnapshots()}），不重新求值、不新建集合；同一帧内与
     * {@link #getCameraState()} 一致。「最上层」= 跨轨拼接顺序的最后一个 lane（轨道顺序 + 轨道内 clip 顺序），
     * 与渲染叠放次序一致。</p>
     *
     * <p>旧格式片段的 lane 合成参数为 {@code null}（渲染侧按旧插值器取值），此处回落
     * {@link CameraLaneComposite#DEFAULT}——即旧链路缺省（全屏 / 不透明 / full mask），与渲染侧同义；
     * 因此本方法只在无活跃 lane 时返回 {@code null}。</p>
     */
    @Override
    public CameraLaneComposite getLaneComposite() {
        PlaybackInstance top = activeInstance();
        if (top == null) return null;
        List<CameraLane> lanes = top.player().activeCameraLanes();
        if (lanes.isEmpty()) return null;
        CameraLaneComposite composite = lanes.get(lanes.size() - 1).composite();
        return composite != null ? composite : CameraLaneComposite.DEFAULT;
    }

    /**
     * {@inheritDoc}
     *
     * <p>只读转发覆盖链：取上一次 {@code evaluate} 记下的胜出 Base 层；无胜出者时 {@code null}。
     * 不重新选择、不重复求值。</p>
     *
     * <p>无活跃播放实例时覆盖链本帧不求值（{@link #refreshCameraState()} 直接置 {@code null}），
     * 链上保留的是上一帧的选择结果；因此这里先按 {@link #isActive()} 门控，避免暴露结束后的陈旧标识。</p>
     */
    @Override
    public String getActiveBaseProvider() {
        if (!isActive()) return null;
        return coverChain.activeProviderId();
    }

    /**
     * 本帧链上是否有 Modifier 参与合成（消费方 = 合成顺序末位 lane 的覆盖链输出替换位：
     * 有 Modifier 时该 lane 取链输出）。
     */
    public boolean modifiersApplied() { return coverChain.modifiersApplied(); }
    /**
     * 本帧链胜出层是否为直控类层（飞行 300 / 编辑器直控 200 / 外部接管 400，即非脚本导演 100）。
     * 消费方 = {@code ScriptLaneDriver} 的覆盖链输出替换位（合成顺序末位 lane）：直控类层胜出时相机轨道
     * 跳过写入（{@code CameraTrackPlayer.onRenderFrame} 在 {@code isPreviewDirectControl()} 时直接返回），
     * 该替换位要么不存在、要么不是本帧相机——画面须取链输出（本帧相机）才跟得上直控。替换位只是覆盖链
     * 的作用点，不代表「主相机」。
     */
    public boolean isDirectControlWinner() {
        return coverChain.winnerPriority() > BaseCameraProvider.PRIORITY_SCRIPT_DIRECTOR;
    }
    /** 当前镜头 yaw（度；方向性预加载用）。 */
    public float getCameraYaw() { return stateHolder.properties().getYaw(); }
    /** 编辑器直控写侧（五参数，位置不动）：写完立即刷新统一快照。 */
    public void setCameraDirect(float yaw, float pitch, float roll, float fov, float zoom) {
        if (!isActive()) return;
        stateHolder.setDirect(yaw, pitch, roll, fov, zoom);
        directControlBase.markWritten();
        refreshCameraState();
    }
    /** 直控写侧（六参数，含位置；飞行取景）：写完立即刷新统一快照。 */
    public void setCameraDirect(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom) {
        stateHolder.setDirect(position, yaw, pitch, roll, fov, zoom);
        refreshCameraState();
    }

    /** 重建统一快照：值 = 覆盖链输出；无活跃实例 / 无活跃 Base 时置 {@code null}（读侧据此回落玩家相机）。 */
    private void refreshCameraState() {
        stateHolder.setSnapshot(registry.isActive() ? coverChain.snapshot() : null);
    }
}
