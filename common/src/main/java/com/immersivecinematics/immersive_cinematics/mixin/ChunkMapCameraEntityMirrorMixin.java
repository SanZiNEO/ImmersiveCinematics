package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraViewManager;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机视图实体流镜像：把原版 {@code ChunkMap$TrackedEntity} 已经产出的实体包旁路复制给相机视图。
 *
 * <ul>
 *   <li>{@code broadcast} TAIL：原版对 {@code seenBy} 的真实玩家发完后，把同一包交给
 *       {@link CameraViewManager#mirrorTrackedEntityPacket(Entity, Packet)}；不修改包、不改 {@code seenBy}。</li>
 *   <li>{@code broadcastRemoved} HEAD：原版对真实玩家发 remove 之前，先让实体流给订阅视图补 remove / 清记录。</li>
 * </ul>
 *
 * 两个注入都整体 try/catch：镜像异常绝不影响原版跟踪与真实玩家。目标类是包私有内部类，只能用字符串 targets；
 * {@code entity} 是包私有字段，{@code @Shadow} 保持同可见性。
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapCameraEntityMirrorMixin {

    @Shadow
    @Final
    Entity entity;

    @Inject(method = "broadcast", at = @At("TAIL"))
    private void immersivecinematics_mirrorEntityPacket(Packet<?> packet, CallbackInfo ci) {
        try {
            CameraViewManager.INSTANCE.mirrorTrackedEntityPacket(this.entity, packet);
        } catch (Exception e) {
            int id = this.entity != null ? this.entity.getId() : -1;
            ErrorLog.logRateLimited("CameraView", "mirror-inject", 30_000L,
                    "实体包镜像注入失败（实体 " + id + "）：本包跳过，原版广播继续", e);
        }
    }

    @Inject(method = "broadcastRemoved", at = @At("HEAD"))
    private void immersivecinematics_mirrorEntityRemoved(CallbackInfo ci) {
        try {
            CameraViewManager.INSTANCE.onTrackedEntityRemoved(this.entity);
        } catch (Exception e) {
            int id = this.entity != null ? this.entity.getId() : -1;
            ErrorLog.logRateLimited("CameraView", "removed-inject", 30_000L,
                    "实体移除镜像注入失败（实体 " + id + "）：跳过，原版移除继续", e);
        }
    }
}
