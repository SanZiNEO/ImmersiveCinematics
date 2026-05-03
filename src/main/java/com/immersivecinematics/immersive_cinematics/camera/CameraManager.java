package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private double gameTimeSeconds = 0;
    private long lastRealNanos = 0;

    private CinematicScript pendingScript = null;
    private CompletionReason pendingCompletionReason = CompletionReason.FINISHED;

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
     * 播放或排队脚本
     *
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    public int playScript(CinematicScript script) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;

        if (active && scriptPlayer.isPlaying()) {
            boolean canInterrupt = requestExit(ExitReason.INTERRUPTED);
            if (!canInterrupt) {
                // 不可打断: 排队等待当前脚本自然结束后播放
                pendingScript = script;
                return 2;
            }
            pendingScript = script;
            return 2;
        }

        startScriptInternal(script);
        return 1;
    }

    /** 是否有脚本在排队等待播放 */
    public boolean hasPendingScript() {
        return pendingScript != null;
    }

    public void stopScript() {
        requestExit(ExitReason.SYSTEM_STOP);
    }

    public ScriptPlayer getScriptPlayer() {
        return scriptPlayer;
    }

    public boolean isScriptMode() {
        return scriptPlayer.isPlaying();
    }

    private void startScriptInternal(CinematicScript script) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 playerPos = mc.player.position();

        CinematicController.INSTANCE.releaseAllKeys();

        activePath.setPositionDirect(playerPos);
        activeProperties.setYawDirect(mc.player.getYRot());
        activeProperties.setPitchDirect(mc.player.getXRot());

        stagedReady = false;
        active = true;
        stopping = false;

        scriptPlayer.start(script);
        CinematicController.INSTANCE.apply(scriptPlayer.getCurrentProperties());
    }

    // ========== 预置状态写入（staged）— 仅供编辑器预览 ==========

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
        if (!active) return;

        if (Minecraft.getInstance().isPaused() && CinematicController.INSTANCE.isPauseWhenGamePaused()) {
            lastRealNanos = 0;
            return;
        }

        long now = System.nanoTime();
        double prevGameTimeSeconds = gameTimeSeconds;
        if (lastRealNanos != 0) {
            gameTimeSeconds += (double)(now - lastRealNanos) / 1_000_000_000.0;
        }
        lastRealNanos = now;

        float deltaTime = (float)(gameTimeSeconds - prevGameTimeSeconds);
        OverlayManager.INSTANCE.update(deltaTime);

        if (scriptPlayer.isPlaying()) {
            scriptPlayer.onRenderFrame(gameTimeSeconds);
        }

        if (stopping && !OverlayManager.INSTANCE.isAnimating()) {
            deactivateNow();
            return;
        }

        if (!stopping && scriptPlayer.isPlaying() && scriptPlayer.isFinished()) {
            ScriptMeta.RuntimeBehavior behavior = scriptPlayer.getCurrentProperties();
            boolean holdAtEnd = behavior != null && behavior.holdAtEnd();
            if (!holdAtEnd) {
                requestExit(ExitReason.NATURAL_END);
            }
        }
    }

    public double getGameTimeSeconds() {
        return gameTimeSeconds;
    }

    private void deactivateNow() {
        active = false;
        stopping = false;
        gameTimeSeconds = 0;
        lastRealNanos = 0;

        CinematicController.INSTANCE.releaseAllKeys();

        CompletionReason reason = pendingCompletionReason;
        pendingCompletionReason = CompletionReason.FINISHED;
        scriptPlayer.stop(reason);

        CinematicController.INSTANCE.revert();
        reset();
        OverlayManager.INSTANCE.reset();

        if (pendingScript != null) {
            CinematicScript next = pendingScript;
            pendingScript = null;
            startScriptInternal(next);
        }
    }

    // ========== tick 驱动（staged 缓冲区过渡插值） ==========

    public void tick() {
        if (!active) return;
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

    public ScriptMeta.RuntimeBehavior getCurrentProperties() {
        return scriptPlayer.getCurrentProperties();
    }

    public void reset() {
        activeProperties.reset();
        activePath.reset();
        stagedProperties.reset();
        stagedPath.reset();
        stagedReady = false;
    }
}
