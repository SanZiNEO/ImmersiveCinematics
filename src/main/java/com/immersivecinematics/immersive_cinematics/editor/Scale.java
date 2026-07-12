package com.immersivecinematics.immersive_cinematics.editor;

/**
 * 编辑器缩放比例 — 集中管理 UI 缩放系数
 * <p>
 * 以 960×540 为参考分辨率，根据实际窗口大小计算缩放比例。
 * 所有 Area 通过 {@code Scale.sx / Scale.sy} 获取缩放系数，
 * 避免直接引用 EditorScreen 的静态字段。
 */
public final class Scale {

    public static final float REF_W = 960f;
    public static final float REF_H = 540f;

    public static float sx = 1f;
    public static float sy = 1f;

    private Scale() {}

    /**
     * 根据实际窗口尺寸更新缩放比例
     *
     * @param width  实际窗口宽度（像素）
     * @param height 实际窗口高度（像素）
     */
    public static void update(int width, int height) {
        sx = (float) width / REF_W;
        sy = (float) height / REF_H;
    }
}
