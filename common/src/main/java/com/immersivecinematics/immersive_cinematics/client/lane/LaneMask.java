package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.KeyframeInterpolator;

import java.util.List;

/**
 * CAMERA lane 合成遮罩的逐帧采样结果（每条 lane 槽位复用一个实例）。
 *
 * 数据来源 = 该 lane 所属 {@link Clip} 的 clip 级 {@code mask_shape}（{@code full} 缺省 / {@code rect} /
 * {@code circle}）与关键帧几何（{@code mask_x} / {@code mask_y} 缺省 {@code 0.5}，宽 / 高缺省 {@code 1}，
 * 半径缺省 {@code 0.5}）；中心 / 宽高为窗口比例，半径为窗口短边比例；几何独立于 {@code source} / {@code dest}。
 *
 * 复用：实例由 {@code ScriptLaneDriver.Slot} 持有，更新槽位时调用 {@link #sample(Clip, float)}；只写自身
 * 字段、不分配；{@code full} 直接复位缺省，不扫描关键帧；{@code sample(null, 0f)} 合法（复位为 full）。
 * 时间：{@code localTime} = 执行账本给出的本次执行局部时间（与相机状态同源），本类不做循环折叠。
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

    /** 形状缓存：片段数据解析后只读，同一 clip 的形状解析一次即可。 */
    private Clip shapeClip;
    private int cachedShape = SHAPE_FULL;

    /**
     * 按片段与本地时间采样遮罩。{@code clip == null} 或未声明 {@code mask_shape} → full；
     * {@code sample(null, 0f)} 合法（链输出主画面复用槽位 0 时复位）。
     * full 不读关键帧；rect 只读 {@code mask_x/y/width/height}；circle 只读 {@code mask_x/y/radius}；
     * 未消费的通道写回缺省，避免槽位复用时残留上一条 lane 的几何。
     * 无关键帧 / 单关键帧 / 端点 / 零长段走同一插值口径，与其它通道逐条一致。
     */
    public void sample(Clip clip, double localTime) {
        if (clip == null) {
            resetFull();
            return;
        }
        int resolvedShape = resolveShape(clip);
        if (resolvedShape == SHAPE_FULL) {
            resetFull();
            return;
        }

        List<Keyframe> keyframes = clip.getKeyframes();
        double time = localTime;
        this.shape = resolvedShape;
        if (resolvedShape == SHAPE_RECT) {
            this.x = KeyframeInterpolator.interpolateChannel(keyframes, time, "mask_x", DEFAULT_X);
            this.y = KeyframeInterpolator.interpolateChannel(keyframes, time, "mask_y", DEFAULT_Y);
            this.width = KeyframeInterpolator.interpolateChannel(keyframes, time, "mask_width", DEFAULT_WIDTH);
            this.height = KeyframeInterpolator.interpolateChannel(keyframes, time, "mask_height", DEFAULT_HEIGHT);
            this.radius = DEFAULT_RADIUS;
        } else {
            this.x = KeyframeInterpolator.interpolateChannel(keyframes, time, "mask_x", DEFAULT_X);
            this.y = KeyframeInterpolator.interpolateChannel(keyframes, time, "mask_y", DEFAULT_Y);
            this.radius = KeyframeInterpolator.interpolateChannel(keyframes, time, "mask_radius", DEFAULT_RADIUS);
            this.width = DEFAULT_WIDTH;
            this.height = DEFAULT_HEIGHT;
        }
    }

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

    /** clip 级形状 → 编码；同一 clip 只解析一次（不逐帧比较字符串）。 */
    private int resolveShape(Clip clip) {
        if (clip != shapeClip) {
            String name = clip.getString("mask_shape", "full");
            cachedShape = "rect".equals(name) ? SHAPE_RECT
                    : "circle".equals(name) ? SHAPE_CIRCLE
                    : SHAPE_FULL;
            shapeClip = clip;
        }
        return cachedShape;
    }
}
