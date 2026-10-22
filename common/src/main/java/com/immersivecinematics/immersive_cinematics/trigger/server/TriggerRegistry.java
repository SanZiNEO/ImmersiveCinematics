package com.immersivecinematics.immersive_cinematics.trigger.server;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 触发器类型注册表：按 id 全局登记 {@link TriggerType}，脚本加载与规则求值都从这里查类型。
 * 静态表不加同步：只在服务端初始化阶段写，运行期只读。
 */
public class TriggerRegistry {

    private static final Map<String, TriggerType> TYPES = new HashMap<>();

    /** 登记一个类型；同 id 后注册者覆盖先注册者。 */
    public static void register(TriggerType type) {
        TYPES.put(type.getId(), type);
    }

    /** 按 id 取类型；未注册返回 {@code null}（调用方须按“该触发器永不命中”处理）。 */
    public static TriggerType get(String typeId) {
        return TYPES.get(typeId);
    }

    /** 全部已注册类型（活视图，遍历期间不得注册）。 */
    public static Collection<TriggerType> getAll() {
        return TYPES.values();
    }

    /** 清空注册表；清空后所有触发器类型的求值都会落空（未知类型按未命中处理）。 */
    public static void clear() {
        TYPES.clear();
    }
}
