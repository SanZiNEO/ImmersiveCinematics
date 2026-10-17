package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.KeyframeInterpolator;

import java.util.List;

/**
 * CAMERA lane 合成遮罩的逐帧采样结果（0.3.6 D2）——每条 lane 槽位复用一个实例。
 *
 * <p>数据来源 = 该 lane 所属 {@link Clip} 的 clip 级 {@code mask_shape}
 * （{@code full} 缺省 / {@code rect} / {@code circle}）与关键帧级几何通道
 * （{@code mask_x} / {@code mask_y} 缺省 {@code 0.5}，{@code mask_width} / {@code mask_height}
 * 缺省 {@code 1}，{@code mask_radius} 缺省 {@code 0.5}）。字段语义与 fade 的局部几何同一口径：
 * 中心 / 宽高为窗口比例，半径为窗口较短边比例；几何独立于 {@code source} / {@code dest}。</p>
 *
 * <p><b>复用约定</b>：实例由 {@code ScriptLaneDriver.Slot} 持有，每次更新槽位时调用
 * {@link #sample(Clip, float)}。本类只写自己的字段，不分配数组 / record、不解析字符串
 * （clip 级形状按 clip 引用缓存，同一片段跨帧不再比较）；{@code shape == SHAPE_FULL} 时直接复位缺省，
 * 不扫描关键帧、不消费任何几何通道。{@code sample(null, 0f)} 是合法调用（链输出主画面复用槽位 0 时复位），
 * 结果 = 缺省 full。</p>
 *
 * <p><b>时间</b>：{@code localTime} = 该片段的实际本地时间（与相机状态 / {@code opacity} / {@code dest}
 * 同源）。循环语义与 {@link KeyframeInterpolator#computeInterpolation(float, Clip)} 一致
 * （repeat / pingpong / 有限循环末端钳制），此处用同一映射后再走
 * {@link KeyframeInterpolator#interpolateChannel} 的线性插值口径——无关键帧 / 单关键帧 / 端点 / 零长段
 * 的行为因此与其它通道完全相同。</p>
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
     * 按片段与本地时间采样遮罩。{@code clip == null} 或片段未声明 {@code mask_shape}（旧脚本）→ full。
     *
     * <p>full 不读取关键帧；rect 只读 {@code mask_x/y/width/height}；circle 只读
     * {@code mask_x/y/radius}。未消费的通道写回缺省，避免槽位复用时残留上一条 lane 的几何。</p>
     */
    public void sample(Clip clip, float localTime) {
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
        float time = effectiveTime(clip, keyframes, localTime);
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

    /**
     * 循环时间映射：与 {@link KeyframeInterpolator#computeInterpolation(float, Clip)} 的循环段逐条一致
     * （repeat / pingpong / 有限循环末端钳制），保证遮罩与相机状态 / 合成参数取同一时刻。
     * 非循环 / 关键帧不足 2 个 / 周期非正 → 原样返回。
     */
    private static float effectiveTime(Clip clip, List<Keyframe> keyframes, float localTime) {
        if (!clip.isLoop() || keyframes.size() < 2) {
            return localTime;
        }
        float period = clip.getAnimPeriod();
        if (!(period > 0f)) {
            return localTime;
        }
        if (clip.getLoopCount() > 0) {
            float maxLoopTime = period * clip.getLoopCount();
            if (localTime >= maxLoopTime) {
                return keyframes.get(keyframes.size() - 1).getTime();
            }
        }
        float offset = keyframes.get(0).getTime();
        if ("pingpong".equals(clip.getLoopMode())) {
            float t = (localTime - offset) % (2f * period);
            return offset + (t <= period ? t : 2f * period - t);
        }
        return offset + (localTime - offset) % period;
    }
}
