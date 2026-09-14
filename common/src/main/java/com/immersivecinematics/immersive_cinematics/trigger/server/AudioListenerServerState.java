package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端保存每个玩家的“音频听者位置”（相机听者模式）。
 * <p>
 * 原版 {@code PlayerList.broadcast} 只按玩家实体坐标筛选声音包，相机听者离玩家很远时
 * 生物音根本不会下发。客户端通过 {@code C2SAudioListenerPacket} 上报相机位置，
 * 混合注入 {@code PlayerList} 后用这里的值作为第二判定点。
 */
public final class AudioListenerServerState {

    private static final Map<UUID, Vec3> LISTENER_POSITIONS = new ConcurrentHashMap<>();

    private AudioListenerServerState() {}

    public static void set(UUID playerId, @Nullable Vec3 position) {
        if (position == null) {
            LISTENER_POSITIONS.remove(playerId);
        } else {
            LISTENER_POSITIONS.put(playerId, position);
        }
    }

    @Nullable
    public static Vec3 get(UUID playerId) {
        return LISTENER_POSITIONS.get(playerId);
    }

    public static void clear(UUID playerId) {
        LISTENER_POSITIONS.remove(playerId);
    }
}
