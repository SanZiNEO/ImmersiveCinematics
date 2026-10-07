package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 统一时间插值入口 — tick(20Hz) 与渲染帧之间的帧间采样。
 * <p>
 * 逻辑 tick 固定 20Hz，而渲染帧为 60/120/… Hz；实体位置、朝向等状态只在 tick 更新，
 * 直接读取当前 tick 值会出现台阶感。渲染侧按 {@code partialTick}（= {@code Minecraft.getFrameTime()}
 * = {@code Timer.partialTick}）在上一 tick 快照与当前 tick 值之间采样即可平滑，且
 * <b>不改变状态语义</b>（只做帧间采样，不是状态过渡/缓动）。
 * <p>
 * 上一 tick 快照复用原版既有机制：{@code Entity.xo/yo/zo}（{@code setOldPosAndRot} 每 tick 写入）、
 * {@code yRotO/xRotO}、{@code LivingEntity.yBodyRotO}，本类不重复造这些字段。
 * <p>
 * 与 {@link MathUtil} 的区别：{@code MathUtil.lerp(from, to, t)} 是通用插值曲线辅助，
 * 本类形参顺序对齐原版 {@code Mth.lerp(partialTick, prev, current)}，语义是"帧间采样"。
 */
public final class TimeInterpolation {

    private TimeInterpolation() {} // 禁止实例化

    // ========== 纯函数：按 partialTick 在 prev/current 间采样 ==========

    /** 标量帧间采样（位置 / 俯仰等数值域）：等价于 {@code Mth.lerp(partialTick, prev, current)}。 */
    public static float lerp(float partialTick, float prev, float current) {
        return Mth.lerp(partialTick, prev, current);
    }

    /** 双精度标量帧间采样：等价于 {@code Mth.lerp(partialTick, prev, current)}。 */
    public static double lerp(float partialTick, double prev, double current) {
        return Mth.lerp(partialTick, prev, current);
    }

    /** 角度帧间采样（最短路径 / 环绕）：等价于 {@code Mth.rotLerp(partialTick, prev, current)}。 */
    public static float rotLerp(float partialTick, float prev, float current) {
        return Mth.rotLerp(partialTick, prev, current);
    }

    /** 位置三轴帧间采样：逐轴 {@code Mth.lerp(partialTick, prev, current)}。 */
    public static Vec3 position(float partialTick,
                                double prevX, double prevY, double prevZ,
                                double currentX, double currentY, double currentZ) {
        return new Vec3(
                Mth.lerp(partialTick, prevX, currentX),
                Mth.lerp(partialTick, prevY, currentY),
                Mth.lerp(partialTick, prevZ, currentZ)
        );
    }

    // ========== 实体采样：复用原版 prev/current 快照 ==========

    /** 当前渲染 partialTick（= {@code Minecraft.getFrameTime()} = {@code Timer.partialTick}）。 */
    public static float partialTick() {
        return Minecraft.getInstance().getFrameTime();
    }

    /** 实体渲染帧插值位置（{@code xo/yo/zo} → 当前坐标，按 partialTick 插值，消除 20Hz 步进卡顿）。 */
    public static Vec3 entityPosition(Entity e) {
        float pt = partialTick();
        return position(pt, e.xo, e.yo, e.zo, e.getX(), e.getY(), e.getZ());
    }

    /**
     * 实体身体 yaw 的渲染帧插值。
     * <p>
     * 原版 {@code LivingEntityRenderer} 使用 {@code Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)}；
     * 非 LivingEntity 退化为 {@code Entity.yRotO → getYRot()}。
     */
    public static float entityBodyYaw(Entity e) {
        float pt = partialTick();
        if (e instanceof LivingEntity le) {
            return rotLerp(pt, le.yBodyRotO, le.yBodyRot);
        }
        return rotLerp(pt, e.yRotO, e.getYRot());
    }

    /** 实体 pitch 的渲染帧插值：原版使用 {@code Mth.lerp(partialTick, xRotO, getXRot())}。 */
    public static float entityPitch(Entity e) {
        float pt = partialTick();
        return lerp(pt, e.xRotO, e.getXRot());
    }
}
