package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.mixin.PlayerListAccessor;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraFakeConnection;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraFakePlayer;
import com.immersivecinematics.immersive_cinematics.trigger.server.FakePlayerBootstrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;

/**
 * Forge 假人引导：不能走完整的 {@code PlayerList.placeNewPlayer()}。
 * <p>
 * Forge 的 placeNewPlayer 内部会执行 {@code NetworkHooks.sendMCRegistryPackets()}，
 * 它要求连接有真实 Netty channel；我们的 {@link CameraFakeConnection} 是纯假连接，
 * 没有 channel，直接走会抛异常并中断 far 模式初始化。
 * <p>
 * 所以这里手动拼一个“最小可用假玩家”：
 * <ol>
 *   <li>手动创建 {@link ServerGamePacketListenerImpl} 并挂到假人 connection；</li>
 *   <li>把假人加进 {@link ServerLevel}（参与区块加载/刷怪/实体跟踪）；</li>
 *   <li>只注册到 PlayerList 的 playersByUUID（方便 remove 清理），
 *       不进入 players 广播列表（避免 Tab/延迟更新泄漏给真实客户端）。</li>
 * </ol>
 * 以后如果 Forge 网络钩子变化，只需要改这里，公共逻辑不用动。
 */
public final class ForgeFakePlayerBootstrapper implements FakePlayerBootstrapper {

    @Override
    public void bootstrap(CameraFakePlayer fake, CameraFakeConnection connection, ServerLevel level) {
        MinecraftServer server = level.getServer();
        // 构造函数内部会把 fake.connection 设为这个 listener
        new ServerGamePacketListenerImpl(server, connection, fake);

        // 加入世界：ChunkMap 会把它当作玩家跟踪，开始发区块/实体/声音给假连接
        level.addNewPlayer(fake);

        // 只进 playersByUUID，不进 players 广播列表
        PlayerList playerList = server.getPlayerList();
        ((PlayerListAccessor) playerList).immersivecinematics_getPlayersByUUID().put(fake.getUUID(), fake);
    }
}
