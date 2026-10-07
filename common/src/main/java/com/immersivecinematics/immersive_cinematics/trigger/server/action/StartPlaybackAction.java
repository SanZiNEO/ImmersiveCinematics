package com.immersivecinematics.immersive_cinematics.trigger.server.action;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPlayScriptPacket;
import com.immersivecinematics.immersive_cinematics.util.ScriptStructureResolver;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * 播放脚本动作（0.3.5 第3.5轮）：支持 target 广播。
 * <ul>
 *   <li>{@code target} 缺省 / {@code "player"}：只发给触发者本人（现状）</li>
 *   <li>{@code "all"}：全体在线玩家（含触发者本人）</li>
 *   <li>{@code "all_except_trigger"}：除触发者外的所有在线玩家</li>
 * </ul>
 */
public class StartPlaybackAction implements TriggerAction {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String scriptId;
    private final String target;

    public StartPlaybackAction(String scriptId) {
        this(scriptId, "player");
    }

    public StartPlaybackAction(String scriptId, String target) {
        this.scriptId = scriptId;
        this.target = target == null || target.isEmpty() ? "player" : target;
    }

    public static StartPlaybackAction fromJson(JsonObject obj) {
        String target = obj.has("target") ? obj.get("target").getAsString() : "player";
        return new StartPlaybackAction(obj.get("script_id").getAsString(), target);
    }

    @Override
    public void execute(ServerPlayer player) {
        CinematicScript script = ScriptManager.INSTANCE.getScript(scriptId);
        if (script == null || script.getRawJson() == null) {
            LOGGER.warn("Cannot play script '{}': script={} rawJson={}", scriptId, script, script != null ? "present" : "null");
            return;
        }
        // 结构/方块来源替换：与 /icinematics play 一致，推送前在服务端按触发者位置定位并替换为坐标
        // （多人服客户端无兜底解析；单人服不受影响，客户端仍保留兜底）
        final String json = ScriptStructureResolver.resolveTargets(
                script.getRawJson(), player.serverLevel(), player.position());
        switch (target) {
            case "all" -> {
                for (ServerPlayer p : player.server.getPlayerList().getPlayers()) {
                    sendTo(p, json);
                }
            }
            case "all_except_trigger" -> {
                for (ServerPlayer p : player.server.getPlayerList().getPlayers()) {
                    if (p != player) sendTo(p, json);
                }
            }
            default -> sendTo(player, json);
        }
    }

    private void sendTo(ServerPlayer p, String json) {
        LOGGER.info("Sending play packet for script '{}' to player {}", scriptId, p.getName().getString());
        S2CPlayScriptPacket.send(p, json);
    }
}
