package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.script.CameraLane;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.KeyframeInterpolator;
import com.immersivecinematics.immersive_cinematics.script.LaneFrame;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 脚本 lane 驱动：把脚本播放器本帧的活跃画面 lane 注册进 {@link LaneRenderer}，并按每条 lane
 * 所属 clip 的关键帧合成参数（{@code opacity} / {@code dest} / {@code source}）逐 lane 上屏。
 *
 * <h2>链路</h2>
 * <pre>
 * GameRendererMixin（{@code GameRenderer.render} 的 HEAD，本帧世界渲染之前）
 *   → CameraManager.onRenderFrame → ScriptPlayer.onRenderFrame → 各 CameraTrackPlayer 填 lane 快照（CameraLane）
 *     + 各 ADJUST 轨道（scope=lane）填本帧的 lane 级调色参数
 *   → 本类 tick → LaneRenderer.setLane(i, 相机状态, 内容档, lane 级调色) + setSink
 * LaneRendererMixin（{@code GameRenderer.renderLevel} 的 RETURN）→ LaneRenderer.render
 *   → 逐 lane 渲染 → （lane 级调色 pass）→ Sink → LaneCompositor.compose（贴到屏幕）
 * </pre>
 * 合成是即时进行的（lane 共用一张离屏缓冲），所以<b>叠放顺序 = 调用顺序 = lane 序号递增</b>：
 * {@code collectCameraLanes()} 已按「轨道层级 → 轨道内 clip 顺序」排好，序号小的先贴、被后贴的盖住。
 *
 * <h2>lane 级调色（{@code scope=lane} 的 ADJUST 轨道）</h2>
 * 每份 {@link LaneFrame} 携带它所属 CAMERA 轨的 lane 级调色参数（由 {@code ScriptPlayer} 归集，
 * 同轨本帧的所有 lane 共用一份）；本类把它原样交给 {@link LaneRenderer#setLane}。真正跑 pass 的位置在
 * {@code LaneRenderer.renderLane}（lane 渲染完成、合成之前），本类只做数据传递。
 * 参数为 {@code null}（无 lane 级 ADJUST / 参数恒等）时该 lane 走原路径（lane FBO 直接进合成），零差异。
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
 * 在<b>该 clip 的本地时间</b>处插值（{@link CameraLane#clipLocalTime()}）——与相机六参数用同一个插值器，
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
 * {@code LevelRendererMixin} 视图中心 / {@code CinematicOcclusion} 整帧遮挡决策）已退役
 * （plans/0.3.6/parallel-playback.md §3.3）：原版主相机照常走玩家视角，玩家看到的画面 = 本类收集的
 * lane 经合成层铺屏的结果——「dest 全屏、opacity=1」的 lane 就是主画面特例，零 lane 时回落原版视角。
 * 视图中心与遮挡剔除的整帧决策改为 <b>lane 驱动</b>（读正在渲染的 lane 自己的相机）。
 *
 * <h2>与调试驱动共存</h2>
 * 脚本 lane 与 {@link LaneDebugDriver}（{@code -Dicinematics.quadrant}）互斥：本类每帧先清空 lane，
 * 本帧有脚本 lane 时返回 {@code true}，调用方（{@link com.immersivecinematics.immersive_cinematics.mixin.GameRendererMixin}）
 * 据此跳过调试驱动——<b>脚本 lane 存在时调试驱动不写 lane、不装合成回调</b>。
 * 无脚本 lane 时本类返回 {@code false}，调试驱动照常工作（游戏内冒烟手段保留）。
 *
 * <h2>默认零差异</h2>
 * 无脚本播放时：{@link LaneRenderer#clear()} 因无活跃 lane 立即返回，本类返回 {@code false}，
 * 调试驱动未开启时也直接返回，{@link LaneRenderer#render} 第一行即返回——不渲染、不分配、不切换状态。
 */
public final class ScriptLaneDriver {

    /** lane 内容档：与主画面一致（理由见类注释）。 */
    private static final LaneRenderer.LaneContent CONTENT = LaneRenderer.LaneContent.FULL;

    /** 本帧各 lane 的合成参数（按 lane 序号槽位复用，避免每帧分配）。 */
    private static final List<Slot> SLOTS = new ArrayList<>();

    private ScriptLaneDriver() {
    }

    /**
     * 注册本帧的脚本 lane（渲染线程，{@code LaneRenderer.render} 之前调用一次）。
     *
     * @return 本帧是否有脚本 lane（{@code true} = 调试驱动本帧必须让位）
     */
    public static boolean tick(Minecraft mc) {
        LaneRenderer renderer = LaneRenderer.INSTANCE;
        // 脚本 lane 是 lane 的唯一来源：先清空（调试驱动让位时不残留它上一帧的 lane）
        renderer.clear();
        // 无脚本播放 → 一定没有 lane（不进收集路径：零分配、零差异）
        if (mc.level == null || mc.player == null || !CameraManager.INSTANCE.isActive()) {
            return false;
        }

        // 跨实例平铺（§3.3/§3.4）：实例按启动顺序（先启动在前），实例内部保持 collectCameraLanes 的顺序
        //（轨道层级 → 轨道内 clip 顺序 → z_index）。总表序号 = 合成顺序 = 叠放顺序：先启动的实例整体在下、
        // 后启动的整体在上（后来者居上），实例内部顺序不变。
        int index = 0;
        for (PlaybackInstance instance : CameraManager.INSTANCE.instances()) {
            ScriptPlayer player = instance.player();
            if (!player.isPlaying()) {
                continue;
            }
            List<LaneFrame> lanes = player.collectCameraLanes();
            for (int i = 0; i < lanes.size(); i++) {
                LaneFrame frame = lanes.get(i);
                CameraLane lane = frame.lane();
                resolve(lane, slot(index));
                renderer.setLane(index, lane.state(), CONTENT, frame.laneAdjust());
                index++;
            }
        }
        if (index == 0) {
            return false;
        }
        renderer.setSink(ScriptLaneDriver::compose);
        return true;
    }

    /**
     * 一条 lane 的合成参数：从它所属 clip 的关键帧在其本地时间处插值（缺省 = 1 / 全屏 / 全幅）。
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
            return;
        }
        Keyframe from = result.from;
        Keyframe to = result.to;
        float s = result.adjustedT;
        slot.opacity = lerp(from.getFloat("opacity", 1.0F), to.getFloat("opacity", 1.0F), s);
        slot.source = interpolateRect(from, to, s, "source");
        slot.dest = interpolateRect(from, to, s, "dest");
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
        return componentValue instanceof Number number ? number.floatValue() : defaultValue;
    }

    private static float lerp(float a, float b, float s) {
        return a + (b - a) * s;
    }

    /** 合成回调：把该 lane 当帧的画面按本帧参数贴到屏幕。 */
    private static void compose(int index, RenderTarget texture) {
        Slot slot = SLOTS.get(index);
        LaneCompositor.compose(texture, slot.source, slot.dest, slot.opacity);
    }

    /** 第 {@code index} 条 lane 的参数槽位（跨帧复用）。 */
    private static Slot slot(int index) {
        while (SLOTS.size() <= index) {
            SLOTS.add(new Slot());
        }
        return SLOTS.get(index);
    }

    /** 一条 lane 的合成参数（可变槽位）。 */
    private static final class Slot {
        private float opacity = 1.0F;
        private LaneCompositor.Rect source = LaneCompositor.Rect.FULL;
        private LaneCompositor.Rect dest = LaneCompositor.Rect.FULL;
    }
}
