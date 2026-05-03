package com.immersivecinematics.immersive_cinematics;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * ImmersiveCinematics 模组配置
 * <p>
 * 当前为最小化配置，后续阶段按需扩展：
 * - 阶段2：脚本播放相关配置（默认播放速度、循环行为等）
 * - 阶段3：网络/触发器相关配置
 * - 阶段4：编辑器相关配置
 */
@Mod.EventBusSubscriber(modid = ImmersiveCinematics.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ===== 相机系统配置 =====

    private static final ForgeConfigSpec.DoubleValue DEFAULT_FOV = BUILDER
            .comment("默认视场角（度）", "标准值: 70, 广角: 90+, 长焦: 50-")
            .defineInRange("defaultFov", 70.0, 20.0, 120.0);

    private static final ForgeConfigSpec.DoubleValue DEFAULT_ZOOM = BUILDER
            .comment("默认缩放倍率", "1.0 = 正常, >1 = 放大（望远镜效果）, <1 = 广角")
            .defineInRange("defaultZoom", 1.0, 0.1, 10.0);

    // ===== UI 配置 =====

    private static final ForgeConfigSpec.BooleanValue SHOW_SKIP_HUD = BUILDER
            .comment("过场动画播放时显示跳过提示（长按进度环 + 按键提示）")
            .define("showSkipHud", true);

    // ===== 调试配置 =====

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("启用调试日志输出")
            .define("debugLogging", false);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // 缓存值（配置加载后填充）
    public static double defaultFov;
    public static double defaultZoom;
    public static boolean showSkipHud;
    public static boolean debugLogging;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        defaultFov = DEFAULT_FOV.get();
        defaultZoom = DEFAULT_ZOOM.get();
        showSkipHud = SHOW_SKIP_HUD.get();
        debugLogging = DEBUG_LOGGING.get();
    }
}
