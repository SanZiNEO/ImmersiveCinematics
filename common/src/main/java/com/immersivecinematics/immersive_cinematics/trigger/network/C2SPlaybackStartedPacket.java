package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerEngine;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * 客户端 → 服务端播放回执，承载两件事，二者可分离：
 * <ul>
 *   <li><b>传输层 ACK</b>：{@link AckTracker#ack} —— 只要包被处理就回执（含“排队等待/被拒绝”，
 *       否则发送方的 {@code AckTracker.expect} 会超时重发 play 包，客户端会重复入队）。</li>
 *   <li><b>播放账本</b>：{@link TriggerEngine#onPlaybackStarted} —— 只有脚本**真正开始播放**时才记账，
 *       由 {@link #isStarted()} 控制。排队等待 / 被拒绝 / 编辑器预览都必须带 {@code started=false}，
 *       否则服务端 {@code ScriptEventManager} 的观看者集合与触发去重（{@code shouldSkip}）会与实际播放状态错位。</li>
 * </ul>
 */
public class C2SPlaybackStartedPacket implements CinematicC2SPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String scriptId;
    private final String refId;
    /** true=脚本已真正开始播放（记入服务端账本）；false=仅传输层 ACK（排队/拒绝，不记账） */
    private final boolean started;

    public C2SPlaybackStartedPacket(String scriptId) {
        this(scriptId, "", true);
    }

    public C2SPlaybackStartedPacket(String scriptId, String refId) {
        this(scriptId, refId, true);
    }

    public C2SPlaybackStartedPacket(String scriptId, String refId, boolean started) {
        this.scriptId = scriptId;
        this.refId = refId;
        this.started = started;
    }

    public C2SPlaybackStartedPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.refId = buf.readUtf();
        this.started = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(refId);
        buf.writeBoolean(started);
    }

    @Override
    public void handle(ServerPlayer player) {
        // N1：play 包的自然回执（传输层——无论是否真的开始播放都要 ACK，避免发送方超时重发）
        AckTracker.ack(refId);
        if (started) {
            TriggerEngine.INSTANCE.onPlaybackStarted(player, scriptId);
        } else {
            LOGGER.debug("Playback not started on client (queued/rejected), ledger unchanged: player={} script={}",
                    player.getName().getString(), scriptId);
        }
    }

    public String getScriptId() { return scriptId; }
    public String getRefId() { return refId; }
    public boolean isStarted() { return started; }
}
