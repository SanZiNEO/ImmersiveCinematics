package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 贝塞尔曲线路径控制 — 仅影响位置路径
 * <p>
 * 朝向（yaw/pitch/roll）和光学属性（fov/zoom/dof）仍按片段的 interpolation 指定的曲线插值。
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
 * JSON 示例：
 * <pre>
 * {
 *   "type": "bezier",
 *   "control_points": [
 *     { "x": 10.0, "y": 1.5, "z": 3.0 },
 *     { "x": 0.0, "y": 2.0, "z": -2.0 }
 *   ]
 * }
 * </pre>
 */
public class BezierCurve {

    /** 曲线类型，当前仅支持 "bezier" */
    private final String type;

    /** 两个控制点 P1、P2 */
    private final List<Vec3> controlPoints;

    public BezierCurve(String type, List<Vec3> controlPoints) {
        this.type = type;
        this.controlPoints = controlPoints;
    }

    public String getType() {
        return type;
    }

    /** 获取控制点 P1（第一个控制点） */
    public Vec3 getP1() {
        return controlPoints.get(0);
    }

    /** 获取控制点 P2（第二个控制点） */
    public Vec3 getP2() {
        return controlPoints.get(1);
    }

    /** 获取所有控制点（不可变视图） */
    public List<Vec3> getControlPoints() {
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
}
