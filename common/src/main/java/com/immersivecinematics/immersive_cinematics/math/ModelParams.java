package com.immersivecinematics.immersive_cinematics.math;

import java.util.List;
import java.util.Map;

/**
 * 模型参数包 — 归一化后的定义参数（缺省已填、越界已钳制、未知已丢弃），由注册表构造后交给工厂。
 *
 * 只读：构造后不再变更；{@code values} 的所有权归本对象。取值走类型化访问器，缺失 / 类型不符 → 调用方给的缺省。
 * 非求值路径使用（仅在定义创建时读取），不参与 step。
 */
public final class ModelParams {

    private final ValueDomain domain;
    private final Map<String, Object> values;

    /**
     * @param domain 本定义的值域（由通道或父定义给定）
     * @param values 归一化参数（键 = 参数名，值 = {@code Double} / {@link MathModel} / {@code List<MathModel>}）
     */
    public ModelParams(ValueDomain domain, Map<String, Object> values) {
        this.domain = domain;
        this.values = Map.copyOf(values);
    }

    /** 本定义的值域。 */
    public ValueDomain domain() {
        return domain;
    }

    /** 浮点参数；缺失 / 非数字 → {@code fallback}。 */
    public double number(String key, double fallback) {
        Object value = values.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    /** 单个子模型定义；未配置 / 无效 → null（调用方按恒等 linear 处置）。 */
    public MathModel model(String key) {
        Object value = values.get(key);
        return value instanceof MathModel m ? m : null;
    }

    /** 子模型数组；未配置 / 无效 → 空列表（= 恒等）。 */
    @SuppressWarnings("unchecked")
    public List<MathModel> modelList(String key) {
        Object value = values.get(key);
        return value instanceof List<?> list ? (List<MathModel>) list : List.of();
    }
}
