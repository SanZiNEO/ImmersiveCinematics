package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络层：平台无关的发送入口。
 * 由 {@link NetworkBridge} 注入实际发送实现，包 ID 常量供 Fabric/Forge 平台注册时使用。
 */
public final class NetworkHandler {

    // ===== 包 ID（平台注册用）=====

    public static final String PLAY_SCRIPT = "play_script";
    public static final String STOP_SCRIPT = "stop_script";
    public static final String TRIGGER_STATE_SYNC = "trigger_state_sync";
    public static final String SKIP_VOTE_UPDATE = "skip_vote_update";
    public static final String SCRIPT_PAUSE_ACK = "script_pause_ack";
    public static final String SCRIPT_RELOAD = "script_reload";
    public static final String RESOLVE_ENTITY_SELECTOR = "resolve_entity_selector";
    public static final String RESOLVE_ENTITY_SELECTOR_RESULT = "resolve_entity_selector_result";

    public static final String SCRIPT_FINISHED = "script_finished";
    public static final String PLAYBACK_STARTED = "playback_started";
    public static final String SCRIPT_PAUSE = "script_pause";
    public static final String SCRIPT_SAVED = "script_saved";
    public static final String CAMERA_CENTER_REQUEST = "camera_center_request";
    public static final String CAMERA_CENTER_POSITION = "camera_center_position";
    public static final String AUDIO_LISTENER = "audio_listener";
    public static final String RULE_COMMAND = "rule_command";

    private static final long SEND_LOG_INTERVAL_MS = 5000L;

    private static NetworkBridge bridge;

    private NetworkHandler() {}

    /** 平台在 common init 前注入实际实现；未注入时所有发送静默丢弃。 */
    public static void setBridge(NetworkBridge b) {
        bridge = b;
    }

    /** 发给单个玩家；bridge 为空（平台未注入）时静默丢弃。 */
    public static void sendToPlayer(ServerPlayer player, CinematicS2CPacket packet) {
        if (bridge == null) return;
        try {
            bridge.sendToPlayer(player, packet);
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            String name = packet != null ? packet.getClass().getSimpleName() : "null";
            ErrorLog.logRateLimited("Net", "send:S2C:" + name, SEND_LOG_INTERVAL_MS,
                    "发送 S2C 包失败（包: " + name + "，玩家: "
                            + (player != null ? player.getGameProfile().getName() + "/" + player.getUUID() : "null")
                            + "）：该包已丢弃，播放继续", e);
        }
    }

    /** 发给服务端；bridge 为空（客户端未注入）时静默丢弃。 */
    public static void sendToServer(CinematicC2SPacket packet) {
        if (bridge == null) return;
        try {
            bridge.sendToServer(packet);
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            String name = packet != null ? packet.getClass().getSimpleName() : "null";
            ErrorLog.logRateLimited("Net", "send:C2S:" + name, SEND_LOG_INTERVAL_MS,
                    "发送 C2S 包失败（包: " + name + "，方向: C2S）：该包已丢弃，播放继续", e);
        }
    }

    /** 空入口：bridge 已由平台在 common init 前注入，此处无需动作。 */
    public static void init() {
    }
}
