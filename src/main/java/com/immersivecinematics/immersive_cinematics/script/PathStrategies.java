package com.immersivecinematics.immersive_cinematics.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 路径策略注册表 — 按 curve.type 名称注册 PathStrategy
 * <p>
 * 默认注册：
 * <ul>
 *   <li>"bezier" → {@link BezierPathStrategy}</li>
 *   <li>"linear" → 线性插值（from.lerp(to, t)）</li>
 * </ul>
 * <p>
 * 未来扩展：注册新的 curve.type 名称和对应的 PathStrategy 实现即可，
 * 无需修改 KeyframeInterpolator。
 */
public final class PathStrategies {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/PathStrategies");

    private static final Map<String, PathStrategy> REGISTRY = new HashMap<>();

    /** 默认策略：贝塞尔曲线 */
    public static final String DEFAULT_TYPE = "bezier";

    static {
        // 注册默认策略
        register("bezier", new BezierPathStrategy());
        register("linear", (from, to, t, curve) -> from.lerp(to, t));
    }

    private PathStrategies() {}  // 工具类，禁止实例化

    /**
     * 注册路径策略
     *
     * @param type     curve.type 名称
     * @param strategy 策略实现
     */
    public static void register(String type, PathStrategy strategy) {
        if (REGISTRY.containsKey(type)) {
            LOGGER.warn("路径策略 '{}' 已存在，将被覆盖", type);
        }
        REGISTRY.put(type, strategy);
    }

    /**
     * 获取路径策略
     *
     * @param type curve.type 名称，null 或空字符串返回默认策略
     * @return 对应的 PathStrategy，未找到时返回默认策略
     */
    public static PathStrategy get(String type) {
        if (type == null || type.isEmpty()) {
            return REGISTRY.get(DEFAULT_TYPE);
        }
        PathStrategy strategy = REGISTRY.get(type);
        if (strategy == null) {
            LOGGER.warn("未知路径策略 '{}'，使用默认策略 '{}'", type, DEFAULT_TYPE);
            return REGISTRY.get(DEFAULT_TYPE);
        }
        return strategy;
    }

    /**
     * 获取已注册策略的只读视图
     */
    public static Map<String, PathStrategy> getRegistered() {
        return Collections.unmodifiableMap(REGISTRY);
    }
}
