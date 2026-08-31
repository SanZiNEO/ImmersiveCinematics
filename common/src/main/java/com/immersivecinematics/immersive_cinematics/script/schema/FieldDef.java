package com.immersivecinematics.immersive_cinematics.script.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 字段元数据定义（0.3.5 第5轮 5B）。
 * <p>
 * 单个字段的 Java 元数据；type/default/required/enum/section
 * 全部在 Java 侧编译期可检查。
 * <p>
 * {@code section} 为未来 WebUI / 自动分组预留；当前游戏内编辑器使用 {@code FieldGroup}，
 * 不直接消费 section。
 */
public record FieldDef(String type, Object defaultValue, boolean required,
                       List<String> enumValues, String section) {

    public FieldDef {
        enumValues = enumValues == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(enumValues));
        section = section == null ? "info" : section;
    }

    public FieldDef(String type, Object defaultValue, boolean required, List<String> enumValues) {
        this(type, defaultValue, required, enumValues, "info");
    }

    public FieldDef(String type, Object defaultValue, boolean required) {
        this(type, defaultValue, required, Collections.emptyList(), "info");
    }

    public FieldDef(String type, Object defaultValue) {
        this(type, defaultValue, false, Collections.emptyList(), "info");
    }
}
