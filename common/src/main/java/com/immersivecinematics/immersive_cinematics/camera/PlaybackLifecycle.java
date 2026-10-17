package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.camera.cover.CameraCoverChain;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.immersivecinematics.immersive_cinematics.util.GameClock;
import com.immersivecinematics.immersive_cinematics.util.PreviewClock;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 播放生命周期与队列：启动 / 退出 / 停止 / 待接播 / 播放队列。
 *
 * 退出统一入口 {@link #requestExit(PlaybackInstance, ExitReason)} 按原因映射完成原因，再走「渐出退场」
 * （{@link #deactivate}）或「立即停用」（{@link #deactivateNow}）；生命周期按实例独立：只要还有实例在播，
 * 共享时钟 / 相机状态 / 覆盖层都不复位，最后一个实例退出才全局复位。
 *
 * 同脚本单实例：{@link #playScript} 命中同脚本活跃实例时按可打断性「立即替换 / 入队」，接播在
 * {@link #deactivateNow} 收尾（{@code pendingScript} 优先于队列；无 exclusive 时按 priority/FIFO
 * 排空全部可播项，遇到新启动的 exclusive 立即停止取项）；队列跨实例存活（只有全停清空），接播先于全局复位。
 *
 * 跨脚本到达裁决（{@code meta.play_mode}，设计 §4）：S 含 exclusive → 新来者入队；S 无 exclusive 但含
 * interruptible → 顶掉全部 interruptible（{@code requestExit(INTERRUPTED)} → 渐出退场）后开播；
 * 其余（S 空 / 仅 parallel）→ 并行加入。出队判据见 {@link #canDequeue()}。
 *
 * 同脚本替换路径（可打断的同一脚本再触发）保持硬切：置原因 + {@code deactivateNow}。
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

    /** 覆盖链：新实例创建时注入实例（Modifier 注册 / 注销由实例自己的生命周期驱动）。 */
    private final CameraCoverChain coverChain;

    /** 统一快照刷新钩子（由 {@code CameraManager} 注入：值 = 覆盖链输出，无活跃实例时为 {@code null}）。 */
    private final Runnable refreshSnapshot;

    /** 待接播脚本（可打断路径暂存）；{@code null} = 无待接播。 */
    private CinematicScript pendingScript = null;

    /** 待接播脚本携带的播放实例 id；本地来源为空串，{@code pendingScript} 为 {@code null} 时无意义。 */
    private String pendingInstanceId = "";

    /** 播放队列（不设上限）：当前脚本不可打断时新脚本入队，结束后自动接播。 */
    private final ScriptQueue scriptQueue = new ScriptQueue();

    /** 出队可播判定：该脚本当前没有游戏实例在播（预览实例不参与）。复用一份，避免每次出队分配。 */
    private final Predicate<CinematicScript> scriptNotPlaying;

    PlaybackLifecycle(PlaybackRegistry registry, PlaybackLedger ledger, PreviewChannel previewChannel,
                      CameraStateHolder stateHolder, GameClock gameClock, PreviewClock previewClock,
                      CameraCoverChain coverChain, Runnable refreshSnapshot) {
        this.registry = registry;
        this.ledger = ledger;
        this.previewChannel = previewChannel;
        this.stateHolder = stateHolder;
        this.gameClock = gameClock;
        this.previewClock = previewClock;
        this.coverChain = coverChain;
        this.refreshSnapshot = refreshSnapshot;
        this.scriptNotPlaying = script -> registry.instancePlaying(script.getId()) == null;
    }

    // ========== 脚本播放 ==========

    /**
     * 播放脚本（到达裁决）：同脚本已有活跃实例 → 既有单实例路径（可打断 → 立即替换；不可打断 → 入队）；
     * 跨脚本 → 按在播集合 S 裁决（设计 §4）：S 含 exclusive → 入队；S 无 exclusive 但含 interruptible →
     * 顶掉全部 interruptible 后开播；其余（S 空 / 仅 parallel）→ 直接并行开播。
     *
     * @param instanceId 服务端播放请求的播放实例 id；本地来源传空串
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    int playScript(CinematicScript script, String instanceId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;

        // 同脚本单实例：只有“同一脚本已在播”才走原有决策树；跨脚本请求按在播集合裁决
        PlaybackInstance sameScript = registry.instancePlaying(script.getId());
        if (sameScript == null) {
            // 在播集合含 exclusive：新来者一律排队（不设上限）；同脚本重复请求替换自己的排队位
            if (registry.hasGameInstancePlaying(ScriptMeta.PlayMode.EXCLUSIVE)) {
                scriptQueue.offerOrReplace(script, instanceId);
                return 2;
            }
            // 无 exclusive 但含 interruptible：先顶掉全部 interruptible 实例（requestExit → 渐出退场），再开播
            interruptInterruptibles();
            // 本次请求直接开播：同脚本旧排队副本已被本次请求取代，移除，避免本实例结束后又拉起旧副本
            scriptQueue.cancel(script.getId());
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

        // 不可打断 → 一律排队（不设上限）；同脚本重复请求替换自己的排队位，不占新格
        scriptQueue.offerOrReplace(script, instanceId);
        return 2;
    }

    /**
     * 顶掉全部在播的 interruptible 非预览实例（跨脚本到达裁决：新来者打断它们后开播）。
     * 打断走统一退出入口 {@link #requestExit(PlaybackInstance, ExitReason)}（{@link ExitReason#INTERRUPTED}）
     * ——渐出退场链：置原因 → {@code deactivate}（只淡出被打断者的层）→ 帧驱动判定完成 → {@code deactivateNow}。
     * 先取目标副本再逐个停用：停用可能接播队列，避免边遍历边增删（新接播的实例不在本次目标内）。
     */
    private void interruptInterruptibles() {
        List<PlaybackInstance> targets = new ArrayList<>();
        List<PlaybackInstance> instances = registry.instances();
        for (int i = 0; i < instances.size(); i++) {
            PlaybackInstance instance = instances.get(i);
            if (!instance.isPreview() && instance.playMode() == ScriptMeta.PlayMode.INTERRUPTIBLE) {
                targets.add(instance);
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            requestExit(targets.get(i), ExitReason.INTERRUPTED);
        }
    }

    /** 停止顶层实例（系统停止）。 */
    void stopScript() {
        requestExit(registry.topInstance(), ExitReason.SYSTEM_STOP);
    }

    /**
     * 按脚本定位并停止实例（服务端 stop 原语，E9）：不限于顶层——非顶层实例同样被停。
     *
     * @param scriptId   脚本 id
     * @param instanceId 播放实例 id；空串 = 该脚本任意实例
     * @return {@code false} = 无匹配实例（调用方转而尝试取消排队项）
     */
    boolean stopScriptById(String scriptId, String instanceId) {
        PlaybackInstance instance = registry.instanceOf(scriptId, instanceId);
        if (instance == null) return false;
        requestExit(instance, ExitReason.SYSTEM_STOP);
        return true;
    }

    /** 取消该脚本的排队项（E3）；{@code true} = 确有排队项被移除。 */
    boolean cancelQueued(String scriptId) {
        return scriptQueue.cancel(scriptId);
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

    /**
     * 声明式退出（规则动作 {@code exit}；设计 §8.1 ③族归 {@link ExitReason#NATURAL_END}，五值不扩）：
     * 与 {@link #requestExit} 的 NATURAL_END 同一映射（{@link CompletionReason#FINISHED} + 渐出退场），
     * 但<b>跳过 {@code hold_at_end} 拒绝</b>——声明式退出优先于末尾驻留（hold 是「内容自然播完后驻留」，
     * 作者在规则里显式声明的退出不该被驻留门挡下）。
     *
     * @param instance 目标实例（本实例作用域；{@code null} = 无播放可结束）
     * @return 恒 {@code true}（声明式退出不经行为开关门控）
     */
    boolean requestDeclaredExit(PlaybackInstance instance) {
        setExitReason(instance, CompletionReason.FINISHED);
        deactivate(instance);
        return true;
    }

    /** 请求指定实例退场渐出（只淡出本实例的层；渐出完成后由帧驱动的结束判定真正停用）。 */
    private void deactivate(PlaybackInstance instance) {
        if (instance == null) return;
        if (instance.isStopping()) return;
        OverlayManager.INSTANCE.startFadeOut(instance);
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
     * 立即停用指定实例（不渐出）：出列 → 通知结束 → 清理该实例 → 接播 → 全局复位或按剩余实例重算。
     * 跨脚本并行下只影响传入实例：只要还有实例在播（含刚接播的），共享时钟 / 相机状态 / 覆盖层 / 输入交接都不复位。
     * 预览实例不上报网络账本，退出它也不触发待接播与队列接播。
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

        // 接播先于全局复位：接播出的新实例要用共享时钟 / 相机状态 / 覆盖层，不能先被复位清掉。
        if (!preview) {
            if (pendingScript != null) {
                // 同脚本替换（可打断路径）的接播：替换目标已被本方法出列
                CinematicScript next = pendingScript;
                String nextInstanceId = pendingInstanceId;
                pendingScript = null;
                pendingInstanceId = "";
                // pending 优先：同脚本旧排队副本被本次请求取代，不能在本实例结束后再拉起
                scriptQueue.cancel(next.getId());
                ledger.reportPlaybackStarted(next, startScriptInternal(next, nextInstanceId));
            }
            // 无 exclusive 时排空全部可播项；每启动一个后重新检查——新启动的 exclusive 会阻塞剩余项
            while (canDequeue()) {
                ScriptQueue.Entry next = scriptQueue.pollFirstEligible(scriptNotPlaying);
                if (next == null) break;
                ledger.reportPlaybackStarted(next.script(), startScriptInternal(next.script(), next.instanceId()));
            }
        }

        if (registry.isEmpty()) {
            // 接播后仍无实例：全局复位（虚拟时钟 / 相机状态 / 预览标志 / 输入交接 / 行为开关 / 覆盖层）
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
            // 仍有实例在播（含刚接播的）：全局时钟与相机状态属于剩余实例，不能复位；只重算行为开关并集。
            CinematicController.INSTANCE.recomputeUnion(registry.instanceBehaviors());
        }

        // 停用即失效快照；若上面接播了 pendingScript/队列（已有新实例），这里重建为接播后的最新值。
        refreshSnapshot.run();
    }

    /**
     * 队列出队判据：{@code true} = 当前可以继续从队列取可播项。口径 = 无 EXCLUSIVE 在播
     * （阻塞队列的只有 exclusive；parallel / interruptible 实例还活着也要出队——它们不阻塞队列）。
     * 每个接播项启动后都重新判定一次：新启动的 exclusive 会立即阻塞剩余项。
     */
    private boolean canDequeue() {
        return !registry.hasGameInstancePlaying(ScriptMeta.PlayMode.EXCLUSIVE);
    }

    /**
     * 复位全局写入缓冲（不改快照；快照由调用方的 refresh 决定）。
     * 队列不在这里清——它跨实例存活，只有全停（{@code deactivateAllNow}）才清空。
     */
    void reset() {
        stateHolder.reset();
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
            stateHolder.setInitialView(mc.player.position(), mc.player.getYRot(), mc.player.getXRot());
        }
        previewChannel.setPreviewInitialized(true);

        // 列表顺序 = 叠放顺序：游戏实例插在预览实例之前，预览实例追加到末位（预览恒为顶层）。
        PlaybackInstance instance = new PlaybackInstance(coverChain, preview);
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
        refreshSnapshot.run();
        return instance;
    }
}
