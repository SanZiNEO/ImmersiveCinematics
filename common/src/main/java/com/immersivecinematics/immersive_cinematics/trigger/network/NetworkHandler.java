package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import dev.architectury.networking.simple.MessageType;
import dev.architectury.networking.simple.SimpleNetworkManager;

/**
 * 网络层 — 使用 Architectury SimpleNetworkManager（参考 FTB-Quests 模式）。
 * <p>
 * 定义 7 个消息类型（4 S2C + 3 C2S），在 {@code ImmsersiveCinematics.init()} 中调用 {@link #init()} 触发 static 加载。
 */
public interface NetworkHandler {
    SimpleNetworkManager NET = SimpleNetworkManager.create(ImmersiveCinematics.MOD_ID);

    // ===== S2C（服务端 → 客户端）=====

    /** 通知客户端播放脚本 */
    MessageType PLAY_SCRIPT = NET.registerS2C("play_script", S2CPlayScriptPacket::new);
    /** 通知客户端停止脚本 */
    MessageType STOP_SCRIPT = NET.registerS2C("stop_script", S2CStopScriptPacket::new);
    /** 同步触发器状态到客户端 */
    MessageType TRIGGER_STATE_SYNC = NET.registerS2C("trigger_state_sync", S2CTriggerStateSyncPacket::new);
    /** 更新跳过投票计数 */
    MessageType SKIP_VOTE_UPDATE = NET.registerS2C("skip_vote_update", S2CSkipVoteUpdatePacket::new);
    /** 暂停/恢复包 ACK 回执（N1） */
    MessageType SCRIPT_PAUSE_ACK = NET.registerS2C("script_pause_ack", S2CScriptPauseAckPacket::new);
    /** 脚本文件重载通知（N2b，S2C — 只带文件名） */
    MessageType SCRIPT_RELOAD = NET.registerS2C("script_reload", S2CScriptReloadPacket::new);

    // ===== C2S（客户端 → 服务端）=====

    /** 客户端通知服务端脚本播放完毕 */
    MessageType SCRIPT_FINISHED = NET.registerC2S("script_finished", C2SScriptFinishedPacket::new);
    /** 客户端通知服务端脚本开始播放 */
    MessageType PLAYBACK_STARTED = NET.registerC2S("playback_started", C2SPlaybackStartedPacket::new);
    /** 客户端通知服务端脚本暂停/恢复 */
    MessageType SCRIPT_PAUSE = NET.registerC2S("script_pause", C2SScriptPausePacket::new);
    /** 编辑器保存成功通知（N2b，C2S — 只带文件名） */
    MessageType SCRIPT_SAVED = NET.registerC2S("script_saved", C2SScriptSavedPacket::new);

    /** 触发 static 字段加载，SimpleNetworkManager 自动完成平台注册 */
    static void init() {
    }
}
