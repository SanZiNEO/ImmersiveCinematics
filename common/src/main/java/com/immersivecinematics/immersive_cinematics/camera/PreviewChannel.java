package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptParser;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.PreviewClock;

/**
 * 编辑器播放通道：编辑器播放实例、脚本缓存、暂停态与直控标志。
 *
 * 编辑器播放 = 播放引擎的另一种时钟源：只被本通道的 pushScript / setTime / resume / pause / stop 驱动，
 * 游戏实例照常播放；实例恒为列表末位（顶层），时钟源 = 编辑器播放头。内容行为参与并集
 * （HUD / lane / 相机 / 音频 / 覆盖层），游戏控制不参与（键鼠屏蔽、暂停联动、播放队列、网络账本、跳过投票）。
 * 通道未激活时脚本只缓存，等 setTime / resume 建实例。
 *
 * 状态所有权：{@code editorPlayback} / {@code editorPaused} / {@code previewInitialized} /
 * {@code previewDirectControl} 只由本类与 {@link PlaybackLifecycle} 读写；{@code previewClock}
 * 由帧驱动推进，本类负责定位与冻结。
 *
 * 线程：仅客户端主线程。
 */
final class PreviewChannel {

    /** 编辑器播放头（编辑器实例的时钟源）。 */
    private final PreviewClock previewClock;

    /** 生命周期编排（构造期由 CameraManager 绑定一次；两者互相引用）。 */
    private PlaybackLifecycle lifecycle;

    /** 编辑器播放通道是否激活（pushScript / setTime / resume 置位，exitPreview / 紧急清理清位）。 */
    private boolean editorPlayback = false;

    /** 编辑器播放是否暂停；缺省 {@code true}（通道未激活时视为暂停）。 */
    private boolean editorPaused = true;

    /** 最近一次推送的编辑器脚本；解析失败时保持原值。 */
    private CinematicScript previewScript;

    /** 编辑器播放实例（列表末位）；无实例时为 {@code null}。 */
    private PlaybackInstance previewInstance = null;

    /** 预览相机是否已初始化（首次进入预览时从玩家位置起步，之后重启不重置）。 */
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

    /** 是否处于编辑器播放（供玩家移动 / 区块预加载 / 编辑器状态回传判读）。 */
    boolean isEditorPlayback() {
        return editorPlayback;
    }

    /** 编辑器播放是否暂停（编辑器播放控制状态）。 */
    boolean isEditorPaused() {
        return editorPaused;
    }

    /** 编辑器拖拽直控是否生效（直控期间轨道写入被跳过）。 */
    boolean isPreviewDirectControl() {
        return previewDirectControl;
    }

    /** 编辑器播放实例（列表末位）；无实例时为 {@code null}。 */
    PlaybackInstance previewInstance() {
        return previewInstance;
    }

    /** 编辑器相机是否已初始化（首次编辑器播放从玩家位置起步，之后重启不重置）。 */
    boolean isPreviewInitialized() {
        return previewInitialized;
    }

    /** 置编辑器相机初始化标志（首次进入编辑器播放时置真，全局复位时置假）。 */
    void setPreviewInitialized(boolean initialized) {
        this.previewInitialized = initialized;
    }

    /** 登记编辑器播放实例。 */
    void setPreviewInstance(PlaybackInstance instance) {
        this.previewInstance = instance;
    }

    /** 该实例出列后同步失效编辑器实例位（下次 pushScript / setTime / resume 重建）。 */
    void clearInstanceIfPreview(PlaybackInstance instance) {
        if (instance == previewInstance) {
            previewInstance = null;
        }
    }

    /** 编辑器拖拽直控：进入/退出直控态。 */
    void setPreviewDirectControl(boolean on) {
        previewDirectControl = on;
    }

    /** 退出编辑器播放通道：激活位清零、置暂停、冻结播放头；不动编辑器实例（实例由调用方清理）。 */
    void deactivateChannel() {
        editorPlayback = false;
        editorPaused = true;
        previewClock.freeze();
    }

    /**
     * 编辑器推送脚本（编辑即预览）：解析并缓存，编辑器播放激活时同步到编辑器实例。
     * 编辑器实例不存在则新建；存在则增量替换数据（零重建零重启）。通道未激活时只缓存脚本。
     */
    void pushScript(String jsonContent) {
        try {
            previewScript = ScriptParser.parse(jsonContent);
            // 编辑内容照常缓存;编辑器播放已激活且已有脚本时保持激活(编辑即预览)
            if (editorPlayback && previewScript != null) {
                PlaybackInstance instance = previewInstance;
                if (instance == null) {
                    instance = lifecycle.startScriptInternal(previewScript, "", true);
                } else {
                    instance.replaceScript(previewScript);
                }
                if (instance == null) return;
                disableMacroLoop(instance);
                double previewHead = previewClock.seconds();
                instance.player().alignTime(previewHead, previewHead);
                // 数据替换后同步暂停态并把实例定位到播放头
                if (editorPaused) {
                    instance.player().pauseAudio();
                } else {
                    instance.player().resumeAudio();
                }
                instance.player().repositionAudio(previewHead);
            }
        } catch (Exception e) {
            // 编辑器边界：解析/应用失败保留上一次可用脚本，编辑器继续可用（限频留全栈供定位）
            ErrorLog.logRateLimited("Editor", "preview:push-script", 5000L,
                    "编辑器脚本应用失败（json 长度=" + (jsonContent == null ? 0 : jsonContent.length())
                            + "）：保留上一次可用脚本", e);
        }
    }

    /**
     * 编辑器定位（seek）：编辑器播放头跳到 {@code seconds} 秒并保持编辑器播放激活、暂停。
     * 只作用于编辑器实例；游戏共享虚拟时钟不被改写（两实例各自计时）。
     */
    void setTime(double seconds) {
        // 编辑器播放定位:始终激活并显示对应帧的相机视角(终止后点关键帧/拖播放头即时可见)
        previewClock.seek(seconds);
        editorPlayback = true;
        editorPaused = true;
        PlaybackInstance instance = previewInstance;
        if (instance == null && previewScript != null) {
            instance = lifecycle.startScriptInternal(previewScript, "", true);
        }
        if (instance == null) return; // 编辑器脚本尚未传入：没有实例可定位
        disableMacroLoop(instance);
        double previewHead = previewClock.seconds();
        // 定位 = 硬定位：读数对齐 + 派生状态归位成对（见 ScriptPlayer#seekTo）
        instance.player().seekTo(previewHead);
        // 定位即同步暂停态——先于 repositionAudio（其 paused 分支依赖此标志），
        // 并覆盖 startScriptInternal 预执行首帧已创建/播放的实例。
        instance.player().pauseAudio();
        instance.player().repositionAudio(previewHead);
    }

    /** 编辑器播放：编辑器实例从播放头续播（通道未激活时先激活并建实例）。 */
    void resume() {
        // 点播放 → 用最新 previewScript 重新激活并从播放头续播
        if (!editorPlayback) {
            if (previewScript == null) return;
            editorPlayback = true;
        }
        if (previewInstance == null) {
            if (previewScript == null) return;
            lifecycle.startScriptInternal(previewScript, "", true);
        }
        if (previewInstance == null) return; // 玩家尚未进入世界 / 启动被跳过：无实例可续播
        disableMacroLoop(previewInstance);
        double previewHead = previewClock.seconds();
        previewInstance.player().alignTime(previewHead, previewHead);
        editorPaused = false;
        previewClock.freeze();
    }

    /** 编辑器暂停：编辑器播放头冻结在当前进度（游戏实例的时钟不受影响）。 */
    void pause() {
        editorPaused = true;
    }

    /** 编辑器停止：编辑器播放激活时播放头归零并保持激活，否则走游戏内停止。 */
    void stop() {
        if (editorPlayback) {
            // 终止 = 重置播放头到第一帧并保持编辑器播放激活(相机回到脚本第一帧视角,而非玩家视角;
            // 玩家视角由脚本时间空隙自然产生——相机片段之间的空缺时段会归还视角)
            setTime(0.0);
        } else {
            // 游戏内停止路径
            lifecycle.stopScript();
        }
    }

    /** 编辑器完全退出：只退编辑器实例，游戏实例继续播放（与 {@link #stop()} 的「归零保持激活」不同）。 */
    void exitPreview() {
        if (editorPlayback) {
            deactivateChannel();
            // 只退编辑器实例：待接播/队列与游戏实例一概不动（它们是游戏播放侧的状态）
            if (previewInstance != null) {
                lifecycle.deactivateNow(previewInstance);
            }
        } else {
            lifecycle.stopScript();
        }
    }

    /** 编辑器播放不做宏观循环：循环是运行时播放控制，编辑器按真实时间线播放（不展开也不折叠）。 */
    private static void disableMacroLoop(PlaybackInstance instance) {
        if (instance != null) instance.player().setMacroLoopAllowed(false);
    }
}
