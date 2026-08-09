package com.immersivecinematics.immersive_cinematics.editor;

/**
 * 编辑器统一主题——集中管理所有颜色常量与轨道配色。
 * <p>
 * 替换前各 widget/area 文件中的魔法数颜色（B1）；颜色值保持与原实现一致
 * （唯一例外：{@link #GHOST_BORDER} 由半透明改为不透明，见 E8 拖拽抬起质感）。
 */
public final class EditorTheme {

    private EditorTheme() {}

    // ===== 背景 =====
    public static final int BG_MAIN       = 0xFF171717;  // 时间轴底
    public static final int BG_PANEL      = 0xFF1F1F1F;  // 菜单栏/标尺/面板头
    public static final int BG_WIDGET     = 0xFF222222;  // 按钮/输入框底
    public static final int BG_HOVER      = 0xFF2A2A2A;  // 悬停/菜单底
    public static final int BG_TRACK      = 0xFF1A1A1A;  // 轨道标签底
    public static final int BG_PREVIEW    = 0xFF111111;  // 预览底

    // ===== 边框 =====
    public static final int BORDER        = 0xFF333333;  // 常规描边
    public static final int BORDER_LIGHT  = 0xFF3A3A3A;  // 预览框/悬停描边
    public static final int BORDER_DARK   = 0xFF2A2A2A;

    // ===== 文字 =====
    public static final int TEXT_PRIMARY   = 0xFFCCCCCC;
    public static final int TEXT_SECONDARY = 0xFF888888;
    public static final int TEXT_DISABLED  = 0xFF555555;
    public static final int TEXT_MUTED     = 0xFF999999;
    public static final int TEXT_DIM       = 0xFF777777;

    // ===== 强调/状态 =====
    public static final int ACCENT          = 0xFF3A6DB5;  // 主强调（CAMERA 蓝）
    public static final int ACCENT_HOVER    = 0xFF5A8DD5;
    public static final int SELECTED        = 0xFFFFDD44;  // 选中金
    public static final int SUCCESS         = 0xFF44AA44;
    public static final int DANGER          = 0xFFAA4444;

    // ===== 滚动条 =====
    public static final int SCROLLBAR_BG    = 0xFF222222;
    public static final int SCROLLBAR_THUMB = 0xFF777777;

    // ===== 分隔/交互 =====
    public static final int SEPARATOR       = 0xFF444444;
    public static final int GHOST_FILL      = 0x803A6DB5;  // 拖拽 ghost（半透明填充）
    public static final int GHOST_BORDER    = 0xFF5A8DD5;  // 拖拽 ghost 描边（不透明，E8 要求醒目轮廓）
    public static final int MARQUEE_FILL    = 0x223A6DB5;  // 框选
    public static final int MARQUEE_BORDER  = 0xFF3A6DB5;
    public static final int SNAP_INDICATOR  = 0xFFFFD700;
    public static final int PLAYHEAD        = 0xFFFF3333;
    public static final int PLAYHEAD_HEAD   = 0xFFFF5555;

    // ===== 轨道配色 =====

    /** 轨道类型 → clip/标记色：CAMERA 蓝 / LETTERBOX 绿 / AUDIO 黄 / EVENT 红 / MOD_EVENT 紫 / OVERLAY 紫 */
    public static int trackTypeColor(String type) {
        return switch (type.toUpperCase()) {
            case "CAMERA" -> 0xFF3A6DB5;
            case "LETTERBOX" -> 0xFF3A8A3A;
            case "AUDIO" -> 0xFF8A8A3A;
            case "EVENT" -> 0xFF8A3A3A;
            case "MOD_EVENT" -> 0xFF8A3A8A;
            case "OVERLAY" -> 0xFF6A3A8A;
            default -> 0xFF666666;
        };
    }

    /** 轨道类型 → 轨道行背景色 */
    public static int trackBgColor(String type) {
        return switch (type.toUpperCase()) {
            case "CAMERA" -> 0xFF1a2744;
            case "LETTERBOX" -> 0xFF1a2e1a;
            case "AUDIO" -> 0xFF2e2e1a;
            case "EVENT" -> 0xFF2e1a1a;
            case "MOD_EVENT" -> 0xFF2e1a2e;
            case "OVERLAY" -> 0xFF2e1a2e;
            default -> 0xFF1A1A2E;
        };
    }

    /** clip 填充色（选中亮化 / 悬停亮化 / 非选中变暗） */
    public static int clipFillColor(String trackType, boolean selected, boolean hovered, boolean dimmed) {
        int base = switch (trackType.toUpperCase()) {
            case "CAMERA" -> 0xFF3A6DB5;
            case "LETTERBOX" -> 0xFF3A8A3A;
            case "AUDIO" -> 0xFF8A8A3A;
            case "EVENT" -> 0xFF8A3A3A;
            case "MOD_EVENT" -> 0xFF8A3A8A;
            case "OVERLAY" -> 0xFF6A3A8A;
            default -> 0xFF3A3F4A;
        };
        if (selected) base = lighten(base, 0.3f);
        if (hovered) base = lighten(base, 0.15f);
        if (dimmed) base = lighten(base, -0.35f);
        return base;
    }

    /** 亮度调整（amount 可为负） */
    public static int lighten(int argb, float amount) {
        int r = Math.min(255, (int)(((argb >> 16) & 0xFF) * (1 + amount)));
        int g = Math.min(255, (int)(((argb >> 8) & 0xFF) * (1 + amount)));
        int b = Math.min(255, (int)((argb & 0xFF) * (1 + amount)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** ARGB 线性插值（E1 按钮 hover 平滑） */
    public static int lerpColor(int from, int to, float t) {
        int a = (int)(((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t);
        int r = (int)(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int)(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int)((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
