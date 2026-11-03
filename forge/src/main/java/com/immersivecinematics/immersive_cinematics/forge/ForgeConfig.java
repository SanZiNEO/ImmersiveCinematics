package com.immersivecinematics.immersive_cinematics.forge;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Forge 平台配置实现。
 *
 * 配置由 {@link ForgeConfigSpec} 管理，在 {@link ModConfigEvent} 触发时重新加载值；
 * 实例在 {@link ImmersiveCinematicsForge} 构造函数中创建并注册。
 *
 * 坑：ForgeConfigSpec 的值在 {@link ModConfigEvent} 触发前不可调用 {@code get()}，
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

    // ===== 跳过 HUD 布局配置 =====

    // 坐标指元素中心：左上角表达不了「水平居中」，只有中心点能同时覆盖贴边与居中两种摆法。
    private static final ForgeConfigSpec.IntValue SKIP_HUD_X = BUILDER
            .comment("跳过长按块中心（环形 + 图标 + 文案）的屏幕横坐标",
                    "元素中心的屏幕百分比坐标（0~100），左上 (0,0) 右下 (100,100)；不做越界保护")
            .defineInRange("skipHudX", 95, 0, 100);

    private static final ForgeConfigSpec.IntValue SKIP_HUD_Y = BUILDER
            .comment("跳过长按块中心（环形 + 图标 + 文案）的屏幕纵坐标",
                    "元素中心的屏幕百分比坐标（0~100），左上 (0,0) 右下 (100,100)；不做越界保护")
            .defineInRange("skipHudY", 90, 0, 100);

    // 票数与长按块是两个独立元素，各有一套中心坐标，互不影响。
    private static final ForgeConfigSpec.IntValue VOTE_HUD_X = BUILDER
            .comment("票数文本中心的屏幕横坐标",
                    "元素中心的屏幕百分比坐标（0~100），左上 (0,0) 右下 (100,100)；不做越界保护")
            .defineInRange("voteHudX", 50, 0, 100);

    private static final ForgeConfigSpec.IntValue VOTE_HUD_Y = BUILDER
            .comment("票数文本中心的屏幕纵坐标",
                    "元素中心的屏幕百分比坐标（0~100），左上 (0,0) 右下 (100,100)；不做越界保护")
            .defineInRange("voteHudY", 90, 0, 100);

    // ===== 调试配置 =====

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("启用调试日志输出")
            .define("debugLogging", false);

    // ===== 相机中心同步配置 =====

    private static final ForgeConfigSpec.IntValue CAMERA_CENTER_REPORT_INTERVAL = BUILDER
            .comment("相机中心位置上报间隔（tick）；位移 ≥1 格或相机模式翻转时会提前发送")
            .defineInRange("cameraCenterReportInterval", 5, 1, 600);

    // ===== 规则命令信任边界配置 =====

    private static final ForgeConfigSpec.IntValue RULE_COMMAND_PERMISSION_LEVEL = BUILDER
            .comment("规则动作 emit_command 的权限上限（0 ~ 4），默认 2",
                    "只影响规则命令；EVENT 轨仍为权限 4，脚本不能覆盖")
            .defineInRange("ruleCommandPermissionLevel", 2, 0, 4);

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
                SKIP_HUD_X.get(),
                SKIP_HUD_Y.get(),
                VOTE_HUD_X.get(),
                VOTE_HUD_Y.get(),
                DEBUG_LOGGING.get(),
                TRIGGER_POLL_LOCATION.get(),
                TRIGGER_POLL_BIOME.get(),
                TRIGGER_POLL_INVENTORY.get(),
                TRIGGER_POLL_STRUCTURE.get(),
                TRIGGER_POLL_GAMESTAGE.get(),
                CAMERA_CENTER_REPORT_INTERVAL.get(),
                RULE_COMMAND_PERMISSION_LEVEL.get()
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
            case "ruleCommandPermissionLevel" -> {
                RULE_COMMAND_PERMISSION_LEVEL.set(value);
                RULE_COMMAND_PERMISSION_LEVEL.save();
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
