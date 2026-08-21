package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 访问 {@link PlayerList} 内部真正的可变 players 列表。
 * Forge 的 {@code getPlayers()} 返回不可变视图，不能直接 remove。
 */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {

    @Accessor("players")
    List<ServerPlayer> immersivecinematics_getPlayersList();

    @Accessor("playersByUUID")
    Map<UUID, ServerPlayer> immersivecinematics_getPlayersByUUID();
}
