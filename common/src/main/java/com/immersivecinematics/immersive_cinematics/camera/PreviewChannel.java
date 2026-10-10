package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.util.PreviewClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编辑器预览通道：预览实例、预览脚本缓存、暂停态与直控标志。
 *
 * 预览是一个独立播放实例（{@link PlaybackInstance#isPreview()}）：只被本通道的
 * pushScript / setTime / resume / pause / stop 驱动，游戏实例照常播放；恒为实例列表末位（顶层）、
 * 用独立时钟（预览播放头）、不参与暂停联动 / 行为并集 / 网络账本。通道未激活时预览脚本只缓存，
 * 等 setTime / resume 建实例。
 *
 * 状态所有权：{@code previewMode} / {@code previewPaused} / {@code previewInitialized} /
 * {@code previewDirectControl} 只由本类与 {@link PlaybackLifecycle} 读写；{@code previewClock}
 * 由帧驱动推进，本类负责定位与冻结。
 *
 * 线程：仅客户端主线程。
 */
final class PreviewChannel {

    /** 日志通道名固定为拆分前值，保持日志输出逐行不变。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    /** 预览播放头（预览实例的时钟源）。 */
    private final PreviewClock previewClock;

    /** 生命周期编排（构造期由 CameraManager 绑定一次；两者互相引用）。 */
    private PlaybackLifecycle lifecycle;

    /** 编辑器预览通道是否激活（pushScript / setTime / resume 置位，exitPreview / 紧急清理清位）。 */
    private boolean previewMode = false;

    /** 预览是否暂停；缺省 {@code true}（通道未激活时视为暂停）。 */
    private boolean previewPaused = true;

    /** 最近一次推送的预览脚本；解析失败时保持原值。 */
    private CinematicScript previewScript;

    /** 预览实例（列表末位）；非预览态为 {@code null}。 */
    private PlaybackInstance previewInstance = null;

    /** 预览相机是否已初始化（首次进入预览时从玩家位置起步，之后重启不再重置）。 */
    private boolean previewInitialized = false;

    /** 编辑器拖拽直控标志（直控期间轨道写入被跳过，相机由编辑器直驱）。 */
    private boolean previewDirectControl = false;

    PreviewChannel(PreviewClock previewClock) {
        this.previewClock = previewClock;
    }

    /** 绑定生命周期编排（构造期一次）。 */
    void bindLifecycle(PlaybackLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** 预览通道是否激活（供玩家移动 / 区块预加载 / 编辑器状态回传判读）。 */
    boolean isPreviewMode() {
        return previewMode;
    }

    /** 预览是否暂停（编辑器播放控制状态）。 */
    boolean isPreviewPaused() {
        return previewPaused;
    }

    /** 编辑器拖拽直控是否生效（直控期间轨道写入被跳过）。 */
    boolean isPreviewDirectControl() {
        return previewDirectControl;
    }

    /** 预览实例（列表末位）；非预览态为 {@code null}。 */
    PlaybackInstance previewInstance() {
        return previewInstance;
    }

    /** 预览相机是否已初始化。 */
    boolean isPreviewInitialized() {
        return previewInitialized;
    }

    /** 置预览相机初始化标志（首次进入预览时置真，全局复位时置假）。 */
    void setPreviewInitialized(boolean initialized) {
        this.previewInitialized = initialized;
    }

    /** 登记预览实例。 */
    void setPreviewInstance(PlaybackInstance instance) {
        this.previewInstance = instance;
    }

    /** 该实例出列后同步失效预览实例位（下次 pushScript / setTime / resume 重建）。 */
    void clearInstanceIfPreview(PlaybackInstance instance) {
        if (instance == previewInstance) {
            previewInstance = null;
        }
    }

    /** 编辑器拖拽直控：进入/退出直控态。 */
    void setPreviewDirectControl(boolean on) {
        previewDirectControl = on;
    }

    /** 退出预览通道：激活位清零、置暂停、冻结播放头；不动预览实例（实例由调用方清理）。 */
    void deactivateChannel() {
        previewMode = false;
        previewPaused = true;
        previewClock.freeze();
    }

    /**
     * 编辑器推送脚本（编辑即预览）：解析并缓存，预览通道激活时同步到预览实例。
     * 预览实例不存在则新建；存在则增量替换数据（零重建零重启）。通道未激活时只缓存脚本。
     */
    void pushScript(String jsonContent) {
        try {
            previewScript = ScriptParser.parse(jsonContent);
            // 编辑内容照常缓存;预览模式且已有脚本时保持激活(编辑即预览)
            if (previewMode && previewScript != null) {
                PlaybackInstance instance = previewInstance;
                if (instance == null) {
                    instance = lifecycle.startScriptInternal(previewScript, "", true);
                } else {
                    instance.replaceScript(previewScript);
                }
                if (instance == null) return;
                disableMacroLoop(instance);
                double previewHead = previewClock.seconds();
                instance.player().alignTime((float) previewHead, previewHead);
                // 数据替换后同步暂停态并把实例定位到播放头
                if (previewPaused) {
                    instance.player().pauseAudio();
                } else {
                    instance.player().resumeAudio();
                }
                instance.player().repositionAudio((float) previewHead);
            }
        } catch (ScriptParser.ScriptParseException e) {
            LOGGER.error("编辑器传入的脚本 JSON 解析失败", e);
        }
    }

    /**
     * 编辑器定位（seek）：预览播放头跳到 {@code seconds} 秒并保持预览激活、暂停。
     * 只作用于预览实例；游戏共享虚拟时钟不被改写（两实例各自计时）。
     */
    void setTime(float seconds) {
        // 预览模式定位:始终激活并显示对应帧的相机视角(终止后点关键帧/拖播放头即时可见)
        previewClock.seek(seconds);
        previewMode = true;
        previewPaused = true;
        PlaybackInstance instance = previewInstance;
        if (instance == null && previewScript != null) {
            instance = lifecycle.startScriptInternal(previewScript, "", true);
        }
        if (instance == null) return; // 预览脚本尚未传入：没有实例可定位
        disableMacroLoop(instance);
        double previewHead = previewClock.seconds();
        instance.player().alignTime((float) previewHead, previewHead);
        // 定位即同步暂停态——先于 repositionAudio（其 paused 分支依赖此标志），
        // 并覆盖 startScriptInternal 预执行首帧已创建/播放的实例。
        instance.player().pauseAudio();
        instance.player().repositionAudio((float) previewHead);
    }

    /** 编辑器播放：预览实例从播放头续播（通道未激活时先激活并建实例）。 */
    void resume() {
        // 点播放 → 用最新 previewScript 重新激活并从播放头续播
        if (!previewMode) {
            if (previewScript == null) return;
            previewMode = true;
        }
        if (previewInstance == null) {
            if (previewScript == null) return;
            lifecycle.startScriptInternal(previewScript, "", true);
        }
        disableMacroLoop(previewInstance);
        double previewHead = previewClock.seconds();
        previewInstance.player().alignTime((float) previewHead, previewHead);
        previewPaused = false;
        previewClock.freeze();
    }

    /** 编辑器暂停：预览播放头冻结在当前进度（游戏实例的时钟不受影响）。 */
    void pause() {
        previewPaused = true;
    }

    /** 编辑器停止：预览激活时播放头归零并保持激活，否则走游戏内停止。 */
    void stop() {
        if (previewMode) {
            // 终止 = 重置播放头到第一帧并保持预览激活(相机回到脚本第一帧视角,而非玩家视角;
            // 玩家视角由脚本时间空隙自然产生——相机片段之间的空缺时段会归还视角)
            setTime(0f);
        } else {
            // 游戏内停止路径
            lifecycle.stopScript();
        }
    }

    /** 编辑器完全退出：只退预览实例，游戏实例继续播放（与 {@link #stop()} 的「归零保持激活」不同）。 */
    void exitPreview() {
        if (previewMode) {
            deactivateChannel();
            // 只退预览实例：待接播/队列与游戏实例一概不动（它们是游戏播放侧的状态）
            if (previewInstance != null) {
                lifecycle.deactivateNow(previewInstance);
            }
        } else {
            lifecycle.stopScript();
        }
    }

    /** 预览通道不做宏观循环：循环是运行时播放控制，预览按真实时间线播放（不展开也不折叠）。 */
    private static void disableMacroLoop(PlaybackInstance instance) {
        if (instance != null) instance.player().setMacroLoopAllowed(false);
    }
}
