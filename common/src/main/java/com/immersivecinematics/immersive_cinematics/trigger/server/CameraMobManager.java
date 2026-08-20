package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 相机锚点管理（0.3.5 第5.5轮，假人完全接管版）。
 * <p>
 * 每个播放者的相机位置对应一个隐藏 {@link CameraFakePlayer}：
 * <ul>
 *   <li>加入服务端玩家列表 → 原版自动加载/生成相机周围区块</li>
 *   <li>spawn=true 时设为生存模式 → 原版刷怪/despawn 把它当真实玩家</li>
 *   <li>spawn=false 时设为旁观模式 → 只加载区块，不触发刷怪</li>
 *   <li>不可见、不出现在玩家列表、发包全部丢弃</li>
 * </ul>
 */
public final class CameraMobManager {

    public static final CameraMobManager INSTANCE = new CameraMobManager();

    private static final class Anchor {
        final UUID player;
        final ServerLevel level;
        ChunkPos center;
        int radius;
        boolean spawn;
        boolean ai;
        CameraFakePlayer fakePlayer;
        CameraFakeConnection fakeConnection;

        Anchor(UUID player, ServerLevel level, ChunkPos center, int radius, boolean spawn, boolean ai) {
            this.player = player;
            this.level = level;
            this.center = center;
            this.radius = Math.max(1, Math.min(16, radius));
            this.spawn = spawn;
            this.ai = ai;
        }
    }

    private final Map<UUID, Anchor> anchors = new HashMap<>();
    private int tickCounter = 0;

    private CameraMobManager() {}

    public void setAnchor(UUID player, ServerLevel level, ChunkPos center, int radius, boolean spawn, boolean ai) {
        Anchor a = anchors.get(player);
        if (a != null && a.level != level) {
            removeAnchor(player);
            a = null;
        }
        if (a == null) {
            a = new Anchor(player, level, center, radius, spawn, ai);
            anchors.put(player, a);
        } else {
            a.center = center;
            a.radius = Math.max(1, Math.min(16, radius));
            a.spawn = spawn;
            a.ai = ai;
        }
        ensureFakePlayer(a);
        applyGameMode(a);
        updateFakePlayerPosition(a);
    }

    public void removeAnchor(UUID player) {
        Anchor a = anchors.remove(player);
        if (a == null) return;
        if (a.fakePlayer != null) {
            a.level.getServer().getPlayerList().remove(a.fakePlayer);
            a.fakePlayer = null;
        }
    }

    public boolean isActive() {
        return !anchors.isEmpty();
    }

    /** 区块发给真实玩家后，补发该区块内尚未同步过的实体（跳过相机假人） */
    public void sendEntitiesForChunk(ServerPlayer player, ServerLevel level, ChunkPos pos) {
        Anchor a = anchors.get(player.getUUID());
        if (a == null || a.fakeConnection == null) return;
        AABB box = new AABB(
                pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1.0, level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1.0);
        for (Entity entity : level.getEntities((Entity) null, box, e -> !(e instanceof CameraFakePlayer))) {
            if (a.fakeConnection.isEntitySynced(entity.getId())) continue;
            ServerEntity serverEntity = new ServerEntity(level, entity, 0, false, p -> {});
            serverEntity.sendPairingData(player, player.connection::send);
            a.fakeConnection.markEntitySynced(entity.getId());
        }
    }

    /** 每 10 tick 把假人钉在相机锚点，防止任何意外移动；每 20 tick 处理 NoAI + 补发相机区实体 */
    public void tick() {
        tickCounter++;
        boolean moveFake = tickCounter % 10 == 0;
        boolean scanAi = tickCounter % 20 == 0;
        for (Anchor a : new ArrayList<>(anchors.values())) {
            if (a.fakePlayer != null && moveFake) {
                updateFakePlayerPosition(a);
            }
            if (scanAi) {
                if (a.spawn && !a.ai) {
                    applyNoAi(a);
                }
                resyncCameraEntities(a);
            }
        }
    }

    /** 周期性补发相机区尚未同步过的实体（兜底区块发送后才刷出来的怪） */
    private void resyncCameraEntities(Anchor a) {
        ServerPlayer player = a.level.getServer().getPlayerList().getPlayer(a.player);
        if (player == null || a.fakeConnection == null) return;
        int r = a.radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(a.center.x + dx, a.center.z + dz);
                if (!a.level.getChunkSource().hasChunk(pos.x, pos.z)) continue;
                sendEntitiesForChunk(player, a.level, pos);
            }
        }
    }

    /** camera_mob_ai=false：相机区刷出的怪用原版 NoAI 冻结为静态布景 */
    private void applyNoAi(Anchor a) {
        int r = a.radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(a.center.x + dx, a.center.z + dz);
                AABB box = new AABB(
                        pos.getMinBlockX(), a.level.getMinBuildHeight(), pos.getMinBlockZ(),
                        pos.getMaxBlockX() + 1.0, a.level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1.0);
                for (Entity e : a.level.getEntities((Entity) null, box, e2 -> e2 instanceof Mob)) {
                    if (e instanceof Mob mob) {
                        mob.setNoAi(true);
                    }
                }
            }
        }
    }

    private void ensureFakePlayer(Anchor a) {
        if (a.fakePlayer != null) return;
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes(("camera_anchor_" + a.player).getBytes(StandardCharsets.UTF_8)),
                "camera_anchor");
        CameraFakePlayer fake = new CameraFakePlayer(a.level.getServer(), a.level, profile);
        ServerPlayer real = a.level.getServer().getPlayerList().getPlayer(a.player);
        CameraFakeConnection connection = new CameraFakeConnection(real);
        a.level.getServer().getPlayerList().placeNewPlayer(connection, fake);
        a.fakePlayer = fake;
        a.fakeConnection = connection;
        applyGameMode(a);
    }

    private void applyGameMode(Anchor a) {
        if (a.fakePlayer == null) return;
        a.fakePlayer.gameMode.changeGameModeForPlayer(a.spawn ? GameType.SURVIVAL : GameType.SPECTATOR);
    }

    private void updateFakePlayerPosition(Anchor a) {
        if (a.fakePlayer == null) return;
        int cx = (a.center.x << 4) + 8;
        int cz = (a.center.z << 4) + 8;
        int y = Math.max(a.level.getMinBuildHeight() + 1, a.level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz));
        a.fakePlayer.moveTo(cx, y, cz, 0.0F, 0.0F);
        a.level.getChunkSource().move(a.fakePlayer);
    }
}
