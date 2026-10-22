package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：脚本暂停/恢复状态同步（载荷带内容相对位置）。
 *
 * 客户端在「本实例暂停中」状态转换那一帧发送：游戏暂停（{@code pause_when_game_paused}）或规则挂起
 * （{@code until} 等待 / {@code pause} 动作）任一成立即冻结服务端 EVENT 时间线。随包带上本实例的
 * 内容相对位置（片段起始秒 + 片段内偏移秒），服务端据此把 EVENT 时间线的基准对到客户端真实位置
 * （位置即真源，恢复从该位置续，不再靠墙钟 tick 推算）。
 *
 * 位置缺省 = 未上报（{@link #getPositionOffset()} 为 {@link Float#NaN}，客户端未提供 / 位置不可得）：
 * 服务端保持既有基准不动（回落 tick 推算，不卡事件）。无片段覆盖（片段间隙）时片段起始秒为 NaN、
 * 偏移即绝对秒——见 {@code ScriptPlayer#contentPosition}。
 *
 * {@code instanceId} 为 {@link S2CPlayScriptPacket#getInstanceId()} 的原样回传，
 * 服务端据此在账本中按 {@code (scriptId, instanceId)} 精确解析暂停信号所属的播放实例。
 * 携带 refId 做 ACK 握手（服务端回 {@link S2CScriptPauseAckPacket} 确认）。
 */
public class C2SScriptPausePacket implements CinematicC2SPacket {

    private final String scriptId;
    /** 播放实例 id；空串=无实例 id 的来源 */
    private final String instanceId;
    private final boolean paused;
    private final String refId;
    /** 内容相对位置：片段起始秒（NaN = 无片段覆盖，位置即 {@link #positionOffset} 的绝对秒） */
    private final float positionClipStart;
    /** 内容相对位置：片段内偏移秒（NaN = 未上报 → 服务端保持既有基准不动） */
    private final float positionOffset;

    public C2SScriptPausePacket(String scriptId, String instanceId, boolean paused, String refId,
                                float positionClipStart, float positionOffset) {
        this.scriptId = scriptId;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.paused = paused;
        this.refId = refId;
        this.positionClipStart = positionClipStart;
        this.positionOffset = positionOffset;
    }

    public C2SScriptPausePacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
        this.paused = buf.readBoolean();
        this.refId = buf.readUtf();
        this.positionClipStart = buf.readFloat();
        this.positionOffset = buf.readFloat();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId);
        buf.writeBoolean(paused);
        buf.writeUtf(refId);
        buf.writeFloat(positionClipStart);
        buf.writeFloat(positionOffset);
    }

    @Override
    public void handle(ServerPlayer player) {
        ScriptEventManager.INSTANCE.handlePause(player, scriptId, instanceId, paused,
                positionClipStart, positionOffset);
        // 处理成功后回执，发送方据此停止重发
        if (refId != null && !refId.isEmpty()) {
            S2CScriptPauseAckPacket.send(player, refId);
        }
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
    public boolean isPaused() { return paused; }
    public String getRefId() { return refId; }

    /** 内容相对位置：片段起始秒（NaN = 无片段覆盖）。 */
    public float getPositionClipStart() { return positionClipStart; }

    /** 内容相对位置：片段内偏移秒（NaN = 未上报）。 */
    public float getPositionOffset() { return positionOffset; }
}
