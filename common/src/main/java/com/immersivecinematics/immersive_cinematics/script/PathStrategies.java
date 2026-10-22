package com.immersivecinematics.immersive_cinematics.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 路径策略注册表 —— 按 {@code curve.type} 名称注册 {@link PathStrategy} 工厂。
 *
 * 默认注册 {@code "bezier"}（{@link BezierPathStrategy}）与 {@code "linear"}（{@code from.lerp(to, t)}）；
 * 未注册或 {@code type} 为 null / 空时回落 {@code "linear"}（最简路径），并记一条 WARN。
 * 工厂按次返回新实例：有状态策略（{@code BezierPathStrategy} 的 LUT 缓存）不会跨次累积。
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
        // bezier 有状态（内部缓存 ArcLengthLUT），每次获取新实例
        register("bezier", BezierPathStrategy::new);
    }

    private PathStrategies() {}

    /**
     * 注册路径策略工厂（同名覆盖并记 WARN）。
     *
     * @param type    {@code curve.type} 名称
     * @param factory 策略工厂
     */
    public static void register(String type, Supplier<PathStrategy> factory) {
        if (REGISTRY.containsKey(type)) {
            LOGGER.warn("路径策略 '{}' 已存在，将被覆盖", type);
        }
        REGISTRY.put(type, factory);
    }

    /**
     * 获取路径策略的新实例。
     *
     * @param type {@code curve.type} 名称；null 或空字符串返回默认策略
     * @return 对应的 {@link PathStrategy} 新实例；未注册时回落 {@code "linear"} 并记 WARN
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
     * 获取已注册策略名称的只读视图。
     *
     * @return 策略名 → 工厂（不可修改）
     */
    public static Map<String, Supplier<PathStrategy>> getRegistered() {
        return Collections.unmodifiableMap(REGISTRY);
    }
}
