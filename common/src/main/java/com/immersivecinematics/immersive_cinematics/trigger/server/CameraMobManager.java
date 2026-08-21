package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.mixin.PlayerListAccessor;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraMob");

    /** 平台假人引导器：Fabric/Forge 各自注入实现 */
    private static FakePlayerBootstrapper bootstrapper;

    public static void setBootstrapper(FakePlayerBootstrapper b) {
        bootstrapper = b;
    }

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
        cleanupSyncedEntities(a);
        if (a.fakePlayer != null) {
            a.level.getServer().getPlayerList().remove(a.fakePlayer);
            LOGGER.info("[camera-fake] 假人移除 玩家={} 中心={}", a.player, a.center);
            a.fakePlayer = null;
        }
    }

    /**
     * 退出 far 模式时按“玩家附近”区分清理：
     * <ul>
     *   <li>实体仍在服务端且位于玩家视距内 → 不删，交还原版玩家跟踪（原版会自己补发）</li>
     *   <li>实体已不存在 / 位于玩家视距外 → 发移除包，避免客户端残留幽灵实体</li>
     * </ul>
     */
    private void cleanupSyncedEntities(Anchor a) {
        if (a.fakeConnection == null) return;
        ServerPlayer player = a.level.getServer().getPlayerList().getPlayer(a.player);
        it.unimi.dsi.fastutil.ints.IntList ids = a.fakeConnection.takeSyncedEntityIds();
        if (ids.isEmpty()) return;
        if (player == null || player.connection == null) return;

        it.unimi.dsi.fastutil.ints.IntList removeIds = new it.unimi.dsi.fastutil.ints.IntArrayList();
        int nearLeft = 0;
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.getInt(i);
            Entity e = a.level.getEntity(id);
            if (e != null && isNearPlayer(player, e)) {
                nearLeft++;
            } else {
                removeIds.add(id);
            }
        }
        if (!removeIds.isEmpty()) {
            player.connection.send(new ClientboundRemoveEntitiesPacket(removeIds));
        }
        LOGGER.info("[camera-entity] 退出 far 交还 {} 个附近实体，移除 {} 个远处/已消失实体",
                nearLeft, removeIds.size());
    }

    /** 实体是否已进入原版玩家跟踪范围（按实体类型自身的 clientTrackingRange，XZ 平面判断） */
    private boolean isNearPlayer(ServerPlayer player, Entity e) {
        double radius = e.getType().clientTrackingRange() * 16.0;
        double dx = e.getX() - player.getX();
        double dz = e.getZ() - player.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    public boolean isActive() {
        return !anchors.isEmpty();
    }

    public boolean hasAnchor(UUID player) {
        return anchors.containsKey(player);
    }

    /**
     * 每 10 tick 把假人钉在相机锚点，防止任何意外移动；每 20 tick 处理 NoAI + 同步状态。
     * 实体发包不再手动补发——完全由 {@link CameraFakeConnection} 转发假人收到的原版实体流。
     */
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
                updateEntitySyncState(a);
                syncCameraEntities(a);
            }
            if (tickCounter % 100 == 0 && a.fakeConnection != null) {
                int[] stats = a.fakeConnection.takeAndResetPacketStats();
                if (stats[0] + stats[1] + stats[2] > 0) {
                    LOGGER.info("[camera-entity] 包统计 转实体={} 转声音={} 拦实体={}", stats[0], stats[1], stats[2]);
                }
            }
        }
    }

    /**
     * 实体中继不再按 far 开关整体开/关，而是由 {@link CameraFakeConnection} 按“实体是否已被原版玩家跟踪”逐实体过滤。
     * 假人在脚本期间保持存活，只转发真实玩家跟踪范围之外的实体。
     */
    private void updateEntitySyncState(Anchor a) {
        if (a.fakeConnection == null || a.fakePlayer == null) return;
        boolean previous = a.fakeConnection.isEntitySyncEnabled();
        a.fakeConnection.setEntitySyncEnabled(true);
        boolean far = ChunkPreloadManager.INSTANCE.isFarMode(a.player);
        boolean prevChunk = a.fakeConnection.isChunkSyncEnabled();
        a.fakeConnection.setChunkSyncEnabled(far);
        if (!previous) {
            LOGGER.info("[camera-entity] 实体中继保持开启（逐实体过滤）");
        }
        if (prevChunk != far) {
            LOGGER.info("[camera-entity] 区块中继 far={}", far);
        }
    }

    /**
     * 对账补发：只补“原版玩家跟踪范围之外、但相机/假人能看到”的实体。
     * 这些实体原版不会发给真实客户端，必须由我们主动发 Add/配对数据。
     * 已同步过的跳过；假人连接后续收到的重复 Add 也会被去重。
     */
    private void syncCameraEntities(Anchor a) {
        if (a.fakeConnection == null || !a.fakeConnection.isEntitySyncEnabled()) return;
        ServerPlayer player = a.level.getServer().getPlayerList().getPlayer(a.player);
        if (player == null || player.connection == null) return;
        int r = a.radius;
        int sent = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(a.center.x + dx, a.center.z + dz);
                if (!a.level.getChunkSource().hasChunk(pos.x, pos.z)) continue;
                AABB box = new AABB(
                        pos.getMinBlockX(), a.level.getMinBuildHeight(), pos.getMinBlockZ(),
                        pos.getMaxBlockX() + 1.0, a.level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1.0);
                for (Entity entity : a.level.getEntities((Entity) null, box,
                        e -> !(e instanceof CameraFakePlayer) && e != player)) {
                    if (a.fakeConnection.isEntitySynced(entity.getId())) continue;
                    if (isNearPlayer(player, entity)) continue; // 原版自己会发
                    ServerEntity serverEntity = new ServerEntity(a.level, entity, 0, false, p -> {});
                    serverEntity.sendPairingData(player, player.connection::send);
                    a.fakeConnection.markEntitySynced(entity.getId());
                    sent++;
                }
            }
        }
        if (sent > 0) {
            LOGGER.info("[camera-entity] 对账补发 新增 {} 实体（原版范围外）", sent);
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
                CameraFakePlayer.FAKE_PLAYER_NAME);
        CameraFakePlayer fake = new CameraFakePlayer(a.level.getServer(), a.level, profile);
        ServerPlayer real = a.level.getServer().getPlayerList().getPlayer(a.player);
        CameraFakeConnection connection = new CameraFakeConnection(real, a.level);
        if (bootstrapper == null) {
            LOGGER.error("[camera-fake] 未设置平台假人引导器，无法创建假人 玩家={}", a.player);
            return;
        }
        // 平台各自引导：Fabric 走 placeNewPlayer；Forge 手动拼最小假玩家（绕开 Forge 网络登录钩子）
        bootstrapper.bootstrap(fake, connection, a.level);
        // 引导过程可能重置外观状态，这里重新强制隐藏/静默/无敌
        fake.setInvisible(true);
        fake.setCustomNameVisible(false);
        fake.setSilent(true);
        fake.getAbilities().invulnerable = true;

        // 假人只作为区块/刷怪占位，绝不能进入 PlayerList 广播列表（否则 Tab/延迟更新会泄漏给真实客户端）。
        // 注意：Forge 的 getPlayers() 是不可变视图，必须通过 Accessor 拿内部可变列表。
        PlayerList playerList = a.level.getServer().getPlayerList();
        ((PlayerListAccessor) playerList).immersivecinematics_getPlayersList().remove(fake);

        a.fakePlayer = fake;
        a.fakeConnection = connection;
        applyGameMode(a);
        LOGGER.info("[camera-fake] 假人创建 玩家={} 中心={} uuid={}", a.player, a.center, fake.getUUID());
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
