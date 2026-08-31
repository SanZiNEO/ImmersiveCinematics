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

    // ===== 区块预加载配置 =====

    private static final ForgeConfigSpec.BooleanValue PRELOAD_ENABLED = BUILDER
            .comment("全局总闸：区块预加载（服务端强制；脚本可 meta.preload:false 单独关闭）")
            .define("preloadEnabled", true);

    private static final ForgeConfigSpec.IntValue PRELOAD_REPORT_INTERVAL = BUILDER
            .comment("相机位置上报间隔（tick）")
            .defineInRange("preloadReportInterval", 20, 1, 600);

    private static final ForgeConfigSpec.IntValue PRELOAD_MAX_BURST_PER_TICK = BUILDER
            .comment("相机区每 tick 补发包上限（防一次性洪峰，渐续铺开）")
            .defineInRange("preloadMaxBurstPerTick", 20, 1, 1000);

    private static final ForgeConfigSpec.IntValue PRELOAD_MAX_REQUESTS_PER_TICK = BUILDER
            .comment("每 tick 新增区块 ticket 上限")
            .defineInRange("preloadMaxRequestsPerTick", 8, 1, 1000);

    private static final ForgeConfigSpec.IntValue PRELOAD_RADIUS_CAP = BUILDER
            .comment("预加载范围上限（区块）：防止低配/过大视距导致卡顿；有效半径不会超过它")
            .defineInRange("preloadRadiusCap", 32, 1, 64);

    private static final ForgeConfigSpec.BooleanValue PRELOAD_FORCE_RADIUS = BUILDER
            .comment("强制使用配置预设范围（忽略玩家渲染距离）")
            .define("preloadForceRadius", false);

    private static final ForgeConfigSpec.IntValue PRELOAD_FORCE_RADIUS_VALUE = BUILDER
            .comment("强制时的预设范围（区块）")
            .defineInRange("preloadForceRadiusValue", 8, 1, 64);

    private static final ForgeConfigSpec.DoubleValue PRELOAD_PREWARM_LEAD_SECONDS = BUILDER
            .comment("lookahead 预载：当前片段剩余多少秒开始预载下一片段")
            .defineInRange("preloadPrewarmLeadSeconds", 2.0, 0.0, 60.0);

    private static final ForgeConfigSpec.IntValue PRELOAD_PREWARM_RADIUS = BUILDER
            .comment("lookahead 预载：下一片段预载范围（区块，取小区域慢速铺）")
            .defineInRange("preloadPrewarmRadius", 8, 1, 64);

    private static final ForgeConfigSpec.IntValue PRELOAD_PREWARM_REQUESTS_PER_TICK = BUILDER
            .comment("lookahead 预载：每 tick 新增预载 ticket 上限（慢速，默认 6）")
            .defineInRange("preloadPrewarmRequestsPerTick", 6, 1, 1000);

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
                EDITOR_ENABLED.get(),
                PRELOAD_ENABLED.get(),
                PRELOAD_REPORT_INTERVAL.get(),
                PRELOAD_MAX_BURST_PER_TICK.get(),
                PRELOAD_MAX_REQUESTS_PER_TICK.get(),
                PRELOAD_RADIUS_CAP.get(),
                PRELOAD_FORCE_RADIUS.get(),
                PRELOAD_FORCE_RADIUS_VALUE.get(),
                (float) (double) PRELOAD_PREWARM_LEAD_SECONDS.get(),
                PRELOAD_PREWARM_RADIUS.get(),
                PRELOAD_PREWARM_REQUESTS_PER_TICK.get()
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
            case "preloadReportInterval" -> {
                PRELOAD_REPORT_INTERVAL.set(value);
                PRELOAD_REPORT_INTERVAL.save();
            }
            case "preloadMaxBurstPerTick" -> {
                PRELOAD_MAX_BURST_PER_TICK.set(value);
                PRELOAD_MAX_BURST_PER_TICK.save();
            }
            case "preloadMaxRequestsPerTick" -> {
                PRELOAD_MAX_REQUESTS_PER_TICK.set(value);
                PRELOAD_MAX_REQUESTS_PER_TICK.save();
            }
            case "preloadRadiusCap" -> {
                PRELOAD_RADIUS_CAP.set(value);
                PRELOAD_RADIUS_CAP.save();
            }
            case "preloadForceRadiusValue" -> {
                PRELOAD_FORCE_RADIUS_VALUE.set(value);
                PRELOAD_FORCE_RADIUS_VALUE.save();
            }
            case "preloadPrewarmRadius" -> {
                PRELOAD_PREWARM_RADIUS.set(value);
                PRELOAD_PREWARM_RADIUS.save();
            }
            case "preloadPrewarmRequestsPerTick" -> {
                PRELOAD_PREWARM_REQUESTS_PER_TICK.set(value);
                PRELOAD_PREWARM_REQUESTS_PER_TICK.save();
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
            case "preloadEnabled" -> {
                PRELOAD_ENABLED.set(value);
                PRELOAD_ENABLED.save();
            }
            case "preloadForceRadius" -> {
                PRELOAD_FORCE_RADIUS.set(value);
                PRELOAD_FORCE_RADIUS.save();
            }
        }
    }

    @Override
    public void setFloat(String key, float value) {
        switch (key) {
            case "preloadPrewarmLeadSeconds" -> {
                PRELOAD_PREWARM_LEAD_SECONDS.set((double) value);
                PRELOAD_PREWARM_LEAD_SECONDS.save();
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
