package com.immersivecinematics.immersive_cinematics.script;

import javax.annotation.Nullable;

/**
 * 相机关键帧 — 存储某一时刻的镜头完整状态
 * <p>
 * 字段与 CameraPath/CameraProperties 完全一一对应，无冗余无缺失。
 * <p>
 * 逐关键帧插值覆盖：
 * <ul>
 *   <li>可选的 {@code interpolation} 字段覆盖 clip 级别的默认插值类型</li>
 *   <li>含义："到达此关键帧"所使用的插值曲线，即 from→to 段使用 to 关键帧上的 interpolation</li>
 *   <li>null = 使用 clip 级别的默认 interpolation</li>
 * </ul>
 * <p>
 * JSON 示例（relative 模式 + 逐关键帧插值覆盖）：
 * <pre>
 * {
 *   "time": 0.0,
 *   "position": { "dx": 30.0, "dy": 2.0, "dz": 0.0 },
 *   "yaw": 90.0, "pitch": 5.0, "roll": 0.0,
 *   "fov": 70.0, "zoom": 1.0,
 *   "interpolation": "ease_in"
 * }
 * </pre>
 */
public class CameraKeyframe {

    /** 片段内相对时间（秒，从0开始） */
    private final float time;

    /** 坐标数据，结构随片段的 position_mode 变化 */
    private final PositionData position;

    /** 偏航角（度），0=南(+Z)，90=西(-X)，±180=北(-Z) */
    private final float yaw;

    /** 俯仰角（度），正值=低头，负值=抬头 */
    private final float pitch;

    /** 滚转角（度），0=正放，正值=左倾，负值=右倾 */
    private final float roll;

    /** 视场角（度），标准70° */
    private final float fov;

    /** 缩放倍率，1.0=正常，>1=放大（通过FOV除法实现） */
    private final float zoom;

    /** 景深（0=禁用），保留字段，预留给光影包合作 */
    private final float dof;

    /**
     * 逐关键帧插值覆盖 — 到达此关键帧所使用的插值曲线
     * <p>
     * null = 使用 clip 级别的默认 interpolation。
     * 仅在 {@link InterpolationScope#SEGMENT} 模式下生效，
     * CLIP 模式下曲线映射应用到整体进度，逐段覆盖无意义。
     */
    @Nullable
    private final InterpolationType interpolation;

    public CameraKeyframe(float time, PositionData position, float yaw, float pitch,
                          float roll, float fov, float zoom, float dof) {
        this(time, position, yaw, pitch, roll, fov, zoom, dof, null);
    }

    public CameraKeyframe(float time, PositionData position, float yaw, float pitch,
                          float roll, float fov, float zoom, float dof,
                          @Nullable InterpolationType interpolation) {
        this.time = time;
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.fov = fov;
        this.zoom = zoom;
        this.dof = dof;
        this.interpolation = interpolation;
    }

    public float getTime() { return time; }
    public PositionData getPosition() { return position; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getRoll() { return roll; }
    public float getFov() { return fov; }
    public float getZoom() { return zoom; }
    public float getDof() { return dof; }

    /** 获取逐关键帧插值覆盖，null=使用 clip 默认 */
    @Nullable
    public InterpolationType getInterpolation() { return interpolation; }

    @Override
    public String toString() {
        return String.format("CameraKeyframe{time=%.2f, pos=%s, yaw=%.1f, pitch=%.1f, roll=%.1f, fov=%.1f, zoom=%.2f, dof=%.1f, interp=%s}",
                time, position, yaw, pitch, roll, fov, zoom, dof, interpolation);
    }
}
