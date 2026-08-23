package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 纯计算用的“虚拟玩家”引用，仅用于让 {@code NaturalSpawner} 能算相机位置到刷怪点的距离。
 * <p>
 * 它不是世界里的实体：
 * <ul>
 *   <li>不会被 addFreshEntity / addEntity</li>
 *   <li>不会进入 ServerLevel.players / PlayerList / ChunkMap</li>
 *   <li>没有 connection，不接收/发送任何包</li>
 *   <li>只提供坐标和 distanceToSqr，用完即弃</li>
 * </ul>
 */
public class CameraAnchorVirtualPlayer extends Player {

    public CameraAnchorVirtualPlayer(ServerLevel level, double x, double y, double z) {
        super(level, BlockPos.containing(x, y, z), 0.0F, new GameProfile(UUID.randomUUID(), "__ic_spawn_ref__"));
        this.moveTo(x, y, z, 0.0F, 0.0F);
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public void tick() {
        // 不参与任何 tick
    }
}
