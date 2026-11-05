package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.continuity.PlayerMotion;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 服务端传送 → 运动代次自增：{@link ServerPlayer#teleportTo(double, double, double)} 与
 * {@link ServerPlayer#teleportTo(ServerLevel, double, double, double, Set, float, float)}
 * 是原版服务端传送玩家坐标的入口。命中时把该玩家的 {@link PlayerMotion} 代次自增，
 * 使传送前后两个采样点不参与同一段扫掠（短距离传送因此不再被当成连续移动）。
 *
 * <p>只在 HEAD 观察，不取消、不改写任何参数；诊断代码异常不外抛（传送路径不得被打断）。
 * Forge 的 5 参 {@code teleportTo(ServerLevel, double, double, double, float, float)}
 * 不是原版方法，不注入。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportMixin {

    /** 判定「目标点与当前坐标不同」的距离平方阈值（1e-3 格）。 */
    private static final double TELEPORT_EPSILON_SQ = 1e-6;

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"))
    private void ic$onTeleportTo(double x, double y, double z, CallbackInfo ci) {
        ic$markDiscontinuity(x, y, z);
    }

    @Inject(
            method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
            at = @At("HEAD"))
    private void ic$onTeleportTo(ServerLevel level, double x, double y, double z,
                                 Set<RelativeMovement> relative, float yaw, float pitch,
                                 CallbackInfoReturnable<Boolean> cir) {
        ic$markDiscontinuity(x, y, z);
    }

    /** 目标点与当前坐标不同（距离平方 &gt; 1e-6）→ 代次自增；任何异常都不得打断传送。 */
    private void ic$markDiscontinuity(double x, double y, double z) {
        try {
            ServerPlayer player = (ServerPlayer) (Object) this;
            double dx = x - player.getX();
            double dy = y - player.getY();
            double dz = z - player.getZ();
            if (dx * dx + dy * dy + dz * dz > TELEPORT_EPSILON_SQ) {
                PlayerMotion.INSTANCE.reset(player.getUUID());
            }
        } catch (Throwable t) {
            FatalErrors.rethrowIfFatal(t);
        }
    }
}
