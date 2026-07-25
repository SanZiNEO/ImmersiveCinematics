package com.immersivecinematics.immersive_cinematics;

import com.immersivecinematics.immersive_cinematics.handler.ClientEventHandler;
import com.immersivecinematics.immersive_cinematics.handler.ServerEventHandler;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;

public final class ImmersiveCinematics {
    public static final String MOD_ID = "immersive_cinematics";

    /** 设为 {@code false} 编译不带编辑器的轻量版（仅播放器） */
    public static final boolean EDITOR_ENABLED = true;

    /**
     * 模组初始化入口。
     * <p>
     * 按以下顺序初始化各子系统：
     * <ol>
     *   <li>配置系统（平台 provider 加载）</li>
     *   <li>网络层</li>
     *   <li>服务端事件</li>
     *   <li>客户端事件（仅在客户端执行）</li>
     * </ol>
     *
     * @param configProvider 平台相关配置提供者（ForgeConfig / FabricConfig）
     */
    public static void init(Config.ConfigProvider configProvider) {
        // === 基础 ===
        Config.init(configProvider);

        // === 网络 ===
        NetworkHandler.init();

        // === 服务端事件 ===
        ServerEventHandler.register();

        // === 客户端注册（安全，不在服务端执行） ===
        EnvExecutor.runInEnv(Env.CLIENT, () -> ClientEventHandler::register);
    }
}
