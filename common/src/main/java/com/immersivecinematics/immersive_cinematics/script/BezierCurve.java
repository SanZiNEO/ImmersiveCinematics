package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 贝塞尔曲线路径控制 — 仅影响位置路径
 * <p>
 * 朝向（yaw/pitch/roll）和光学属性（fov/zoom）仍按片段的 interpolation 指定的曲线插值。
 * <p>
 * 数学逻辑：
 * <ul>
 *   <li>起始关键帧位置 = P0，结束关键帧位置 = P3</li>
 *   <li>P0 + P1 + P2 + P3 = 三次贝塞尔曲线 B(t)</li>
 *   <li>P1 == P2（重叠）：控制点重合为圆心，P0→P3 做正圆弧运动</li>
 *   <li>P1、P2 在一条线上：镜头做椭圆弧线运动</li>
 *   <li>四段贝塞尔曲线首尾相连：可近似任意圆或椭圆运动</li>
 * </ul>
 * <p>
 * 控制点支持两种模式（与 position 对象同模式，每个控制点自描述）：
 * <ul>
 *   <li>绝对（有 x/y/z）：世界坐标，直接参与曲线</li>
 *   <li>相对（有 dx/dy/dz）：相对<b>段起点关键帧</b>（from）的偏移——运行时求值为
 *       {@code from + 偏移}；适合"以玩家为出发点绕圆"等相对场景（玩家位置运行时才知道，绝对模式写不了）</li>
 * </ul>
 * JSON 示例：
 * <pre>
 * {
 *   "type": "bezier",
 *   "control_points": [
 *     { "dx": 10.0, "dy": 1.5, "dz": 3.0 },
 *     { "dx": 0.0, "dy": 2.0, "dz": -2.0 }
 *   ]
 * }
 * </pre>
 */
public class BezierCurve {

    /** 曲线类型，当前仅支持 "bezier" */
    private final String type;

    /** 两个控制点（自描述：相对/绝对） */
    private final List<ControlPoint> controlPoints;

    public BezierCurve(String type, List<ControlPoint> controlPoints) {
        this.type = type;
        this.controlPoints = controlPoints;
    }

    public String getType() {
        return type;
    }

    /**
     * 求值控制点 P1 的世界坐标：相对模式 = 段起点 from + 偏移；绝对模式 = 原值。
     */
    public Vec3 resolveP1(Vec3 segmentStart) {
        return controlPoints.get(0).resolve(segmentStart);
    }

    /**
     * 求值控制点 P2 的世界坐标：相对模式 = 段起点 from + 偏移；绝对模式 = 原值。
     */
    public Vec3 resolveP2(Vec3 segmentStart) {
        return controlPoints.get(1).resolve(segmentStart);
    }

    /** 获取所有控制点（不可变视图） */
    public List<ControlPoint> getControlPoints() {
        return controlPoints;
    }

    /**
     * 验证贝塞尔曲线数据是否合法
     *
     * @return true 如果 control_points 恰好有2个点
     */
    public boolean isValid() {
        return controlPoints != null && controlPoints.size() == 2;
    }

    @Override
    public String toString() {
        if (controlPoints != null && controlPoints.size() >= 2) {
            return String.format("BezierCurve{type=%s, p1=%s, p2=%s}",
                    type, controlPoints.get(0), controlPoints.get(1));
        }
        return String.format("BezierCurve{type=%s, controlPoints=%s}", type, controlPoints);
    }

    /**
     * 贝塞尔控制点 — 自描述坐标（与 PositionData 同模式）
     * <ul>
     *   <li>relative=true：dx/dy/dz — 相对段起点关键帧的偏移</li>
     *   <li>relative=false：x/y/z — 世界绝对坐标</li>
     * </ul>
     */
    public static class ControlPoint {

        /** true=相对偏移（dx/dy/dz），false=绝对坐标（x/y/z） */
        private final boolean relative;
        private final float x;
        private final float y;
        private final float z;

        public static ControlPoint absolute(float x, float y, float z) {
            return new ControlPoint(false, x, y, z);
        }

        public static ControlPoint relative(float dx, float dy, float dz) {
            return new ControlPoint(true, dx, dy, dz);
        }

        private ControlPoint(boolean relative, float x, float y, float z) {
            this.relative = relative;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public boolean isRelative() {
            return relative;
        }

        /** 分量（绝对坐标或相对偏移） */
        public Vec3 toVec3() {
            return new Vec3(x, y, z);
        }

        /** 求值世界坐标：相对 = 段起点 + 偏移；绝对 = 原值 */
        public Vec3 resolve(Vec3 segmentStart) {
            return relative ? segmentStart.add(x, y, z) : new Vec3(x, y, z);
        }

        @Override
        public String toString() {
            return String.format("ControlPoint{%s, %s, %s, %s}", relative ? "relative" : "absolute", x, y, z);
        }
    }
}
