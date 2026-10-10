package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.util.GameClock;
import com.immersivecinematics.immersive_cinematics.util.PreviewClock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 相机管理器（门面 + 帧驱动）：持有统一状态、双时钟与四个播放组件，对外只转发。
 *
 * 组件分工：{@link PlaybackRegistry} 实例列表与查询、{@link PreviewChannel} 编辑器预览通道、
 * {@link PlaybackLedger} 网络账本、{@link PlaybackLifecycle} 生命周期与队列；{@link CameraStateHolder}
 * 是六参数的唯一写入缓冲与统一快照来源。
 *
 * 帧驱动 {@link #onRenderFrame()} 的顺序固定：时钟推进 → 逐实例驱动 → 帧末重建快照。
 * 公开方法签名与语义在拆分前后逐点一致，调用方零改动。
 *
 * 线程：仅客户端主线程（渲染线程）。
 */
public class CameraManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    public static final CameraManager INSTANCE = new CameraManager();

    /** 全局相机状态（写入缓冲 + 统一快照）——门面读数与轨道播放器写入都经它。 */
    private final CameraStateHolder stateHolder = new CameraStateHolder();

    /** 游戏共享虚拟时钟：游戏实例的时钟源（暂停冻结、末实例退出归零）。 */
    private final GameClock gameClock = new GameClock();

    /** 预览播放头：预览实例的时钟源（与游戏时钟互不影响）。 */
    private final PreviewClock previewClock = new PreviewClock();

    /** 活跃实例注册表（列表顺序 = 启动顺序 = 叠放顺序）。 */
    private final PlaybackRegistry registry = new PlaybackRegistry();

    /** 网络账本（播放回执 / 暂停握手 / 结束通知）。 */
    private final PlaybackLedger ledger = new PlaybackLedger();

    /** 编辑器预览通道。 */
    private final PreviewChannel previewChannel = new PreviewChannel(previewClock);

    /** 播放生命周期与队列。 */
    private final PlaybackLifecycle lifecycle =
            new PlaybackLifecycle(registry, ledger, previewChannel, stateHolder, gameClock, previewClock);

    /** 客户端实际实体数日志计数（tick 每 100 次打印一次诊断）。 */
    private int clientEntityLogCounter = 0;

    private CameraManager() {
        previewChannel.bindLifecycle(lifecycle);
    }

    // ========== 实例查询（PlaybackRegistry）==========

    /**
     * 活跃播放实例 —— 顶层实例（启动最晚者，后来者居上）；无播放时为 {@code null}。
     * 预览激活时顶层 = 预览实例（恒排列表末位）：编辑器正在编辑的那份画面/相机就是当前代表。
     */
    public PlaybackInstance activeInstance() { return registry.topInstance(); }

    /**
     * 全部活跃播放实例，按启动顺序排列（先启动在前，顶层 = 最后一个）——只读视图。
     * 需要「按实例逐个处理」的消费方遍历本方法；内容随实例增删实时变化。
     */
    public List<PlaybackInstance> instances() { return registry.instances(); }

    /**
     * 暂停联动取并集：任一游戏实例声明 {@code pause_when_game_paused} 即 {@code true}；
     * 无活跃实例时为 {@code false}。预览实例不参与（预览的暂停/播放由编辑器驱动）。
     */
    public boolean isAnyPauseWhenGamePaused() { return registry.isAnyPauseWhenGamePaused(); }

    /** 顶层实例是否可跳过 —— 跳过提示只由它决定；无活跃实例时为 {@code false}。 */
    public boolean isTopInstanceSkippable() { return registry.isTopInstanceSkippable(); }

    /** 顶层实例的脚本 id（无活跃实例时 {@code "<none>"}）。 */
    public String getActiveScriptId() { return registry.activeScriptId(); }

    /** 是否处于脚本播放模式（顶层实例的播放器在播放）。 */
    public boolean isScriptMode() { return registry.isScriptMode(); }

    /** 全部实例本帧的活跃 CAMERA clip 并集（帧级缓存）。 */
    public boolean hasActiveCameraClip() { return registry.hasActiveCameraClip(); }

    /** 顶层实例本帧自身是否有活跃 CAMERA clip —— 听者判定的门控口径（不是全实例并集）。 */
    public boolean topInstanceHasActiveCameraClip() { return registry.topInstanceHasActiveCameraClip(); }

    /** 相机是否被播放实例接管（= 有活跃实例）。 */
    public boolean isActive() { return registry.isActive(); }

    // ========== 播放控制（PlaybackLifecycle）==========

    /** 统一退出入口：作用于顶层实例（跳过键 / 强制退出键等玩家交互面向的就是顶层）。 */
    public boolean requestExit(ExitReason reason) { return lifecycle.requestExit(registry.topInstance(), reason); }

    /**
     * 播放脚本：跨脚本一律新建实例并行播放；同脚本已有活跃实例时按可打断性「立即替换 / 入队」。
     *
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    public int playScript(CinematicScript script) { return playScript(script, ""); }

    /**
     * 同 {@link #playScript(CinematicScript)}，另携带服务端播放请求的播放实例 id（账本实例键）。
     *
     * @param instanceId 服务端播放请求的播放实例 id；本地来源传空串
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    public int playScript(CinematicScript script, String instanceId) {
        return lifecycle.playScript(script, instanceId);
    }

    /** 停止顶层实例（系统停止）。 */
    public void stopScript() { lifecycle.stopScript(); }

    /** {@link #playScript} 的别名（语义相同，返回值透传）。 */
    public int playCinematic(CinematicScript script) { return playScript(script); }

    /** 同 {@link #playCinematic(CinematicScript)}，另携带服务端播放请求的播放实例 id。 */
    public int playCinematic(CinematicScript script, String instanceId) { return playScript(script, instanceId); }

    /** 强制立即停用全部活跃实例（服务端 stop / 调试强制退出）：不渐出、不接播。 */
    public void forceDeactivate() { lifecycle.forceDeactivate(); }

    /** D2：紧急停止（世界退出/断线）：跳过退场动画直接清理全部实例，防止 OpenAL 音频残留。 */
    public void emergencyStop() { lifecycle.emergencyStop(); }

    // ========== 编辑器预览（PreviewChannel）==========

    /** 编辑器推送脚本（编辑即预览）：解析并缓存，预览通道激活时同步到预览实例。 */
    public void pushScript(String jsonContent) { previewChannel.pushScript(jsonContent); }

    /** 编辑器定位（seek）：预览播放头跳到 {@code seconds} 秒并保持预览激活、暂停。 */
    public void setTime(float seconds) { previewChannel.setTime(seconds); }

    /** 编辑器播放：预览实例从播放头续播（预览通道未激活时先激活并建实例）。 */
    public void resume() { previewChannel.resume(); }

    /** 编辑器暂停：预览播放头冻结在当前进度。 */
    public void pause() { previewChannel.pause(); }

    /** 编辑器停止：预览激活时播放头归零并保持激活，否则走游戏内停止。 */
    public void stop() { previewChannel.stop(); }

    /** 编辑器完全退出：只退预览实例，游戏实例继续播放。 */
    public void exitPreview() { previewChannel.exitPreview(); }

    /** 编辑器拖拽直控：进入/退出直控态（直控期间轨道写入被跳过，相机由编辑器直驱）。 */
    public void setPreviewDirectControl(boolean on) { previewChannel.setPreviewDirectControl(on); }

    /** 编辑器拖拽直控是否生效。 */
    public boolean isPreviewDirectControl() { return previewChannel.isPreviewDirectControl(); }

    /** 是否处于编辑器预览模式（预览时玩家移动不由玩家输入驱动）。 */
    public boolean isPreviewMode() { return previewChannel.isPreviewMode(); }

    /** 编辑器预览是否处于暂停。 */
    public boolean isPreviewPaused() { return previewChannel.isPreviewPaused(); }

    // ========== 时钟读数 ==========

    /** 游戏共享虚拟时钟读数（秒）。 */
    public double getGameTimeSeconds() { return gameClock.seconds(); }

    /**
     * 编辑器预览播放头（秒）——预览通道激活时 = 预览播放头读数，未激活时回落游戏共享虚拟时钟。
     * 编辑器读这个而不是全局虚拟时钟：预览实例与游戏实例各自计时。
     */
    public double getPreviewTimeSeconds() {
        return previewChannel.isPreviewMode() ? previewClock.seconds() : gameClock.seconds();
    }

    // ========== 帧回调驱动 ==========

    /**
     * 每渲染帧驱动全部活跃实例：先各自推进一步时钟（游戏共享虚拟时钟按暂停冻结、预览播放头按预览暂停态），
     * 再按列表顺序（= 启动顺序）逐个驱动播放器（后驱动者后写入共享相机状态 → 顶层胜出）；
     * 某实例结束只退它自己，最后一个实例退出才全局复位；末尾重建统一快照。
     *
     * 调用点：{@code GameRendererMixin}（{@code GameRenderer.render} 的 HEAD，世界渲染之前），每帧一次；
     * 必须早于 {@code ScriptLaneDriver.tick}（后者读本方法填好的 lane 快照）。
     */
    public void onRenderFrame() {
        if (registry.isEmpty()) {
            registry.clearFrameCaches();
            refreshCameraState();
            return;
        }

        // 暂停联动取并集：任一游戏实例声明 pause_when_game_paused 即按游戏暂停处理；
        // 预览实例不参与——预览的暂停/播放由编辑器驱动，与游戏暂停无关。
        boolean gamePaused = Minecraft.getInstance().isPaused() && registry.isAnyPauseWhenGamePaused();

        // 诊断：打印暂停判定组成——若游戏内播放被误判为暂停（gamePaused/预览态异常）即可见。
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("camera pauseSync: gamePaused={} previewMode={} previewPaused={} instances={}",
                    gamePaused, previewChannel.isPreviewMode(), previewChannel.isPreviewPaused(), registry.size());
        }

        // 时钟一：游戏共享虚拟时钟——游戏暂停时冻结（不退出相机画面，继续应用相机/轨道）。
        gameClock.advance(gamePaused, System.nanoTime());

        // 时钟二：预览播放头（预览实例专用）——暂停 = 冻结在播放头；播放 = 按真实时间推进。
        previewClock.advance(!(previewChannel.isPreviewMode() && !previewChannel.isPreviewPaused()
                && previewChannel.previewInstance() != null), System.nanoTime());

        float deltaTime = 1f / 20f;
        OverlayManager.INSTANCE.update(deltaTime);

        // 暂停↔恢复转换是帧级事件：转换发生的那一帧为每个在播游戏实例各发一条握手。
        boolean pauseTransition = ledger.pauseTransition(gamePaused);

        float gameTime = (float) gameClock.seconds();
        float previewTime = (float) previewClock.seconds();
        boolean anyActiveCameraClip = false;
        // 顶层实例（启动最晚的活跃实例）——听者门控口径只认它自己的 CAMERA clip
        PlaybackInstance top = registry.topInstance();
        boolean topActiveCameraClip = false;

        // 遍历副本：帧内可能有实例退出（出列）或接播（追加新实例），避免并发修改
        for (PlaybackInstance instance : new ArrayList<>(registry.instances())) {
            if (!registry.contains(instance)) continue; // 本帧更早的退出已移除该实例
            ScriptPlayer player = instance.player();
            // 本实例的暂停态：游戏实例 = 游戏暂停联动（并集）；预览实例 = 编辑器暂停态
            boolean instancePaused = instance.isPreview() ? previewChannel.isPreviewPaused() : gamePaused;
            // 本实例的时钟读数：游戏实例 = 共享虚拟时钟；预览实例 = 预览播放头（各自计时）
            float instanceClock = instance.isPreview() ? previewTime : gameTime;

            // 每帧同步音频暂停状态（幂等；对齐 MC SoundEngine：暂停时无新声音、已有实例幂等 pause）。
            // 必须位于 player.onRenderFrame 之前并每帧执行。
            if (instancePaused) {
                player.pauseAudio();
            } else {
                player.resumeAudio();
            }

            if (pauseTransition && !instance.isPreview() && player.isPlaying()) {
                String scriptId = player.getScriptId();
                if (!"<none>".equals(scriptId)) {
                    ledger.sendPausePacket(scriptId, instance.instanceId(), gamePaused);
                }
            }

            if (player.isPlaying()) {
                float instanceTime = instanceClock;
                // holdAtEnd=true：时间耗尽后把渲染时间钳到总时长末尾，让最后一帧保持住
                if (player.isFinished() && instance.isHoldAtEnd()) {
                    CinematicScript s = player.getScript();
                    if (s != null) {
                        float total = s.getTotalDuration();
                        if (total > 0f) instanceTime = Math.max(0f, total - 0.001f);
                    }
                }
                player.onRenderFrame(instanceTime);
                // 帧级缓存取并集：任一实例本帧有活跃 Camera 轨道即算有
                boolean instanceCameraClip = player.hasActiveCameraTrack(instanceTime);
                anyActiveCameraClip |= instanceCameraClip;
                // 顶层实例单独记一份（同一钳制时间口径）——听者门控只认它
                if (instance == top) topActiveCameraClip = instanceCameraClip;
            }

            if (instance.isStopping() && !OverlayManager.INSTANCE.isAnimating()) {
                lifecycle.deactivateNow(instance);
                continue;
            }

            if (!instance.isStopping() && player.isPlaying() && player.isFinished()) {
                boolean holdAtEnd = instance.isHoldAtEnd();
                // 诊断：退出链路（脚本自然结束检查）
                LOGGER.info("NATURAL_END check: playing={} finished=true stopping={} holdAtEnd={} elapsed={}",
                        player.isPlaying(), instance.isStopping(), holdAtEnd,
                        String.format("%.2f", instanceClock));
                if (!holdAtEnd) {
                    LOGGER.info("NATURAL_END -> requestExit");
                    // 精确作用于本实例：其他并行实例不受影响
                    lifecycle.requestExit(instance, ExitReason.NATURAL_END);
                }
            }
        }

        registry.setFrameCaches(anyActiveCameraClip, topActiveCameraClip);

        // 帧末统一生成/替换快照：本帧所有渲染侧读取（lane 收集、听者、预加载、预览 HUD）
        // 都拿到这一份，且同帧内多次读取一致。
        refreshCameraState();
    }

    // ========== tick 驱动 ==========

    public void tick() {
        if (!isActive()) return;
        clientEntityLogCounter++;
        if (clientEntityLogCounter % 100 == 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Vec3 pos = stateHolder.path().getPosition();
                int radius = 64;
                AABB box = new AABB(
                        pos.x - radius, pos.y - radius, pos.z - radius,
                        pos.x + radius, pos.y + radius, pos.z + radius);
                int count = mc.level.getEntities((Entity) null, box, e -> true).size();
                LOGGER.info("[camera-client] 实际实体数 radius={} count={} pos=({},{},{})",
                        radius, count,
                        String.format("%.1f", pos.x), String.format("%.1f", pos.y), String.format("%.1f", pos.z));
            }
        }
    }

    // ========== 全局相机状态（写侧 = 轨道播放器，读侧 = 统一快照）==========

    /** 朝向与光学写入缓冲（供轨道播放器逐帧写入当前值）。 */
    public CameraProperties getProperties() { return stateHolder.properties(); }

    /** 位置写入缓冲（供轨道播放器逐帧写入当前值）。 */
    public CameraPath getPath() { return stateHolder.path(); }

    /**
     * 统一只读相机状态快照（本帧最新值）；无活跃相机时为 {@code null}。
     *
     * 值 = 本帧最后写入全局相机状态的实例（= 顶层实例）的顶层活跃 clip 六参数，与画面 lane 里
     * 最上层那条 lane 的 {@link CameraState} 同源同值。刷新点：帧末、停用/重启、直写入口——
     * 任何写入后立即反映新值，同一帧内多次读取结果一致。
     */
    public CameraState getCameraState() { return stateHolder.snapshot(); }

    /** 从写入缓冲（顶层 clip 当前值）重建统一快照；无活跃实例时置 {@code null}。 */
    private void refreshCameraState() { stateHolder.refresh(registry.isActive()); }

    /** 当前镜头 yaw（方向性预加载用）。 */
    public float getCameraYaw() { return stateHolder.properties().getYaw(); }

    /** WebUI 编辑器直接设置预览相机参数（yaw/pitch/roll/fov/zoom），写完立即刷新快照。 */
    public void setCameraDirect(float yaw, float pitch, float roll, float fov, float zoom) {
        if (!isActive()) return;
        stateHolder.setDirect(yaw, pitch, roll, fov, zoom);
        // 直写穿透：保证本帧 getFov 立即读到新值
        refreshCameraState();
    }

    /** 直控写入（含位置）：飞行取景等需要同时写位置与光学的直写路径，写完立即刷新统一快照。 */
    public void setCameraDirect(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom) {
        stateHolder.setDirect(position, yaw, pitch, roll, fov, zoom);
        refreshCameraState();
    }

    /** 复位全局写入缓冲与播放队列。 */
    public void reset() { lifecycle.reset(); }
}
