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

    // ===== 跳过投票配置 =====

    private static final ForgeConfigSpec.IntValue SKIP_VOTE_RATIO = BUILDER
            .comment("跳过投票所需比例（百分比），全部玩家投票后跳过才生效",
                    "例: 100 = 所有玩家必须投跳过, 50 = 半数即可")
            .defineInRange("skipVoteRatio", 100, 10, 100);

    // ===== 调试配置 =====

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("启用调试日志输出")
            .define("debugLogging", false);

    // ===== 触发器轮询间隔配置 =====

    private static final ForgeConfigSpec.IntValue TRIGGER_POLL_LOCATION = BUILDER
            .comment("location 触发器的轮询间隔（tick，20 tick = 1 秒）")
            .defineInRange("triggerPollInterval_location", 20, 1, 600);

    private static final ForgeConfigSpec.IntValue TRIGGER_POLL_BIOME = BUILDER
            .comment("biome 触发器的轮询间隔（tick）")
            .defineInRange("triggerPollInterval_biome", 40, 1, 600);

    private static final ForgeConfigSpec.IntValue TRIGGER_POLL_INVENTORY = BUILDER
            .comment("inventory 触发器的轮询间隔（tick）")
            .defineInRange("triggerPollInterval_inventory", 20, 1, 600);

    private static final ForgeConfigSpec.IntValue TRIGGER_POLL_STRUCTURE = BUILDER
            .comment("structure 触发器的轮询间隔（tick）")
            .defineInRange("triggerPollInterval_structure", 20, 1, 600);

    private static final ForgeConfigSpec.IntValue TRIGGER_POLL_GAMESTAGE = BUILDER
            .comment("gamestage 触发器的轮询间隔（tick）")
            .defineInRange("triggerPollInterval_gamestage", 20, 1, 600);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    // 缓存值（配置加载后填充）
    public static int skipHoldThresholdMs;
    public static boolean showSkipHud;
    public static int skipVoteRatio;
    public static boolean debugLogging;
    public static int triggerPollIntervalLocation;
    public static int triggerPollIntervalBiome;
    public static int triggerPollIntervalInventory;
    public static int triggerPollIntervalStructure;
    public static int triggerPollIntervalGamestage;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        skipHoldThresholdMs = SKIP_HOLD_THRESHOLD_MS.get();
        showSkipHud = SHOW_SKIP_HUD.get();
        skipVoteRatio = SKIP_VOTE_RATIO.get();
        debugLogging = DEBUG_LOGGING.get();
        triggerPollIntervalLocation = TRIGGER_POLL_LOCATION.get();
        triggerPollIntervalBiome = TRIGGER_POLL_BIOME.get();
        triggerPollIntervalInventory = TRIGGER_POLL_INVENTORY.get();
        triggerPollIntervalStructure = TRIGGER_POLL_STRUCTURE.get();
        triggerPollIntervalGamestage = TRIGGER_POLL_GAMESTAGE.get();
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
