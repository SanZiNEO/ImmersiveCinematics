package com.immersivecinematics.immersive_cinematics.trigger.server.continuity;

/**
 * 一次运动采样的不可变快照：位置 + 朝向 + 采样 tick。
 *
 * <p>{@link SegmentSweep} 把相邻两次采样之间的运动近似为
 * <b>直线段</b>（位置）与 <b>最短弧</b>（yaw / pitch），不是真连续时间轨迹；
 * 只用于把「两次采样之间是否碰到过条件区域」从点判定扩展为段判定。
 *
 * <p>{@code tick} 只用于采样去重与时间窗口判定，不参与几何计算。
 * 纯数据载体，无 MC 依赖，可无头直调。
 *
 * @param x     世界坐标 X
 * @param y     世界坐标 Y
 * @param z     世界坐标 Z
 * @param yaw   朝向 yaw（度，任意范围；判定时内部 wrap 到 ±180°）
 * @param pitch 朝向 pitch（度，正 = 向下看）
 * @param tick  采样所属服务端 tick
 */
public record MotionSample(double x, double y, double z, float yaw, float pitch, int tick) {}
