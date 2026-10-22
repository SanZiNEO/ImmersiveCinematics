package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 贝塞尔曲线路径控制：仅影响位置路径；朝向与光学属性按匀速线性插值（缓动由编辑器烘焙成显式关键帧）。
 * P1 / P2 自描述：绝对（x/y/z，世界坐标）或相对（dx/dy/dz，相对段起点 from）。形状口径：P1 == P2 时以重合点为圆心做正圆弧，
 * P1、P2 共线时为椭圆弧，四段首尾相连可近似任意圆 / 椭圆。控制点必须恰好 2 个，否则按直线插值。
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

    /**
     * 控制点列表（构造时传入的顺序：P1、P2）。
     *
     * @return 控制点列表
     */
    public List<ControlPoint> getControlPoints() {
        return controlPoints;
    }

    /**
     * 曲线数据是否可用（控制点恰好 2 个）。
     *
     * @return 恰好 2 个控制点返回 true；否则调用方按直线插值
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
     * 贝塞尔控制点 —— 自描述坐标（与 PositionData 同模式）：
     * {@code relative=true} 用 dx/dy/dz（相对段起点关键帧的偏移），{@code relative=false} 用 x/y/z（世界绝对坐标）。
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
