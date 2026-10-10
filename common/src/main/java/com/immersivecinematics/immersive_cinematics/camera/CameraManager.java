package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CameraManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    public static final CameraManager INSTANCE = new CameraManager();

    private final CameraProperties activeProperties = new CameraProperties();
    private final CameraPath activePath = new CameraPath();

    /**
     * 播放实例列表（并行播放模型：播放 = 实例，数量不设上限，见 plans/0.3.6/parallel-playback.md §1）。
     * <p>列表顺序 = 启动顺序：帧驱动按顺序遍历（后写入者覆盖先写入者 → 后来者居上，§3.4），
     * 顶层实例 = 列表最后一个（{@link #topInstance()}）。同脚本同玩家保持单实例（§3.5）：
     * 同脚本的第二个请求走拒绝/排队；跨脚本请求一律新建实例并行播放（§3.6）。
     */
    private final List<PlaybackInstance> instances = new ArrayList<>();

    /** {@link #instances} 的只读视图（避免每次取用都包装一遍；与底层列表同步反映增删） */
    private final List<PlaybackInstance> instancesView = Collections.unmodifiableList(instances);

    /** hasActiveCameraClip 的帧级缓存，避免 9 个 Mixin 调用点每帧重复扫描 */
    private boolean cachedHasActiveCameraClip = false;

    /**
     * 顶层实例（后来者居上）本帧自身是否有活跃 CAMERA clip —— 帧级缓存，与
     * {@link #cachedHasActiveCameraClip}（全实例并集）同帧同点计算。
     * <p>听者判定（{@code AudioListenerController}，§3.3）必须取顶层实例这一份，而不是并集：
     * 听者由启动最晚的活跃实例决定，门控取同一实例才能口径一致（并集会让下层实例的 CAMERA clip
     * 把顶层自身没有 CAMERA clip 时的听者误判为 camera）。
     */
    private boolean cachedTopInstanceHasActiveCameraClip = false;

    /** 统一只读相机状态快照（每帧更新末尾刷新；无活跃状态时为 null） */
    private CameraState cameraState = null;

    private double gameTimeSeconds = 0;
    private long lastRealNanos = 0;

    private CinematicScript pendingScript = null;

    /**
     * 待接播请求（{@link #pendingScript}）携带的播放实例 id（§3.7）：可打断路径把请求暂存在
     * {@code pendingScript} 时一并记下，{@link #deactivateNow} 接播时随 {@code C2SPlaybackStarted} 上报。
     * 本地来源为空串；{@code pendingScript} 为 null 时该值无意义。
     */
    private String pendingInstanceId = "";

    /** C1：播放队列（容量 8，当前脚本不可打断时新脚本一律入队，结束后自动接播） */
    private final ScriptQueue scriptQueue = new ScriptQueue();

    /**
     * 编辑器预览通道是否激活（{@link #pushScript} / {@link #setTime} / {@link #resume} 置位，
     * {@link #exitPreview} / {@link #emergencyStop} 清位）。
     * <p>
     * 预览状态本身收敛在 {@link #previewInstance} 上（时钟 = {@link #previewTime}、暂停态 =
     * {@link #previewPaused}）；本标志只表示"编辑器预览通道已接管"，供 {@link #isPreviewMode()} 的
     * 消费方（玩家移动 / 区块预加载 / 编辑器状态回传）判读。
     */
    private boolean previewMode = false;
    private boolean previewPaused = true;

    /** 预览播放头（秒）＝ 预览实例的时钟：暂停 = 冻结在此处；播放 = 按真实时间推进（见 {@link #onRenderFrame()}） */
    private float previewTime;
    private CinematicScript previewScript;

    /**
     * 预览实例（{@code plans/0.3.6/parallel-playback.md} §7 步骤 5）：编辑器预览的<b>独立</b>播放实例。
     * <p>
     * 预览不再复用 / 替换游戏实例——{@link #pushScript} / {@link #setTime} / {@link #resume} /
     * {@link #stop} 只作用于本实例，游戏实例照常播放；{@link #exitPreview()} 只退本实例。
     * 它恒为 {@link #instances} 的<b>末位</b>（顶层：预览画面与相机是编辑器正在编辑的那一份，
     * §3.4 后来者居上），用<b>独立时钟</b>（{@link #previewTime}），且不参与暂停联动 / 行为并集，
     * 不上报网络账本与跳过投票（本地预览无账本）。非预览态为 {@code null}。
     */
    private PlaybackInstance previewInstance = null;

    /** 预览时钟的上一帧真实纳秒（0 = 本帧重新起算）；与游戏时钟 {@link #lastRealNanos} 相互独立 */
    private long lastPreviewRealNanos = 0;

    /** 上一帧的暂停状态，用于检测暂停↔恢复的转换 */
    private boolean lastFramePaused = false;

    /** 客户端实际实体数日志计数 */
    private int clientEntityLogCounter = 0;

    /** 组 6：预览模式相机是否已初始化（首次 start 时从玩家位置起步，之后重启不再重置相机 → 消除拖拽卡顿） */
    private boolean previewInitialized = false;

    /** 组 7：编辑器拖拽直控标志 — 直控期间 CameraTrackPlayer 跳过轨道写入，相机由编辑器直驱 */
    private boolean previewDirectControl = false;

    /**
     * 活跃播放实例 —— 返回<b>顶层实例</b>（启动最晚者，§3.4 后来者居上）；无播放时为 null。
     * <p>跨脚本并行放开后可能同时存在多个实例：需要“当前代表”的消费方（画面 lane 收集、听者、
     * 玩家移动、预加载、服务端账本上报）都取顶层；按实例逐个处理的路径见 {@link #onRenderFrame()}、
     * {@link #instanceBehaviors()}、{@link #requestExit(ExitReason)}。
     * <p>预览激活时顶层 = <b>预览实例</b>（恒排列表末位，见 {@link #previewInstance}）：编辑器正在编辑的
     * 那份画面/相机就是当前代表。
     */
    public PlaybackInstance activeInstance() {
        return topInstance();
    }

    /**
     * 全部活跃播放实例，按<b>启动顺序</b>排列（先启动在前，顶层 = 最后一个）——只读视图。
     * <p>需要「按实例逐个处理」的消费方遍历本方法，而不是只看 {@link #activeInstance()} 的顶层：
     * 画面 lane 收集（{@code ScriptLaneDriver}）要把<b>所有</b>实例的 lane 平铺成一张总表
     * （§3.3 有什么就放什么），平铺顺序 = 本列表顺序 → 实例内部各自顺序，先启动的实例整体在下、
     * 后启动的整体在上（§3.4 后来者居上）。
     * <p>返回的列表不可修改；内容随实例增删实时变化（帧内遍历需自行防并发修改，同 {@link #onRenderFrame()}）。
     */
    public List<PlaybackInstance> instances() {
        return instancesView;
    }

    /**
     * 活跃实例的行为快照列表 —— 运行时控制并集（{@code CinematicController.recomputeUnion}）的输入。
     * 实例增删后调用，见 {@code plans/0.3.6/parallel-playback.md} §3.2。
     */
    private List<ScriptMeta.RuntimeBehavior> instanceBehaviors() {
        List<ScriptMeta.RuntimeBehavior> behaviors = new ArrayList<>(instances.size());
        for (PlaybackInstance instance : instances) {
            // 预览实例不参与行为并集：预览通道不套用脚本行为（既有语义，见 startScriptInternal 预览分支）
            if (instance.isPreview()) continue;
            behaviors.add(instance.behavior());
        }
        return behaviors;
    }

    /**
     * 暂停联动取并集（{@code plans/0.3.6/parallel-playback.md} §3.1 与审查修订②）：
     * 任一活跃实例声明 {@code pause_when_game_paused} 即返回 true（游戏暂停时放行输入）；
     * 无活跃实例时为 false（无播放可联动，与调用点的 {@code isActive()} 前置判断一致）。
     *
     * <p>单实例下等价于该实例自己的 {@code isPauseWhenGamePaused()}（并集 of 一个 = 该值），零回归。
     */
    public boolean isAnyPauseWhenGamePaused() {
        for (PlaybackInstance instance : instances) {
            // 预览实例不参与：预览的暂停/播放由编辑器驱动（previewPaused），与游戏暂停无关（§7 步骤 5）
            if (!instance.isPreview() && instance.isPauseWhenGamePaused()) return true;
        }
        return false;
    }

    /**
     * 顶层实例（后来者居上，§3.4）是否可跳过 —— 跳过提示只由它决定；无活跃实例时 false（不显示提示）。
     *
     * <p>实例按启动顺序追加（见 {@link #startScriptInternal}），故顶层 = 最后启动的活跃实例。
     */
    public boolean isTopInstanceSkippable() {
        PlaybackInstance top = topInstance();
        return top != null && top.isSkippable();
    }

    /**
     * 顶层实例 = 启动最晚的活跃实例（后来者居上，§3.4）；无活跃实例时为 null。
     * <p>列表不变量：预览实例恒为末位（{@link #startScriptInternal} 创建游戏实例时插在它之前），
     * 因此预览激活时顶层 = 预览实例。
     */
    private PlaybackInstance topInstance() {
        return instances.isEmpty() ? null : instances.get(instances.size() - 1);
    }

    /**
     * 同脚本冲突实例（§3.5 同脚本单实例）：正在播放 {@code scriptId} 的活跃实例；无则 null。
     * <p>跨脚本请求不查这里——它们一律新建实例并行播放（§3.6）；只有命中本方法的请求才走
     * “可打断 = 立即替换 / 不可打断 = 排队或拒绝”的原有单实例语义。
     */
    private PlaybackInstance instancePlaying(String scriptId) {
        if (scriptId == null) return null;
        for (PlaybackInstance instance : instances) {
            if (instance.player().isPlaying() && scriptId.equals(instance.scriptId())) return instance;
        }
        return null;
    }

    /** 请求指定实例退场渐出（渐出完成后由 {@link #onRenderFrame()} 的结束判定真正停用）。 */
    private void deactivate(PlaybackInstance instance) {
        if (instance == null) return;
        if (instance.isStopping()) return;
        OverlayManager.INSTANCE.startFadeOut();
        instance.markStopping();
    }

    // ========== 统一退出入口 ==========

    /**
     * 统一退出入口 —— 作用于<b>顶层实例</b>（后来者居上，§3.4）：跳过键 / 强制退出键等玩家交互
     * 面向的就是顶层实例（跳过提示也只由它决定，见 {@link #isTopInstanceSkippable()}）。
     */
    public boolean requestExit(ExitReason reason) {
        return requestExit(topInstance(), reason);
    }

    /**
     * 统一退出入口（指定实例）：跨脚本并行下，帧内自然结束等路径必须精确作用于某个实例——
     * 退出一个实例不影响其他实例（§3.1 生命周期按实例独立）。
     *
     * @param instance 目标实例；null = 无播放可结束
     */
    private boolean requestExit(PlaybackInstance instance, ExitReason reason) {
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

    /** 置入实例的退出原因；无活跃实例时没有播放可结束，无需记录。 */
    private static void setExitReason(PlaybackInstance instance, CompletionReason reason) {
        if (instance != null) instance.setExitReason(reason);
    }

    // ========== 脚本播放模式 ==========

    /**
     * 播放脚本（并行播放模型，§3.5/§3.6）：
     * <ul>
     *   <li><b>跨脚本</b>（无同脚本活跃实例）→ 一律<b>新建实例并行播放</b>（1）：不排队、不打断、不阻塞——
     *       有什么就放什么；已有实例继续播放（§3.6 不做互斥组）。</li>
     *   <li><b>同脚本</b>已有活跃实例 → 维持单实例语义（§3.5）：可打断 → 立即替换该实例（1）；
     *       不可打断 → 一律排队（容量 8，满则拒绝），priority 只用于队列内排序（2 / 0）。</li>
     * </ul>
     *
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    public int playScript(CinematicScript script) {
        return playScript(script, "");
    }

    /**
     * 同 {@link #playScript(CinematicScript)}，另携带服务端播放请求的<b>播放实例 id</b>（§3.7）：
     * 该 id 会随 {@link #reportPlaybackStarted} 回传服务端，作为播放账本的实例键。
     *
     * @param instanceId {@code S2CPlayScriptPacket.getInstanceId()}；本地来源（编辑器重载等）传空串
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    public int playScript(CinematicScript script, String instanceId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;

        // 同脚本单实例（§3.5）：只有“同一脚本已在播”才走原有决策树；跨脚本请求不受在播实例影响
        PlaybackInstance sameScript = instancePlaying(script.getId());
        if (sameScript == null) {
            PlaybackInstance started = startScriptInternal(script, instanceId);
            reportPlaybackStarted(script, started);
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

    public void stopScript() {
        requestExit(ExitReason.SYSTEM_STOP);
    }

    /**
     * {@link #playScript} 的别名（语义相同，返回值透传）。
     *
     * @return 0=被拒绝, 1=已开始播放, 2=已排队等待
     */
    public int playCinematic(CinematicScript script) {
        return playScript(script);
    }

    /** 同 {@link #playCinematic(CinematicScript)}，另携带服务端播放请求的播放实例 id（§3.7）。 */
    public int playCinematic(CinematicScript script, String instanceId) {
        return playScript(script, instanceId);
    }

    /** 顶层实例的脚本 id（无活跃实例时 {@code "<none>"}）。 */
    public String getActiveScriptId() {
        PlaybackInstance instance = activeInstance();
        return instance != null ? instance.scriptId() : "<none>";
    }

    /**
     * 强制立即停用<b>全部</b>活跃实例（服务端 stop 命令 / 调试强制退出）：不渐出、不接播。
     * <p>跨脚本并行下“停止”是整体动作——停一个留一个会让服务端账本与客户端画面不一致。
     */
    public void forceDeactivate() {
        deactivateAllNow();
    }

    /** 立即停用全部实例：清空待接播与队列后逐个精确退出（每个实例各自通知结束、各自清理）。 */
    private void deactivateAllNow() {
        pendingScript = null;
        pendingInstanceId = "";
        scriptQueue.clear();
        while (!instances.isEmpty()) {
            deactivateNow(topInstance());
        }
    }

    public boolean isScriptMode() {
        PlaybackInstance instance = activeInstance();
        return instance != null && instance.player().isPlaying();
    }

    public boolean hasActiveCameraClip() {
        return cachedHasActiveCameraClip;
    }

    /**
     * 顶层实例（启动最晚的活跃实例，§3.4 后来者居上）本帧自身是否有活跃 CAMERA clip。
     * <p>听者判定的门控口径（§3.3）：听者由顶层实例的 {@code meta.listener} 决定，门控也取同一实例。
     * 与 {@link #hasActiveCameraClip()} 的差别仅在多实例：顶层自身无 CAMERA clip、而下层实例有时，
     * 并集为 true，本方法为 false（听者回落 player）。单实例下两者恒等。
     */
    public boolean topInstanceHasActiveCameraClip() {
        return cachedTopInstanceHasActiveCameraClip;
    }

    // ========== 编辑器预览 ==========

    /**
     * 预览通道不做宏观循环：循环是运行时播放控制，编辑器预览按真实时间线播放（不展开也不折叠）。
     * 作用于预览实例（{@link #previewInstance}）——预览通道只动自己那一个实例。
     */
    private static void disableMacroLoop(PlaybackInstance instance) {
        if (instance != null) instance.player().setMacroLoopAllowed(false);
    }

    /**
     * 编辑器推送脚本（编辑即预览）：解析并缓存，预览通道激活时同步到<b>预览实例</b>。
     * <p>
     * 预览实例不存在则新建（仍是预览实例，不动游戏实例）；存在则增量替换数据（零重建零重启）。
     * 预览通道未激活时只缓存脚本，等 {@link #setTime} / {@link #resume} 建实例。
     */
    public void pushScript(String jsonContent) {
        try {
            previewScript = com.immersivecinematics.immersive_cinematics.script.ScriptParser.parse(jsonContent);
            // 编辑内容照常缓存;预览模式且已有脚本时保持激活(编辑即预览)
            if (previewMode && previewScript != null) {
                PlaybackInstance instance = previewInstance;
                if (instance == null) {
                    instance = startScriptInternal(previewScript, "", true);
                } else {
                    // 组 A：编辑模式常驻播放器 — 增量替换数据（零重建零重启；
                    // TrackPlayer 数据源动态化，音频实例按 sound+startTime+duration 重映射复用）
                    instance.replaceScript(previewScript);
                }
                if (instance == null) return;
                disableMacroLoop(instance);
                instance.player().alignTime(previewTime, previewTime);
                // 组 1/2：数据替换后同步暂停态并把实例定位到播放头
                if (previewPaused) {
                    instance.player().pauseAudio();
                } else {
                    instance.player().resumeAudio();
                }
                instance.player().repositionAudio(previewTime);
            }
        } catch (com.immersivecinematics.immersive_cinematics.script.ScriptParser.ScriptParseException e) {
            LOGGER.error("编辑器传入的脚本 JSON 解析失败", e);
        }
    }

    /**
     * 编辑器定位（seek）：预览播放头跳到 {@code seconds} 并保持预览激活、暂停。
     * <p>
     * 只作用于预览实例——游戏共享虚拟时钟不再被播放头改写（两实例各自计时，§7 步骤 5）；
     * 编辑器读到的播放头见 {@link #getPreviewTimeSeconds()}。
     */
    public void setTime(float seconds) {
        // 预览模式定位:始终激活并显示对应帧的相机视角(终止后点关键帧/拖播放头即时可见)
        previewTime = seconds;
        previewMode = true;
        previewPaused = true;
        PlaybackInstance instance = previewInstance;
        if (instance == null && previewScript != null) {
            instance = startScriptInternal(previewScript, "", true);
        }
        if (instance == null) return; // 预览脚本尚未传入：没有实例可定位
        disableMacroLoop(instance);
        // Align so that elapsed = previewTime（预览实例的时钟源就是 previewTime，故当前时钟读数同值）
        instance.player().alignTime(previewTime, previewTime);
        // 组 1：定位即同步暂停态——先于 repositionAudio（其 paused 分支依赖此标志），
        // 并覆盖 startScriptInternal 预执行首帧已创建/播放的实例。
        instance.player().pauseAudio();
        instance.player().repositionAudio(previewTime);
    }

    /** 编辑器播放：预览实例从播放头 {@link #previewTime} 续播（预览通道未激活时先激活并建实例）。 */
    public void resume() {
        // 点播放 → 用最新 previewScript 重新激活并从 previewTime 续播
        if (!previewMode) {
            if (previewScript == null) return;
            previewMode = true;
        }
        if (previewInstance == null) {
            if (previewScript == null) return;
            startScriptInternal(previewScript, "", true);
        }
        disableMacroLoop(previewInstance);
        previewInstance.player().alignTime(previewTime, previewTime);
        previewPaused = false;
        lastPreviewRealNanos = 0;
    }

    /** 编辑器暂停：预览时钟冻结在当前播放头（游戏实例的时钟不受影响）。 */
    public void pause() {
        // 暂停必须记住当前播放进度，否则冻结时钟时会回退到上一次 setTime 的旧位置（表现为“暂停回到开头”）。
        // 预览播放头即预览时钟：播放中它已在推进，暂停只置标志（onRenderFrame 随即冻结它）。
        previewPaused = true;
    }

    /** 编辑器停止：预览播放头归零并保持预览激活（游戏实例照常播放）。 */
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
     * 编辑器完全退出(关闭编辑器时调用):只退<b>预览实例</b>,游戏实例继续播放(§7 步骤 5)。
     * 与 {@link #stop()}("终止=归零保持激活")语义不同——关闭编辑器必须真正退出预览通道。
     */
    public void exitPreview() {
        if (previewMode) {
            previewMode = false;
            previewPaused = true;
            lastPreviewRealNanos = 0;
            // 只退预览实例：待接播/队列与游戏实例一概不动（它们是游戏播放侧的状态）
            if (previewInstance != null) {
                deactivateNow(previewInstance);
            }
        } else {
            stopScript();
        }
    }

    /** D2：紧急停止（世界退出/断线时调用）：跳过退场动画直接清理，防止 OpenAL 音频残留 */
    public void emergencyStop() {
        previewMode = false;
        previewPaused = true;
        lastPreviewRealNanos = 0;
        // 世界已退出：全部实例一起清理（跨脚本并行下逐个清，预览实例也在内，音频/覆盖层都不能残留）
        for (PlaybackInstance instance : instances) {
            instance.setExitReason(CompletionReason.STOPPED);
        }
        deactivateAllNow();
    }

    /**
     * 新建并启动一个播放实例（并行播放模型：每次真正开始播放 = 一个新实例，§3.5）——游戏实例路径。
     * <p>调用点只负责决定“是否该开始”——同脚本单实例的判定在 {@link #playScript}，
     * 接播判定在 {@link #deactivateNow}；本方法不做任何互斥检查。
     *
     * @return 新启动的实例（调用方据此上报服务端账本，§3.7）
     */
    private PlaybackInstance startScriptInternal(CinematicScript script, String instanceId) {
        return startScriptInternal(script, instanceId, false);
    }

    /**
     * 新建并启动一个播放实例（并行播放模型：每次真正开始播放 = 一个新实例，§3.5）。
     * <p>调用点只负责决定“是否该开始”——同脚本单实例的判定在 {@link #playScript}，
     * 接播判定在 {@link #deactivateNow}；本方法不做任何互斥检查。
     *
     * @param preview {@code true} = 编辑器预览实例（§7 步骤 5）：用独立时钟（预览播放头）、
     *                恒排实例列表末位（顶层）、不参与行为并集与网络账本上报。
     *                游戏实例传 {@code false}（与改造前逐点等价）
     * @return 新启动的实例（调用方据此上报服务端账本，§3.7）
     */
    private PlaybackInstance startScriptInternal(CinematicScript script, String instanceId, boolean preview) {
        Minecraft mc = Minecraft.getInstance();
        if (!previewMode) {
            mc.setScreen(null);
        }

        // 只有真正屏蔽键鼠的脚本才清空按键；非屏蔽脚本保留玩家当前输入状态，避免切换时中断
        if (script.getMeta().isBlockKeyboard() || script.getMeta().isBlockMouse()) {
            CinematicController.INSTANCE.releaseAllKeys();
        }

        // 组 6：预览实例重启不再重置相机到玩家位置（否则每次 pushScript 节流重启都闪回 → 拖拽卡顿）；
        // 首次进入预览（previewInitialized=false）仍初始化一次，保证预览首帧从玩家位置起步不闪白。
        // 游戏实例保持原语义：每次开播都从玩家位置起步。
        if (!preview || !previewInitialized) {
            Vec3 playerPos = mc.player.position();
            activePath.setPositionDirect(playerPos);
            activeProperties.setYawDirect(mc.player.getYRot());
            activeProperties.setPitchDirect(mc.player.getXRot());
        }
        previewInitialized = true;

        // 列表顺序 = 叠放顺序（后来者居上，§3.4）：帧驱动按顺序遍历，后写入者覆盖先写入者。
        // 不变量：预览实例恒为末位（顶层）——游戏实例插在它之前，预览实例本身追加到末尾。
        PlaybackInstance instance = new PlaybackInstance(preview);
        if (preview) {
            instances.add(instance);
            previewInstance = instance;
        } else if (previewInstance != null) {
            int at = instances.indexOf(previewInstance);
            instances.add(at >= 0 ? at : instances.size(), instance);
        } else {
            instances.add(instance);
        }

        // 组 A：预执行首帧用播放头时间（预览实例），避免首帧写 t=0 造成画面跳变；游戏内播放传 0 保持原语义
        // 预览通道不做宏观循环：循环是运行时播放控制，编辑器预览按真实时间线播放（不展开也不折叠）
        instance.player().setMacroLoopAllowed(!preview);
        if (preview) {
            // 预览实例的时钟 = 预览播放头：与游戏共享虚拟时钟分离（两实例各自计时，§7 步骤 5）
            instance.player().setClockSource(this::previewClockSeconds);
        }
        instance.start(script, instanceId, preview ? previewTime : 0f);
        if (preview) {
            // 预览通道不套用脚本行为（既有语义）：允许键鼠。有游戏实例并行时改按游戏实例重算并集——
            // 预览不解除游戏实例的键鼠屏蔽（游戏实例零回归）。
            List<ScriptMeta.RuntimeBehavior> gameBehaviors = instanceBehaviors();
            if (gameBehaviors.isEmpty()) {
                CinematicController.INSTANCE.setBlockKeyboard(false);
                CinematicController.INSTANCE.setBlockMouse(false);
            } else {
                CinematicController.INSTANCE.recomputeUnion(gameBehaviors);
            }
        } else {
            // 行为开关按全部活跃实例取并集（§3.2）；生命周期判定一律读活跃实例（§3.1），无全局副本
            CinematicController.INSTANCE.recomputeUnion(instanceBehaviors());
        }

        // 写侧收口：instance.start 已预执行脚本首帧、直写内部状态；这里立即刷新统一快照，
        // 使同一 tick 内后续读取（如 PreloadRequester）看到接播后的最新值，而非过期/空快照。
        refreshCameraState();
        return instance;
    }

    /** 预览实例的时钟读数（秒）＝ 预览播放头：暂停时冻结在播放头，播放时由 {@link #onRenderFrame()} 推进。 */
    private double previewClockSeconds() {
        return previewTime;
    }

    /**
     * 服务端账本上报：脚本**真正开始播放**时发 C2SPlaybackStarted（{@code started=true}）。
     * <p>
     * 只在“实际开始播放”的入口调用——直接开始（{@link #playScript}）与结束接播
     * （{@link #deactivateNow} 的 pendingScript / scriptQueue 分支）。排队等待、被拒绝、
     * 编辑器预览都不上报，否则服务端 {@code ScriptEventManager} 的观看者账本与触发去重
     * （{@code TriggerEngine.shouldSkip}）会与实际播放状态错位。
     * <p>
     * refId 留空：play 命令的传输层 ACK 由 {@code ClientScriptReceiver} 单独回执（ACK 与“已开始”
     * 两件事解耦，见 {@code C2SPlaybackStartedPacket}）；{@code instanceId} 取<b>本次刚启动的那个实例</b>
     * 自带的播放实例 id（§3.7；跨脚本并行下可能同时有多个实例，不能按“顶层”推断，故由调用方传入）。
     */
    private void reportPlaybackStarted(CinematicScript script, PlaybackInstance started) {
        if (script == null) return;
        String id = script.getId();
        if (id == null || id.isEmpty()) return;
        final String instanceId = started != null && started.instanceId() != null ? started.instanceId() : "";
        com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SPlaybackStarted",
                () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                        new com.immersivecinematics.immersive_cinematics.trigger.network.C2SPlaybackStartedPacket(id, instanceId, true)));
    }

    // ========== 组 7：编辑器拖拽直控（bbs 式"编辑即生效"，零解析零重启） ==========

    /** 编辑器拖拽直控：进入/退出直控态（直控期间 CameraTrackPlayer 跳过写入，相机由编辑器直驱） */
    public void setPreviewDirectControl(boolean on) { previewDirectControl = on; }

    public boolean isPreviewDirectControl() { return previewDirectControl; }

    // ========== 帧回调驱动 ==========

    /**
     * 每渲染帧驱动<b>全部</b>活跃实例（并行播放模型，§3.1/§3.4）：
     * <ol>
     *   <li>帧级时钟与暂停判定只做一次：<b>游戏共享虚拟时钟</b>（游戏暂停时冻结）与
     *       <b>预览播放头</b>（预览实例专用：暂停冻结、播放推进）各自独立推进（§7 步骤 5）；</li>
     *   <li>按列表顺序（= 启动顺序）逐个驱动实例的播放器：后驱动的实例后写入共享相机状态 →
     *       顶层实例（预览激活时 = 预览实例）的画面与状态胜出（§3.4 后来者居上）；</li>
     *   <li>某个实例结束（渐出完成 / 自然结束）→ 只退出该实例（{@link #deactivateNow}），
     *       其余实例继续播放；</li>
     *   <li>最后一个实例退出后由 {@code deactivateNow} 复位全局状态（相机、时钟、覆盖层、行为开关）。</li>
     * </ol>
     *
     * <p>调用点：{@code LaneRendererMixin}（{@code GameRenderer.renderLevel} 的 RETURN）开头，
     * 每个渲染帧一次；主相机替换链退役前挂点在 {@code CameraMixin.onSetup}（主相机 setup）。
     * 顺序要求：必须早于 {@code ScriptLaneDriver.tick} —— 后者读的就是本方法填好的 lane 快照。</p>
     */
    public void onRenderFrame() {
        if (instances.isEmpty()) {
            cachedHasActiveCameraClip = false;
            cachedTopInstanceHasActiveCameraClip = false;
            refreshCameraState();
            return;
        }

        // 暂停联动取并集（§3.1）：任一<b>游戏</b>实例声明 pause_when_game_paused 即按游戏暂停处理；
        // 单实例下与改造前逐点等价（并集 of 一个 = 该实例自己的值）。预览实例不参与——
        // 预览的暂停/播放由编辑器驱动（previewPaused），与游戏暂停无关（§7 步骤 5）。
        boolean gamePaused = Minecraft.getInstance().isPaused() && isAnyPauseWhenGamePaused();

        // 诊断：打印暂停判定组成——若游戏内播放被误判为暂停（gamePaused/previewMode 异常）即可见。
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("camera pauseSync: gamePaused={} previewMode={} previewPaused={} instances={}",
                    gamePaused, previewMode, previewPaused, instances.size());
        }

        // 时钟一：游戏共享虚拟时钟——游戏暂停时冻结（不退出相机画面，继续应用相机/轨道）。
        // 预览的暂停/播放不再冻结它（预览实例有自己的时钟，§7 步骤 5）。
        if (gamePaused) {
            lastRealNanos = 0;
        } else {
            long now = System.nanoTime();
            if (lastRealNanos != 0) {
                gameTimeSeconds += (double)(now - lastRealNanos) / 1_000_000_000.0;
            }
            lastRealNanos = now;
        }

        // 时钟二：预览播放头（预览实例专用）——暂停 = 冻结在播放头；播放 = 按真实时间推进。
        // 两时钟互不影响：编辑器拖播放头/暂停只动预览实例，游戏实例照常按共享虚拟时钟走。
        if (previewMode && !previewPaused && previewInstance != null) {
            long now = System.nanoTime();
            if (lastPreviewRealNanos != 0) {
                previewTime += (float)((now - lastPreviewRealNanos) / 1_000_000_000.0);
            }
            lastPreviewRealNanos = now;
        } else {
            lastPreviewRealNanos = 0;
        }

        float deltaTime = 1f / 20f;
        OverlayManager.INSTANCE.update(deltaTime);

        // 暂停↔恢复转换是帧级事件：转换发生的那一帧为每个在播实例各发一条握手（§3.7 账本按实例解析）。
        // 只跟游戏暂停：预览实例不上报网络账本（本地预览，§7 步骤 5）。
        boolean pauseTransition = gamePaused != lastFramePaused;
        if (pauseTransition) {
            lastFramePaused = gamePaused;
        }

        float gameTime = (float) gameTimeSeconds;
        float previewClock = previewTime;
        boolean anyActiveCameraClip = false;
        // 顶层实例（启动最晚的活跃实例）——听者门控口径（§3.3）只认它自己的 CAMERA clip
        PlaybackInstance top = topInstance();
        boolean topActiveCameraClip = false;

        // 遍历副本：帧内可能有实例退出（deactivateNow 出列）或接播（追加新实例），避免并发修改
        for (PlaybackInstance instance : new ArrayList<>(instances)) {
            if (!instances.contains(instance)) continue; // 本帧更早的退出已移除该实例
            ScriptPlayer player = instance.player();
            // 本实例的暂停态：游戏实例 = 游戏暂停联动（并集）；预览实例 = 编辑器暂停态（§7 步骤 5）
            boolean instancePaused = instance.isPreview() ? previewPaused : gamePaused;
            // 本实例的时钟读数：游戏实例 = 共享虚拟时钟；预览实例 = 预览播放头（各自计时）
            float instanceClock = instance.isPreview() ? previewClock : gameTime;

            // 组 1：每帧同步音频暂停状态（幂等；对齐 MC SoundEngine：暂停时无新声音、已有实例幂等 pause）。
            // 必须位于 player.onRenderFrame 之前并每帧执行——修复「暂停检测在实例创建前」的时序缺陷。
            if (instancePaused) {
                player.pauseAudio();
            } else {
                player.resumeAudio();
            }

            if (pauseTransition && !instance.isPreview() && player.isPlaying()) {
                String scriptId = player.getScriptId();
                if (!"<none>".equals(scriptId)) {
                    sendPausePacket(scriptId, instance.instanceId(), gamePaused);
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
                // 帧级缓存取并集：任一实例本帧有活跃 Camera 轨道即算有（§3.6 有什么就放什么）
                boolean instanceCameraClip = player.hasActiveCameraTrack(instanceTime);
                anyActiveCameraClip |= instanceCameraClip;
                // 顶层实例单独记一份（同一钳制时间口径）——听者门控只认它（§3.3）
                if (instance == top) topActiveCameraClip = instanceCameraClip;
            }

            if (instance.isStopping() && !OverlayManager.INSTANCE.isAnimating()) {
                deactivateNow(instance);
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
                    // 精确作用于本实例：其他并行实例不受影响（§3.1）
                    requestExit(instance, ExitReason.NATURAL_END);
                }
            }
        }

        cachedHasActiveCameraClip = anyActiveCameraClip;
        cachedTopInstanceHasActiveCameraClip = topActiveCameraClip;

        // 帧末统一生成/替换快照：本帧所有渲染侧读取（lane 收集、听者、预加载、预览 HUD）
        // 都拿到这一份，且同帧内多次读取一致。
        refreshCameraState();
    }

    /** N1：暂停/恢复握手（按实例，§3.7）——立即发一条 + 登记 ACK 超时重发（handlePause 幂等）。 */
    private static void sendPausePacket(String scriptId, String instanceId, boolean paused) {
        // 发包经 NetworkGuard 防断线崩溃
        String refId = com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.newRefId();
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.expect(refId,
                () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SScriptPause",
                        () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                                new com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptPausePacket(scriptId, instanceId, paused, refId))));
        com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard.sendToServer("C2SScriptPause",
                () -> com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToServer(
                        new com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptPausePacket(scriptId, instanceId, paused, refId)));
    }

    public double getGameTimeSeconds() {
        return gameTimeSeconds;
    }

    /**
     * 编辑器预览播放头（秒）——预览通道激活时 = 预览实例的时钟（暂停 = 播放头位置；播放 = 已播到的时间），
     * 未激活时回落全局虚拟时钟（与改造前 {@code WebEditorApi} 读 {@link #getGameTimeSeconds()} 的读数一致）。
     * <p>
     * 编辑器（{@code playback.state.time}）读这个，而不是全局虚拟时钟：预览实例与游戏实例各自计时，
     * 游戏实例的时钟不再代表预览播放头（§7 步骤 5）。
     */
    public double getPreviewTimeSeconds() {
        return previewMode ? previewTime : gameTimeSeconds;
    }

    /** 编辑器预览是否处于暂停（WebUI 预览屏的播放控制状态）。 */
    public boolean isPreviewPaused() {
        return previewPaused;
    }

    /**
     * 立即停用<b>指定</b>实例（不渐出）：出列 → 通知结束 → 清理该实例 → 全局复位或按剩余实例重算 → 接播。
     * <p>跨脚本并行下本方法只影响传入实例（§3.1 生命周期按实例独立）：只要还有实例在播，
     * 共享虚拟时钟、相机状态、覆盖层、输入交接都不能复位——它们属于剩余实例。
     * <p>预览实例（{@link PlaybackInstance#isPreview()}）另有两条例外：不上报网络账本
     * （结束通知 / 跳过投票都是游戏播放侧的账本），退出它也不触发待接播（§7 步骤 5）。
     *
     * @param instance 目标实例；null = 无播放可结束（仅做一次全局复位检查）
     */
    private void deactivateNow(PlaybackInstance instance) {
        CompletionReason reason = instance != null ? instance.exitReason() : CompletionReason.FINISHED;
        // 诊断：退出链路（deactivateNow 执行）
        LOGGER.info("deactivateNow: reason={}", reason);

        boolean preview = instance != null && instance.isPreview();

        // 实例先出列（等价改造前 active=false 的位置）：后续读取不再看到本实例
        if (instance != null) {
            instances.remove(instance);
            // 预览实例出列 → 预览字段同步失效（下次 pushScript / setTime / resume 重建）
            if (instance == previewInstance) {
                previewInstance = null;
            }
        }

        String finishedScriptId = instance != null ? instance.scriptId() : null;
        if (finishedScriptId != null && !preview) {
            // 预览实例不上报账本：本地预览没有服务端观看者/触发器去重可言（§7 步骤 5）
            String finishedInstanceId = instance.instanceId() != null ? instance.instanceId() : "";
            com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptNotifier
                    .notifyScriptFinished(finishedScriptId, finishedInstanceId, reason);
        }
        if (!preview) {
            // 跳过投票账本同理：预览实例不参与投票，退出它不该清游戏播放的投票进度
            com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver.resetSkipVote();
        }

        if (instance != null) {
            instance.stop(reason);
        }

        if (instances.isEmpty()) {
            // 最后一个实例退出：全局复位（虚拟时钟 / 相机状态 / 预览标志 / 输入交接 / 行为开关 / 覆盖层）
            gameTimeSeconds = 0;
            lastRealNanos = 0;
            // 组 6/7：停止后复位直控与初始化标志（下次预览重新从玩家位置起步）
            previewDirectControl = false;
            previewInitialized = false;
            // 退出输入优雅交接：键盘按当前物理状态重同步 + 鼠标按钮同步 + 清鼠标累积量
            // （不再 releaseAll 全量释放——避免玩家仍按着键时退出导致按键失效直到松开重按）
            CinematicController.INSTANCE.syncInputStateAfterExit();
            CinematicController.INSTANCE.revert();
            reset();
            OverlayManager.INSTANCE.reset();
        } else {
            // 仍有实例在播（跨脚本并行）：全局时钟与相机状态属于剩余实例，不能复位；
            // 只按剩余实例重算行为开关并集（§3.2）。队列与现状一致地随实例结束清空
            // （队列语义待 plans/0.3.6/parallel-playback.md §6 定稿）。
            scriptQueue.clear();
            CinematicController.INSTANCE.recomputeUnion(instanceBehaviors());
        }

        if (!preview && pendingScript != null) {
            // 同脚本替换（可打断路径）的接播：与剩余实例无关，替换目标已被本方法出列
            CinematicScript next = pendingScript;
            String nextInstanceId = pendingInstanceId;
            pendingScript = null;
            pendingInstanceId = "";
            reportPlaybackStarted(next, startScriptInternal(next, nextInstanceId));
        } else if (!preview && !scriptQueue.isEmpty()) {
            // 队列接播（同脚本单实例的排队请求）：当前不可达——队列在上面的实例结束路径已清空，
            // 语义待 §6 定稿后一并实现（需要“按脚本匹配取队头”的队列 API）。
            ScriptQueue.Entry next = scriptQueue.poll();
            reportPlaybackStarted(next.script(), startScriptInternal(next.script(), next.instanceId()));
        } else {
            // 真正回到正常游戏：不再无条件/按尾段空档强制 allChanged；
            // 释放时由服务端差集补发自动决定需要重发的玩家区区块。
        }

        // 停用即失效快照；若 deactivateNow 末尾接播了 pendingScript/queue（已有新实例），
        // 这里重建为接播后的最新值——否则本帧 Mixin 会读到 null 而原实现读到接播脚本的首帧值。
        refreshCameraState();
    }

    // ========== tick 驱动 ==========

    public void tick() {
        if (!isActive()) return;
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
    }

    // ========== 全局相机状态（写侧 = 轨道播放器，读侧 = 统一快照）==========
    // 主相机替换链退役后，这两个内部对象不再被渲染 Mixin 读取：唯一写入者是轨道播放器
    // （CameraTrackPlayer 每帧把顶层 clip 的六参数写进来），唯一读侧是下面的统一快照。

    public CameraProperties getProperties() {
        return activeProperties;
    }

    public CameraPath getPath() {
        return activePath;
    }

    /**
     * 统一只读相机状态快照（本帧最新值）；无活跃相机时为 {@code null}。
     * <p>
     * 值 = 本帧最后写入全局相机状态的实例（= 顶层实例）的顶层活跃 clip 六参数，与画面 lane 里
     * 最上层那条 lane 的 {@link CameraState} 同源同值（{@code CameraTrackPlayer.writeAttributes}
     * 写全局状态并返回同一份快照）。
     * <p>
     * 由 {@link #refreshCameraState()} 在每帧更新末尾（{@code onRenderFrame}）、
     * 停用/重启（{@code deactivateNow} / {@code startScriptInternal}）与直写入口
     * （{@code setCameraDirect}）处刷新，
     * 保证任何写入后立即反映新值、同一帧内多次读取结果一致。
     * <p>
     * 消费方（主相机替换链退役后）：{@code AudioListenerController}（听者位置）、
     * {@code PreloadRequester}（区块预加载中心）、{@code WebPreviewScreen}（预览 HUD 显示）。
     */
    public CameraState getCameraState() {
        return cameraState;
    }

    /** 从内部状态（activePath / activeProperties 的 current 值）重建统一快照；无活跃相机时置 null。 */
    private void refreshCameraState() {
        cameraState = isActive()
                ? new CameraState(activePath.getPosition(),
                        activeProperties.getYaw(), activeProperties.getPitch(),
                        activeProperties.getRoll(), activeProperties.getFov(), activeProperties.getZoom())
                : null;
    }

    /** 相机是否被播放实例接管（本版本 = 有活跃实例）。 */
    public boolean isActive() {
        return !instances.isEmpty();
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

    /** WebUI 编辑器直接设置预览相机参数（yaw/pitch/roll/fov/zoom） */
    public void setCameraDirect(float yaw, float pitch, float roll, float fov, float zoom) {
        if (!isActive()) return;
        activeProperties.setAllDirect(yaw, pitch, roll, fov, zoom);
        // 直写穿透：保证本帧 getFov 立即读到新值
        refreshCameraState();
    }

    /**
     * 直控写入（含位置）：飞行取景等需要同时写位置与光学的直写路径。
     * <p>写侧收口——调用方不再直写内部 {@code activePath}/{@code activeProperties}；
     * 写完立即刷新统一快照，保证任何写入后 {@link #getCameraState()} 立即反映新值。
     */
    public void setCameraDirect(Vec3 position, float yaw, float pitch, float roll, float fov, float zoom) {
        activePath.setPositionDirect(position);
        activeProperties.setAllDirect(yaw, pitch, roll, fov, zoom);
        refreshCameraState();
    }
    public void reset() {
        activeProperties.reset();
        activePath.reset();
        scriptQueue.clear();
    }
}
