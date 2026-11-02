package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 实体位置缓存的直接读写口（{@code position} / {@code blockPosition} / {@code chunkPosition}）。
 *
 * <p>相机中心需要在 {@code ChunkMap.move} 的同步窗口内把「玩家位置视图」换成相机眼位，但不能改真实位置：
 * 只临时替换这三个缓存字段，窗口结束立即还原。不走 {@link Entity#setPosRaw}——那会触发
 * {@code levelCallback.onMove()}（实体 section 管理 / 跟踪），把相机位置泄漏给实体管理器。</p>
 *
 * <p>三个字段都是 {@code Entity} 的私有缓存；{@code blockPosition} 是 {@code SectionPos.of(EntityAccess)}
 * 与 {@code getBlockX/getBlockZ} 的来源，{@code position} 是 {@code getX/getZ/position()} 的来源，
 * {@code chunkPosition} 一并替换以保持三者一致。</p>
 */
@Mixin(Entity.class)
public interface EntityAccessor {

    @Accessor("position")
    Vec3 ic$getPosition();

    @Accessor("position")
    @Mutable
    void ic$setPosition(Vec3 position);

    @Accessor("blockPosition")
    BlockPos ic$getBlockPosition();

    @Accessor("blockPosition")
    @Mutable
    void ic$setBlockPosition(BlockPos blockPosition);

    @Accessor("chunkPosition")
    ChunkPos ic$getChunkPosition();

    @Accessor("chunkPosition")
    @Mutable
    void ic$setChunkPosition(ChunkPos chunkPosition);
}
