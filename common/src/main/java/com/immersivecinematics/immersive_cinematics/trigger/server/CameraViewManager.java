package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SCameraViewPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CCameraViewPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CDimensionInfoPacket;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 跨维度视图服务端流式加载：玩家本体留在原维度，客户端按目标维度 lane 上报视图，
 * 服务端按 {@code (玩家, 视图 id)} 独立维护「ticket + 区块差集 + 实体配对」。
 *
 * 与 {@link CameraCenterManager}（F6 单锚点，走 {@code ChunkMap.move} 差集）并行但互不干扰：
 * <ul>
 *   <li>ticket：{@code DistanceManager.addPlayer(相机 section, 真实玩家)} —— 让目标维度的区块加载与
 *       自然刷怪距离门（{@code hasPlayersNearby} / {@code naturalSpawnChunkCounter}）围绕相机生效；
 *       section 变化时先移除旧 section 再添加，避免 ticket 泄漏。玩家不写入目标维度的 {@code ChunkMap.playerMap}，
 *       因此不会触发原版 move 的维度无关中心包，也不会与真实位置差集冲突。</li>
 *   <li>区块：{@code getChunkNow} 取已加载区块，打上维度标签经 {@link S2CCameraViewPacket} 发到客户端目标视图；
 *       离开半径的区块补发 {@code ClientboundForgetLevelChunkPacket}。</li>
 *   <li>实体：{@code ServerEntity.sendPairingData} 只发初始配对（不调 {@code sendChanges}——那会抢走原版
 *       追踪实例的 dirty 数据）；离开范围 / 被移除时补发 {@code ClientboundRemoveEntitiesPacket}。</li>
 *   <li>刷怪：每个视图一个 {@link CameraAnchorVirtualPlayer}（纯坐标引用，不进世界），供
 *       {@code NaturalSpawner} / {@code Mob.checkDespawn} 的「最近玩家」使用。</li>
 * </ul>
 *
 * 线程：所有入口都在服务端主线程；状态表只在主线程读写。
 */
public final class CameraViewManager {

    public static final CameraViewManager INSTANCE = new CameraViewManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraView");

    /** 区块差集刷新间隔（tick）。 */
    private static final int CHUNK_INTERVAL = 5;
    /** 实体配对刷新间隔（tick）。 */
    private static final int ENTITY_INTERVAL = 10;
    /** 实体配对半径（格）。 */
    private static final double ENTITY_RANGE = 64.0;
    /** 刷怪门 / 消失判定的水平资格半径（格，同原版 128 格口径）。 */
    private static final double SPAWN_GATE_SQ = 16384.0;
    /** 服务端实际发送的区块半径上限（区块数）。 */
    private static final int MAX_CHUNK_RADIUS = 8;
    /** 自定义通道单包上限（原版 CustomPayload 上限 1 MiB，留出信封头余量）。 */
    private static final int MAX_ENVELOPE_PAYLOAD = 1_000_000;

    private final Map<ViewKey, ViewState> views = new HashMap<>();

    private CameraViewManager() {}

    // ===== 包入口 =====

    /** 客户端视图上报：{@code active=false} 释放；否则新建 / 更新并确保 ticket。 */
    public void handle(C2SCameraViewPacket packet, ServerPlayer player) {
        if (player == null || packet.viewId().isEmpty() || packet.viewId().length() > 128) {
            return;
        }
        ViewKey key = new ViewKey(player.getUUID(), packet.viewId());
        if (!packet.active()) {
            release(key, "client-end");
            return;
        }
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        ResourceLocation dimensionId = packet.dimension();
        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel level = server.getLevel(levelKey);
        if (level == null) {
            if (Config.debugLogging) {
                LOGGER.info("[camera-view] 忽略未知维度 玩家={} 视图={} 维度={}",
                        player.getName().getString(), packet.viewId(), dimensionId);
            }
            return;
        }

        if (player.level() == level) {
            // 玩家本体已在目标维度：原版追踪负责该维度，视图流只会造成重复配对
            release(key, "player-in-dimension");
            return;
        }

        ViewState st = views.get(key);
        if (st == null) {
            st = new ViewState(key, player);
            views.put(key, st);
            if (Config.debugLogging) {
                LOGGER.info("[camera-view] 新建视图 玩家={} 视图={} 维度={}",
                        player.getName().getString(), packet.viewId(), dimensionId);
            }
        }
        st.player = player;
        st.lastReportTick = server.getTickCount();
        st.radius = Mth.clamp(packet.radius(), 2, 12);

        Vec3 pos = new Vec3(packet.x(), packet.y(), packet.z());
        if (st.level != level) {
            // 维度变化：旧维度的区块 / 实体 / ticket 全部作废（仍按旧维度标签补发 forget），再按新维度重建
            releaseStream(st, "dimension-change");
            st.level = level;
            st.lastChunkTick = 0L;
            st.lastEntityTick = 0L;
        }
        st.dimension = dimensionId;
        st.pos = pos;
        updateVirtual(st);
        ensureTicket(st);
        level.resetEmptyTime();
    }

    // ===== tick =====

    /**
     * 每 tick：超时 / 玩家消失 / 维度卸载兜底释放，并刷新区块与实体流。
     * 客户端停发（崩溃 / 断线 / END 丢失）时不会让目标维度的 ticket 与流永久留在服务端。
     */
    public void tick(MinecraftServer server) {
        if (views.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        long timeout = Math.max(100L, 5L * Config.cameraCenterReportInterval);
        for (ViewState st : List.copyOf(views.values())) {
            ServerPlayer player = st.player;
            if (player == null || player.isRemoved()) {
                release(st.key, "player-gone");
                continue;
            }
            if (st.level == null || server.getLevel(st.level.dimension()) != st.level) {
                release(st.key, "level-unloaded");
                continue;
            }
            if (now - st.lastReportTick > timeout) {
                release(st.key, "timeout");
                continue;
            }
            if (player.level() == st.level) {
                // 玩家自己进入目标维度：原版追踪接管，服务端流必须让位（否则重复配对 / 误删）
                release(st.key, "player-in-dimension");
                continue;
            }
            st.level.resetEmptyTime();
            if (now - st.lastChunkTick >= CHUNK_INTERVAL) {
                sendChunks(st, now);
            }
            if (now - st.lastEntityTick >= ENTITY_INTERVAL) {
                sendEntities(st, now);
            }
        }
    }

    // ===== 释放 =====

    public void onPlayerQuit(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        for (ViewState st : List.copyOf(views.values())) {
            if (st.key.player().equals(uuid)) {
                release(st.key, "quit");
            }
        }
    }

    /** 服务端停止：只清表，不碰 chunk source（可能已关闭），也不发包。 */
    public void clearAll() {
        if (!views.isEmpty() && Config.debugLogging) {
            LOGGER.info("[camera-view] 服务端停止，清空 {} 个视图", views.size());
        }
        views.clear();
    }

    /** 维度卸载：释放该维度的视图（尽量补发 forget / remove，ticket 移除失败可忽略）。 */
    public void onLevelUnload(ServerLevel level) {
        if (level == null) {
            return;
        }
        for (ViewState st : List.copyOf(views.values())) {
            if (st.level == level) {
                release(st.key, "level-unload");
            }
        }
    }

    private void release(ViewKey key, String reason) {
        ViewState st = views.remove(key);
        if (st == null) {
            return;
        }
        releaseStream(st, reason);
        if (Config.debugLogging) {
            LOGGER.info("[camera-view] 释放视图 玩家={} 视图={} 原因={} 剩余={}",
                    st.player != null ? st.player.getName().getString() : "?",
                    key.viewId(), reason, views.size());
        }
    }

    /** 释放一条视图的流资源（区块 / 实体 / ticket / 虚拟玩家），保留 ViewState 以便换维度复用。 */
    private void releaseStream(ViewState st, String reason) {
        boolean canSend = st.player != null && !st.player.isRemoved() && !st.player.hasDisconnected();
        if (st.level != null) {
            if (canSend && !st.sentChunks.isEmpty()) {
                for (LongIterator it = st.sentChunks.iterator(); it.hasNext(); ) {
                    long packed = it.nextLong();
                    // 同一玩家的另一视图还在同一维度用它：客户端是同一份 ClientLevel，不能替对方遗忘
                    if (otherViewHasChunk(st, packed)) {
                        continue;
                    }
                    sendEnvelope(st, new ClientboundForgetLevelChunkPacket(
                            ChunkPos.getX(packed), ChunkPos.getZ(packed)));
                }
            }
            if (canSend && !st.sentEntities.isEmpty()) {
                int[] ids = new int[st.sentEntities.size()];
                int count = 0;
                for (IntIterator it = st.sentEntities.iterator(); it.hasNext(); ) {
                    int id = it.nextInt();
                    if (!otherViewHasEntity(st, id)) {
                        ids[count++] = id;
                    }
                }
                if (count > 0) {
                    int[] removals = new int[count];
                    System.arraycopy(ids, 0, removals, 0, count);
                    sendEnvelope(st, new ClientboundRemoveEntitiesPacket(removals));
                }
            }
            if (st.ticketAdded) {
                removeTicket(st);
            }
        }
        st.sentChunks.clear();
        st.sentEntities.clear();
        st.ticketAdded = false;
        st.section = null;
        st.virtual = null;
        if (Config.debugLogging) {
            LOGGER.info("[camera-view] 释放流 视图={} 原因={}", st.key.viewId(), reason);
        }
    }

    // ===== ticket =====

    /** 相机 section 变化时移动 PLAYER ticket（先移除旧 section，避免 ticket 泄漏）。 */
    private void ensureTicket(ViewState st) {
        if (st.level == null || st.player == null || st.pos == null) {
            return;
        }
        SectionPos section = SectionPos.of(BlockPos.containing(st.pos));
        if (st.ticketAdded && section.equals(st.section)) {
            return;
        }
        if (st.ticketAdded) {
            removeTicket(st);
        }
        try {
            st.level.getChunkSource().chunkMap.getDistanceManager().addPlayer(section, st.player);
            st.ticketAdded = true;
            st.section = section;
            // section 变化后立即刷新区块差集
            st.lastChunkTick = st.level.getServer().getTickCount() - CHUNK_INTERVAL;
        } catch (Exception e) {
            st.ticketAdded = false;
            st.section = null;
            LOGGER.warn("[camera-view] 添加相机 ticket 失败（视图 {}，section {}）：该视图区块可能不加载",
                    st.key.viewId(), section, e);
        }
    }

    /**
     * 移除相机 ticket；{@code removePlayer} 在表缺失时会 NPE，必须兜底。
     * 同一 {@code (section, 玩家)} 条目可能还被原版玩家 ticket（玩家已进入该维度且同 section）
     * 或同一玩家的另一视图使用——此时只清本地标记，不真的移除，否则会连带删掉对方的加载票。
     */
    private void removeTicket(ViewState st) {
        if (!st.ticketAdded || st.section == null || st.level == null || st.player == null) {
            return;
        }
        SectionPos section = st.section;
        boolean sharedWithPlayer = st.player.level() == st.level
                && section.equals(SectionPos.of(st.player.blockPosition()));
        if (sharedWithPlayer || otherViewHasTicket(st, section)) {
            st.ticketAdded = false;
            st.section = null;
            return;
        }
        try {
            st.level.getChunkSource().chunkMap.getDistanceManager().removePlayer(section, st.player);
        } catch (Exception e) {
            if (Config.debugLogging) {
                LOGGER.info("[camera-view] 移除相机 ticket 失败（可忽略，视图 {}）：{}",
                        st.key.viewId(), e.toString());
            }
        } finally {
            st.ticketAdded = false;
            st.section = null;
        }
    }

    private void updateVirtual(ViewState st) {
        if (st.level == null || st.pos == null) {
            return;
        }
        if (st.virtual == null) {
            st.virtual = new CameraAnchorVirtualPlayer(st.level, st.pos.x, st.pos.y, st.pos.z);
        } else {
            st.virtual.moveTo(st.pos.x, st.pos.y, st.pos.z, 0.0F, 0.0F);
        }
    }

    // ===== 区块流 =====

    private void sendChunks(ViewState st, long now) {
        st.lastChunkTick = now;
        if (st.level == null || st.pos == null) {
            return;
        }
        int radius = Math.min(st.radius, MAX_CHUNK_RADIUS);
        int centerX = SectionPos.blockToSectionCoord(st.pos.x);
        int centerZ = SectionPos.blockToSectionCoord(st.pos.z);
        ServerChunkCache cache = st.level.getChunkSource();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                long packed = ChunkPos.asLong(x, z);
                if (st.sentChunks.contains(packed)) {
                    continue;
                }
                LevelChunk chunk = cache.getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }
                sendEnvelope(st, new ClientboundLevelChunkWithLightPacket(
                        chunk, st.level.getLightEngine(), null, null));
                st.sentChunks.add(packed);
            }
        }
        // 离开半径的区块补发 forget，客户端目标视图不保留旧块。
        // 先收集、后删除：不在 fastutil 迭代器遍历中直接改集合。
        if (!st.sentChunks.isEmpty()) {
            LongSet forget = st.forgetScratch;
            forget.clear();
            for (LongIterator it = st.sentChunks.iterator(); it.hasNext(); ) {
                long packed = it.nextLong();
                int x = ChunkPos.getX(packed);
                int z = ChunkPos.getZ(packed);
                if (Math.abs(x - centerX) > radius || Math.abs(z - centerZ) > radius) {
                    // 另一视图仍在用同一区块时不发 forget（客户端共享一份 ClientLevel）
                    if (!otherViewHasChunk(st, packed)) {
                        sendEnvelope(st, new ClientboundForgetLevelChunkPacket(x, z));
                    }
                    forget.add(packed);
                }
            }
            for (LongIterator it = forget.iterator(); it.hasNext(); ) {
                st.sentChunks.remove(it.nextLong());
            }
        }
    }

    // ===== 实体流 =====

    private void sendEntities(ViewState st, long now) {
        st.lastEntityTick = now;
        if (st.level == null || st.pos == null || st.player == null) {
            return;
        }
        Vec3 center = st.pos;
        AABB box = new AABB(center.x - ENTITY_RANGE, center.y - ENTITY_RANGE, center.z - ENTITY_RANGE,
                center.x + ENTITY_RANGE, center.y + ENTITY_RANGE, center.z + ENTITY_RANGE);
        List<Entity> entities = st.level.getEntitiesOfClass(Entity.class, box,
                e -> !e.isRemoved() && e != st.virtual);
        IntSet present = st.presentScratch;
        present.clear();
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            int id = entity.getId();
            present.add(id);
            if (st.sentEntities.contains(id)) {
                continue;
            }
            // 只发初始配对；持续同步仍由原版追踪负责，避免抢占其 dirty 数据
            Consumer<Packet<?>> broadcast = p -> sendEnvelope(st, p);
            try {
                new ServerEntity(st.level, entity, 20, true, broadcast)
                        .sendPairingData(st.player, p -> sendEnvelope(st, p));
                st.sentEntities.add(id);
            } catch (Exception e) {
                LOGGER.warn("[camera-view] 实体配对失败（视图 {}，实体 {}）：该实体本 tick 跳过",
                        st.key.viewId(), id, e);
            }
        }
        if (!st.sentEntities.isEmpty()) {
            int[] removed = new int[st.sentEntities.size()];
            int count = 0;
            IntSet missing = st.missingScratch;
            missing.clear();
            for (IntIterator it = st.sentEntities.iterator(); it.hasNext(); ) {
                int id = it.nextInt();
                if (present.contains(id)) {
                    continue;
                }
                missing.add(id);
                if (!otherViewHasEntity(st, id)) {
                    removed[count++] = id;
                }
            }
            // 先收集、后删除：fastutil 的迭代器不允许在遍历中直接改集合
            for (IntIterator it = missing.iterator(); it.hasNext(); ) {
                st.sentEntities.remove(it.nextInt());
            }
            if (count > 0) {
                int[] ids = new int[count];
                System.arraycopy(removed, 0, ids, 0, count);
                sendEnvelope(st, new ClientboundRemoveEntitiesPacket(ids));
            }
        }
    }

    // ===== 同玩家多视图去重（客户端同一维度共享一份 ClientLevel）=====

    /** 同一玩家在目标维度的另一视图是否也发送过该区块（是则不能替对方遗忘）。 */
    private boolean otherViewHasChunk(ViewState st, long packed) {
        for (ViewState other : views.values()) {
            if (other != st && other.level == st.level
                    && other.key.player().equals(st.key.player())
                    && other.sentChunks.contains(packed)) {
                return true;
            }
        }
        return false;
    }

    /** 同一玩家在目标维度的另一视图是否持有同一 section 的 ticket（是则不能替对方移除）。 */
    private boolean otherViewHasTicket(ViewState st, SectionPos section) {
        for (ViewState other : views.values()) {
            if (other != st && other.level == st.level
                    && other.key.player().equals(st.key.player())
                    && other.ticketAdded && section.equals(other.section)) {
                return true;
            }
        }
        return false;
    }

    /** 同一玩家在目标维度的另一视图是否也配对了该实体（是则不能替对方移除）。 */
    private boolean otherViewHasEntity(ViewState st, int entityId) {
        for (ViewState other : views.values()) {
            if (other != st && other.level == st.level
                    && other.key.player().equals(st.key.player())
                    && other.sentEntities.contains(entityId)) {
                return true;
            }
        }
        return false;
    }

    // ===== 发包 =====

    /** 把一条原版 PLAY 客户端包编码进带维度标签的信封包。 */
    private void sendEnvelope(ViewState st, Packet<?> packet) {
        ServerPlayer player = st.player;
        if (player == null || player.isRemoved() || player.hasDisconnected()) {
            return;
        }
        int id;
        try {
            id = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
        } catch (Exception e) {
            LOGGER.warn("[camera-view] 原版包未注册（视图 {}，包 {}）：丢弃",
                    st.key.viewId(), packet.getClass().getName(), e);
            return;
        }
        if (id < 0) {
            LOGGER.warn("[camera-view] 原版包无网络 id（视图 {}，包 {}）：丢弃",
                    st.key.viewId(), packet.getClass().getName());
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buf);
            byte[] payload = new byte[buf.readableBytes()];
            buf.readBytes(payload);
            if (payload.length > MAX_ENVELOPE_PAYLOAD) {
                // 原版 CustomPayload 上限 1 MiB：超长区块包只能丢弃（客户端该区块留空，其余流继续）
                ErrorLog.logRateLimited("CameraView", "oversize:" + st.key.viewId(), 30_000L,
                        "跨维度视图包超过自定义通道上限（视图 " + st.key.viewId()
                                + "，包 " + packet.getClass().getSimpleName() + "，" + payload.length
                                + " 字节）：该包已丢弃，客户端对应区块 / 实体可能缺失");
                return;
            }
            NetworkHandler.sendToPlayer(player,
                    new S2CCameraViewPacket(st.key.viewId(), st.dimension, id, payload));
        } catch (Exception e) {
            // 单个坏包（超长区块 / 编码异常）只丢本包，不影响同一 tick 的其它视图
            LOGGER.warn("[camera-view] 原版包编码失败（视图 {}，包 {}）：本包丢弃",
                    st.key.viewId(), packet.getClass().getName(), e);
        } finally {
            buf.release();
        }
    }

    // ===== 刷怪 / 消失查询（mixin 用）=====

    /** 某区块附近是否存在相机视图（区块中心到相机 x/z 的 2D 距离 < 128 格，同原版刷怪门）。 */
    public boolean isAnyViewNear(ServerLevel level, ChunkPos chunkPos) {
        for (ViewState st : views.values()) {
            if (st.level != level || st.pos == null) {
                continue;
            }
            double dx = (chunkPos.x << 4) + 8 - st.pos.x;
            double dz = (chunkPos.z << 4) + 8 - st.pos.z;
            if (dx * dx + dz * dz < SPAWN_GATE_SQ) {
                return true;
            }
        }
        return false;
    }

    /**
     * 刷怪局部上限记账用的真实玩家：取 128 格内最近的视图属主。
     * 返回真实 {@link ServerPlayer}（不是虚拟玩家），局部 mob cap 仍按玩家记账，不新增假玩家。
     */
    public ServerPlayer playerForSpawn(ServerLevel level, ChunkPos chunkPos) {
        ServerPlayer best = null;
        double bestSq = SPAWN_GATE_SQ;
        for (ViewState st : views.values()) {
            if (st.level != level || st.pos == null || st.player == null) {
                continue;
            }
            double dx = (chunkPos.x << 4) + 8 - st.pos.x;
            double dz = (chunkPos.z << 4) + 8 - st.pos.z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestSq) {
                bestSq = d2;
                best = st.player;
            }
        }
        return best;
    }

    /**
     * 给 {@code NaturalSpawner} / {@code Mob.checkDespawn} 返回最近的视图虚拟玩家。
     * 128 格水平资格过滤与原版刷怪门一致，3D 距离取最近；没有视图返回 {@code null}。
     */
    public CameraAnchorVirtualPlayer getVirtualPlayer(ServerLevel level, BlockPos pos) {
        CameraAnchorVirtualPlayer best = null;
        double bestSq = Double.MAX_VALUE;
        for (ViewState st : views.values()) {
            if (st.level != level || st.virtual == null || st.pos == null) {
                continue;
            }
            double dx = pos.getX() - st.pos.x;
            double dz = pos.getZ() - st.pos.z;
            if (dx * dx + dz * dz >= SPAWN_GATE_SQ) {
                continue;
            }
            double d2 = st.virtual.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
            if (d2 < bestSq) {
                bestSq = d2;
                best = st.virtual;
            }
        }
        return best;
    }

    // ===== 维度元数据 =====

    /** 玩家加入时下发维度 id → 维度类型表（客户端建目标 {@code ClientLevel} 用）。 */
    public void sendDimensionInfo(ServerPlayer player) {
        S2CDimensionInfoPacket.send(player, player != null ? player.server : null);
    }

    // ===== 内部类型 =====

    /** 视图身份：玩家 + 客户端视图 id（{@code captureId@维度}）。 */
    public record ViewKey(UUID player, String viewId) {}

    private static final class ViewState {
        final ViewKey key;
        ServerPlayer player;
        ServerLevel level;
        ResourceLocation dimension;
        Vec3 pos;
        int radius = 8;
        long lastReportTick;
        SectionPos section;
        boolean ticketAdded;
        final LongSet sentChunks = new LongOpenHashSet();
        /** 区块差集复用的待遗忘集合（先收集后删除，避免遍历中改集合）。 */
        final LongSet forgetScratch = new LongOpenHashSet();
        final IntSet sentEntities = new IntOpenHashSet();
        /** 实体刷新复用的存在集合（避免每 10 tick 分配）。 */
        final IntSet presentScratch = new IntOpenHashSet();
        /** 实体刷新复用的待删除集合（先收集后删除，避免遍历中改集合）。 */
        final IntSet missingScratch = new IntOpenHashSet();
        CameraAnchorVirtualPlayer virtual;
        long lastChunkTick;
        long lastEntityTick;

        ViewState(ViewKey key, ServerPlayer player) {
            this.key = key;
            this.player = player;
        }
    }
}
