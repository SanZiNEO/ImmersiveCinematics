package com.immersivecinematics.immersive_cinematics.editor.fields;

import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;

/**
 * 字段应使用的编辑器控件类型（0.3.5 收尾 / WebUI 预留）。
 * <p>
 * 由 {@link FieldDef} 决定，游戏内编辑器与未来 WebUI 共用同一套决策：
 * <ul>
 *   <li>bool → 开关</li>
 *   <li>tristate → 三态切换</li>
 *   <li>enum（≤3 值）→ 循环切换</li>
 *   <li>enum（>3 值）→ 下拉</li>
 *   <li>基础类型 → 对应文本/数字/映射组件</li>
 * </ul>
 */
public enum FieldControl {

    TOGGLE,
    TRISTATE,
    DROPDOWN,
    CYCLE,
    TEXT,
    FLOAT,
    INT,
    MAP,
    OBJECT,
    ARRAY,
    POSITION,
    BEZIER_CURVE,
    UNKNOWN;

    public static FieldControl of(FieldDef def) {
        if (def == null) return UNKNOWN;
        return switch (def.type()) {
            case "bool" -> TOGGLE;
            case "tristate" -> TRISTATE;
            case "enum" -> def.enumValues().size() > 3 ? DROPDOWN : CYCLE;
            case "int" -> INT;
            case "float" -> FLOAT;
            case "string" -> TEXT;
            case "map" -> MAP;
            case "object" -> OBJECT;
            case "array" -> ARRAY;
            case "position" -> POSITION;
            case "bezier_curve" -> BEZIER_CURVE;
            default -> UNKNOWN;
        };
    }
}
