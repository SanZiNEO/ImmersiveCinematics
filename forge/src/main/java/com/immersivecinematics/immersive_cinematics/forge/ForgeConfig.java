package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Forge 平台配置实现。
 * <p>
 * 使用 ForgeConfigSpec 管理配置，在 {@link ModConfigEvent} 触发时重新加载值。
 * 在 {@link ImmersiveCinematicsForge} 构造函数中创建并注册。
 * <p>
 * 注意：ForgeConfigSpec 的值在 {@link ModConfigEvent} 触发前不可调用 {@code get()}，
 * 因此首次 {@link #load()} 返回 {@link Config.ConfigValues#defaults()}。
 */
public class ForgeConfig implements Config.ConfigProvider {

    public static final ForgeConfig INSTANCE = new ForgeConfig();

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

    // ===== 编辑器配置 =====

    private static final ForgeConfigSpec.BooleanValue EDITOR_ENABLED = BUILDER
            .comment("是否启用编辑器（F6 键绑定与编辑器界面；关闭即无编辑器版本，需重启生效）")
            .define("editorEnabled", true);

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

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private boolean loaded = false;

    private ForgeConfig() {}

    @Override
    public Config.ConfigValues load() {
        // ForgeConfigSpec.get() 在 ModConfigEvent 触发前不可调用
        if (!loaded) return Config.ConfigValues.defaults();
        return new Config.ConfigValues(
                SKIP_HOLD_THRESHOLD_MS.get(),
                SHOW_SKIP_HUD.get(),
                SKIP_VOTE_RATIO.get(),
                DEBUG_LOGGING.get(),
                TRIGGER_POLL_LOCATION.get(),
                TRIGGER_POLL_BIOME.get(),
                TRIGGER_POLL_INVENTORY.get(),
                TRIGGER_POLL_STRUCTURE.get(),
                TRIGGER_POLL_GAMESTAGE.get(),
                EDITOR_ENABLED.get()
        );
    }

    @Override
    public void setInt(String key, int value) {
        switch (key) {
            case "skipHoldThresholdMs" -> {
                SKIP_HOLD_THRESHOLD_MS.set(value);
                SKIP_HOLD_THRESHOLD_MS.save();
            }
            case "skipVoteRatio" -> {
                SKIP_VOTE_RATIO.set(value);
                SKIP_VOTE_RATIO.save();
            }
            case "triggerPollIntervalLocation" -> {
                TRIGGER_POLL_LOCATION.set(value);
                TRIGGER_POLL_LOCATION.save();
            }
            case "triggerPollIntervalBiome" -> {
                TRIGGER_POLL_BIOME.set(value);
                TRIGGER_POLL_BIOME.save();
            }
            case "triggerPollIntervalInventory" -> {
                TRIGGER_POLL_INVENTORY.set(value);
                TRIGGER_POLL_INVENTORY.save();
            }
            case "triggerPollIntervalStructure" -> {
                TRIGGER_POLL_STRUCTURE.set(value);
                TRIGGER_POLL_STRUCTURE.save();
            }
            case "triggerPollIntervalGamestage" -> {
                TRIGGER_POLL_GAMESTAGE.set(value);
                TRIGGER_POLL_GAMESTAGE.save();
            }
        }
    }

    @Override
    public void setBoolean(String key, boolean value) {
        switch (key) {
            case "showSkipHud" -> {
                SHOW_SKIP_HUD.set(value);
                SHOW_SKIP_HUD.save();
            }
            case "debugLogging" -> {
                DEBUG_LOGGING.set(value);
                DEBUG_LOGGING.save();
            }
            case "editorEnabled" -> {
                EDITOR_ENABLED.set(value);
                EDITOR_ENABLED.save();
            }
        }
    }

    @Mod.EventBusSubscriber(modid = ImmersiveCinematics.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class Events {
        @SubscribeEvent
        public static void onModConfig(final ModConfigEvent event) {
            if (event.getConfig().getSpec() == SPEC) {
                INSTANCE.loaded = true;
                Config.init(INSTANCE);
            }
        }
    }
}
