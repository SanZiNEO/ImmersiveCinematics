package com.immersivecinematics.immersive_cinematics.script.object;

/**
 * 对象值转换失败：带字段路径的加载期错误。
 *
 * <p>{@link #getMessage()} 统一为 {@code <fieldPath>: <message>}，与 parser / validator 的
 * {@code <path>.value: 需要有限数字} 文案口径一致。</p>
 */
public class ObjectFormatException extends RuntimeException {

    private final String fieldPath;

    /**
     * @param fieldPath 出错字段路径（如 {@code position.x.value}）
     * @param message 具体原因（不含路径前缀）
     */
    public ObjectFormatException(String fieldPath, String message) {
        super(message);
        this.fieldPath = fieldPath;
    }

    /** 出错字段路径；构造时传入，可能为 {@code null}。 */
    public String fieldPath() {
        return fieldPath;
    }

    @Override
    public String getMessage() {
        String detail = super.getMessage();
        return fieldPath == null ? detail : fieldPath + ": " + detail;
    }
}
