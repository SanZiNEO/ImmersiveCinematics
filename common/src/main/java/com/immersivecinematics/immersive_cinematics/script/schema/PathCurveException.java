package com.immersivecinematics.immersive_cinematics.script.schema;

/**
 * 路径曲线定义错误：带 JSON 字段路径的解析期异常。
 *
 * {@code fieldPath} 是出错位置（如 {@code timeline.tracks[0].clips[1].curve.control_points[0]}），
 * {@code getMessage()} 只是说明文字；调用方（{@code ScriptParser} / {@code ScriptValidator}）
 * 用两者拼出与其它字段同一口径的报错。
 */
public final class PathCurveException extends RuntimeException {

    /** 出错字段的 JSON 路径。 */
    private final String fieldPath;

    public PathCurveException(String fieldPath, String message) {
        super(message);
        this.fieldPath = fieldPath;
    }

    /** 出错字段的 JSON 路径。 */
    public String fieldPath() {
        return fieldPath;
    }
}
