package com.immersivecinematics.immersive_cinematics.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 路径策略注册表 — 按 curve.type 名称注册 PathStrategy
 * <p>
 * 默认注册：
 * <ul>
 *   <li>"bezier" → {@link BezierPathStrategy}</li>
 *   <li>"linear" → 线性插值（from.lerp(to, t)）</li>
 * </ul>
 * <p>
 * 默认策略为 "linear"：当 curveType 为 null 或未知时，使用最简单的线性插值，
 * 语义更清晰（null/未知 → 最简路径）。显式指定 "bezier" 不受影响。
 * <p>
 * 未来扩展：注册新的 curve.type 名称和对应的 PathStrategy 实现即可，
 * 无需修改 KeyframeInterpolator。
 */
public final class PathStrategies {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/PathStrategies");

    /** 策略工厂注册表：存储 Supplier 以便每次获取新实例 */
    private static final Map<String, Supplier<PathStrategy>> REGISTRY = new HashMap<>();

    /** 默认策略：线性插值 */
    public static final String DEFAULT_TYPE = "linear";

    static {
        // 注册默认策略（linear 无状态，可复用同一实例）
        register("linear", () -> (from, to, t, curve) -> from.lerp(to, t));
    }

    private PathStrategies() {}  // 工具类，禁止实例化

    /**
     * 注册路径策略工厂
     * <p>
     * 使用 Supplier 而不是直接存储实例，允许每次获取时创建新实例。
     * 无状态策略（如 linear）可返回同一个 lambda 实例。
     *
     * @param type     curve.type 名称
     * @param factory  策略工厂
     */
    public static void register(String type, Supplier<PathStrategy> factory) {
        if (REGISTRY.containsKey(type)) {
            LOGGER.warn("路径策略 '{}' 已存在，将被覆盖", type);
        }
        REGISTRY.put(type, factory);
    }

    /**
     * 获取路径策略
     * <p>
     * 每次调用返回一个新的策略实例（有状态的策略如 BezierPathStrategy 不会累积缓存）。
     *
     * @param type curve.type 名称，null 或空字符串返回默认策略
     * @return 对应的 PathStrategy 新实例，未找到时返回默认策略
     */
    public static PathStrategy get(String type) {
        if (type == null || type.isEmpty()) {
            return REGISTRY.get(DEFAULT_TYPE).get();
        }
        Supplier<PathStrategy> factory = REGISTRY.get(type);
        if (factory == null) {
            LOGGER.warn("未知路径策略 '{}'，使用默认策略 '{}'", type, DEFAULT_TYPE);
            return REGISTRY.get(DEFAULT_TYPE).get();
        }
        return factory.get();
    }

    /**
     * 获取已注册策略名称的只读视图
     */
    public static Map<String, Supplier<PathStrategy>> getRegistered() {
        return Collections.unmodifiableMap(REGISTRY);
    }
}
