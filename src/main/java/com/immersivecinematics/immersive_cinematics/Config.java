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

    // ===== 跳过行为配置 =====

    private static final ForgeConfigSpec.IntValue SKIP_HOLD_THRESHOLD_MS = BUILDER
            .comment("长按跳过键的判定时间（毫秒）", "范围: 500 ~ 10000，默认 3000")
            .defineInRange("skipHoldThresholdMs", 3000, 500, 10000);

    // ===== UI 配置 =====

    private static final ForgeConfigSpec.BooleanValue SHOW_SKIP_HUD = BUILDER
            .comment("过场动画播放时显示跳过提示（长按进度环 + 按键提示）")
            .define("showSkipHud", true);

    // ===== 调试配置 =====

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("启用调试日志输出")
            .define("debugLogging", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    // 缓存值（配置加载后填充）
    public static int skipHoldThresholdMs;
    public static boolean showSkipHud;
    public static boolean debugLogging;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        skipHoldThresholdMs = SKIP_HOLD_THRESHOLD_MS.get();
        showSkipHud = SHOW_SKIP_HUD.get();
        debugLogging = DEBUG_LOGGING.get();
    }

    // ===== GUI 写入接口 =====

    public static void setSkipHoldThresholdMs(int value) {
        skipHoldThresholdMs = value;
        SKIP_HOLD_THRESHOLD_MS.set(value);
        SKIP_HOLD_THRESHOLD_MS.save();
    }

    public static void setShowSkipHud(boolean value) {
        showSkipHud = value;
        SHOW_SKIP_HUD.set(value);
        SHOW_SKIP_HUD.save();
    }

    public static void setDebugLogging(boolean value) {
        debugLogging = value;
        DEBUG_LOGGING.set(value);
        DEBUG_LOGGING.save();
    }
}
