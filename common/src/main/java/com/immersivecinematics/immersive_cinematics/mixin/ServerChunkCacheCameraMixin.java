package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.CameraVirtualCenterState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机中心：在 {@code ServerChunkCache.move} 期间把玩家的位置视图临时换成相机眼位。
 *
 * <p>原版 {@code ServerChunkCache.move} 只做一件事——调 {@code ChunkMap.move(player)}；而
 * {@code ChunkMap.move}（含 Lithium/Radium 的 {@code @Overwrite} 重写版）读取的中心来源只有三个：
 * {@code blockPosition}（{@code SectionPos.of} / {@code getBlockX/getBlockZ}）、{@code position}
 * （{@code getX/getZ/position()}）与 {@code lastSectionPos}（上一次 move 写回）。因此在 move 的
 * HEAD 临时替换前两个缓存字段、RETURN 还原，整条原版差集链路（ticket、区块发送/卸载、
 * {@code playerMap}、客户端 chunk cache 中心、实体配对）就自动围绕相机运转。</p>
 *
 * <p>这样不需要对 {@code ChunkMap.move} 做任何注入，也不依赖 Radium/Lithium 的方法实现：
 * 它们读到的仍是「玩家」的位置，只是这个位置在同步窗口内是相机。窗口外玩家的真实位置、
 * 碰撞、AI、发包都不受影响。</p>
 *
 * <p>无相机中心（{@code cameraCenterFor == null}）时完全不动；相机结束清状态后再调 move，
 * 旧中心=相机、新中心=玩家，原版自动回落。</p>
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheCameraMixin {

    @Unique private Vec3 ic$savedPosition;
    @Unique private BlockPos ic$savedBlockPosition;
    @Unique private ChunkPos ic$savedChunkPosition;
    @Unique private boolean ic$cameraCenterApplied;

    @Inject(method = "move", at = @At("HEAD"))
    private void ic$applyCameraCenter(ServerPlayer player, CallbackInfo ci) {
        ic$cameraCenterApplied = false;
        if (player == null) return;
        Vec3 camera = CameraVirtualCenterState.cameraCenterFor(player);
        if (camera == null) return;

        EntityAccessor accessor = (EntityAccessor) player;
        ic$savedPosition = accessor.ic$getPosition();
        ic$savedBlockPosition = accessor.ic$getBlockPosition();
        ic$savedChunkPosition = accessor.ic$getChunkPosition();

        BlockPos block = BlockPos.containing(camera.x, camera.y, camera.z);
        accessor.ic$setPosition(camera);
        accessor.ic$setBlockPosition(block);
        accessor.ic$setChunkPosition(new ChunkPos(block));
        ic$cameraCenterApplied = true;
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void ic$restorePlayerPosition(ServerPlayer player, CallbackInfo ci) {
        if (!ic$cameraCenterApplied) return;
        EntityAccessor accessor = (EntityAccessor) player;
        accessor.ic$setPosition(ic$savedPosition);
        accessor.ic$setBlockPosition(ic$savedBlockPosition);
        accessor.ic$setChunkPosition(ic$savedChunkPosition);
        ic$cameraCenterApplied = false;
    }
}
