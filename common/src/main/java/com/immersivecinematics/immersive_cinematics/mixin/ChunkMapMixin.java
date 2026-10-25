package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraAnchorManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraCenterDiagnostics;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraViewManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.CameraVirtualCenterState;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 让原版刷怪判定认为「相机附近也有刷怪中心」。
 *
 * 纯坐标方案：不创建任何 Player 实体。
 * <ul>
 *   <li>{@code anyPlayerCloseEnoughForSpawning} HEAD：观众模式下原版 move 不会写 PLAYER ticket，
 *       外层 {@code hasPlayersNearby} 门会先失败；这里按相机锚点直接放行，覆盖「状态已写入、move 尚未生效」的窗口。</li>
 *   <li>{@code playerIsCloseEnoughForSpawning} HEAD：原版同一方法同时服务外层门与
 *       {@code getPlayersCloseForSpawning}（局部上限 {@code LocalMobCapCalculator}）。相机 128 格内放行，
 *       返回对象仍是真实 {@link ServerPlayer}，局部上限按玩家记账，不新增假玩家。</li>
 * </ul>
 * 语义：相机是观察者，不额外加 {@code isSpectator()} 判断——镜头所在区域允许刷怪（当前行为）。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    private ServerLevel level;

    @Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_cameraAnchorCloseForSpawning(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (CameraAnchorManager.INSTANCE.isAnyAnchorNear(this.level, chunkPos)) {
            CameraCenterDiagnostics.onSpawnGateHit(this.level, chunkPos);
            cir.setReturnValue(true);
        }
    }

    /**
     * 跨维度视图（独立于 F6 单锚点）：目标维度里玩家不在该 level 的 {@code playerMap}，
     * 原版外层门 {@code anyPlayerCloseEnoughForSpawning} 会直接返回 false，刷怪根本不会发生。
     * 视图 128 格内按相机放行，与原版玩家口径一致。
     */
    @Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_cameraViewCloseForSpawning(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (CameraViewManager.INSTANCE.isAnyViewNear(this.level, chunkPos)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 刷怪局部上限（{@code LocalMobCapCalculator}）取「区块附近玩家」：视图附近没有真实玩家时，
     * 返回视图属主的真实 {@link ServerPlayer}（不伪造玩家），让 mob cap 继续按玩家记账。
     */
    @Inject(method = "getPlayersCloseForSpawning", at = @At("RETURN"), cancellable = true)
    private void immersivecinematics_cameraViewPlayersForSpawn(ChunkPos chunkPos,
                                                              CallbackInfoReturnable<List<ServerPlayer>> cir) {
        List<ServerPlayer> players = cir.getReturnValue();
        if (players != null && !players.isEmpty()) {
            return;
        }
        if (!CameraViewManager.INSTANCE.isAnyViewNear(this.level, chunkPos)) {
            return;
        }
        ServerPlayer viewer = CameraViewManager.INSTANCE.playerForSpawn(this.level, chunkPos);
        if (viewer != null) {
            cir.setReturnValue(List.of(viewer));
        }
    }

    @Inject(method = "playerIsCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_cameraPlayerCloseForSpawning(ServerPlayer player, ChunkPos chunkPos,
                                                                 CallbackInfoReturnable<Boolean> cir) {
        Vec3 camera = CameraVirtualCenterState.cameraCenterFor(player);
        if (camera == null) return;
        double dx = (chunkPos.x << 4) + 8 - camera.x;
        double dz = (chunkPos.z << 4) + 8 - camera.z;
        if (dx * dx + dz * dz < 16384.0) {
            cir.setReturnValue(true);
        }
    }
}
