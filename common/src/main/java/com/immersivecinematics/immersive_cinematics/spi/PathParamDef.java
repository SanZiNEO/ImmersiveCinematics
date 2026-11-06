package com.immersivecinematics.immersive_cinematics.spi;

import com.immersivecinematics.immersive_cinematics.script.schema.PathParamKind;

import java.util.List;

/**
 * 路径曲线参数声明：一个已注册曲线类型接受的一个 JSON 键。
 *
 * 声明即契约：
 * {@link com.immersivecinematics.immersive_cinematics.script.schema.PathCurveRegistry#create}
 * 按 {@code kind} 严格读值（类型不符 = 报错），
 * {@link com.immersivecinematics.immersive_cinematics.script.schema.SchemaExporter}
 * 把同一份声明导出给编辑器（类型下拉 + 参数表单），Java 侧始终是唯一权威。
 *
 * @param name         参数名（JSON 键，不含保留键 {@code type}）
 * @param kind         取值形态
 * @param defaultValue 缺省值（{@code null} = 无缺省，由 {@link PathParams} 的取值方法给 fallback）
 * @param required     是否必填（true = JSON 里缺失即报错）
 * @param enumValues   可选值（仅 {@link PathParamKind#ENUM} 非空，null 归一为空表）
 * @param doc          面向作者 / 编辑器的说明（null 归一为空串）
 */
public record PathParamDef(String name, PathParamKind kind, Object defaultValue, boolean required,
                           List<String> enumValues, String doc) {

    public PathParamDef {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        doc = doc == null ? "" : doc;
    }

    /** 可选参数（无缺省值、非必填）。 */
    public PathParamDef(String name, PathParamKind kind, Object defaultValue) {
        this(name, kind, defaultValue, false, null, null);
    }

    /** 无枚举域的参数（必填性由 {@code required} 指定）。 */
    public PathParamDef(String name, PathParamKind kind, Object defaultValue, boolean required) {
        this(name, kind, defaultValue, required, null, null);
    }
}
