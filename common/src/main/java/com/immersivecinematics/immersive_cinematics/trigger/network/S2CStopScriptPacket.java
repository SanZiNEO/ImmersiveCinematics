package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端 → 客户端：停止指定脚本（或全部脚本）。
 * <p>
 * {@code instanceId} 为该次播放请求的实例 id（§3.7；空串=无实例 id 的来源，或 scriptId 为空
 * 的"停止全部"广播）。客户端据此回执 {@link C2SScriptFinishedPacket} 时原样带回，服务端按
 * {@code (scriptId, instanceId)} 精确移除该实例的观看者。
 */
public class S2CStopScriptPacket implements CinematicS2CPacket {

    private final String scriptId;
    /** 播放实例 id（§3.7）；空串=无实例 id 的来源 / 停止全部 */
    private final String instanceId;
    private final String refId;

    public S2CStopScriptPacket(String scriptId, String instanceId, String refId) {
        this.scriptId = scriptId;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.refId = refId;
    }

    public S2CStopScriptPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
        this.refId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId);
        buf.writeUtf(refId);
    }

    @Override
    public void handle() {
        ClientScriptReceiver.handleStopScript(this);
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
    public String getRefId() { return refId; }

    public static void send(ServerPlayer player, String scriptId, String instanceId, String refId) {
        NetworkHandler.sendToPlayer(player, new S2CStopScriptPacket(scriptId, instanceId, refId));
    }
}
