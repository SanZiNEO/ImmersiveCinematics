package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptNotifier;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class CameraManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    public static final CameraManager INSTANCE = new CameraManager();

    private final CameraProperties activeProperties = new CameraProperties();
    private final CameraPath activePath = new CameraPath();

    private final CameraProperties stagedProperties = new CameraProperties();
    private final CameraPath stagedPath = new CameraPath();
    private boolean stagedReady = false;

    private final ScriptPlayer scriptPlayer = new ScriptPlayer();
    private boolean active = false;
    private boolean stopping = false;

    /** hasActiveCameraClip 的帧级缓存，避免 9 个 Mixin 调用点每帧重复扫描 */
    private boolean cachedHasActiveCameraClip = false;

    private double gameTimeSeconds = 0;
    private long lastRealNanos = 0;

    private CinematicScript pendingScript = null;
    private CompletionReason pendingCompletionReason = CompletionReason.FINISHED;

    /** C1：播放队列（容量 8，当前脚本不可打断时新脚本一律入队，结束后自动接播） */
    private final ScriptQueue scriptQueue = new ScriptQueue();

    private boolean previewMode = false;
    private boolean previewPaused = true;
    private float previewTime;
    private CinematicScript previewScript;

    /** 上一帧的暂停状态，用于检测暂停↔恢复的转换 */
    private boolean lastFramePaused = false;

    /** 客户端实际实体数日志计数 */
    private int clientEntityLogCounter = 0;

    /** 组 6：预览模式相机是否已初始化（首次 start 时从玩家位置起步，之后重启不再重置相机 → 消除拖拽卡顿） */
    private boolean previewInitialized = false;

    /** 组 7：编辑器拖拽直控标志 — 直控期间 CameraTrackPlayer 跳过轨道写入，相机由编辑器直驱 */
    private boolean previewDirectControl = false;

    public void activate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 playerPos = mc.player.position();
        float playerYaw = mc.player.getYRot();
        float playerPitch = mc.player.getXRot();

        activePath.setPositionDirect(playerPos);
        activeProperties.setYawDirect(playerYaw);
        activeProperties.setPitchDirect(playerPitch);

        stagedReady = false;
        active = true;
        stopping = false;
    }

    public void deactivate() {
        if (!active) return;
        if (stopping) return;
        OverlayManager.INSTANCE.startFadeOut();
        stopping = true;
    }

    // ========== 统一退出入口 ==========

    public boolean requestExit(ExitReason reason) {
        CinematicController ctrl = CinematicController.INSTANCE;

        switch (reason) {
            case FORCE_QUIT:
                pendingCompletionReason = CompletionReason.FORCE_QUIT;
                deactivateNow();
                return true;

            case SYSTEM_STOP:
                pendingCompletionReason = CompletionReason.STOPPED;
                deactivate();
                return true;

            case INTERRUPTED:
                if (!ctrl.isInterruptible()) {
                    LOGGER.debug("脚本不可打断(interruptible=false)，拒绝抢占");
                    return false;
                }
                pendingCompletionReason = CompletionReason.INTERRUPTED;
                deactivate();
                return true;

            case USER_SKIP:
                if (!ctrl.isSkippable()) {
                    LOGGER.debug("脚本不可跳过(skippable=false)，拒绝用户退出");
                    return false;
                }
                pendingCompletionReason = CompletionReason.SKIPPED;
                deactivate();
                return true;

            case NATURAL_END:
                if (ctrl.isHoldAtEnd()) {
                    return false;
                }
                pendingCompletionReason = CompletionReason.FINISHED;
                deactivate();
                return true;
        }
        return false;
    }

    // ========== 脚本播放模式 ==========

    /**
     * 播放或排队脚本（C1 决策树，规则固定：优先级不能大于打断）
     * <ul>
     *   <li>无播放 → 直接开始（1）</li>
     *   <li>当前脚本可打断 → 立即替换（无渐出，新脚本马上播）（1）</li>
     *   <li>当前脚本不可打断 → 一律排队（容量 8，满则拒绝），priority 只用于队列内排序（2 / 0）</li>
     * </ul>
     *
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    public int playScript(CinematicScript script) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;

        if (!(active && scriptPlayer.isPlaying())) {
            startScriptInternal(script);
            return 1;
        }

        CinematicController ctrl = CinematicController.INSTANCE;
        if (ctrl.isInterruptible()) {
            // 可打断 → 立即替换：置原因 + deactivateNow 直接切换（deactivateNow 末尾接播 pendingScript）
            pendingScript = script;
            pendingCompletionReason = CompletionReason.INTERRUPTED;
            deactivateNow();
            return 1;
        }

        // 不可打断 → 一律排队（容量满则拒绝）
        if (scriptQueue.offer(script)) return 2;
        return 0;
    }

    /** 是否有脚本在排队等待播放 */
    public boolean hasPendingScript() {
        return pendingScript != null;
    }

    public void stopScript() {
        requestExit(ExitReason.SYSTEM_STOP);
    }

    public void playCinematic(CinematicScript script) {
        playScript(script);
    }

    public String getActiveScriptId() {
        return scriptPlayer.getScriptId();
    }

    public void forceDeactivate() {
        deactivateNow();
    }

    public ScriptPlayer getScriptPlayer() {
        return scriptPlayer;
    }

    public boolean isScriptMode() {
        return scriptPlayer.isPlaying();
    }

    public boolean hasActiveCameraClip() {
        return cachedHasActiveCameraClip;
    }

    // ========== 编辑器预览 ==========

    public void pushScript(String jsonContent) {
        try {
            previewScript = com.immersivecinematics.immersive_cinematics.script.ScriptParser.parse(jsonContent);
            // 编辑内容照常缓存;预览模式且已有脚本时保持激活(编辑即预览)
            if (previewMode && previewScript != null) {
                if (!active) {
                    startScriptInternal(previewScript);
                } else {
                    // 组 A：编辑模式常驻播放器 — 增量替换数据（零重建零重启；
                    // TrackPlayer 数据源动态化，音频实例按 sound+startTime+duration 重映射复用）
                    scriptPlayer.replaceScript(previewScript);
                }
                scriptPlayer.alignTime(previewTime, previewTime);
                // 组 1/2：数据替换后同步暂停态并把实例定位到播放头
                if (previewPaused) {
                    scriptPlayer.pauseAudio();
                } else {
                    scriptPlayer.resumeAudio();
                }
                scriptPlayer.repositionAudio(previewTime);
            }
        } catch (com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException e) {
            LOGGER.error("编辑器传入的脚本 JSON 解析失败", e);
        }
    }

    public void setTime(float seconds) {
        // 预览模式定位:始终激活并显示对应帧的相机视角(终止后点关键帧/拖播放头即时可见)
        previewTime = seconds;
        previewMode = true;
        previewPaused = true;
        if (!active && previewScript != null) {
            startScriptInternal(previewScript);
        }
        // Align so that elapsed = previewTime when onRenderFrame sets gameTimeSeconds = previewTime
        scriptPlayer.alignTime(previewTime, previewTime);
        // 组 1：定位即同步暂停态——先于 repositionAudio（其 paused 分支依赖此标志），
        // 并覆盖 startScriptInternal 预执行首帧已创建/播放的实例。
        scriptPlayer.pauseAudio();
        scriptPlayer.repositionAudio(previewTime);
    }

    public void resume() {
        // 点播放 → 用最新 previewScript 重新激活并从 previewTime 续播
        if (!previewMode) {
            if (previewScript == null) return;
            previewMode = true;
            if (!active) startScriptInternal(previewScript);
            scriptPlayer.alignTime(previewTime, previewTime);
        }
        previewPaused = false;
        lastRealNanos = 0;
    }

    public void pause() {
        previewPaused = true;
    }

    public void stop() {
        if (previewMode) {
            // 终止 = 重置播放头到第一帧并保持预览激活(相机回到脚本第一帧视角,而非玩家视角;
            // 玩家视角由脚本时间空隙自然产生——相机片段之间的空缺时段会归还视角)
            setTime(0f);
        } else {
            // 游戏内停止路径
            stopScript();
        }
    }

    /**
     * 编辑器完全退出(关闭编辑器时调用):停止预览播放并释放相机,回到玩家视角。
     * 与 {@link #stop()}("终止=归零保持激活")语义不同——关闭编辑器必须真正退出。
     */
    public void exitPreview() {
        if (previewMode) {
            previewMode = false;
            previewPaused = true;
            pendingScript = null;
            scriptQueue.clear();
            deactivateNow();
        } else {
            stopScript();
        }
    }

    /** D2：紧急停止（世界退出/断线时调用）：跳过退场动画直接清理，防止 OpenAL 音频残留 */
    public void emergencyStop() {
        previewMode = false;
        previewPaused = true;
        // 世界已退出，不可能接播（pendingScript 会经 deactivateNow 自动启动，必须先清掉）
        pendingScript = null;
        pendingCompletionReason = CompletionReason.STOPPED;
        deactivateNow();
    }

    private void startScriptInternal(CinematicScript script) {
        Minecraft mc = Minecraft.getInstance();
        if (!previewMode) {
            mc.setScreen(null);
        }

        // 只有真正屏蔽键鼠的脚本才清空按键；非屏蔽脚本保留玩家当前输入状态，避免切换时中断
        if (script.getMeta().isBlockKeyboard() || script.getMeta().isBlockMouse()) {
            CinematicController.INSTANCE.releaseAllKeys();
        }

        // 组 6：预览模式重启不再重置相机到玩家位置（否则每次 pushScript 节流重启都闪回 → 拖拽卡顿）。
        // 首次进入预览（previewInitialized=false）仍初始化一次，保证预览首帧从玩家位置起步不闪白。
        if (!previewMode || !previewInitialized) {
            Vec3 playerPos = mc.player.position();
            activePath.setPositionDirect(playerPos);
            activeProperties.setYawDirect(mc.player.getYRot());
            activeProperties.setPitchDirect(mc.player.getXRot());
        }
        previewInitialized = true;

        stagedReady = false;
        active = true;
        stopping = false;

        // 组 A：预执行首帧用播放头时间（预览模式），避免首帧写 t=0 造成画面跳变；游戏内播放传 0 保持原语义
        scriptPlayer.start(script, previewMode ? previewTime : 0f);
        if (previewMode) {
            CinematicController.INSTANCE.setBlockKeyboard(false);
            CinematicController.INSTANCE.setBlockMouse(false);
        } else {
            CinematicController.INSTANCE.apply(scriptPlayer.getCurrentProperties());
        }
    }

    // ========== 预置状态写入（staged）— 仅供编辑器预览 ==========

    // ========== 组 7：编辑器拖拽直控（bbs 式"编辑即生效"，零解析零重启） ==========

    /** 编辑器拖拽直控：进入/退出直控态（直控期间 CameraTrackPlayer 跳过写入，相机由编辑器直驱） */
    public void setPreviewDirectControl(boolean on) { previewDirectControl = on; }

    public boolean isPreviewDirectControl() { return previewDirectControl; }

    /** 编辑器拖拽直控：直接设置当前相机值（立即生效，零解析零重启） */
    public void previewSetCamera(float yaw, float pitch, float roll, float fov, float zoom) {
        activeProperties.setAllDirect(yaw, pitch, roll, fov, zoom);
    }

    public void stageTargetPosition(Vec3 pos, float duration) {
        stagedPath.setTargetPosition(pos, duration);
        stagedReady = true;
    }

    public void stageTargetYaw(float yaw, float duration) {
        stagedProperties.setTargetYaw(yaw, duration);
        stagedReady = true;
    }

    public void stageTargetPitch(float pitch, float duration) {
        stagedProperties.setTargetPitch(pitch, duration);
        stagedReady = true;
    }

    public void stageTargetRoll(float roll, float duration) {
        stagedProperties.setTargetRoll(roll, duration);
        stagedReady = true;
    }

    public void stageTargetFov(float fov, float duration) {
        stagedProperties.setTargetFov(fov, duration);
        stagedReady = true;
    }

    public void stageTargetZoom(float zoom, float duration) {
        stagedProperties.setTargetZoom(zoom, duration);
        stagedReady = true;
    }

    public void commitStagedState() {
        if (!stagedReady) return;

        activePath.overrideFrom(stagedPath);
        activeProperties.overrideFrom(stagedProperties);

        stagedReady = false;
    }

    public boolean isStagedReady() {
        return stagedReady;
    }

    // ========== 帧回调驱动 ==========

    public void onRenderFrame() {
        if (!active) {
            cachedHasActiveCameraClip = false;
            return;
        }

        boolean gamePaused = Minecraft.getInstance().isPaused() && CinematicController.INSTANCE.isPauseWhenGamePaused();
        // 编辑器预览暂停也算暂停
        boolean effectivelyPaused = gamePaused || (previewMode && previewPaused);

        // 组 1：每帧同步音频暂停状态（幂等；对齐 MC SoundEngine：暂停时无新声音、已有实例幂等 pause）。
        // 必须位于 scriptPlayer.onRenderFrame 之前并每帧执行——修复「暂停检测在实例创建前」的时序缺陷。
        // 诊断：打印暂停判定组成——若游戏内播放被误判为暂停（gamePaused/previewMode 异常）即可见。
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("camera pauseSync: eff={} gamePaused={} previewMode={} previewPaused={}",
                    effectivelyPaused, gamePaused, previewMode, previewPaused);
        }
        if (effectivelyPaused) {
            scriptPlayer.pauseAudio();
        } else {
            scriptPlayer.resumeAudio();
        }

        // 检测暂停↔恢复转换，通知服务端（握手仅转换时发送）
        if (effectivelyPaused != lastFramePaused) {
            lastFramePaused = effectivelyPaused;
            if (scriptPlayer.isPlaying()) {
                String scriptId = scriptPlayer.getScriptId();
                if (!"<none>".equals(scriptId)) {
                    // N1：暂停/恢复握手 — 登记 ACK，超时重发（handlePause 幂等）；发包经 NetworkGuard 防断线崩溃
                    String refId = com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.newRefId();
                    com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.expect(refId,
                            () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SScriptPause",
                                    () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                                            new com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptPausePacket(scriptId, effectivelyPaused, refId))));
                    com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SScriptPause",
                            () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                                    new com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptPausePacket(scriptId, effectivelyPaused, refId)));
                }
            }
        }

        // 暂停：不退出相机画面——冻结时钟但继续应用相机/轨道（修复"暂停切回玩家视角"）
        boolean freezeTime = gamePaused || (previewMode && previewPaused);
        if (freezeTime) {
            lastRealNanos = 0;
            if (previewMode && previewPaused) {
                gameTimeSeconds = previewTime;
            }
        } else {
            long now = System.nanoTime();
            if (lastRealNanos != 0) {
                gameTimeSeconds += (double)(now - lastRealNanos) / 1_000_000_000.0;
            }
            lastRealNanos = now;
        }

        float deltaTime = 1f / 20f;
        OverlayManager.INSTANCE.update(deltaTime);

        float effectiveTime = (float) getGameTimeSeconds();
        if (scriptPlayer.isPlaying()) {
            // holdAtEnd=true：时间耗尽后把渲染时间钳到总时长末尾，让最后一帧保持住
            if (scriptPlayer.isFinished() && CinematicController.INSTANCE.isHoldAtEnd()) {
                CinematicScript s = scriptPlayer.getScript();
                if (s != null) {
                    float total = s.getTotalDuration();
                    if (total > 0f) effectiveTime = Math.max(0f, total - 0.001f);
                }
            }
            scriptPlayer.onRenderFrame(effectiveTime);
        }

        // 帧级缓存：用与实际渲染相同的 effectiveTime 判断活跃 Camera 轨道
        cachedHasActiveCameraClip = scriptPlayer.hasActiveCameraTrack(effectiveTime);

        if (stopping && !OverlayManager.INSTANCE.isAnimating()) {
            deactivateNow();
            return;
        }

        if (!stopping && scriptPlayer.isPlaying() && scriptPlayer.isFinished()) {
            ScriptMeta.RuntimeBehavior behavior = scriptPlayer.getCurrentProperties();
            boolean holdAtEnd = behavior != null && behavior.holdAtEnd();
            // 诊断：退出链路（脚本自然结束检查）
            LOGGER.info("NATURAL_END check: playing={} finished=true stopping={} holdAtEnd={} elapsed={}",
                    scriptPlayer.isPlaying(), stopping, holdAtEnd,
                    String.format("%.2f", (float)(getGameTimeSeconds() - 0)));
            if (!holdAtEnd) {
                LOGGER.info("NATURAL_END -> requestExit");
                requestExit(ExitReason.NATURAL_END);
            }
        }
    }

    public double getGameTimeSeconds() {
        return gameTimeSeconds;
    }

    /**
     * 结束是否强制客户端重载：脚本最后落在相机片段里才需要；
     * 若最后一个 CAMERA 片段结束时间早于总时长（尾段空档），玩家视角已自然接上。
     */
    private static boolean shouldForceClientReload(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return false;
        Optional<TimelineTrack> track = script.getTimeline().getCameraTrack();
        if (track.isEmpty()) return false;
        List<Clip> clips = track.get().getClips();
        if (clips.isEmpty()) return false;
        float total = script.getTotalDuration();
        if (total < 0) return true;
        float lastEnd = 0f;
        for (Clip c : clips) {
            if (c.isEffectivelyInfinite()) return true;
            float end = c.getWindowEnd();
            if (end > lastEnd) lastEnd = end;
        }
        return lastEnd >= total;
    }

    private void deactivateNow() {
        // 诊断：退出链路（deactivateNow 执行）
        LOGGER.info("deactivateNow: reason={}", pendingCompletionReason);
        active = false;
        stopping = false;
        gameTimeSeconds = 0;
        lastRealNanos = 0;
        // 组 6/7：停止后复位直控与初始化标志（下次预览重新从玩家位置起步）
        previewDirectControl = false;
        previewInitialized = false;

        CompletionReason reason = pendingCompletionReason;
        pendingCompletionReason = CompletionReason.FINISHED;

        String finishedScriptId = scriptPlayer.getScriptId();
        if (finishedScriptId != null) {
            com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptNotifier
                    .notifyScriptFinished(finishedScriptId, reason);
        }
        com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver.resetSkipVote();

        // 结束判定：只有“最后没有尾段空档、脚本确实结束在相机片段里”才强制全量重建；
        // 有尾段空档/无 CAMERA 片段的脚本，玩家视角已经自然接上，不额外刷新。
        CinematicScript finishedScript = scriptPlayer.getScript();
        boolean needsForceReload = finishedScript != null
                && com.immersivecinematics.immersive_cinematics.trigger.client.PreloadRequester.INSTANCE.isPreloadActive()
                && shouldForceClientReload(finishedScript);

        scriptPlayer.stop(reason);
        // 退出输入优雅交接：键盘按当前物理状态重同步 + 鼠标按钮同步 + 清鼠标累积量
        // （不再 releaseAll 全量释放——避免玩家仍按着键时退出导致按键失效直到松开重按）
        CinematicController.INSTANCE.syncInputStateAfterExit();
        CinematicController.INSTANCE.revert();
        reset();
        OverlayManager.INSTANCE.reset();

        if (pendingScript != null) {
            CinematicScript next = pendingScript;
            pendingScript = null;
            startScriptInternal(next);
        } else if (!scriptQueue.isEmpty()) {
            startScriptInternal(scriptQueue.poll());
        } else {
            // 真正回到正常游戏：仅当脚本结束在相机片段里才强制一次全量区块重建；
            // 尾段空档/无 CAMERA 片段的脚本已自然回到玩家视角，不触发。
            if (needsForceReload) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.levelRenderer != null) {
                    mc.levelRenderer.allChanged();
                }
            }
        }
    }

    // ========== tick 驱动（staged 缓冲区过渡插值） ==========

    public void tick() {
        if (!active) return;
        clientEntityLogCounter++;
        if (clientEntityLogCounter % 100 == 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Vec3 pos = activePath.getPosition();
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
        if (stagedReady) {
            float deltaTime = 1f / 20f;
            stagedProperties.tick(deltaTime);
            stagedPath.tick(deltaTime);
        }
    }

    // ========== Mixin 读取接口 ==========

    public CameraProperties getProperties() {
        return activeProperties;
    }

    public CameraPath getPath() {
        return activePath;
    }

    public boolean isActive() {
        return active;
    }

    /** 是否处于编辑器预览模式（预览时 PlayerMoveController 不驱动真实玩家） */
    public boolean isPreviewMode() {
        return previewMode;
    }

    /** 当前镜头 yaw（方向性预加载用）；无活跃属性时回退玩家朝向 */
    public float getCameraYaw() {
        if (activeProperties != null) return activeProperties.getYaw();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player != null ? mc.player.getYRot() : 0;
    }

    public ScriptMeta.RuntimeBehavior getCurrentProperties() {
        return scriptPlayer.getCurrentProperties();
    }

    public void reset() {
        activeProperties.reset();
        activePath.reset();
        stagedProperties.reset();
        stagedPath.reset();
        stagedReady = false;
        scriptQueue.clear();
    }
}
