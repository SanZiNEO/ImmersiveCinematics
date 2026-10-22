package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 服务端 → 客户端播放请求。
 *
 * 两个 id 职责不同：{@code refId} 是传输层 ACK 标识（{@link AckTracker}），用于抑制 play 包超时重发；
 * {@code instanceId} 是播放实例 id，同一次播放请求对全部目标玩家是同一个 id，客户端原样回传
 * （{@link C2SPlaybackStartedPacket}），服务端据此按实例维护播放账本（观看者 / 跳过投票 / 事件时间线）。
 *
 * 命令路径把 refId 直接复用为 instanceId：一次播放请求的 refId 本就唯一，且对该请求的所有目标一致，
 * 正是实例 id 需要的语义。无 refId 的调用点（{@code StartPlaybackAction} 按玩家逐个发包）实例 id
 * 留空，该脚本的全部观看者共用退化键 {@code (scriptId, "")}。
 *
 * 脚本正文走 gzip + 长度前缀而不是 {@code writeUtf}：后者受 32,767 字符上限约束，
 * 25 机位量级的脚本（约 47 KB JSON）会被直接拒绝。
 */
public class S2CPlayScriptPacket implements CinematicS2CPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 解压后的脚本 JSON 上限。 */
    private static final int MAX_SCRIPT_JSON_BYTES = 4 * 1024 * 1024;
    /** 压缩后的脚本上限（防止畸形压缩包占满内存）。 */
    private static final int MAX_COMPRESSED_BYTES = 1024 * 1024;

    private static final int COPY_BUFFER = 8 * 1024;

    private final byte[] scriptGzip;
    private final String refId;
    private final String instanceId;

    public S2CPlayScriptPacket(String scriptJson) {
        this(scriptJson, "");
    }

    /** refId 同时充当实例 id，理由见类注释。 */
    public S2CPlayScriptPacket(String scriptJson, String refId) {
        this.scriptGzip = encode(scriptJson);
        this.refId = refId;
        this.instanceId = refId == null ? "" : refId;
    }

    public S2CPlayScriptPacket(FriendlyByteBuf buf) {
        int len = buf.readVarInt();
        if (len < 0 || len > MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("脚本压缩包长度非法：" + len);
        }
        this.scriptGzip = new byte[len];
        buf.readBytes(scriptGzip);
        this.refId = buf.readUtf();
        this.instanceId = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(scriptGzip.length);
        buf.writeBytes(scriptGzip);
        buf.writeUtf(refId);
        buf.writeUtf(instanceId);
    }

    @Override
    public void handle() {
        // 平台网络层保证在客户端主线程执行
        ClientScriptReceiver.handlePlayScript(this);
    }

    public String getScriptJson() { return decode(scriptGzip); }
    public String getRefId() { return refId; }
    public String getInstanceId() { return instanceId; }

    /** 发送前预检：返回错误消息，{@code null} 表示脚本可发送。 */
    public static String validate(String scriptJson) {
        try {
            encode(scriptJson);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** @return 是否真的发出（脚本超限时只记录日志并返回 {@code false}）。 */
    public static boolean send(ServerPlayer player, String scriptJson) {
        return send(player, scriptJson, "");
    }

    /** @return 是否真的发出（脚本超限时只记录日志并返回 {@code false}）。 */
    public static boolean send(ServerPlayer player, String scriptJson, String refId) {
        try {
            NetworkHandler.sendToPlayer(player, new S2CPlayScriptPacket(scriptJson, refId));
            return true;
        } catch (IllegalArgumentException e) {
            LOGGER.error("无法发送脚本到 {}：{}", player.getName().getString(), e.getMessage());
            return false;
        }
    }

    private static byte[] encode(String json) {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_SCRIPT_JSON_BYTES) {
            throw new IllegalArgumentException(
                    "脚本 JSON 过大：" + raw.length + " 字节，超过 " + MAX_SCRIPT_JSON_BYTES + " 字节上限");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("脚本压缩失败：" + e.getMessage(), e);
        }
        byte[] out = bos.toByteArray();
        if (out.length > MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException(
                    "脚本压缩后过大：" + out.length + " 字节，超过 " + MAX_COMPRESSED_BYTES + " 字节上限");
        }
        return out;
    }

    private static String decode(byte[] gzip) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, gzip.length * 2));
        byte[] chunk = new byte[COPY_BUFFER];
        int total = 0;
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzip))) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > MAX_SCRIPT_JSON_BYTES) {
                    throw new IllegalArgumentException(
                            "脚本解压后过大：超过 " + MAX_SCRIPT_JSON_BYTES + " 字节上限");
                }
                out.write(chunk, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("脚本解压失败：" + e.getMessage(), e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
