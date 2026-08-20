package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 相机隐藏假人：作为真实 ServerPlayer 加入服务端玩家列表，驱动原版区块加载/刷怪/despawn。
 * <ul>
 *   <li>不可见、不出现在玩家列表</li>
 *   <li>不执行玩家移动/AI，位置由 CameraMobManager 每帧钉在相机锚点</li>
 *   <li>发包走 {@link CameraFakeConnection}，全部丢弃</li>
 * </ul>
 */
public class CameraFakePlayer extends ServerPlayer {

    /** 内部专用标识名：只用于隐藏/过滤我们自己的假人，不对外泄露 */
    public static final String FAKE_PLAYER_NAME = "__ic_camera__";

    public CameraFakePlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
        setInvisible(true);
        setCustomNameVisible(false);
        setSilent(true);
        setInvulnerable(true);
        getAbilities().invulnerable = true;
    }

    @Override
    public void tick() {
        if (connection != null && level() instanceof ServerLevel serverLevel
                && serverLevel.getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            serverLevel.getChunkSource().move(this);
        }
        // 不调用 super.tick()：假人不移动、不坠落、不执行玩家逻辑
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return false;
    }
}
