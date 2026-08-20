package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.trigger.network.S2CScriptReloadPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.ScriptFingerprint;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * N2b 编辑同步 — 服务端指纹登记 + 广播。
 * <p>
 * 编辑器模式统一使用全局脚本目录（serverDir/immersive_cinematics/scripts，与编辑器保存目录同语义），
 * 不读世界脚本目录（避免目录不一致导致的重复发包与内容错位）。
 * 收到 C2SScriptSaved 只做文件存在性校验 + 指纹登记 + 广播（零解析）。
 */
public final class ScriptSyncState {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptSync");
    private static final String GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts";
    private static final Map<String, String> fingerprints = new ConcurrentHashMap<>();

    private ScriptSyncState() {}

    public static void onScriptSaved(ServerPlayer sender, String fileName) {
        MinecraftServer server = sender.server;
        Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
        Path file = globalDir.resolve(fileName);
        if (!Files.exists(file)) {
            LOGGER.warn("C2SScriptSaved: 文件不存在（全局脚本目录）: {}", file);
            return;
        }
        try {
            byte[] content = Files.readAllBytes(file);
            fingerprints.put(fileName, ScriptFingerprint.of(content));
            // 广播 reload 给所有在线玩家（含发送者；客户端本地指纹对比去重）
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler.sendToPlayer(
                        p, new S2CScriptReloadPacket(fileName));
            }
        } catch (IOException e) {
            LOGGER.error("C2SScriptSaved: 读取失败 {}", fileName, e);
        }
    }
}
