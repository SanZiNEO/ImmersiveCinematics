package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
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
 * 调用点：{@code GameRendererMixin}（{@code GameRenderer.render} 的 HEAD，世界渲染之前），有活跃实例的帧各一次；
 * 必须早于 {@code ScriptLaneDriver.tick}（后者读本方法填好的 lane 快照）。
 * 线程契约：仅客户端主线程；帧内遍历用副本防并发修改；帧内路径不新建集合以外对象——例外：诊断日志的拼串 / 装箱
 * （自然结束帧的 {@code NATURAL_END} 日志无条件拼串；{@code pauseSync} 调试日志仅在 debug 开启时装箱）。
 */
final class FrameDriver {

    /** 日志通道名固定为拆分前值，保持日志输出逐行不变。 */
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
        // 直控层活跃窗口收口：一次五参数直写只在其穿透刷新那一瞬被选中，帧末求值前已清零
        // （三层同源同值，本版本无可观察差异；压制语义待各层各持自有快照后才生效）
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
        refreshSnapshot.run();
    }
}
