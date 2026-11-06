package com.immersivecinematics.immersive_cinematics.script.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 字段元数据定义。
 *
 * 单个字段的 Java 元数据；type/default/required/enum/section 全部在 Java 侧编译期可检查。
 *
 * {@code section} 为 WebUI 自动分组预留；Java 侧只做元数据声明，不消费该字段。
 *
 * @param type 字段类型标识（string / int / float / bool / enum / map / array / model ...）
 * @param defaultValue 缺省值；null = 无缺省，由消费方处理
 * @param required 是否必填；true = 缺失即校验失败
 * @param enumValues 可选值列表；仅 enum 类型非空，null 归一为空表
 * @param section WebUI 分组名；null 归一为 {@code info}
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
