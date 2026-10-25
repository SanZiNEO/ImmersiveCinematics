package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraAnchorManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraViewManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * despawn 的「最近玩家」改用相机锚点虚拟玩家。
 *
 * 相机锚点存在（预加载会话期间）且锚点虚拟玩家比真实玩家更近时返回虚拟玩家，让相机区域的普通怪按原版规则
 * 留在原处（128 格外才 despawn、32 格内清 {@code noActionTime}）；否则相机一飞远，镜头里的怪就被清掉。
 * 锚点不在附近（>128 格）或本方法运行在客户端时返回原版结果。
 */
@Mixin(Mob.class)
public abstract class MobDespawnCameraMixin {

    @Redirect(
            method = "checkDespawn",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;")
    )
    private Player immersiveCinematics$cameraAwareNearestPlayer(Level level, Entity entity, double distance) {
        Player real = level.getNearestPlayer(entity, distance);
        if (!(level instanceof ServerLevel serverLevel)) {
            return real;
        }
        BlockPos pos = entity.blockPosition();
        Player anchor = CameraAnchorManager.INSTANCE.getVirtualPlayer(serverLevel, pos);
        Player view = CameraViewManager.INSTANCE.getVirtualPlayer(serverLevel, pos);
        // 真实玩家优先（同距保持原行为），虚拟玩家只在与真实玩家更近时接管
        Player best = real;
        double bestSq = real == null ? Double.MAX_VALUE : real.distanceToSqr(entity);
        if (anchor != null) {
            double d2 = anchor.distanceToSqr(entity);
            if (d2 < bestSq) {
                bestSq = d2;
                best = anchor;
            }
        }
        if (view != null) {
            double d2 = view.distanceToSqr(entity);
            if (d2 < bestSq) {
                best = view;
            }
        }
        return best;
    }
}
