package com.immersivecinematics.immersive_cinematics.api;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPlayScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * {@link ServerPlaybackApi} 的唯一实现：无状态，只转发到既有的服务端播放链
 * （脚本表 → 规则命令表 → 观看者授权 → 下发播放包）。
 *
 * <p>线程：仅服务端主线程（调用方负责 marshal）。
 *
 * <p>实例 id 随包下发：{@code S2CPlayScriptPacket} 的 {@code refId} 同时充当实例 id（与
 * {@code /icinematics play} 同一机制），因此客户端以同一个 id 开播并把该 id 回传。不这样做的话，
 * 服务端按 {@code (scriptId, instanceId)} 签发的观看者授权永远匹配不上，客户端也无法按实例 id 停播。
 * 该 refId 未登记 {@code AckTracker}，客户端回传的传输层 ACK 会被静默忽略（{@code ack} 对未知 id 是空操作）。
 */
final class ServerPlaybackApiImpl implements ServerPlaybackApi {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public boolean playFor(ServerPlayer player, String scriptId, String instanceId) {
        if (player == null || scriptId == null || scriptId.isBlank()) {
            return false;
        }
        CinematicScript script = ScriptManager.INSTANCE.getScript(scriptId);
        if (script == null || script.getRawJson() == null) {
            LOGGER.warn("Cannot play script '{}': script={} rawJson={}",
                    scriptId, script, script != null ? "present" : "null");
            return false;
        }
        // 直接下发脚本原始 JSON：结构 / 方块来源由客户端按本地世界解析。
        String json = script.getRawJson();
        String iid = instanceId == null ? "" : instanceId;
        // 规则命令信任边界：动作表按即将下发的脚本编译；观看者授权 = 本次实际收包的目标玩家。
        ScriptEventManager.INSTANCE.primeCommandTable(script);
        ScriptEventManager.INSTANCE.createGrant(script.getId(), iid, List.of(player.getUUID()));
        boolean sent = S2CPlayScriptPacket.send(player, json, iid);
        if (!sent) {
            LOGGER.warn("脚本 '{}' 过大，未发送给 {}", scriptId, player.getName().getString());
        }
        return sent;
    }

    @Override
    public boolean stopFor(ServerPlayer player, String scriptId, String instanceId) {
        if (player == null || scriptId == null) {
            return false;
        }
        // 只派发停止包；服务端账本由客户端回执（C2SScriptFinishedPacket）按 (scriptId, instanceId) 回收。
        S2CStopScriptPacket.send(player, scriptId, instanceId == null ? "" : instanceId, "");
        return true;
    }
}
