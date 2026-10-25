package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SCameraViewPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 跨维度视图上报器（客户端 tick 驱动）：把 {@link LaneRenderer#activeViews()} 里的目标维度 lane
 * 转成 C2S 视图包，让服务端在相机附近流式加载目标维度的区块与实体。
 *
 * 口径：
 * <ul>
 *   <li>只上报 {@code dimension} 非空且不等于玩家当前维度的 lane（当前维度走原版连接）；</li>
 *   <li>首次出现立即发 {@code active=true}，之后每 5 tick 或位移 ≥1 格补发（服务端据此移动 ticket）；</li>
 *   <li>lane 消失时发一次 {@code active=false} 并停止跟踪；</li>
 *   <li>退出世界 / 无世界时对全部跟踪项发 end（连接仍在时），随后清表。</li>
 * </ul>
 * 状态表只在客户端主线程读写；每 tick 复用同一个 HashMap，不按帧分配新表。
 */
public final class CameraViewReporter {

    /** 由客户端 tick 驱动的唯一实例。 */
    public static final CameraViewReporter INSTANCE = new CameraViewReporter();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraView");

    /** 位置 / 心跳上报间隔（tick）。 */
    private static final int REPORT_INTERVAL = 5;

    /** 已上报 START 的视图：viewId → 跟踪状态。 */
    private final Map<String, TrackedView> tracked = new HashMap<>();
    private int tickCounter;

    private CameraViewReporter() {}

    /** 断线 / 退出世界：只清状态，不发包（连接已不可用）。 */
    public void reset() {
        tracked.clear();
        tickCounter = 0;
    }

    /** 每客户端 tick 调用；{@code mc} 为客户端实例（读当前维度 / 视距 / lane 快照）。 */
    public void tick(Minecraft mc) {
        if (mc.level == null) {
            endAll(mc);
            return;
        }
        String currentDimension = DimensionViews.currentDimensionId(mc.level);
        int radius = mc.options.getEffectiveRenderDistance();
        List<LaneRenderer.LaneView> views = LaneRenderer.INSTANCE.activeViews();
        for (int i = 0; i < views.size(); i++) {
            LaneRenderer.LaneView view = views.get(i);
            String dimension = view.dimension();
            if (dimension == null || dimension.isEmpty() || dimension.equals(currentDimension)) {
                continue;
            }
            ResourceLocation dimensionId = ResourceLocation.tryParse(dimension);
            if (dimensionId == null) {
                ErrorLog.logRateLimited("CameraView", "dimension-id:" + dimension, 5000L,
                        "跨维度视图维度 id 非法（" + dimension + "）：该 lane 不上报服务端");
                continue;
            }
            TrackedView state = tracked.get(view.viewId());
            if (state == null) {
                state = new TrackedView(view.viewId(), dimensionId);
                tracked.put(view.viewId(), state);
                send(state, view.position(), true, radius);
            } else if (!state.dimension.equals(dimensionId)) {
                // viewId 含维度后缀，正常不会变；防御性重启（旧视图先 end）
                send(state, state.lastPos, false, 0);
                tracked.remove(view.viewId());
                continue;
            } else {
                boolean moved = state.lastPos.distanceToSqr(view.position()) >= 1.0;
                if (moved || tickCounter - state.lastSentTick >= REPORT_INTERVAL) {
                    send(state, view.position(), true, radius);
                }
            }
            state.seen = true;
        }
        // 本 tick 没出现的视图：发一次 end 后停止跟踪
        if (!tracked.isEmpty()) {
            Iterator<Map.Entry<String, TrackedView>> it = tracked.entrySet().iterator();
            while (it.hasNext()) {
                TrackedView state = it.next().getValue();
                if (state.seen) {
                    state.seen = false;
                } else {
                    send(state, state.lastPos, false, 0);
                    it.remove();
                }
            }
        }
        tickCounter++;
    }

    /** 退出世界：连接仍在时逐条 end，然后清表。 */
    private void endAll(Minecraft mc) {
        if (tracked.isEmpty()) {
            return;
        }
        if (mc.getConnection() != null) {
            for (TrackedView state : tracked.values()) {
                send(state, state.lastPos, false, 0);
            }
        }
        tracked.clear();
    }

    private void send(TrackedView state, Vec3 pos, boolean active, int radius) {
        state.lastPos = pos;
        state.lastSentTick = tickCounter;
        if (Config.debugLogging) {
            LOGGER.info("[camera-view] 上报 视图={} 维度={} 位置=({},{},{}) active={} 半径={}",
                    state.viewId, state.dimension, pos.x, pos.y, pos.z, active, radius);
        }
        NetworkGuard.sendToServer("C2SCameraView", () -> NetworkHandler.sendToServer(
                new C2SCameraViewPacket(state.viewId, state.dimension,
                        pos.x, pos.y, pos.z, active, radius)));
    }

    /** 单个视图的跟踪状态。 */
    private static final class TrackedView {
        final String viewId;
        final ResourceLocation dimension;
        Vec3 lastPos = Vec3.ZERO;
        int lastSentTick;
        boolean seen;

        TrackedView(String viewId, ResourceLocation dimension) {
            this.viewId = viewId;
            this.dimension = dimension;
        }
    }
}
