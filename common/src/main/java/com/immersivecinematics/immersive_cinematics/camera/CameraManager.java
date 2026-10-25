package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.api.BaseCameraProvider;
import com.immersivecinematics.immersive_cinematics.api.CameraApi;
import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraCoverChain;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.util.GameClock;
import com.immersivecinematics.immersive_cinematics.util.PreviewClock;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 相机门面：持有统一状态、双时钟与各播放组件，公开方法只转发；只读查询面见 {@link CameraApi}。
 * 组件分工：{@link PlaybackRegistry} 实例列表、{@link PreviewChannel} 编辑器预览、{@link PlaybackLedger} 网络账本、
 * {@link PlaybackLifecycle} 生命周期与队列、{@link FrameDriver} 帧驱动、{@link ClientEntityProbe} tick / 渲染 pass 诊断；
 * {@link CameraStateHolder} 是六参数的唯一写入缓冲与统一快照来源（快照 = 覆盖链输出：先按优先级选胜出 Base 层，
 * 再按注册表合成 Modifier）。各转发方法的定义 / 缺省 / 边界见对应组件类 javadoc。
 * 线程契约：仅客户端主线程（渲染线程）。
 */
public class CameraManager implements CameraApi {

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
    private final ClientEntityProbe entityProbe = new ClientEntityProbe(registry, stateHolder);

    private CameraManager() {
        previewChannel.bindLifecycle(lifecycle);
        // 注册顺序 = 同优先级次序（每层各注册一个）；档位表见 BaseCameraProvider
        coverChain.register(new ScriptDirectorBase(registry, stateHolder));
        coverChain.register(directControlBase);
        coverChain.register(new FlightBase(previewChannel, stateHolder));
    }

    // ========== 实例查询（PlaybackRegistry）==========
    /** 活跃播放实例 = 顶层实例（启动最晚者，后启动者覆盖先启动者）；无播放时为 {@code null}。预览激活时顶层 = 预览实例。 */
    public PlaybackInstance activeInstance() { return registry.topInstance(); }
    /** 全部活跃播放实例，按启动顺序排列（先启动在前，顶层 = 最后一个）——只读视图，随增删实时变化。 */
    public List<PlaybackInstance> instances() { return registry.instances(); }
    /** 暂停联动取并集：任一游戏实例声明 {@code pause_when_game_paused} 即 {@code true}（预览实例不参与）。 */
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

    // ========== 编辑器预览（PreviewChannel）==========
    public void pushScript(String jsonContent) { previewChannel.pushScript(jsonContent); }
    /** 编辑器定位（seek）：预览播放头跳到 {@code seconds} 秒并保持预览激活、暂停。 */
    public void setTime(float seconds) { previewChannel.setTime(seconds); }
    public void resume() { previewChannel.resume(); }
    public void pause() { previewChannel.pause(); }
    /** 编辑器停止：预览激活时播放头归零并保持激活，否则走游戏内停止。 */
    public void stop() { previewChannel.stop(); }
    public void exitPreview() { previewChannel.exitPreview(); }
    public void setPreviewDirectControl(boolean on) { previewChannel.setPreviewDirectControl(on); }
    /** 编辑器拖拽直控是否生效（轨道写入据此跳过）。 */
    public boolean isPreviewDirectControl() { return previewChannel.isPreviewDirectControl(); }
    /** 是否处于编辑器预览模式（预览时玩家移动不由玩家输入驱动）。 */
    public boolean isPreviewMode() { return previewChannel.isPreviewMode(); }
    public boolean isPreviewPaused() { return previewChannel.isPreviewPaused(); }

    // ========== 时钟读数 ==========
    /** 游戏共享虚拟时钟读数（秒；游戏实例的时间轴）。 */
    public double getGameTimeSeconds() { return gameClock.seconds(); }
    /** 编辑器预览播放头（秒）；预览通道未激活时回落游戏共享虚拟时钟。 */
    public double getPreviewTimeSeconds() {
        return previewChannel.isPreviewMode() ? previewClock.seconds() : gameClock.seconds();
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
     */
    @Override
    public CameraState getCameraState() { return stateHolder.snapshot(); }
    /** 本帧链上是否有 Modifier 参与合成（消费方 = 主相机 lane：有 Modifier 时取链输出）。 */
    public boolean modifiersApplied() { return coverChain.modifiersApplied(); }
    /**
     * 本帧链胜出层是否为直控类层（飞行 300 / 编辑器直控 200 / 外部接管 400，即非脚本导演 100）。
     * 消费方 = 主画面 lane 的取用点（{@code ScriptLaneDriver}）：直控类层胜出时相机轨道跳过写入
     * （{@code CameraTrackPlayer.onRenderFrame} 在 {@code isPreviewDirectControl()} 时直接返回），
     * 主相机 lane 要么不存在、要么不是本帧相机——画面须取链输出（本帧相机）才跟得上直控。
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
