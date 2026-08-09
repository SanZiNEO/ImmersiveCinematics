package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptSyncState;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 编辑器保存成功通知（N2b）— 只带文件名，不含脚本内容。
 * <p>
 * 服务端只做文件存在性校验 + 指纹登记 + 广播（零解析），客户端指纹变化才解析一次。
 */
public class C2SScriptSavedPacket extends BaseC2SMessage {

    private final String fileName;

    public C2SScriptSavedPacket(String fileName) {
        this.fileName = fileName;
    }

    public C2SScriptSavedPacket(FriendlyByteBuf buf) {
        this.fileName = buf.readUtf();
    }

    @Override
    public MessageType getType() {
        return NetworkHandler.SCRIPT_SAVED;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(fileName);
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        ScriptSyncState.onScriptSaved((ServerPlayer) context.getPlayer(), fileName);
    }

    public String getFileName() { return fileName; }
}
