package com.immersivecinematics.immersive_cinematics.trigger.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * 网络层（0.3.5 第7轮去 Arch）。
 * <p>
 * 平台无关：由 {@link NetworkBridge} 注入实际发送实现，
 * 包 ID 常量供 Fabric/Forge 平台注册时使用。
 */
public final class NetworkHandler {

    // ===== 包 ID（平台注册用）=====

    public static final String PLAY_SCRIPT = "play_script";
    public static final String STOP_SCRIPT = "stop_script";
    public static final String TRIGGER_STATE_SYNC = "trigger_state_sync";
    public static final String SKIP_VOTE_UPDATE = "skip_vote_update";
    public static final String SCRIPT_PAUSE_ACK = "script_pause_ack";
    public static final String SCRIPT_RELOAD = "script_reload";
    public static final String PRELOAD_RESULT = "preload_result";

    public static final String SCRIPT_FINISHED = "script_finished";
    public static final String PLAYBACK_STARTED = "playback_started";
    public static final String SCRIPT_PAUSE = "script_pause";
    public static final String SCRIPT_SAVED = "script_saved";
    public static final String PRELOAD_REQ = "preload_req";
    public static final String PRELOAD_POS = "preload_pos";

    private static NetworkBridge bridge;

    private NetworkHandler() {}

    public static void setBridge(NetworkBridge b) {
        bridge = b;
    }

    public static void sendToPlayer(ServerPlayer player, CinematicS2CPacket packet) {
        if (bridge != null) bridge.sendToPlayer(player, packet);
    }

    public static void sendToServer(CinematicC2SPacket packet) {
        if (bridge != null) bridge.sendToServer(packet);
    }

    /** 兼容旧调用：由平台在 common init 前注入 bridge */
    public static void init() {
    }
}
