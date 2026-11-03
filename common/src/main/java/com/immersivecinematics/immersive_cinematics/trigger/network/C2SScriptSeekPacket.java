package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：脚本时间线 seek（{@code goto_clip} 等 gameplay 跳转）。
 *
 * 客户端在内容时间线被重定位时发送：服务端据此把本实例的 EVENT 时间线基准对到目标位置，
 * 并把每条事件运行时重置到目标时间之后（{@link com.immersivecinematics.immersive_cinematics.script.event.EventSeekPlan}）——
 * 前跳不补触发、后跳回退可重新触发已越过的事件、目标之前的记录不重复触发。
 *
 * 暂停态与 seek 正交：本包不改服务端 {@code paused}，暂停中的 seek 只重定位基准与游标，
 * 恢复仍按暂停握手（{@code C2SScriptPausePacket}）上报的位置续。
 *
 * {@code instanceId} 为 {@link S2CPlayScriptPacket#getInstanceId()} 的原样回传；服务端凭
 * {@code (scriptId, instanceId)} 精确解析播放实例。携带 {@code refId} 做 ACK 握手
 * （服务端回 {@link S2CScriptSeekAckPacket}）。
 */
public class C2SScriptSeekPacket implements CinematicC2SPacket {

    private final String scriptId;
    /** 播放实例 id；空串 = 无实例 id 的来源（服务端不会命中账本） */
    private final String instanceId;
    /** 目标内容时间（全局秒，{@code >= 0}）。 */
    private final double targetSeconds;
    private final String refId;

    public C2SScriptSeekPacket(String scriptId, String instanceId, double targetSeconds, String refId) {
        this.scriptId = scriptId == null ? "" : scriptId;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.targetSeconds = targetSeconds;
        this.refId = refId == null ? "" : refId;
    }

    public C2SScriptSeekPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
        this.targetSeconds = buf.readDouble();
        this.refId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId);
        buf.writeDouble(targetSeconds);
        buf.writeUtf(refId);
    }

    @Override
    public void handle(ServerPlayer player) {
        ScriptEventManager.INSTANCE.handleSeek(player, scriptId, instanceId, targetSeconds);
        // 处理完即回执，发送方据此停止重发（与暂停握手同一幂等口径）
        if (!refId.isEmpty()) {
            S2CScriptSeekAckPacket.send(player, refId);
        }
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
    public double getTargetSeconds() { return targetSeconds; }
    public String getRefId() { return refId; }
}
