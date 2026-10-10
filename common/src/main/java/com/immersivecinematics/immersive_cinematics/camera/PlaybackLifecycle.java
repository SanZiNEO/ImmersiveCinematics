package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.immersivecinematics.immersive_cinematics.util.GameClock;
import com.immersivecinematics.immersive_cinematics.util.PreviewClock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 播放生命周期与队列：启动 / 退出 / 停止 / 待接播 / 播放队列。
 *
 * 退出统一入口 {@link #requestExit(PlaybackInstance, ExitReason)} 按原因映射完成原因，再走
 * 「渐出退场」（{@link #deactivate}）或「立即停用」（{@link #deactivateNow}）；渐出完成与自然结束
 * 由帧驱动判定后调 {@link #deactivateNow}。生命周期按实例独立：只要还有实例在播，共享时钟 /
 * 相机状态 / 覆盖层都不复位；最后一个实例退出才全局复位。
 *
 * 同脚本单实例：{@link #playScript} 命中同脚本活跃实例时按可打断性「立即替换 / 入队」，
 * 接播在 {@link #deactivateNow} 末尾（{@code pendingScript} 优先于队列）。
 *
 * 线程：仅客户端主线程。
 */
final class PlaybackLifecycle {

    /** 日志通道名固定为拆分前值，保持日志输出逐行不变。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    private final PlaybackRegistry registry;
    private final PlaybackLedger ledger;
    private final PreviewChannel previewChannel;
    private final CameraStateHolder stateHolder;
    private final GameClock gameClock;
    private final PreviewClock previewClock;

    /** 待接播脚本（可打断路径暂存）；{@code null} = 无待接播。 */
    private CinematicScript pendingScript = null;

    /** 待接播脚本携带的播放实例 id；本地来源为空串，{@code pendingScript} 为 {@code null} 时无意义。 */
    private String pendingInstanceId = "";

    /** 播放队列（容量 8）：当前脚本不可打断时新脚本入队，结束后自动接播。 */
    private final ScriptQueue scriptQueue = new ScriptQueue();

    PlaybackLifecycle(PlaybackRegistry registry, PlaybackLedger ledger, PreviewChannel previewChannel,
                      CameraStateHolder stateHolder, GameClock gameClock, PreviewClock previewClock) {
        this.registry = registry;
        this.ledger = ledger;
        this.previewChannel = previewChannel;
        this.stateHolder = stateHolder;
        this.gameClock = gameClock;
        this.previewClock = previewClock;
    }

    // ========== 脚本播放 ==========

    /**
     * 播放脚本：跨脚本（无同脚本活跃实例）一律新建实例并行播放；同脚本已有活跃实例时维持单实例语义
     * （可打断 → 立即替换该实例；不可打断 → 入队，容量满则拒绝）。
     *
     * @param instanceId 服务端播放请求的播放实例 id；本地来源传空串
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    int playScript(CinematicScript script, String instanceId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;

        // 同脚本单实例：只有“同一脚本已在播”才走原有决策树；跨脚本请求不受在播实例影响
        PlaybackInstance sameScript = registry.instancePlaying(script.getId());
        if (sameScript == null) {
            PlaybackInstance started = startScriptInternal(script, instanceId);
            ledger.reportPlaybackStarted(script, started);
            return 1;
        }

        if (sameScript.isInterruptible()) {
            // 可打断 → 立即替换该实例：置原因 + 精确退场（deactivateNow 末尾接播 pendingScript）
            pendingScript = script;
            pendingInstanceId = instanceId;
            sameScript.setExitReason(CompletionReason.INTERRUPTED);
            deactivateNow(sameScript);
            return 1;
        }

        // 不可打断 → 一律排队（容量满则拒绝）
        if (scriptQueue.offer(script, instanceId)) return 2;
        return 0;
    }

    /** 停止顶层实例（系统停止）。 */
    void stopScript() {
        requestExit(registry.topInstance(), ExitReason.SYSTEM_STOP);
    }

    /** 强制立即停用全部活跃实例：不渐出、不接播。 */
    void forceDeactivate() {
        deactivateAllNow();
    }

    // ========== 统一退出入口 ==========

    /**
     * 统一退出入口（指定实例）：退出一个实例不影响其他实例。
     *
     * @param instance 目标实例；{@code null} = 无播放可结束
     * @param reason   退出请求原因
     * @return {@code false} = 被实例自身的行为开关拒绝（不可打断 / 不可跳过 / 末尾保持）
     */
    boolean requestExit(PlaybackInstance instance, ExitReason reason) {
        switch (reason) {
            case FORCE_QUIT:
                setExitReason(instance, CompletionReason.FORCE_QUIT);
                deactivateNow(instance);
                return true;

            case SYSTEM_STOP:
                setExitReason(instance, CompletionReason.STOPPED);
                deactivate(instance);
                return true;

            case INTERRUPTED:
                if (instance != null && !instance.isInterruptible()) {
                    LOGGER.debug("脚本不可打断(interruptible=false)，拒绝抢占");
                    return false;
                }
                setExitReason(instance, CompletionReason.INTERRUPTED);
                deactivate(instance);
                return true;

            case USER_SKIP:
                if (instance != null && !instance.isSkippable()) {
                    LOGGER.debug("脚本不可跳过(skippable=false)，拒绝用户退出");
                    return false;
                }
                setExitReason(instance, CompletionReason.SKIPPED);
                deactivate(instance);
                return true;

            case NATURAL_END:
                if (instance != null && instance.isHoldAtEnd()) {
                    return false;
                }
                setExitReason(instance, CompletionReason.FINISHED);
                deactivate(instance);
                return true;
        }
        return false;
    }

    /** 请求指定实例退场渐出（渐出完成后由帧驱动的结束判定真正停用）。 */
    private void deactivate(PlaybackInstance instance) {
        if (instance == null) return;
        if (instance.isStopping()) return;
        OverlayManager.INSTANCE.startFadeOut();
        instance.markStopping();
    }

    /** 置入实例的退出原因；无实例时没有播放可结束，无需记录。 */
    private static void setExitReason(PlaybackInstance instance, CompletionReason reason) {
        if (instance != null) instance.setExitReason(reason);
    }

    /** 紧急停止（世界退出/断线）：跳过退场动画直接清理全部实例，防止 OpenAL 音频残留。 */
    void emergencyStop() {
        previewChannel.deactivateChannel();
        // 世界已退出：全部实例一起清理（跨脚本并行下逐个清，预览实例也在内，音频/覆盖层都不能残留）
        for (PlaybackInstance instance : registry.instances()) {
            instance.setExitReason(CompletionReason.STOPPED);
        }
        deactivateAllNow();
    }

    /** 立即停用全部实例：清空待接播与队列后逐个精确退出。 */
    private void deactivateAllNow() {
        pendingScript = null;
        pendingInstanceId = "";
        scriptQueue.clear();
        while (!registry.isEmpty()) {
            deactivateNow(registry.topInstance());
        }
    }

    // ========== 实例启停 ==========

    /**
     * 立即停用指定实例（不渐出）：出列 → 通知结束 → 清理该实例 → 全局复位或按剩余实例重算 → 接播。
     * 跨脚本并行下只影响传入实例：只要还有实例在播，共享时钟 / 相机状态 / 覆盖层 / 输入交接都不复位。
     * 预览实例不上报网络账本，退出它也不触发待接播。
     *
     * @param instance 目标实例；{@code null} = 无播放可结束（仅做一次全局复位检查）
     */
    void deactivateNow(PlaybackInstance instance) {
        CompletionReason reason = instance != null ? instance.exitReason() : CompletionReason.FINISHED;
        LOGGER.info("deactivateNow: reason={}", reason);

        boolean preview = instance != null && instance.isPreview();

        // 实例先出列：后续读取不再看到本实例
        if (instance != null) {
            registry.remove(instance);
            previewChannel.clearInstanceIfPreview(instance);
        }

        ledger.reportScriptFinished(instance, reason);

        if (instance != null) {
            instance.stop(reason);
        }

        if (registry.isEmpty()) {
            // 最后一个实例退出：全局复位（虚拟时钟 / 相机状态 / 预览标志 / 输入交接 / 行为开关 / 覆盖层）
            gameClock.reset();
            previewChannel.setPreviewDirectControl(false);
            previewChannel.setPreviewInitialized(false);
            // 退出输入优雅交接：键盘按当前物理状态重同步 + 鼠标按钮同步 + 清鼠标累积量
            // （不再 releaseAll 全量释放——避免玩家仍按着键时退出导致按键失效直到松开重按）
            CinematicController.INSTANCE.syncInputStateAfterExit();
            CinematicController.INSTANCE.revert();
            reset();
            OverlayManager.INSTANCE.reset();
        } else {
            // 仍有实例在播：全局时钟与相机状态属于剩余实例，不能复位；只按剩余实例重算行为开关并集。
            // 队列与现状一致地随实例结束清空（队列语义待 plans/0.3.6/parallel-playback.md §6 定稿）。
            scriptQueue.clear();
            CinematicController.INSTANCE.recomputeUnion(registry.instanceBehaviors());
        }

        if (!preview && pendingScript != null) {
            // 同脚本替换（可打断路径）的接播：替换目标已被本方法出列
            CinematicScript next = pendingScript;
            String nextInstanceId = pendingInstanceId;
            pendingScript = null;
            pendingInstanceId = "";
            ledger.reportPlaybackStarted(next, startScriptInternal(next, nextInstanceId));
        } else if (!preview && !scriptQueue.isEmpty()) {
            // 队列接播（同脚本单实例的排队请求）：当前不可达——队列在上面的实例结束路径已清空，
            // 语义待 §6 定稿后一并实现（需要「按脚本匹配取队头」的队列 API）。
            ScriptQueue.Entry next = scriptQueue.poll();
            ledger.reportPlaybackStarted(next.script(), startScriptInternal(next.script(), next.instanceId()));
        } else {
            // 真正回到正常游戏：释放时由服务端差集补发自动决定需要重发的玩家区区块。
        }

        // 停用即失效快照；若上面接播了 pendingScript/队列（已有新实例），这里重建为接播后的最新值。
        refreshState();
    }

    /** 复位全局写入缓冲与播放队列（不改快照；快照由调用方的 refresh 决定）。 */
    void reset() {
        stateHolder.reset();
        scriptQueue.clear();
    }

    // ========== 实例创建 ==========

    /** 新建并启动一个游戏播放实例；调用点已判定「是否该开始」，本方法不做互斥检查。 */
    PlaybackInstance startScriptInternal(CinematicScript script, String instanceId) {
        return startScriptInternal(script, instanceId, false);
    }

    /**
     * 新建并启动一个播放实例（每次真正开始播放 = 一个新实例）。
     *
     * @param preview {@code true} = 编辑器预览实例（独立时钟、恒排列表末位、不参与行为并集与账本）
     * @return 新启动的实例（调用方据此上报服务端账本）
     */
    PlaybackInstance startScriptInternal(CinematicScript script, String instanceId, boolean preview) {
        Minecraft mc = Minecraft.getInstance();
        if (!previewChannel.isPreviewMode()) {
            mc.setScreen(null);
        }

        // 只有真正屏蔽键鼠的脚本才清空按键；非屏蔽脚本保留玩家当前输入状态，避免切换时中断
        if (script.getMeta().isBlockKeyboard() || script.getMeta().isBlockMouse()) {
            CinematicController.INSTANCE.releaseAllKeys();
        }

        // 预览实例重启不再重置相机到玩家位置（否则每次 pushScript 节流重启都闪回 → 拖拽卡顿）；
        // 首次进入预览仍初始化一次，保证预览首帧从玩家位置起步。游戏实例每次开播都从玩家位置起步。
        if (!preview || !previewChannel.isPreviewInitialized()) {
            Vec3 playerPos = mc.player.position();
            stateHolder.path().setPositionDirect(playerPos);
            stateHolder.properties().setYawDirect(mc.player.getYRot());
            stateHolder.properties().setPitchDirect(mc.player.getXRot());
        }
        previewChannel.setPreviewInitialized(true);

        // 列表顺序 = 叠放顺序：游戏实例插在预览实例之前，预览实例追加到末位（预览恒为顶层）。
        PlaybackInstance instance = new PlaybackInstance(preview);
        if (preview) {
            registry.addLast(instance);
            previewChannel.setPreviewInstance(instance);
        } else {
            registry.insertBefore(previewChannel.previewInstance(), instance);
        }

        // 预执行首帧用播放头时间（预览实例），避免首帧写 t=0 造成画面跳变；游戏内播放传 0。
        instance.player().setMacroLoopAllowed(!preview);
        if (preview) {
            instance.player().setClockSource(previewClock);
        }
        instance.start(script, instanceId, preview ? (float) previewClock.seconds() : 0f);
        if (preview) {
            // 预览通道不套用脚本行为：允许键鼠。有游戏实例并行时改按游戏实例重算并集——
            // 预览不解除游戏实例的键鼠屏蔽。
            List<ScriptMeta.RuntimeBehavior> gameBehaviors = registry.instanceBehaviors();
            if (gameBehaviors.isEmpty()) {
                CinematicController.INSTANCE.setBlockKeyboard(false);
                CinematicController.INSTANCE.setBlockMouse(false);
            } else {
                CinematicController.INSTANCE.recomputeUnion(gameBehaviors);
            }
        } else {
            // 行为开关按全部活跃实例取并集
            CinematicController.INSTANCE.recomputeUnion(registry.instanceBehaviors());
        }

        // 写侧收口：instance.start 已预执行脚本首帧、直写内部状态；这里立即刷新统一快照，
        // 使同一 tick 内后续读取看到接播后的最新值，而非过期/空快照。
        refreshState();
        return instance;
    }

    /** 从写入缓冲重建统一快照；无活跃实例时置 {@code null}。 */
    private void refreshState() {
        stateHolder.refresh(registry.isActive());
    }
}
