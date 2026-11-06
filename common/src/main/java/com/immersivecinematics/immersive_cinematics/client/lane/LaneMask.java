package com.immersivecinematics.immersive_cinematics.client.lane;

/**
 * CAMERA lane 合成遮罩的逐帧参数快照（每条 lane 槽位复用一个实例）。
 *
 * 数据来源 = 相机求值器从同一 {@code SampledClip} 算出的 {@code mask_shape}（{@code full} 缺省 /
 * {@code rect} / {@code circle}）与遮罩几何（{@code mask_x} / {@code mask_y} 缺省 {@code 0.5}，
 * 宽 / 高缺省 {@code 1}，半径缺省 {@code 0.5}）；中心 / 宽高为窗口比例，半径为窗口短边比例；
 * 几何独立于 {@code source} / {@code dest}。
 *
 * 复用：实例由 {@code ScriptLaneDriver.Slot} 持有，更新槽位时调用 {@link #set(int, float, float, float,
 * float, float)}；只写自身字段、不分配；{@code full} 直接复位缺省。
 */
public final class LaneMask {

    /** {@code mask_shape=full}：整幅 lane，不裁切。 */
    public static final int SHAPE_FULL = 0;
    /** {@code mask_shape=rect}：矩形窗口。 */
    public static final int SHAPE_RECT = 1;
    /** {@code mask_shape=circle}：圆形窗口（物理像素真圆，半径按窗口短边比例）。 */
    public static final int SHAPE_CIRCLE = 2;

    /** 中心缺省（窗口比例）。 */
    public static final float DEFAULT_X = 0.5F;
    public static final float DEFAULT_Y = 0.5F;
    /** rect 宽高缺省（窗口比例）。 */
    public static final float DEFAULT_WIDTH = 1.0F;
    public static final float DEFAULT_HEIGHT = 1.0F;
    /** circle 半径缺省（窗口短边比例）。 */
    public static final float DEFAULT_RADIUS = 0.5F;

    private int shape = SHAPE_FULL;
    private float x = DEFAULT_X;
    private float y = DEFAULT_Y;
    private float width = DEFAULT_WIDTH;
    private float height = DEFAULT_HEIGHT;
    private float radius = DEFAULT_RADIUS;

    /**
     * 直接写入合成参数快照（新格式：参数已由相机求值器从同一 {@code SampledClip} 算出，不再重复采样）。
     *
     * @param shape  {@link #SHAPE_FULL} / {@link #SHAPE_RECT} / {@link #SHAPE_CIRCLE}（未知值按 full）
     * @param x      窗口中心 X（窗口比例）
     * @param y      窗口中心 Y（窗口比例）
     * @param width  rect 宽度（窗口比例）
     * @param height rect 高度（窗口比例）
     * @param radius circle 半径（窗口短边比例）
     */
    public void set(int shape, float x, float y, float width, float height, float radius) {
        if (shape != SHAPE_RECT && shape != SHAPE_CIRCLE) {
            resetFull();
            return;
        }
        this.shape = shape;
        this.x = x;
        this.y = y;
        if (shape == SHAPE_RECT) {
            this.width = width;
            this.height = height;
            this.radius = DEFAULT_RADIUS;
        } else {
            this.radius = radius;
            this.width = DEFAULT_WIDTH;
            this.height = DEFAULT_HEIGHT;
        }
    }

    /** 合成形状：{@link #SHAPE_FULL} / {@link #SHAPE_RECT} / {@link #SHAPE_CIRCLE}。 */
    public int shape() {
        return shape;
    }

    /** 窗口中心 X（窗口比例，可越界）。 */
    public float x() {
        return x;
    }

    /** 窗口中心 Y（窗口比例，可越界）。 */
    public float y() {
        return y;
    }

    /** rect 宽度（窗口比例，非负；0 不绘制）。 */
    public float width() {
        return width;
    }

    /** rect 高度（窗口比例，非负；0 不绘制）。 */
    public float height() {
        return height;
    }

    /** circle 半径（窗口短边比例，非负；0 不绘制）。 */
    public float radius() {
        return radius;
    }

    private void resetFull() {
        shape = SHAPE_FULL;
        x = DEFAULT_X;
        y = DEFAULT_Y;
        width = DEFAULT_WIDTH;
        height = DEFAULT_HEIGHT;
        radius = DEFAULT_RADIUS;
    }
}
