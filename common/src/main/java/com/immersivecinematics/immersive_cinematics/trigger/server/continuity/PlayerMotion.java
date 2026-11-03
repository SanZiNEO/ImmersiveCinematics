package com.immersivecinematics.immersive_cinematics.trigger.server.continuity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 玩家运动采样与「代次」（generation）：连续段的分段标记。
 *
 * <p>段扫掠只在<b>同一代次</b>的相邻采样点之间进行。以下情形会让代次自增，
 * 使跳变前后的两个采样点不参与同一段判定（否则传送 / 换维度会在两点之间
 * 划出一条现实中不存在的长线段，造成误报）：
 * <ul>
 *   <li>维度切换：{@link #reset(UUID)} 由 {@code ServerEventHandler#onChangeDimension} 调用；</li>
 *   <li>载具变化：每 tick 比对 {@code player.getVehicle()} 的 UUID；</li>
 *   <li>每 tick 位移超过 {@link #MAX_TICK_DISPLACEMENT} 格（传送兜底，无跨平台事件）；</li>
 *   <li>玩家退出 / 重新加入：状态被移除后重新建，代次从 0 起算。</li>
 * </ul>
 *
 * <p><b>近似口径</b>：短距离传送（单 tick 位移 ≤ 16 格）无法与正常移动区分，
 * 按连续移动处理——这是保守近似，写入契约。位移阈值只兜底大跳变。
 *
 * <p>线程契约：只在服务端主线程使用（无同步）。
 */
public final class PlayerMotion {

    public static final PlayerMotion INSTANCE = new PlayerMotion();

    /** 单 tick 位移超过该格数即视为跳变（传送兜底）：该段不参与扫掠。 */
    public static final double MAX_TICK_DISPLACEMENT = 16.0;

    private static final double MAX_TICK_DISPLACEMENT_SQ = MAX_TICK_DISPLACEMENT * MAX_TICK_DISPLACEMENT;

    private final Map<UUID, State> states = new HashMap<>();

    private PlayerMotion() {}

    /**
     * 每 tick 调用一次（{@code TriggerEngine#onServerTick} 开头）：推进所有在线玩家的
     * 位移 / 载具比对，并清理已不在线的玩家状态。
     *
     * <p>首次见到的玩家只建立基线（不增代次）；此后每 tick 更新基线位置与载具 UUID。
     */
    public void tick(MinecraftServer server) {
        var playerList = server.getPlayerList();
        states.keySet().removeIf(uuid -> playerList.getPlayer(uuid) == null);
        for (ServerPlayer player : playerList.getPlayers()) {
            State state = states.get(player.getUUID());
            if (state == null) {
                states.put(player.getUUID(), State.baseline(player));
            } else {
                state.advance(player);
            }
        }
    }

    /** 玩家当前代次；无状态（未上线 / 已清理）→ 0。 */
    public int generation(UUID player) {
        State state = states.get(player);
        return state != null ? state.generation : 0;
    }

    /** 抓取当前运动快照；纯读取，不改变基线（基线由 {@link #tick} 推进）。 */
    public MotionSample capture(ServerPlayer player, int tick) {
        return new MotionSample(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), tick);
    }

    /** 标记一次跳变（维度切换）：代次自增，下一次采样与之前的采样不再属于同一段。 */
    public void reset(UUID player) {
        State state = states.get(player);
        if (state == null) {
            // 尚未建立基线（刚上线就切维度）：先占位，下一次 tick 只建基线不再额外自增。
            state = new State();
            states.put(player, state);
        }
        state.generation++;
    }

    /** 移除玩家状态（退出）：代次随之丢失，重新上线从 0 起算。 */
    public void remove(UUID player) {
        states.remove(player);
    }

    /** 清空全部状态（服务端停止 / 换档 / 触发器重载）。 */
    public void clearAll() {
        states.clear();
    }

    /** 单个玩家的运动基线：上一 tick 的位置、载具 UUID 与代次。 */
    private static final class State {
        private double x;
        private double y;
        private double z;
        private UUID vehicle;
        private int generation;
        /** 是否已有位置基线；false = 下一 tick 只建基线（不判位移）。 */
        private boolean baselined;

        static State baseline(ServerPlayer player) {
            State state = new State();
            state.rebaseline(player);
            return state;
        }

        void advance(ServerPlayer player) {
            if (!baselined) {
                rebaseline(player);
                return;
            }
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            Entity currentVehicle = player.getVehicle();
            UUID currentVehicleId = currentVehicle != null ? currentVehicle.getUUID() : null;
            if (dx * dx + dy * dy + dz * dz > MAX_TICK_DISPLACEMENT_SQ
                    || !Objects.equals(vehicle, currentVehicleId)) {
                generation++;
            }
            rebaseline(player);
        }

        private void rebaseline(ServerPlayer player) {
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            Entity currentVehicle = player.getVehicle();
            vehicle = currentVehicle != null ? currentVehicle.getUUID() : null;
            baselined = true;
        }
    }
}
