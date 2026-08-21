package com.immersivecinematics.immersive_cinematics.fabric;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraFakeConnection;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraFakePlayer;
import com.immersivecinematics.immersive_cinematics.trigger.server.FakePlayerBootstrapper;
import net.minecraft.server.level.ServerLevel;

/**
 * Fabric 假人引导：直接走原版 {@code PlayerList.placeNewPlayer()}。
 * <p>
 * Fabric 没有 Forge 的 {@code NetworkHooks.sendMCRegistryPackets()}，
 * 假 Connection 可以骗过完整登录流程，所以这里保持最简单、最接近原版的方式。
 * <p>
 * 注意：placeNewPlayer 会把假人加进 PlayerList，公共代码之后会把它从广播列表移除。
 */
public final class FabricFakePlayerBootstrapper implements FakePlayerBootstrapper {

    @Override
    public void bootstrap(CameraFakePlayer fake, CameraFakeConnection connection, ServerLevel level) {
        level.getServer().getPlayerList().placeNewPlayer(connection, fake);
    }
}
