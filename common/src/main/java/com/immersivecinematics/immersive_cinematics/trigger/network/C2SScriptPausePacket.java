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
 * N1：携带 refId 做 ACK 握手（服务端回 {@link S2CScriptPauseAckPacket} 确认）。
 */
public class C2SScriptPausePacket implements CinematicC2SPacket {

    private final String scriptId;
    private final boolean paused;
    private final String refId;

    public C2SScriptPausePacket(String scriptId, boolean paused) {
        this(scriptId, paused, "");
    }

    public C2SScriptPausePacket(String scriptId, boolean paused, String refId) {
        this.scriptId = scriptId;
        this.paused = paused;
        this.refId = refId;
    }

    public C2SScriptPausePacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.paused = buf.readBoolean();
        this.refId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeBoolean(paused);
        buf.writeUtf(refId);
    }

    @Override
    public void handle(ServerPlayer player) {
        ScriptEventManager.INSTANCE.handlePause(player, scriptId, paused);
        // N1：处理成功后回执
        if (refId != null && !refId.isEmpty()) {
            S2CScriptPauseAckPacket.send(player, refId);
        }
    }

    public String getScriptId() { return scriptId; }
    public boolean isPaused() { return paused; }
    public String getRefId() { return refId; }
}
