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

    // ===== 区块预加载配置（0.3.5 第3轮；静态默认值，平台配置文件持久化随第5轮配置/编辑器接入） =====

    /** 全局总闸：区块预加载（服务端强制；脚本可 meta.preload:false 单独关闭） */
    public static boolean preloadEnabled = true;
    /** 相机位置上报间隔（tick） */
    public static int preloadReportInterval = 20;
    /** 相机区每 tick 补发包上限（防一次性洪峰，渐续铺开） */
    public static int preloadMaxBurstPerTick = 20;
    /** 每 tick 新增区块 ticket 上限 */
    public static int preloadMaxRequestsPerTick = 8;
    /** 预加载范围上限（区块）：防止低配/过大视距导致卡顿；有效半径不会超过它 */
    public static int preloadRadiusCap = 32;
    /** 强制使用配置预设范围（忽略玩家渲染距离） */
    public static boolean preloadForceRadius = false;
    /** 强制时的预设范围（区块） */
    public static int preloadForceRadiusValue = 8;
    /** lookahead 预载：当前片段剩余多少秒开始预载下一片段 */
    public static float preloadPrewarmLeadSeconds = 2.0f;
    /** lookahead 预载：下一片段预载范围（区块，取小区域慢速铺） */
    public static int preloadPrewarmRadius = 8;
    /** lookahead 预载：每 tick 新增预载 ticket 上限（慢速，默认 6） */
    public static int preloadPrewarmRequestsPerTick = 6;

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

        /** 持久化单个 float 配置项 */
        void setFloat(String key, float value);
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
            boolean editorEnabled,
            boolean preloadEnabled,
            int preloadReportInterval,
            int preloadMaxBurstPerTick,
            int preloadMaxRequestsPerTick,
            int preloadRadiusCap,
            boolean preloadForceRadius,
            int preloadForceRadiusValue,
            float preloadPrewarmLeadSeconds,
            int preloadPrewarmRadius,
            int preloadPrewarmRequestsPerTick
    ) {
        /** 使用默认值构造 */
        public static ConfigValues defaults() {
            return new ConfigValues(3000, true, 100, false, 20, 40, 20, 20, 20, true,
                    true, 20, 20, 8, 32, false, 8, 2.0f, 8, 6);
        }
    }

    /**
     * 初始化配置系统。
     * <p>
     * 从平台 provider 加载配置值，填充到静态字段。
     * 游戏启动时由 {@link ImmersiveCinematics} 调用。
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
        preloadEnabled = values.preloadEnabled();
        preloadReportInterval = values.preloadReportInterval();
        preloadMaxBurstPerTick = values.preloadMaxBurstPerTick();
        preloadMaxRequestsPerTick = values.preloadMaxRequestsPerTick();
        preloadRadiusCap = values.preloadRadiusCap();
        preloadForceRadius = values.preloadForceRadius();
        preloadForceRadiusValue = values.preloadForceRadiusValue();
        preloadPrewarmLeadSeconds = values.preloadPrewarmLeadSeconds();
        preloadPrewarmRadius = values.preloadPrewarmRadius();
        preloadPrewarmRequestsPerTick = values.preloadPrewarmRequestsPerTick();
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

    // ===== 区块预加载配置写入接口 =====

    public static void setPreloadEnabled(boolean value) {
        preloadEnabled = value;
        if (provider != null) provider.setBoolean("preloadEnabled", value);
    }

    public static void setPreloadReportInterval(int value) {
        preloadReportInterval = value;
        if (provider != null) provider.setInt("preloadReportInterval", value);
    }

    public static void setPreloadMaxBurstPerTick(int value) {
        preloadMaxBurstPerTick = value;
        if (provider != null) provider.setInt("preloadMaxBurstPerTick", value);
    }

    public static void setPreloadMaxRequestsPerTick(int value) {
        preloadMaxRequestsPerTick = value;
        if (provider != null) provider.setInt("preloadMaxRequestsPerTick", value);
    }

    public static void setPreloadRadiusCap(int value) {
        preloadRadiusCap = value;
        if (provider != null) provider.setInt("preloadRadiusCap", value);
    }

    public static void setPreloadForceRadius(boolean value) {
        preloadForceRadius = value;
        if (provider != null) provider.setBoolean("preloadForceRadius", value);
    }

    public static void setPreloadForceRadiusValue(int value) {
        preloadForceRadiusValue = value;
        if (provider != null) provider.setInt("preloadForceRadiusValue", value);
    }

    public static void setPreloadPrewarmLeadSeconds(float value) {
        preloadPrewarmLeadSeconds = value;
        if (provider != null) provider.setFloat("preloadPrewarmLeadSeconds", value);
    }

    public static void setPreloadPrewarmRadius(int value) {
        preloadPrewarmRadius = value;
        if (provider != null) provider.setInt("preloadPrewarmRadius", value);
    }

    public static void setPreloadPrewarmRequestsPerTick(int value) {
        preloadPrewarmRequestsPerTick = value;
        if (provider != null) provider.setInt("preloadPrewarmRequestsPerTick", value);
    }
}
