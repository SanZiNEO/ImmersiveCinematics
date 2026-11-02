package com.immersivecinematics.immersive_cinematics.script.schema;

import com.immersivecinematics.immersive_cinematics.script.object.Interp;
import com.immersivecinematics.immersive_cinematics.script.object.PropertyMode;
import com.immersivecinematics.immersive_cinematics.script.object.PropertyType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单个轨道对象路径的 schema 声明（{@code objects} 的条目）。
 *
 * <p>不可变；字段与 {@code schema-and-types.md} §3.2 的 {@code ObjectDef} 表一一对应。
 * 构造期做归一化：{@code interp} 缺省 {@link Interp#NONE}；空白的 {@code requires} 归一为
 * {@code null}；{@code enumValues}/{@code appliesTo} 缺省为空表；{@code type=nullable_float}
 * 时 {@code nullable} 隐含为 {@code true}。其余 {@code null} 表示“未声明”，原样保留。</p>
 *
 * <p>{@code defaultValue} 在 Java 里不能叫 {@code default}（关键字），语义等同设计文档的
 * {@code default} 列。</p>
 *
 * @param path 点分路径；track 内唯一
 * @param mode 字段形态
 * @param type 值类型
 * @param defaultValue 缺省值；{@code null} = 无缺省
 * @param interp 插值方式；{@code null} 归一为 {@link Interp#NONE}
 * @param requires 前置条件表达式（如 {@code orient=tangent}）；{@code null} = 无条件
 * @param required 是否必填
 * @param enumValues 枚举取值；仅 {@code type=enum} 使用
 * @param nullable 是否允许 {@code null}；仅 {@code type=nullable_float} 合法
 * @param min 数值下界；{@code null} = 不限制
 * @param max 数值上界；{@code null} = 不限制
 * @param section 编辑器分组；不参与运行时
 * @param group 运行时组名；组级参数与组成员取同一值
 * @param appliesTo 限定层类型/轨道子类型；空 = 全部
 * @param labelKey 编辑器 i18n 键；缺省用 {@code path}
 * @param unit 编辑器显示单位（如 {@code degree}）；不影响运行时
 */
public record ObjectDef(
        String path,
        PropertyMode mode,
        PropertyType type,
        Object defaultValue,
        Interp interp,
        String requires,
        boolean required,
        List<String> enumValues,
        boolean nullable,
        Double min,
        Double max,
        String section,
        String group,
        List<String> appliesTo,
        String labelKey,
        String unit) {

    public ObjectDef {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(type, "type");
        interp = interp == null ? Interp.NONE : interp;
        requires = requires == null || requires.isBlank() ? null : requires;
        enumValues = enumValues == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(enumValues));
        appliesTo = appliesTo == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(appliesTo));
        nullable = nullable || type == PropertyType.NULLABLE_FLOAT;
    }

    /** 开始构造一条声明。 */
    public static Builder builder(String path, PropertyMode mode, PropertyType type) {
        return new Builder(path, mode, type);
    }

    /** 可复用的声明构造器；{@link #build()} 之后实例不可变。 */
    public static final class Builder {

        private final String path;
        private final PropertyMode mode;
        private final PropertyType type;
        private Object defaultValue;
        private Interp interp;
        private String requires;
        private boolean required;
        private final List<String> enumValues = new ArrayList<>();
        private boolean nullable;
        private Double min;
        private Double max;
        private String section;
        private String group;
        private final List<String> appliesTo = new ArrayList<>();
        private String labelKey;
        private String unit;

        private Builder(String path, PropertyMode mode, PropertyType type) {
            this.path = path;
            this.mode = mode;
            this.type = type;
        }

        public Builder def(Object value) {
            this.defaultValue = value;
            return this;
        }

        public Builder interp(Interp value) {
            this.interp = value;
            return this;
        }

        public Builder requires(String value) {
            this.requires = value;
            return this;
        }

        public Builder required(boolean value) {
            this.required = value;
            return this;
        }

        public Builder enumValues(String... values) {
            this.enumValues.clear();
            if (values != null) {
                Collections.addAll(this.enumValues, values);
            }
            return this;
        }

        public Builder nullable(boolean value) {
            this.nullable = value;
            return this;
        }

        public Builder min(double value) {
            this.min = value;
            return this;
        }

        public Builder max(double value) {
            this.max = value;
            return this;
        }

        public Builder section(String value) {
            this.section = value;
            return this;
        }

        public Builder group(String value) {
            this.group = value;
            return this;
        }

        public Builder appliesTo(String... values) {
            this.appliesTo.clear();
            if (values != null) {
                Collections.addAll(this.appliesTo, values);
            }
            return this;
        }

        public Builder labelKey(String value) {
            this.labelKey = value;
            return this;
        }

        public Builder unit(String value) {
            this.unit = value;
            return this;
        }

        public ObjectDef build() {
            return new ObjectDef(path, mode, type, defaultValue, interp, requires, required,
                    enumValues, nullable, min, max, section, group, appliesTo, labelKey, unit);
        }
    }
}
