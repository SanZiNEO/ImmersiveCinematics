package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 相机视图的实体流：按 {@code (玩家 UUID, 目标维度)} 共享的一条实体通道。
 *
 * <p>实体流不属于某个视图，而是属于「玩家 + 目标维度」：同一玩家在同一目标维度只有一份客户端
 * {@code ClientLevel}，多条 lane 只是不同订阅者。实体只做一次初始配对（{@code sendPairingData}），
 * 后续更新由 {@code ChunkMap$TrackedEntity.broadcast} 镜像统一发给该玩家，不按 lane 重复。
 *
 * <p>引用计数口径：{@link Interest#views} 记录还需要该实体的视图 id；最后一个订阅者释放时才发
 * {@code ClientboundRemoveEntitiesPacket}。类本身只做纯数据维护，不碰网络 / 世界状态，便于无头探针覆盖。
 *
 * <p>线程：只在服务端主线程读写，与 {@link CameraViewManager} 相同。
 */
final class CameraEntityStream {

    final UUID playerUuid;
    final ServerLevel level;
    final ResourceLocation dimension;
    /** 视图 id → 视图身份（同一玩家同一维度的多条 lane 各占一项）。 */
    final Map<String, CameraViewManager.ViewKey> subscribers = new HashMap<>();
    /** 实体 id → 订阅 / 配对状态。 */
    final Map<Integer, Interest> interests = new HashMap<>();

    CameraEntityStream(UUID playerUuid, ServerLevel level, ResourceLocation dimension) {
        this.playerUuid = playerUuid;
        this.level = level;
        this.dimension = dimension;
    }

    /** 单个实体的订阅记录：哪些视图还需要它，是否已完成初始配对。 */
    static final class Interest {
        final Entity entity;
        final Set<String> views = new HashSet<>();
        boolean paired;

        Interest(Entity entity) {
            this.entity = entity;
        }
    }

    /** 取 / 建实体订阅记录（不改变配对状态）。 */
    Interest interest(int id, Entity entity) {
        Interest interest = interests.get(id);
        if (interest == null) {
            interest = new Interest(entity);
            interests.put(id, interest);
        }
        return interest;
    }

    /** 只读查询实体订阅记录，不存在返回 {@code null}。 */
    Interest peek(int id) {
        return interests.get(id);
    }

    /** 订阅某实体；视图同时登记为该流的订阅者。返回该视图是否为新订阅。 */
    boolean retain(int id, String viewId) {
        Interest interest = interests.get(id);
        if (interest == null || !interest.views.add(viewId)) {
            return false;
        }
        if (!subscribers.containsKey(viewId)) {
            subscribers.put(viewId, new CameraViewManager.ViewKey(playerUuid, viewId));
        }
        return true;
    }

    /** 取消一个视图的实体订阅；views 空了移除记录并返回 {@code true}（调用方补发 remove）。 */
    boolean release(int id, String viewId) {
        Interest interest = interests.get(id);
        if (interest == null) {
            return false;
        }
        interest.views.remove(viewId);
        boolean last = interest.views.isEmpty();
        if (last) {
            interests.remove(id);
        }
        if (!hasInterest(viewId)) {
            subscribers.remove(viewId);
        }
        return last;
    }

    boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    /** 原版实体被移除：删记录，并清掉不再持有任何实体订阅的视图。 */
    void forget(int id) {
        if (interests.remove(id) == null) {
            return;
        }
        subscribers.keySet().removeIf(viewId -> !hasInterest(viewId));
    }

    /** 取消该视图的全部实体订阅，返回因失去最后一个订阅者而需要 remove 的实体 id。 */
    List<Integer> releaseAllViews(String viewId) {
        subscribers.remove(viewId);
        List<Integer> emptied = new ArrayList<>();
        for (Iterator<Map.Entry<Integer, Interest>> it = interests.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Interest> entry = it.next();
            Interest interest = entry.getValue();
            interest.views.remove(viewId);
            if (interest.views.isEmpty()) {
                emptied.add(entry.getKey());
                it.remove();
            }
        }
        return emptied;
    }

    boolean isEmpty() {
        return subscribers.isEmpty() && interests.isEmpty();
    }

    /** 该视图在该流里是否仍持有至少一个实体。 */
    private boolean hasInterest(String viewId) {
        for (Interest interest : interests.values()) {
            if (interest.views.contains(viewId)) {
                return true;
            }
        }
        return false;
    }
}
