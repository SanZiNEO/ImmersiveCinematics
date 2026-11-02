package com.immersivecinematics.immersive_cinematics.script.object;

import java.util.List;

/**
 * 新对象模型的选择器调用点名单与调用点专属字段名。
 *
 * <p>与旧 {@code selector/SelectorSchema.CALLPOINTS} 分开维护：旧名单含 {@code follow} /
 * {@code facing_origin} 等已移除字段名，新格式不得写出；{@code selector/SelectorSchema}
 * 本轮一行不改。字段名模式仍为 {@code <字段>_<调用点>}。</p>
 */
public final class ObjectCallpoints {

    /**
     * 新对象模型的调用点：{@code position_origin} / {@code look_at} / {@code yaw_base} /
     * {@code yaw_base_from} / {@code yaw_base_to} / {@code facing_target} / {@code facing_entity}。
     * 顺序 = 字段登记顺序。
     */
    public static final List<String> NEW_CALLPOINTS = List.of(
            "position_origin", "look_at", "yaw_base",
            "yaw_base_from", "yaw_base_to", "facing_target", "facing_entity");

    /**
     * 调用点专属字段名 = {@code <字段>_<调用点>}（如 {@code selector_anchor_look_at}）。
     *
     * @param field 通用字段名
     * @param callpoint 调用点名；{@code null} = 该调用点无专属字段
     * @return 调用点专属字段名；{@code callpoint} 为 {@code null} 时返回 {@code null}
     */
    public static String field(String field, String callpoint) {
        return callpoint == null ? null : field + "_" + callpoint;
    }

    private ObjectCallpoints() {}
}
