package com.immersivecinematics.immersive_cinematics.script;

/** 画面层模式（脚本字段 {@code screen_layer_mode}）：normal = 逐层抑制；allowlist = 接管整帧后按白名单重画。 */
public enum ScreenLayerMode {
    NORMAL("normal"),
    ALLOWLIST("allowlist");

    private static final ScreenLayerMode[] VALUES = values();

    private final String id;

    ScreenLayerMode(String id) { this.id = id; }

    /** JSON 取值字面量。 */
    public String id() { return id; }

    /** 取值是否在域内（解析期告警 / 校验器用）。 */
    public static boolean isKnown(String value) {
        for (ScreenLayerMode mode : VALUES) {
            if (mode.id.equals(value)) return true;
        }
        return false;
    }

    /** 取值域内 → 枚举；缺省 / 域外 → {@link #NORMAL}（解析宽容：未知取值不让脚本失效）。 */
    public static ScreenLayerMode fromString(String value) {
        for (ScreenLayerMode mode : VALUES) {
            if (mode.id.equals(value)) return mode;
        }
        return NORMAL;
    }
}
