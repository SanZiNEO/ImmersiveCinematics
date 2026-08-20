package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.server.level.ServerPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Set;

/**
 * 相机假人的连接：接收服务端发给假人的世界数据包，并转发给真实玩家客户端。
 * <ul>
 *   <li>声音/粒子/方块音效类包始终转发</li>
 *   <li>实体/方块更新类包仅在 {@code entitySyncEnabled} 时转发</li>
 *   <li>相机进入真实玩家范围后关闭实体转发，交还原版玩家跟踪</li>
 *   <li>不转发区块包（区块仍由 ChunkPreloadManager 手动发送）</li>
 *   <li>不转发玩家自身状态包（登录/生命/物品栏/命令树/keep-alive 等）</li>
 *   <li>记录已转发的实体生成 ID，供 ChunkPreloadManager 补发时去重</li>
 * </ul>
 */
public class CameraFakeConnection extends Connection {

    private static final SocketAddress FAKE_ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    private final ServerPlayer target;
    private final Set<Integer> syncedEntityIds = new HashSet<>();
    private boolean entitySyncEnabled = true;

    public CameraFakeConnection(ServerPlayer target) {
        super(PacketFlow.SERVERBOUND);
        this.target = target;
    }

    public boolean isEntitySynced(int entityId) {
        return syncedEntityIds.contains(entityId);
    }

    public void markEntitySynced(int entityId) {
        syncedEntityIds.add(entityId);
    }

    /** 取出并清空所有已同步实体 ID，用于退出 far 模式时向玩家发送移除包 */
    public IntList takeSyncedEntityIds() {
        IntList ids = new IntArrayList(syncedEntityIds);
        syncedEntityIds.clear();
        return ids;
    }

    public boolean isEntitySyncEnabled() {
        return entitySyncEnabled;
    }

    public void setEntitySyncEnabled(boolean entitySyncEnabled) {
        this.entitySyncEnabled = entitySyncEnabled;
    }

    @Override
    public void send(Packet<?> packet) {
        if (target != null && target.connection != null && shouldForward(packet)) {
            rememberSpawned(packet);
            forgetRemoved(packet);
            target.connection.send(packet);
        }
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        if (target != null && target.connection != null && shouldForward(packet)) {
            rememberSpawned(packet);
            forgetRemoved(packet);
            target.connection.send(packet, listener);
        }
    }

    private void rememberSpawned(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket add) {
            syncedEntityIds.add(add.getId());
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket orb) {
            syncedEntityIds.add(orb.getId());
        }
    }

    private void forgetRemoved(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove) {
            remove.getEntityIds().forEach(syncedEntityIds::remove);
        }
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return FAKE_ADDRESS;
    }

    @Override
    public void disconnect(Component reason) {
        // 假人退出由 PlayerList.remove 直接处理，不需要真实断线流程
    }

    private boolean shouldForward(Packet<?> packet) {
        if (isSoundOrParticle(packet)) return true;
        return entitySyncEnabled && isWorldPacket(packet);
    }

    private boolean isSoundOrParticle(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundStopSoundPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundLevelEventPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundExplodePacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
    }

    private boolean isWorldPacket(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundAnimatePacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundBossEventPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundDamageEventPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundEntityEventPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
    }
}
