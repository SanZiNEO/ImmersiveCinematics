package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.script.CameraLane;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.KeyframeInterpolator;
import com.immersivecinematics.immersive_cinematics.script.LaneFrame;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 脚本 lane 驱动：把脚本播放器本帧的活跃画面 lane 注册进 {@link LaneRenderer}，并按每条 lane
 * 所属 clip 的关键帧合成参数（{@code opacity} / {@code dest} / {@code source} / lane 遮罩）逐 lane 上屏。
 *
 * <h2>链路</h2>
 * <pre>
 * GameRendererMixin（{@code GameRenderer.render} 的 HEAD，本帧世界渲染之前）
 *   → CameraManager.onRenderFrame → ScriptPlayer.onRenderFrame → 各 CameraTrackPlayer 填 lane 快照（CameraLane）
 *     + 各相机片段携带自己的调色（collectCameraLanes 按片段本地时间采样）
 *   → 本类 tick → LaneRenderer.setLane(i, 相机状态, 内容档, lane 级调色, 相机 id) + setSink
 * LaneRendererMixin（{@code GameRenderer.renderLevel} 的 RETURN）→ LaneRenderer.render
 *   → 逐 lane 渲染 → （lane 级调色 pass）→ Sink → LaneCompositor.compose（贴到屏幕）
 * </pre>
 * 合成是即时进行的（lane 共用一张离屏缓冲），所以<b>叠放顺序 = 调用顺序 = lane 序号递增</b>：
 * {@code collectCameraLanes()} 已按「轨道层级 → 轨道内 clip 顺序」排好，序号小的先贴、被后贴的盖住。
 *
 * <h2>lane 级调色（来自该 lane 的相机片段）</h2>
 * 每份 {@link LaneFrame} 携带该 lane 的调色参数（来自该 lane 的相机片段，由 {@code ScriptPlayer}
 * 在片段本地时间处采样，每条 lane 各自一份）；本类把它原样交给 {@link LaneRenderer#setLane}。
 * 真正跑 pass 的位置在 {@code LaneRenderer.renderLane}（lane 渲染完成、合成之前），本类只做数据传递。
 * 参数为 {@code null}（片段没写调色 / 参数恒等）时该 lane 走原路径（lane FBO 直接进合成），零差异。
 * <p>该参数<b>只承载 RGB</b>（不含 alpha / opacity）：lane 级调色 pass 只动 RGB、alpha 逐位直通；
 * 透明度在<b>合成之后</b>由合成层（{@code opacity} / {@code ColorModulator.a}）调控，绝不烤进画面。</p>
 *
 * <h2>跨实例平铺（§3.3 / §3.4）</h2>
 * 本类每帧遍历<b>全部</b>活跃实例（{@link CameraManager#instances()}，按启动顺序）并收集各自的 lane，
 * 平铺成一张总 lane 表：先启动实例的全部 lane 在前，后启动实例的全部 lane 在后。于是<b>实例整体按启动
 * 顺序叠放</b>——后启动实例的所有输出整体在上（后来者居上），而每个实例<b>内部</b>保持它自己的顺序
 * （轨道层级 → 轨道内 clip 顺序 → 层内 {@code z_index}）；不做跨实例的逐 lane 混排。
 * 实例之间不共享 lane 序号：总表序号从 0 连续递增，某实例本帧无 lane（纯音频 / 事件 / overlay）时不占位。
 *
 * <h2>合成参数的来源</h2>
 * 每 lane 的 {@code opacity} / {@code dest} / {@code source} 取自该 lane 所属 clip 的关键帧，
 * <b>lane 遮罩</b>（{@code mask_shape} / {@code mask_x} / {@code mask_y} / {@code mask_width} /
 * {@code mask_height} / {@code mask_radius}）同样在该 clip 本地时间处由 {@link LaneMask} 采样；
 * 每槽复用一个 {@link LaneMask} 实例，无逐帧分配。链输出注册的主画面 lane（无 CAMERA clip）显式
 * 复位 full，避免槽位跨帧复用时残留上一帧的遮罩。
 * <p>上述参数均在<b>该 clip 的本地时间</b>处插值（{@link CameraLane#clipLocalTime()}）——与相机六参数用同一个插值器，
 * 所以 hold（片段末尾复制延长）、loop / pingpong 的时间语义与相机完全一致。段内进度为<b>匀速线性</b>
 * （0.3.6 起运行时统一线性：缓动由编辑器烘焙成显式关键帧，运行时不求值）。缺省 = {@code 1} / 全屏 / 全幅
 * （字段与校验见 docs/SCRIPT_FORMAT.md §4「合成参数」）。矩形按分量整体插值（同 {@code position} 的复合值口径）。
 *
 * <h2>内容档 = 与主画面一致（{@link LaneRenderer.LaneContent#FULL}）</h2>
 * 单条全屏 lane（opacity=1、dest 全屏）会盖住主画面，且叠化要求两条 lane 观感一致，所以 lane 必须与
 * 主画面同内容：实体 / 粒子 / 天空 / 天气全开。{@code WORLD_ONLY} 只画地形——全屏 lane 下玩家会看不到
 * 生物 / 掉落物 / 天空 / 雨雪，叠化的两帧也不一致。
 *
 * <h2>画面 = 合成层输出（主相机替换链已退役）</h2>
 * 主相机替换链（{@code CameraMixin} 主相机分支 / {@code GameRendererMixin} getFov·roll /
 * {@code LevelRendererMixin} 视图中心）已退役（plans/0.3.6/parallel-playback.md §3.3）：原版主相机照常走
 * 玩家视角，玩家看到的画面 = 本类收集的 lane 经合成层铺屏的结果——「dest 全屏、opacity=1」的 lane 就是
 * 主画面特例，零 lane 时回落原版视角。视图中心与遮挡剔除都改为 <b>lane 驱动</b>（读正在渲染的 lane
 * 自己的相机）。
 *
 * 主画面的相机来源 = <b>覆盖链输出</b>（本帧相机）：链上本帧有 Modifier 生效、或链胜出层是直控类层
 * （飞行 / 编辑器直控 / 外部接管——该态下相机轨道跳过写入，本帧没有脚本 lane）时，主画面那份取链输出
 * （有脚本 lane 时换进最上层 lane，没有脚本 lane 时由链输出注册一条全屏 lane，见
 * {@link #registerChainLane}）。脚本导演胜出且无 Modifier 时链输出与主相机 lane 自身快照逐位同值，故不替换
 * ——纯脚本播放路径逐帧不变。次级 lane 保持各自片段的原始相机（覆盖链定稿 §14③）。
 *
 * <h2>lane 来源与相机 id</h2>
 * 本类是 lane 的<b>唯一来源</b>（代码级调试驱动已删，四象限演示改为脚本 {@code cinematics/release/quadrant.json}）：
 * 每帧先清空 lane，再按脚本播放实例注册。每条 lane 带相机 id
 * {@code <scriptId>_cam<相机轨序号>}（序号见 {@link LaneFrame#cameraTrackIndex()}）——调试捕获
 * （{@link LaneDebugCapture}）按它命名产出文件；同一相机轨本帧产出的多条 lane（重叠窗口下
 * {@code captureLowerLanes}）共用同一 id，与 lane 级调色的定位口径一致。
 *
 * <h2>调试捕获（{@code ICINEMATICS_CAPTURE}）</h2>
 * 本类只把相机 id 与 {@code dest} 传下去：每相机 raw 读回在 {@code LaneRenderer.renderLane} 内，
 * 每相机 composited 读回在本类 {@link #compose} 里（该相机贴到主画面之后、按 {@code dest} 裁剪）。
 * 开关关闭时两处都第一行即返回——不读回、不分配、不切 GL 状态，零差异。
 *
 * <h2>默认零差异</h2>
 * 无脚本播放时：{@link LaneRenderer#clear()} 因无活跃 lane 立即返回，本类返回 {@code false}，
 * {@link LaneRenderer#render} 第一行即返回——不渲染、不分配、不切换状态。
 */
public final class ScriptLaneDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** lane 内容档：与主画面一致（理由见类注释）。 */
    private static final LaneRenderer.LaneContent CONTENT = LaneRenderer.LaneContent.FULL;

    /** 主画面 lane 的调试捕获相机 id（链输出注册的主画面那份，见 {@link #registerChainLane}）。 */
    private static final String CHAIN_CAPTURE_ID = "chain";

    /** 本帧各 lane 的合成参数（按 lane 序号槽位复用，避免每帧分配）。 */
    private static final List<Slot> SLOTS = new ArrayList<>();

    private ScriptLaneDriver() {
    }

    /**
     * 注册本帧的画面 lane（渲染线程，{@code LaneRenderer.render} 之前调用一次）：
     * 脚本播放器本帧的活跃画面 lane；本帧没有脚本 lane 而链胜出层是直控类层时，由链输出注册主画面那条
     * （见 {@link #registerChainLane}）。
     *
     * @return 本帧是否有 lane
     */
    public static boolean tick(Minecraft mc) {
        LaneRenderer renderer = LaneRenderer.INSTANCE;
        // 本帧注册前的活跃度（= 上一帧的注册结果）：注册完与之比对，跳变时强刷可见集合（见 onLaneActivityChanged）
        boolean lanesWereActive = renderer.hasActiveLanes();
        // lane 的唯一来源：先清空（本帧没有脚本 lane 时不残留上一帧的 lane）
        renderer.clear();
        // 无脚本播放 → 一定没有 lane（不进收集路径：零分配、零差异）
        if (mc.level == null || mc.player == null || !CameraManager.INSTANCE.isActive()) {
            onLaneActivityChanged(mc, lanesWereActive, false, renderer);
            return false;
        }

        // 跨实例平铺（§3.3/§3.4）：实例按启动顺序（先启动在前），实例内部保持 collectCameraLanes 的顺序
        //（轨道层级 → 轨道内 clip 顺序 → z_index）。总表序号 = 合成顺序 = 叠放顺序：先启动的实例整体在下、
        // 后启动的整体在上（后来者居上），实例内部顺序不变。
        int index = 0;
        LaneFrame mainFrame = null;
        for (PlaybackInstance instance : CameraManager.INSTANCE.instances()) {
            ScriptPlayer player = instance.player();
            if (!player.isPlaying()) {
                continue;
            }
            List<LaneFrame> lanes = player.collectCameraLanes();
            for (int i = 0; i < lanes.size(); i++) {
                LaneFrame frame = lanes.get(i);
                CameraLane lane = frame.lane();
                Slot slot = slot(index);
                resolve(lane, slot);
                // 相机 id（调试捕获用文件名口径）：<scriptId>_cam<相机轨序号>。同一相机轨本帧的多条 lane
                // 共用同一 id（与 lane 级调色的定位口径一致）；文件名安全化在 LaneDebugCapture 内做。
                slot.captureId = instance.scriptId() + "_cam" + frame.cameraTrackIndex();
                renderer.setLane(index, lane.state(), CONTENT, frame.laneAdjust(), slot.captureId);
                mainFrame = frame;   // 总表最上层 lane = 主相机那份（主画面特例）
                index++;
            }
        }
        if (index == 0) {
            // 直控类层胜出（飞行 / 编辑器直控）：该态下相机轨道跳过写入 → 本帧没有脚本 lane，但画面
            // 仍必须跟链输出——按「主画面 = dest 全屏、opacity=1 的全屏 lane 特例」由链输出注册一条。
            if (registerChainLane(renderer)) {
                onLaneActivityChanged(mc, lanesWereActive, true, renderer);
                return true;
            }
            onLaneActivityChanged(mc, lanesWereActive, false, renderer);
            return false;
        }
        applyModifiers(renderer, index, mainFrame);
        renderer.setSink(ScriptLaneDriver::compose);
        onLaneActivityChanged(mc, lanesWereActive, true, renderer);
        return true;
    }

    /**
     * 活跃度跳变（本帧注册结果 vs 上一帧）的恢复动作：强制一次 {@code LevelRenderer.needsUpdate()}。
     *
     * <p><b>为什么必须有</b>：可见集合（共享门闩 + BFS）与网格中心（{@code LevelRendererMixin} 取本帧最上层
     * lane 的相机位置）都是<b>惰性重建</b>的——起播时中心从玩家跳到 lane、停播时跳回玩家，不强制重建就要
     * 等相机再移动 8 格才换口径（缺块 / 中心滞后）。起 / 停播各一次，停播恢复就靠这一条。</p>
     *
     * <p><b>为什么按「帧」而不是按 {@link LaneRenderer#setLane} / {@link LaneRenderer#clear} 的每次调用</b>：
     * 注册是「先清空、再逐条写入」，一帧内有 N 次槽位增删而活跃度只跳变一次；按每次调用判定会把每帧的
     * 「清空 → 重注册」当成一次停播 + 一次起播（每帧两次强刷 + 刷屏日志）。活跃度由 {@link LaneRenderer}
     * 独占，注册只在本方法里发生，故「注册前 / 注册后」两次读数即帧粒度的活跃度。</p>
     *
     * <p>线程：本方法只在渲染线程（{@code GameRenderer.render} 的 HEAD，世界渲染之前）被调用，
     * 故可直接读 {@code Minecraft.levelRenderer}。</p>
     */
    private static void onLaneActivityChanged(Minecraft mc, boolean lanesWereActive, boolean lanesAreActive,
                                              LaneRenderer renderer) {
        // 先清理已消失的 lane 视角：活跃度不变时也可能少了一条 lane（3 → 2）。
        ViewStateCapture.releaseStale(renderer.captureIds());
        if (lanesWereActive == lanesAreActive) {
            return;
        }
        if (mc.levelRenderer != null) {
            mc.levelRenderer.needsUpdate();
        }
        LOGGER.info(lanesAreActive
                ? "[lane] 有活跃 lane：per-lane 可见集合生效（逐 lane 独立候选集 / 可见集合 / 门闩，"
                        + "遮挡剪枝按各 pass 的相机判定）"
                : "[lane] 无活跃 lane：可见集合 / 网格中心回玩家相机口径，遮挡剔除交回原版判定");
    }

    /**
     * 主相机那份（总表最上层 lane）取覆盖链输出：链输出与主相机 lane 自身快照<b>不同源</b>的两种情形——
     * ① 链上有 Modifier 生效（链输出 = 脚本状态 + Modifier）；② 链胜出层是直控类层（飞行 / 编辑器直控 /
     * 外部接管）——此时相机轨道跳过写入，lane 快照不是本帧相机。把合成后的六参数写进该 lane——Modifier /
     * 直控只作用主相机，次级 lane 保持各自片段的原始相机（覆盖链定稿 §14③）。
     *
     * <p>其余情形（脚本导演胜出且无 Modifier）链输出与主相机 lane 自身快照逐位同值，故不替换（零改动）；
     * 链输出为 {@code null}（无活跃 Base）时同样不动。</p>
     *
     * @param laneCount 本帧总 lane 数（&gt; 0）：最上层 lane 序号 = {@code laneCount - 1}
     * @param mainFrame 最上层 lane 的帧数据（取它的 lane 级调色交给替换后的同一 lane）
     */
    private static void applyModifiers(LaneRenderer renderer, int laneCount, LaneFrame mainFrame) {
        if (!chainOutputIsFrameCamera()) return;
        CameraState main = CameraManager.INSTANCE.getCameraState();
        if (main == null) return;
        renderer.setLane(laneCount - 1, main, CONTENT, mainFrame.laneAdjust(), slot(laneCount - 1).captureId);
    }

    /**
     * 本帧无脚本 lane 时，主画面由链输出单独注册一条（全屏、不透明、无 lane 级调色）。
     *
     * <p>触发条件 = {@link #chainOutputIsFrameCamera()}（链胜出层非脚本导演）：该态下相机由飞控 /
     * 编辑器直写驱动、相机轨道跳过写入，lane 表为空——不注册就回落原版视角（画面不跟直控）。
     * 无活跃 Base / 无活跃实例（链输出 {@code null}）时不注册，仍回落原版视角。</p>
     *
     * @return 是否已注册主画面 lane
     */
    private static boolean registerChainLane(LaneRenderer renderer) {
        if (!chainOutputIsFrameCamera()) return false;
        CameraState main = CameraManager.INSTANCE.getCameraState();
        if (main == null) return false;
        Slot slot = slot(0);
        // 合成参数显式归位（槽位跨帧复用：上一帧同序号 lane 的 dest / opacity 不得残留）
        slot.opacity = 1.0F;
        slot.source = LaneCompositor.Rect.FULL;
        slot.dest = LaneCompositor.Rect.FULL;
        // 链输出 = 主画面，无 CAMERA clip：显式复位 full（槽位跨帧复用，防上一帧同序号 lane 的遮罩残留）。
        // sample(null, 0) 是数据层的复位约定：全部字段回默认 full，不抛异常、不分配。
        slot.mask.sample(null, 0.0F);
        slot.captureId = CHAIN_CAPTURE_ID;
        renderer.setLane(0, main, CONTENT, null, slot.captureId);
        renderer.setSink(ScriptLaneDriver::compose);
        return true;
    }

    /**
     * 主画面的相机是否必须取覆盖链输出（本帧相机）：链上有 Modifier 生效，或链胜出层是直控类层
     * （飞行 / 编辑器直控 / 外部接管）。其余情形链输出与主相机 lane 自身快照逐位同值（取与不取等价）。
     */
    private static boolean chainOutputIsFrameCamera() {
        CameraManager manager = CameraManager.INSTANCE;
        return manager.modifiersApplied() || manager.isDirectControlWinner();
    }

    /**
     * 一条 lane 的合成参数：从它所属 clip 的关键帧在其本地时间处插值（缺省 = 1 / 全屏 / 全幅），
     * 并在同一时间点采样 lane 遮罩（{@link LaneMask}；缺省 / 旧 clip = full）。
     * <p>
     * 段内进度为匀速线性（0.3.6 起运行时统一线性；缓动由编辑器烘焙成显式关键帧，运行时不求值）。
     */
    private static void resolve(CameraLane lane, Slot slot) {
        KeyframeInterpolator.InterpolationResult result =
                KeyframeInterpolator.computeInterpolation(lane.clipLocalTime(), lane.clip());
        if (result == null) {
            // 关键帧为空 → 全部取默认（全屏、不透明）
            slot.opacity = 1.0F;
            slot.source = LaneCompositor.Rect.FULL;
            slot.dest = LaneCompositor.Rect.FULL;
        } else {
            Keyframe from = result.from;
            Keyframe to = result.to;
            float s = result.adjustedT;
            slot.opacity = lerp(from.getFloat("opacity", 1.0F), to.getFloat("opacity", 1.0F), s);
            slot.source = interpolateRect(from, to, s, "source");
            slot.dest = interpolateRect(from, to, s, "dest");
        }
        // 遮罩：与合成参数同一 clip 本地时间采样（沿用 loop / morph 的既有时间口径）。
        // 无 mask 字段 / 空关键帧的旧 clip → full（LaneMask 内部复位，不扫描没消费的几何）。
        slot.mask.sample(lane.clip(), lane.clipLocalTime());
    }

    /** 关键帧矩形字段 {@code {x,y,w,h}} 的逐分量插值；字段缺省 = 全幅 / 全屏。 */
    private static LaneCompositor.Rect interpolateRect(Keyframe from, Keyframe to, float s, String key) {
        float x = lerp(component(from, key, "x", 0.0F), component(to, key, "x", 0.0F), s);
        float y = lerp(component(from, key, "y", 0.0F), component(to, key, "y", 0.0F), s);
        float w = lerp(component(from, key, "w", 1.0F), component(to, key, "w", 1.0F), s);
        float h = lerp(component(from, key, "h", 1.0F), component(to, key, "h", 1.0F), s);
        return x == 0.0F && y == 0.0F && w == 1.0F && h == 1.0F
                ? LaneCompositor.Rect.FULL
                : new LaneCompositor.Rect(x, y, w, h);
    }

    /** 关键帧 data 里的一个矩形分量；字段或分量缺省时取默认值。 */
    private static float component(Keyframe kf, String key, String component, float defaultValue) {
        Object value = kf.getObject(key);
        if (!(value instanceof Map<?, ?> rect)) {
            return defaultValue;
        }
        Object componentValue = rect.get(component);
        if (!(componentValue instanceof Number number)) {
            return defaultValue;
        }
        // 数据入口守卫（与 Keyframe.getFloat 同口径）：矩形分量直达合成层（quad 顶点 / UV），
        // 脚本 JSON 的 NaN / Infinity 字面量或溢出数字（1e999）不得直通 GL——NaN 坐标会画飞 / 不写 = 黑
        return MathUtil.sanitizeFloatLogged(number.floatValue(), defaultValue,
                "keyframe." + key + "." + component);
    }

    private static float lerp(float a, float b, float s) {
        return a + (b - a) * s;
    }

    /** 合成回调：把该 lane 当帧的画面按本帧参数贴到屏幕，随后按相机 id 读回该相机的合成结果（调试捕获）。 */
    private static void compose(int index, RenderTarget texture, boolean written) {
        Slot slot = SLOTS.get(index);
        // 失败 / 未写的 lane 由合成层守门跳过（判定与限频告警在 LaneCompositor.compose 内）
        boolean composited = LaneCompositor.compose(texture, slot.source, slot.dest, slot.opacity, written,
                slot.mask);
        // 调试捕获 b（每相机 composited）：该相机确实贴到主画面之后立刻读回主 framebuffer 的该相机区域
        // （保真 alpha，按 dest 裁剪）。ICINEMATICS_CAPTURE 门控；关闭 / 未上屏（守门跳过）时零差异。
        if (composited) {
            String captureId = slot.captureId != null ? slot.captureId : "lane" + index;
            LaneDebugCapture.onLaneComposited(captureId, slot.dest);
        }
    }

    /** 第 {@code index} 条 lane 的参数槽位（跨帧复用）。 */
    private static Slot slot(int index) {
        while (SLOTS.size() <= index) {
            SLOTS.add(new Slot());
        }
        return SLOTS.get(index);
    }

    /** 一条 lane 的合成参数（可变槽位）+ 调试捕获用的相机 id。 */
    private static final class Slot {
        private float opacity = 1.0F;
        private LaneCompositor.Rect source = LaneCompositor.Rect.FULL;
        private LaneCompositor.Rect dest = LaneCompositor.Rect.FULL;
        /** CAMERA lane 遮罩：每槽复用，每次 resolve / 链输出复位时重新采样（无逐帧分配）。 */
        private final LaneMask mask = new LaneMask();
        private String captureId;
    }
}
