package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.math.path.PathPoint;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * 路径曲线参数包：注册表按 {@link PathParamDef} 归一后的只读值表，交给曲线工厂构造
 * {@link com.immersivecinematics.immersive_cinematics.math.path.PathSpec}。
 *
 * 只由 {@link PathCurveRegistry#create} 构造：进到这里时值已按 kind 校验过，取值方法因此只做
 * 「有 / 无」判定——缺省由调用方给的 fallback 提供，必填缺失由注册表在构造前拦下。
 * 实例与内部集合都不可变（{@code numbers} 返回的数组由调用方只读）。
 */
public final class PathParams {

    /** 曲线定义的 JSON 字段路径（出错时定位用）。 */
    private final String path;
    /** 参数名 → 已归一的值（NUMBER → Double、INTEGER → Integer、POINT → PathPoint、VECTOR → Vec3 …）。 */
    private final Map<String, Object> values;

    /** 包内构造：只允许 {@link PathCurveRegistry} 建包。 */
    PathParams(String path, Map<String, Object> values) {
        this.path = path;
        this.values = values;
    }

    /** 曲线定义的 JSON 字段路径（出错时定位用）。 */
    public String path() {
        return path;
    }

    /** 有限数字参数；缺失 → {@code fallback}。 */
    public double number(String key, double fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /** 必填数字参数；缺失 → {@link PathCurveException}（路径 = {@code path.key}）。 */
    public double requiredNumber(String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new PathCurveException(path + "." + key, "缺失");
        }
        return number.doubleValue();
    }

    /** 整数参数；缺失 → {@code fallback}。 */
    public int integer(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** 布尔参数；缺失 → {@code fallback}。 */
    public boolean flag(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }

    /** 字符串参数；缺失 → {@code fallback}。 */
    public String text(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text ? text : fallback;
    }

    /** 单个控制点；缺失 → {@code null}。 */
    public PathPoint point(String key) {
        Object value = values.get(key);
        return value instanceof PathPoint point ? point : null;
    }

    /** 控制点数组；缺失 → 空表（恒非 {@code null}）。 */
    @SuppressWarnings("unchecked")
    public List<PathPoint> points(String key) {
        Object value = values.get(key);
        return value instanceof List<?> list ? (List<PathPoint>) list : List.of();
    }

    /** 有限数字数组；缺失 → 空数组（恒非 {@code null}）。 */
    public double[] numbers(String key) {
        Object value = values.get(key);
        return value instanceof double[] array ? array : new double[0];
    }

    /** 向量参数（仅 {@code {x,y,z}} 绝对形态）；缺失 → {@code fallback}。 */
    public Vec3 vector(String key, Vec3 fallback) {
        Object value = values.get(key);
        return value instanceof Vec3 vector ? vector : fallback;
    }
}
