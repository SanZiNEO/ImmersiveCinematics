package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionView;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.mixin.ClientPacketListenerAccessor;
import com.immersivecinematics.immersive_cinematics.mixin.MinecraftAccessor;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CCameraViewPacket;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 跨维度视图数据包路由（客户端主线程）：把带维度标签的原版 PLAY 包送进目标 {@code ClientLevel}。
 *
 * 流程：按维度取 {@link DimensionView} → 临时把全局 {@code Minecraft.level} / {@code levelRenderer}
 * 与 {@code ClientPacketListener.level} 指向目标视图 → 用原版协议表重建包并交给原版处理器 →
 * 立刻排空目标世界的光照更新队列 → finally 还原三个全局引用。
 *
 * 失败（目标视图不可用 / 包 id 非法 / 原版处理器异常）只丢弃本包并限频记录，绝不崩溃客户端；
 * 玩家本体所在世界与主画面渲染器在 finally 中无条件还原。
 */
public final class CameraViewPacketRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraView");
    private static final long FAIL_LOG_INTERVAL_MS = 5000L;

    /** 已记录过「目标视图不可用」的维度（每个维度只记一次，避免逐包刷屏）。 */
    private static final Set<String> DROPPED_DIMENSIONS = new HashSet<>();

    private CameraViewPacketRouter() {}

    /** 由 {@link S2CCameraViewPacket#handle()} 调用（客户端主线程）。 */
    public static void handle(S2CCameraViewPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (mc.level == null || connection == null) {
            return;
        }
        String dimension = packet.dimension().toString();
        DimensionView view = DimensionViews.acquire(mc, dimension);
        if (view == null || view.isMain()) {
            if (Config.debugLogging && DROPPED_DIMENSIONS.add(dimension)) {
                LOGGER.info("[camera-view] 丢弃数据包：目标视图不可用（维度 {}，视图 {}）",
                        dimension, packet.viewId());
            }
            return;
        }
        ClientLevel target = view.level();
        LevelRenderer targetRenderer = view.renderer();
        if (target == null || targetRenderer == null) {
            return;
        }
        ClientPacketListenerAccessor accessor = (ClientPacketListenerAccessor) connection;
        ClientLevel previousLevel = mc.level;
        LevelRenderer previousRenderer = mc.levelRenderer;
        ClientLevel previousListenerLevel = accessor.ic$getLevel();
        try {
            mc.level = target;
            ((MinecraftAccessor) mc).ic$setLevelRenderer(targetRenderer);
            accessor.ic$setLevel(target);
            Packet<?> decoded = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND,
                    packet.vanillaPacketId(), new FriendlyByteBuf(Unpooled.wrappedBuffer(packet.payload())));
            if (decoded == null) {
                ErrorLog.logRateLimited("CameraView", "route-id:" + packet.vanillaPacketId(), FAIL_LOG_INTERVAL_MS,
                        "跨维度视图数据包的原版包 id 未注册（id " + packet.vanillaPacketId()
                                + "，维度 " + dimension + "）：本包已丢弃");
                return;
            }
            @SuppressWarnings("unchecked")
            Packet<ClientGamePacketListener> gamePacket = (Packet<ClientGamePacketListener>) decoded;
            gamePacket.handle(connection);
            // 区块包只入队光照更新；在全局仍指向目标视图时排空，脏区块才会落到目标渲染器
            target.pollLightUpdates();
        } catch (Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            ErrorLog.logRateLimited("CameraView", "route-fail:" + dimension, FAIL_LOG_INTERVAL_MS,
                    "跨维度视图数据包处理失败（维度 " + dimension + "，原版包 id "
                            + packet.vanillaPacketId() + "）：本包已丢弃，主画面继续", t);
        } finally {
            accessor.ic$setLevel(previousListenerLevel);
            mc.level = previousLevel;
            ((MinecraftAccessor) mc).ic$setLevelRenderer(previousRenderer);
        }
    }
}
