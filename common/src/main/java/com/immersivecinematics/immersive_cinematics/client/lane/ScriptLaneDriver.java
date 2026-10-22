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
 * 脚本 lane 驱动：把脚本播放器本帧的活跃画面 lane 注册进 {@link LaneRenderer}，并按每条 lane 所属 clip 的
 * 关键帧合成参数（{@code opacity} / {@code dest} / {@code source} / lane 遮罩）逐 lane 上屏。
 * 链路：GameRenderer.render HEAD → CameraManager.onRenderFrame → ScriptPlayer.onRenderFrame
 * （填 lane 快照，按片段本地时间采样各 lane 的调色）→ 本类 tick（注册 lane + setSink）
 * → LaneRenderer.render（GameRenderer.renderLevel RETURN）→ 逐 lane 渲染 → lane 级调色
 * → Sink → {@link LaneCompositor#compose}。
 *
 * 叠放顺序 = 合成顺序 = lane 序号递增：先注册的在下、后注册的在上。本类每帧按启动顺序平铺全部活跃实例
 * 的 lane（实例整体按启动顺序叠放），实例内部保持自己的顺序（轨道层级 → 轨道内 clip 顺序 → z_index）。
 * lane 级调色只传数据、只承载 RGB（透明度由合成层调控）；内容档 = {@code FULL}。
 * 主画面 = 覆盖链输出（链上有 Modifier 生效或链胜出层为直控类层时替换最上层 lane / 单独注册一条）。
 * 无脚本播放时零差异：不渲染、不分配、不切换状态。
 */
public final class ScriptLaneDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** lane 内容档：{@code FULL} = 与主画面同内容（实体 / 粒子 / 天空 / 天气）。 */
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

        // 跨实例平铺：实例按启动顺序，总表序号 = 叠放顺序（见类 javadoc）
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
                // 相机 id（产出文件名口径）：<scriptId>_cam<相机轨序号>；同一相机轨本帧的多条 lane 共用同一 id
                slot.captureId = instance.scriptId() + "_cam" + frame.cameraTrackIndex();
                renderer.setLane(index, lane.state(), CONTENT, frame.laneAdjust(), slot.captureId);
                mainFrame = frame;   // 总表最上层 lane = 主相机那份（主画面特例）
                index++;
            }
        }
        if (index == 0) {
            // 直控类层胜出：相机轨道跳过写入 → 本帧无脚本 lane，按「dest 全屏、opacity=1」的主画面特例
            // 由链输出注册一条，画面仍跟链输出。
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
     * 活跃度跳变（本帧注册结果 vs 上一帧）时强制一次 {@code LevelRenderer.needsUpdate()}：
     * 可见集合 / 网格中心惰性重建，不强刷要等相机再移动 8 格才换口径（缺块 / 中心滞后）；
     * 按帧判一次，因为一帧内「清空 → 逐条注册」会被按次判定误读成停播 + 起播（两次强刷 + 刷屏）。
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
     * 主画面那份（总表最上层 lane）改取覆盖链输出：链上有 Modifier 生效、或链胜出层为直控类层时，
     * 链输出与 lane 自身快照不同源；Modifier / 直控只作用主画面，次级 lane 保持各自片段的原始相机。
     * 其余情形（脚本导演胜出且无 Modifier）链输出与 lane 快照逐位同值，不替换；链输出 {@code null} 时不动。
     *
     * @param laneCount 本帧总 lane 数（&gt; 0）：最上层 lane 序号 = {@code laneCount - 1}
     * @param mainFrame 最上层 lane 的帧数据（沿用它的 lane 级调色）
     */
    private static void applyModifiers(LaneRenderer renderer, int laneCount, LaneFrame mainFrame) {
        if (!chainOutputIsFrameCamera()) return;
        CameraState main = CameraManager.INSTANCE.getCameraState();
        if (main == null) return;
        renderer.setLane(laneCount - 1, main, CONTENT, mainFrame.laneAdjust(), slot(laneCount - 1).captureId);
    }

    /**
     * 本帧无脚本 lane 时，主画面由链输出单独注册一条（全屏、不透明、无 lane 级调色）。
     * 触发条件见 {@link #chainOutputIsFrameCamera()}；链输出 {@code null} 时不注册，回落原版视角。
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
        // 链输出无 CAMERA clip：显式复位 full（槽位跨帧复用，防上一帧同序号 lane 的遮罩残留）
        slot.mask.sample(null, 0.0F);
        slot.captureId = CHAIN_CAPTURE_ID;
        renderer.setLane(0, main, CONTENT, null, slot.captureId);
        renderer.setSink(ScriptLaneDriver::compose);
        return true;
    }

    /**
     * 主画面的相机是否必须取覆盖链输出（本帧相机）：链上有 Modifier 生效，或链胜出层是直控类层
     * （飞行 / 编辑器直控 / 外部接管）。其余情形取与不取等价（链输出与 lane 快照逐位同值）。
     */
    private static boolean chainOutputIsFrameCamera() {
        CameraManager manager = CameraManager.INSTANCE;
        return manager.modifiersApplied() || manager.isDirectControlWinner();
    }

    /**
     * 一条 lane 的合成参数：所属 clip 的关键帧在本地时间处插值（缺省 = 1 / 全屏 / 全幅），
     * 同一时间点采样 lane 遮罩（{@link LaneMask}；无 mask 字段的 clip = full）。
     * 段内进度匀速线性（运行时线性，缓动由编辑器烘焙成显式关键帧，运行时不求值）。
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
        slot.mask.sample(lane.clip(), lane.clipLocalTime());
    }

    /** 关键帧矩形字段（{@code x} / {@code y} / {@code w} / {@code h}）的逐分量插值；字段缺省 = 全幅 / 全屏。 */
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
        // 调试捕获：贴屏后立刻读回该相机的区域（门控与产出见 LaneDebugCapture.onLaneComposited）
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
        /** CAMERA lane 遮罩：每槽复用，无逐帧分配。 */
        private final LaneMask mask = new LaneMask();
        private String captureId;
    }
}
