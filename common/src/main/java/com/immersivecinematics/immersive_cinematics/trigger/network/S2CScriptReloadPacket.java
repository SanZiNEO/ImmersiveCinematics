package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 → 客户端：脚本文件已保存，请对比指纹并按需重载（N2b）。
 * <p>
 * 客户端读本地全局脚本目录同名文件，指纹相同则忽略（不解析不重算）。
 */
public class S2CScriptReloadPacket implements CinematicS2CPacket {

    private final String fileName;

    public S2CScriptReloadPacket(String fileName) {
        this.fileName = fileName;
    }

    public S2CScriptReloadPacket(FriendlyByteBuf buf) {
        this.fileName = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(fileName);
    }

    @Override
    public void handle() {
        ClientScriptReceiver.handleScriptReload(this);
    }

    public String getFileName() { return fileName; }
}
