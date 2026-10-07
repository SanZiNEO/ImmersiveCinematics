package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端 → 客户端播放请求。
 * <p>
 * 两个 id 职责不同：
 * <ul>
 *   <li>{@code refId}：传输层 ACK 标识（{@link AckTracker}），用于抑制 play 包超时重发；</li>
 *   <li>{@code instanceId}：<b>播放实例 id</b>（{@code plans/0.3.6/parallel-playback.md} §3.7）。
 *       同一次播放请求对全部目标玩家是同一个 id；客户端原样回传（{@link C2SPlaybackStartedPacket}），
 *       服务端据此按实例维护播放账本（观看者 / 跳过投票 / 事件时间线）。</li>
 * </ul>
 * 最简做法（本版本）：命令路径把 refId 直接复用为 instanceId —— 一次播放请求的 refId 本就唯一，
 * 且对该请求的所有目标一致，正是实例 id 需要的语义。无 refId 的调用点（触发器
 * {@code StartPlaybackAction} 按玩家逐个发包）实例 id 留空，该脚本的全部观看者共用退化键
 * {@code (scriptId, "")} —— 与实例化之前的账本语义一致。
 */
public class S2CPlayScriptPacket implements CinematicS2CPacket {

    private final String scriptJson;
    private final String refId;
    private final String instanceId;

    public S2CPlayScriptPacket(String scriptJson) {
        this(scriptJson, "");
    }

    /** refId 复用为实例 id：同一次播放请求的 refId 唯一且对该请求的所有目标一致。 */
    public S2CPlayScriptPacket(String scriptJson, String refId) {
        this.scriptJson = scriptJson;
        this.refId = refId;
        this.instanceId = refId == null ? "" : refId;
    }

    public S2CPlayScriptPacket(FriendlyByteBuf buf) {
        this.scriptJson = buf.readUtf();
        this.refId = buf.readUtf();
        this.instanceId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptJson);
        buf.writeUtf(refId);
        buf.writeUtf(instanceId);
    }

    @Override
    public void handle() {
        // 平台网络层保证在客户端主线程执行
        ClientScriptReceiver.handlePlayScript(this);
    }

    public String getScriptJson() { return scriptJson; }
    public String getRefId() { return refId; }
    public String getInstanceId() { return instanceId; }

    public static void send(ServerPlayer player, String scriptJson) {
        NetworkHandler.sendToPlayer(player, new S2CPlayScriptPacket(scriptJson));
    }

    public static void send(ServerPlayer player, String scriptJson, String refId) {
        NetworkHandler.sendToPlayer(player, new S2CPlayScriptPacket(scriptJson, refId));
    }
}
