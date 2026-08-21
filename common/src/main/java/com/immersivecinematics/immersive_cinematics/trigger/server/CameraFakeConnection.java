package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

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
    private final ServerLevel level;
    private final Set<Integer> syncedEntityIds = new HashSet<>();
    private boolean entitySyncEnabled = true;
    private boolean chunkSyncEnabled = true;
    private int forwardedEntityPackets = 0;
    private int forwardedSoundPackets = 0;
    private int blockedEntityPackets = 0;

    public CameraFakeConnection(ServerPlayer target, ServerLevel level) {
        super(PacketFlow.SERVERBOUND);
        this.target = target;
        this.level = level;
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

    public boolean isChunkSyncEnabled() {
        return chunkSyncEnabled;
    }

    public void setChunkSyncEnabled(boolean chunkSyncEnabled) {
        this.chunkSyncEnabled = chunkSyncEnabled;
    }

    /** 取出并重置包统计：[转发的实体/方块包, 转发的声音/粒子包, 被拦截的实体/方块包] */
    public int[] takeAndResetPacketStats() {
        int[] stats = new int[]{forwardedEntityPackets, forwardedSoundPackets, blockedEntityPackets};
        forwardedEntityPackets = 0;
        forwardedSoundPackets = 0;
        blockedEntityPackets = 0;
        return stats;
    }

    @Override
    public void send(Packet<?> packet) {
        if (target == null || target.connection == null) return;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle) {
            for (net.minecraft.network.protocol.Packet<?> sub : bundle.subPackets()) {
                send(sub);
            }
            return;
        }
        Packet<?> toSend = filterRemovePacket(packet);
        if (toSend == null) return;
        boolean sound = isSoundOrParticle(packet);
        boolean world = isWorldPacket(packet);
        boolean forward = shouldForward(packet);
        if (sound) {
            forwardedSoundPackets++;
        } else if (world) {
            if (forward) forwardedEntityPackets++;
            else blockedEntityPackets++;
        }
        if (forward) {
            rememberSpawned(toSend);
            forgetRemoved(toSend);
            target.connection.send(toSend);
        }
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        if (target == null || target.connection == null) return;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle) {
            for (net.minecraft.network.protocol.Packet<?> sub : bundle.subPackets()) {
                send(sub);
            }
            return;
        }
        Packet<?> toSend = filterRemovePacket(packet);
        if (toSend == null) return;
        boolean sound = isSoundOrParticle(packet);
        boolean world = isWorldPacket(packet);
        boolean forward = shouldForward(packet);
        if (sound) {
            forwardedSoundPackets++;
        } else if (world) {
            if (forward) forwardedEntityPackets++;
            else blockedEntityPackets++;
        }
        if (forward) {
            rememberSpawned(toSend);
            forgetRemoved(toSend);
            target.connection.send(toSend, listener);
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

    /** 移除包先剔除真实玩家/假人自己的 id；全被剔除则整包丢弃 */
    private Packet<?> filterRemovePacket(Packet<?> packet) {
        if (!(packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove)) {
            return packet;
        }
        IntList filtered = new IntArrayList();
        IntList ids = remove.getEntityIds();
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.getInt(i);
            // 真实玩家自己、假人自己、以及已经进入原版玩家跟踪范围的实体，都不由假人连接删除
            if (!isSelfOrFake(id) && !isManagedByVanilla(id)) {
                filtered.add(id);
            }
        }
        if (filtered.isEmpty()) return null;
        return new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(filtered);
    }

    private boolean shouldForward(Packet<?> packet) {
        if (isSoundOrParticle(packet)) return true;
        if (isChunkPacket(packet)) return chunkSyncEnabled;
        if (!entitySyncEnabled || !isWorldPacket(packet)) return false;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket) return true;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket add && isEntitySynced(add.getId())) {
            return false; // 已由对账补发过，避免重复 Add
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket orb && isEntitySynced(orb.getId())) {
            return false;
        }
        if (isPacketForSelfOrFake(packet)) return false;
        int entityId = entityIdOf(packet);
        return entityId < 0 || !isManagedByVanilla(entityId);
    }

    /** 该实体是否已经由原版玩家跟踪负责（真实玩家附近），不需要假人连接转发 */
    private boolean isManagedByVanilla(int entityId) {
        Entity e = level.getEntity(entityId);
        if (e == null) return false;
        if (isSelfOrFakeEntity(e)) return true;
        double radius = e.getType().clientTrackingRange() * 16.0;
        double dx = e.getX() - target.getX();
        double dz = e.getZ() - target.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean isSelfOrFake(int entityId) {
        if (entityId == target.getId()) return true;
        Entity e = level.getEntity(entityId);
        return e instanceof CameraFakePlayer;
    }

    private boolean isSelfOrFakeEntity(Entity e) {
        return e == target || e instanceof CameraFakePlayer;
    }

    /** 判断实体/方块包是否指向真实玩家自己或假人（RemoveEntities 单独在 filterRemovePacket 处理） */
    private boolean isPacketForSelfOrFake(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetPassengersPacket p) {
            if (isSelfOrFake(p.getVehicle())) return true;
            for (int passenger : p.getPassengers()) {
                if (isSelfOrFake(passenger)) return true;
            }
            return false;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket p) {
            return isSelfOrFake(p.getItemId()) || isSelfOrFake(p.getPlayerId());
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket p) {
            return isSelfOrFake(p.getSourceId()) || isSelfOrFake(p.getDestId());
        }
        int entityId = entityIdOf(packet);
        return entityId >= 0 && isSelfOrFake(entityId);
    }

    private int entityIdOf(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket p) return p.getId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket p) return p.getId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundAnimatePacket p) return p.getId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket p) return p.getId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundDamageEventPacket p) return p.entityId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundEntityEventPacket p) {
            Entity e = p.getEntity(level);
            return e != null ? e.getId() : -1;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket p) return p.id();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundMoveEntityPacket p) {
            Entity e = p.getEntity(level);
            return e != null ? e.getId() : -1;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundRotateHeadPacket p) {
            Entity e = p.getEntity(level);
            return e != null ? e.getId() : -1;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket p) return p.id();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket p) return p.getId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket p) return p.getEntity();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket p) return p.getId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket p) return p.getEntityId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket p) return p.getEntityId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket p) {
            Entity e = p.getEntity(level);
            return e != null ? e.getId() : -1;
        }
        return -1;
    }

    /** 假人收到的区块包：far 期间原样转发，让区块和实体走同一条原版时序 */
    private boolean isChunkPacket(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundLightUpdatePacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
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
