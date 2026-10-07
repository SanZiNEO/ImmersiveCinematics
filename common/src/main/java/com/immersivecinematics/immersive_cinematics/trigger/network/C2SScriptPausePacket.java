package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：脚本暂停/恢复状态同步。
 * <p>
 * 当客户端游戏暂停（pause_when_game_paused=true 且 MC 暂停）时发送，
 * 服务端停止处理该脚本的 EVENT keyframe，避免事件在暂停期间误触发。
 * <p>
 * {@code instanceId} 为 {@link S2CPlayScriptPacket#getInstanceId()} 的原样回传（§3.7），
 * 服务端据此在账本中按 {@code (scriptId, instanceId)} 精确解析暂停信号所属的播放实例。
 * <p>
 * N1：携带 refId 做 ACK 握手（服务端回 {@link S2CScriptPauseAckPacket} 确认）。
 */
public class C2SScriptPausePacket implements CinematicC2SPacket {

    private final String scriptId;
    /** 播放实例 id（§3.7）；空串=无实例 id 的来源 */
    private final String instanceId;
    private final boolean paused;
    private final String refId;

    public C2SScriptPausePacket(String scriptId, String instanceId, boolean paused, String refId) {
        this.scriptId = scriptId;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.paused = paused;
        this.refId = refId;
    }

    public C2SScriptPausePacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
        this.paused = buf.readBoolean();
        this.refId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId);
        buf.writeBoolean(paused);
        buf.writeUtf(refId);
    }

    @Override
    public void handle(ServerPlayer player) {
        ScriptEventManager.INSTANCE.handlePause(player, scriptId, instanceId, paused);
        // N1：处理成功后回执
        if (refId != null && !refId.isEmpty()) {
            S2CScriptPauseAckPacket.send(player, refId);
        }
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
    public boolean isPaused() { return paused; }
    public String getRefId() { return refId; }
}
