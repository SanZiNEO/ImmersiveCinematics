package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.server.level.ServerLevel;

/**
 * 假人引导器（平台差异隔离）。
 * <p>
 * Fabric/Forge 的玩家登录链路不同：
 * <ul>
 *   <li>Fabric 可以直接走 {@code PlayerList.placeNewPlayer()}，假 Connection 能骗过去；</li>
 *   <li>Forge 的 {@code placeNewPlayer()} 内部会执行 {@code NetworkHooks.sendMCRegistryPackets()}，
 *       要求真实 Netty channel，假 Connection 会直接挂，所以 Forge 必须手动拼“最小可用假玩家”。</li>
 * </ul>
 * 各平台按自己能跑通的方式实现，不要强行统一。
 */
public interface FakePlayerBootstrapper {

    /**
     * 把假玩家接入服务端世界，使其能参与区块加载/刷怪/实体跟踪。
     *
     * @param fake       已创建的隐藏假玩家
     * @param connection 假玩家专用的转发连接（会把假人视角的包转给真实客户端）
     * @param level      假人所处的服务端世界
     */
    void bootstrap(CameraFakePlayer fake, CameraFakeConnection connection, ServerLevel level);
}
