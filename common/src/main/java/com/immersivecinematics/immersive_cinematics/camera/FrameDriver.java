package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import com.immersivecinematics.immersive_cinematics.util.GameClock;
import com.immersivecinematics.immersive_cinematics.util.PreviewClock;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * 帧驱动：每渲染帧推进双时钟并逐个驱动活跃实例，帧末重建统一快照。
 *
 * 顺序固定：直控层活跃窗口收口 → 时钟推进 → 逐实例驱动（按启动顺序，后驱动者后写入共享相机状态）
 * → 帧缓存写入 → 快照重建。某实例结束只退它自己，最后一个实例退出才全局复位。
 * 逐实例驱动：轨道分发之后跑本实例的判定规则引擎（{@code PlaybackInstance.ruleEngine}），挂起状态变化
 * 那一帧发暂停握手（服务端冻结 / 解冻 EVENT 时间线）；音频同步每帧幂等（音轨实例走全局 SoundEngine
 * 暂停链路，规则一次性声音走 per-instance 停声）。退场渐出按实例的层判定
 * （{@code OverlayManager.isAnimating(instance)}）：A 实例的渐出不牵连 B 实例的退场时机。
 * 调用点：{@code GameRendererMixin}（{@code GameRenderer.render} 的 HEAD，世界渲染之前），有活跃实例的帧各一次；
 * 必须早于 {@code ScriptLaneDriver.tick}（后者读本方法填好的 lane 快照）。
 * 线程契约：仅客户端主线程；帧内遍历用副本防并发修改；帧内路径不新建集合以外对象——例外为诊断日志的
 * 拼串 / 装箱（自然结束帧的 {@code NATURAL_END} 日志无条件拼串；{@code pauseSync} 调试日志仅在 debug 开启时装箱）。
 */
final class FrameDriver {

    /** 日志通道名固定为 {@code CameraManager}（日志格式契约）。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    private final PlaybackRegistry registry;
    private final PreviewChannel previewChannel;
    private final GameClock gameClock;
    private final PreviewClock previewClock;
    private final PlaybackLedger ledger;
    private final PlaybackLifecycle lifecycle;
    private final DirectControlBase directControlBase;

    /** 统一快照刷新钩子（值 = 覆盖链输出，无活跃实例时为 {@code null}）。 */
    private final Runnable refreshSnapshot;

    /** 暂停握手的内容位置复用缓冲（{@code [0] = 片段起始秒, [1] = 片段内偏移秒}；帧内零分配）。 */
    private final float[] pausePosition = new float[2];

    FrameDriver(PlaybackRegistry registry, PreviewChannel previewChannel, GameClock gameClock,
                PreviewClock previewClock, PlaybackLedger ledger, PlaybackLifecycle lifecycle,
                DirectControlBase directControlBase, Runnable refreshSnapshot) {
        this.registry = registry;
        this.previewChannel = previewChannel;
        this.gameClock = gameClock;
        this.previewClock = previewClock;
        this.ledger = ledger;
        this.lifecycle = lifecycle;
        this.directControlBase = directControlBase;
        this.refreshSnapshot = refreshSnapshot;
    }

    /** 每渲染帧驱动一次（见类 javadoc 的顺序）。 */
    void frame() {
        // 直控层活跃窗口收口：一次五参数直写只在其穿透刷新那一瞬被选中，帧末求值前已清零。
        // 已知限制：直控层与脚本导演层同源同值（同一写入缓冲），压制语义要等各层各持状态快照后才生效。
        directControlBase.clearFrame();

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
            try {
                ScriptPlayer player = instance.player();
                // 本实例的暂停态：游戏实例 = 游戏暂停联动（并集）；预览实例 = 编辑器暂停态
                boolean instancePaused = instance.isPreview() ? previewChannel.isPreviewPaused() : gamePaused;
                // 本实例的时钟读数：游戏实例 = 共享虚拟时钟；预览实例 = 预览播放头（各自计时）
                float instanceClock = instance.isPreview() ? previewTime : gameTime;

                // 每帧同步音频状态（幂等；对齐 MC SoundEngine：暂停时无新声音、已有实例幂等 pause）。
                // 必须位于 player.onRenderFrame 之前并每帧执行。挂起（规则 until / pause 动作）与游戏暂停
                // 都冻结本实例声音：音轨实例走既有全局 SoundEngine 暂停链路；规则挂起另停本实例的一次性声音
                // （play_sound 容器是实例自己的，全局暂停不覆盖它，见 AudioTrackPlayer#stopOneShots）。
                boolean audioPaused = instancePaused || player.isSuspended();
                if (audioPaused) {
                    player.pauseAudio();
                } else {
                    player.resumeAudio();
                }
                if (player.isSuspended()) {
                    player.stopOneShotSounds();
                }

                if (pauseTransition && !instance.isPreview() && player.isPlaying()) {
                    String scriptId = player.getScriptId();
                    if (!"<none>".equals(scriptId)) {
                        // 语义 = 「实例暂停中」：游戏暂停或规则挂起任一成立即冻结服务端 EVENT 时间线；
                        // 随包带上内容相对位置（服务端据此对基准，不再靠墙钟推算）
                        player.contentPosition(pausePosition);
                        ledger.sendPausePacket(scriptId, instance.instanceId(),
                                gamePaused || player.isSuspended(), pausePosition[0], pausePosition[1]);
                    }
                }

                if (player.isPlaying()) {
                    // 本实例已播放时间（不是帧驱动的时钟读数）：并行实例的起始读数各不相同，且挂起中要冻结，
                    // 用绝对时钟读数当 elapsed 会误判活跃（见下方 hasActiveCameraTrack）。
                    float instanceTime = player.getElapsedSeconds();
                    // 末帧保持（{@code holdAtEnd}）：时间耗尽后把渲染时间钳到总时长末尾，让最后一帧保持住
                    if (player.isFinished() && instance.isHoldAtEnd()) {
                        CinematicScript s = player.getScript();
                        if (s != null) {
                            float total = s.getTotalDuration();
                            if (total > 0f) instanceTime = Math.max(0f, total - 0.001f);
                        }
                    }
                    player.onRenderFrame(instanceTime);
                    // 判定规则：轨道分发之后逐实例判定（挂起 / 恢复 / 动作都作用在本实例上）
                    boolean suspendedBefore = player.isSuspended();
                    try {
                        instance.ruleEngine().evaluate(player);
                    } catch (Exception e) {
                        ErrorLog.logRateLimited("Playback", "rule-engine", 5000L, "规则引擎判定异常（本帧跳过）", e);
                    }
                    // 挂起开始 / 结束是帧级事件：状态变化那一帧发一条暂停握手（服务端冻结 / 解冻 EVENT 时间线）
                    if (!instance.isPreview() && player.isPlaying()
                            && player.isSuspended() != suspendedBefore) {
                        String scriptId = player.getScriptId();
                        if (!"<none>".equals(scriptId)) {
                            player.contentPosition(pausePosition);
                            ledger.sendPausePacket(scriptId, instance.instanceId(),
                                    gamePaused || player.isSuspended(), pausePosition[0], pausePosition[1]);
                        }
                    }
                    // 帧级缓存取并集：任一实例本帧有活跃 Camera 轨道即算有（门控与上面分发的时间同一口径）
                    boolean instanceCameraClip = player.hasActiveCameraTrack(instanceTime);
                    anyActiveCameraClip |= instanceCameraClip;
                    // 顶层实例单独记一份（同一钳制时间口径）——听者门控只认它
                    if (instance == top) topActiveCameraClip = instanceCameraClip;
                }

                if (instance.isStopping() && !OverlayManager.INSTANCE.isAnimating(instance)) {
                    lifecycle.deactivateNow(instance);
                    continue;
                }

                if (!instance.isStopping() && player.isPlaying() && player.isFinished()) {
                    boolean holdAtEnd = instance.isHoldAtEnd();
                    // 诊断：退出链路（脚本自然结束检查）——elapsed = 脚本播放头，instanceClock = 本实例时钟读数
                    LOGGER.info("NATURAL_END check: playing={} finished=true stopping={} holdAtEnd={} elapsed={} instanceClock={}",
                            player.isPlaying(), instance.isStopping(), holdAtEnd,
                            String.format("%.2f", player.getElapsedSeconds()),
                            String.format("%.2f", instanceClock));
                    if (!holdAtEnd) {
                        LOGGER.info("NATURAL_END -> requestExit");
                        // 精确作用于本实例：其他并行实例不受影响
                        lifecycle.requestExit(instance, ExitReason.NATURAL_END);
                    }
                }
            } catch (Exception e) {
                FatalErrors.rethrowIfFatal(e);
                String scriptId = instance.scriptId();
                String instanceId = instance.instanceId();
                ErrorLog.logRateLimited("Playback", "frame-instance:" + instanceId, 5000L,
                        "帧驱动实例异常（script=" + scriptId + ", instance=" + instanceId
                                + ", preview=" + instance.isPreview()
                                + "）：该实例本帧停用，其他实例继续", e);
                try {
                    lifecycle.deactivateNow(instance);
                } catch (Exception deactivateError) {
                    FatalErrors.rethrowIfFatal(deactivateError);
                    ErrorLog.logRateLimited("Playback", "frame-instance-deactivate:" + instanceId, 5000L,
                            "实例异常后停用失败（script=" + scriptId + ", instance=" + instanceId
                                    + "）：实例已从播放表移除，继续其他实例", deactivateError);
                }
            }
        }

        registry.setFrameCaches(anyActiveCameraClip, topActiveCameraClip);

        // 帧末统一生成/替换快照：本帧所有渲染侧读取（lane 收集、听者、预加载、预览 HUD）
        // 都拿到这一份，且同帧内多次读取一致。
        try {
            refreshSnapshot.run();
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            ErrorLog.logRateLimited("Playback", "frame-snapshot", 5000L,
                    "帧末快照重建异常（保持上一帧快照，其他实例继续）", e);
        }
    }
}
