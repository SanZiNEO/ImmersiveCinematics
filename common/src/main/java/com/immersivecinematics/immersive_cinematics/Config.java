package com.immersivecinematics.immersive_cinematics;

/**
 * ImmersiveCinematics 跨平台配置
 * <p>
 * 静态字段存储运行时配置值。平台差异通过 {@link ConfigProvider} 接口抽象：
 * <ul>
 *   <li>Forge: {@code ForgeConfig.java} 使用 ForgeConfigSpec</li>
 *   <li>Fabric: {@code FabricConfig.java} 使用 JSON 文件</li>
 * </ul>
 * <p>
 * 配置值在游戏启动时通过 {@link #init(ConfigProvider)} 从平台 provider 加载，
 * ConfigScreen 修改时通过 setter 更新静态字段并写入持久化存储。
 */
public class Config {

    // ===== 跳过行为配置 =====

    /** 长按跳过键的判定时间（毫秒），范围 500 ~ 10000，默认 3000 */
    public static int skipHoldThresholdMs = 3000;

    // ===== UI 配置 =====

    /** 过场动画播放时显示跳过提示 */
    public static boolean showSkipHud = true;

    // ===== 跳过投票配置 =====

    /** 跳过投票所需比例（百分比），10 ~ 100，默认 100 */
    public static int skipVoteRatio = 100;

    // ===== 调试配置 =====

    /** 启用调试日志输出 */
    public static boolean debugLogging = false;

    // ===== 编辑器配置 =====

    /** 是否启用编辑器（F6 键绑定与编辑器界面；关闭即"无编辑器版本"，需重启生效） */
    public static boolean editorEnabled = true;

    // ===== 触发器轮询间隔配置 =====

    /** location 触发器的轮询间隔（tick） */
    public static int triggerPollIntervalLocation = 20;
    /** biome 触发器的轮询间隔（tick） */
    public static int triggerPollIntervalBiome = 40;
    /** inventory 触发器的轮询间隔（tick） */
    public static int triggerPollIntervalInventory = 20;
    /** structure 触发器的轮询间隔（tick） */
    public static int triggerPollIntervalStructure = 20;
    /** gamestage 触发器的轮询间隔（tick） */
    public static int triggerPollIntervalGamestage = 20;

    private static ConfigProvider provider;

    /**
     * 平台配置提供者接口。
     * <p>
     * Forge 和 Fabric 各自实现此接口，将配置读写到平台对应的持久化机制
     * （ForgeConfigSpec / JSON 文件）。
     */
    public interface ConfigProvider {
        /** 读取所有配置值，返回一个填充好的 ConfigValues 对象 */
        ConfigValues load();

        /** 持久化单个 int 配置项 */
        void setInt(String key, int value);

        /** 持久化单个 boolean 配置项 */
        void setBoolean(String key, boolean value);
    }

    /**
     * 配置值容器，由 {@link ConfigProvider#load()} 返回并应用到静态字段。
     */
    public record ConfigValues(
            int skipHoldThresholdMs,
            boolean showSkipHud,
            int skipVoteRatio,
            boolean debugLogging,
            int triggerPollIntervalLocation,
            int triggerPollIntervalBiome,
            int triggerPollIntervalInventory,
            int triggerPollIntervalStructure,
            int triggerPollIntervalGamestage,
            boolean editorEnabled
    ) {
        /** 使用默认值构造 */
        public static ConfigValues defaults() {
            return new ConfigValues(3000, true, 100, false, 20, 40, 20, 20, 20, true);
        }
    }

    /**
     * 初始化配置系统。
     * <p>
     * 从平台 provider 加载配置值，填充到静态字段。
     * 游戏启动时由 {@link ImmersiveCinematics#init()} 调用。
     */
    public static void init(ConfigProvider p) {
        provider = p;
        ConfigValues values = p.load();
        apply(values);
    }

    /** 将 ConfigValues 应用到静态字段 */
    private static void apply(ConfigValues values) {
        skipHoldThresholdMs = values.skipHoldThresholdMs();
        showSkipHud = values.showSkipHud();
        skipVoteRatio = values.skipVoteRatio();
        debugLogging = values.debugLogging();
        triggerPollIntervalLocation = values.triggerPollIntervalLocation();
        triggerPollIntervalBiome = values.triggerPollIntervalBiome();
        triggerPollIntervalInventory = values.triggerPollIntervalInventory();
        triggerPollIntervalStructure = values.triggerPollIntervalStructure();
        triggerPollIntervalGamestage = values.triggerPollIntervalGamestage();
        editorEnabled = values.editorEnabled();
    }

    // ===== ConfigScreen 写入接口 =====

    public static void setSkipHoldThresholdMs(int value) {
        skipHoldThresholdMs = value;
        if (provider != null) provider.setInt("skipHoldThresholdMs", value);
    }

    public static void setShowSkipHud(boolean value) {
        showSkipHud = value;
        if (provider != null) provider.setBoolean("showSkipHud", value);
    }

    public static void setDebugLogging(boolean value) {
        debugLogging = value;
        if (provider != null) provider.setBoolean("debugLogging", value);
    }

    public static void setEditorEnabled(boolean value) {
        editorEnabled = value;
        if (provider != null) provider.setBoolean("editorEnabled", value);
    }
}
