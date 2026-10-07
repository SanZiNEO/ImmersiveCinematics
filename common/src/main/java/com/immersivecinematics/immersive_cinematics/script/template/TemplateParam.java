package com.immersivecinematics.immersive_cinematics.script.template;

import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;

import java.util.Collections;
import java.util.List;

/**
 * 模板参数声明：{@code key} + 显示名 + 与 {@code script/schema} 同源的 {@link FieldDef}。
 *
 * <p>模板参数不新造一套参数体系：类型 / 默认值 / 枚举候选 / 分组全部走 {@link FieldDef}
 * （与轨道字段、meta 字段同一底层），因此模板参数天然可以喂给 schema 驱动的表单
 * （见 {@code plans/0.3.6/templates.md} §3.1）。</p>
 *
 * <p>参数一律"有默认值"：{@code required} 恒为 false——模板的意义就是填几个目标参数即可生成，
 * 未填写的走 {@link #defaultValue()}。</p>
 */
public record TemplateParam(String key, String label, FieldDef field) {

    public TemplateParam {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("模板参数 key 不能为空");
        }
        if (field == null) {
            throw new IllegalArgumentException("模板参数 " + key + " 缺少 FieldDef");
        }
    }

    /** 字段类型（float / int / bool / string / enum …），语义同 {@link FieldDef#type()}。 */
    public String type() {
        return field.type();
    }

    /** 未填写时使用的值。 */
    public Object defaultValue() {
        return field.defaultValue();
    }

    /** {@code type=enum} 时的候选值（其它类型为空表）。 */
    public List<String> enumValues() {
        return field.enumValues();
    }

    /** 表单分组（{@link FieldDef#section()}）。 */
    public String section() {
        return field.section();
    }

    // ── 声明快捷构造（让模板的 params() 表保持一行一个参数）──

    public static TemplateParam of(String key, String label, FieldDef field) {
        return new TemplateParam(key, label, field);
    }

    public static TemplateParam floatParam(String key, String label, float def, String section) {
        return new TemplateParam(key, label, new FieldDef("float", def, false, Collections.emptyList(), section));
    }

    public static TemplateParam floatParam(String key, String label, float def) {
        return floatParam(key, label, def, "core");
    }

    public static TemplateParam intParam(String key, String label, int def, String section) {
        return new TemplateParam(key, label, new FieldDef("int", def, false, Collections.emptyList(), section));
    }

    public static TemplateParam intParam(String key, String label, int def) {
        return intParam(key, label, def, "core");
    }

    public static TemplateParam boolParam(String key, String label, boolean def, String section) {
        return new TemplateParam(key, label, new FieldDef("bool", def, false, Collections.emptyList(), section));
    }

    public static TemplateParam stringParam(String key, String label, String def, String section) {
        return new TemplateParam(key, label, new FieldDef("string", def, false, Collections.emptyList(), section));
    }

    public static TemplateParam enumParam(String key, String label, String def, String section, String... values) {
        return new TemplateParam(key, label, new FieldDef("enum", def, false, List.of(values), section));
    }
}
