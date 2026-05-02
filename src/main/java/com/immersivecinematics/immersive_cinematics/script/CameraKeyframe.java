package com.immersivecinematics.immersive_cinematics.script;

/**
 * 相机关键帧 — 存储某一时刻的镜头完整状态和速度控制
 * <p>
 * 字段与 CameraPath/CameraProperties 完全一一对应，无冗余无缺失。
 * <p>
 * 速度驱动模型下，每个关键帧控制该时刻的瞬时速度：
 * <ul>
 *   <li>{@code speed} — 该关键帧处的瞬时速度值 (0~2)，默认 1.0</li>
 *   <li>{@code curve_bias} — smooth 模式下切线弯曲方向控制 (-1~1)，默认 0.0</li>
 * </ul>
 * <p>
 * JSON 示例：
 * <pre>
 * {
 *   "time": 0.0,
 *   "position": { "dx": 30.0, "dy": 2.0, "dz": 0.0 },
 *   "yaw": 90.0, "pitch": 5.0, "roll": 0.0,
 *   "fov": 70.0, "zoom": 1.0,
 *   "speed": 0.5,
 *   "curve_bias": 0.3
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

    /** 该关键帧处的瞬时速度 (0~2)，默认 1.0 */
    private final float speed;

    /** smooth 模式下切线弯曲方向控制 (-1~1)，默认 0.0 */
    private final float curveBias;

    public CameraKeyframe(float time, PositionData position, float yaw, float pitch,
                          float roll, float fov, float zoom, float dof) {
        this(time, position, yaw, pitch, roll, fov, zoom, dof, 1.0f, 0.0f);
    }

    public CameraKeyframe(float time, PositionData position, float yaw, float pitch,
                          float roll, float fov, float zoom, float dof,
                          float speed, float curveBias) {
        this.time = time;
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.fov = fov;
        this.zoom = zoom;
        this.dof = dof;
        this.speed = speed;
        this.curveBias = curveBias;
    }

    public float getTime() { return time; }
    public PositionData getPosition() { return position; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getRoll() { return roll; }
    public float getFov() { return fov; }
    public float getZoom() { return zoom; }
    public float getDof() { return dof; }
    public float getSpeed() { return speed; }
    public float getCurveBias() { return curveBias; }

    @Override
    public String toString() {
        return String.format("CameraKeyframe{time=%.2f, pos=%s, yaw=%.1f, pitch=%.1f, roll=%.1f, fov=%.1f, zoom=%.2f, dof=%.1f, speed=%.2f, bias=%.2f}",
                time, position, yaw, pitch, roll, fov, zoom, dof, speed, curveBias);
    }
}
