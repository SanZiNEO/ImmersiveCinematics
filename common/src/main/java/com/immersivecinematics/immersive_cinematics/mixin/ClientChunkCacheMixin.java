package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * 客户端区块缓存诊断（调试用）：
 * 打印客户端收到的区块包、缓存中心变化和被丢弃的区块，
 * 用于确认远端相机区域的区块是否真的进入了客户端缓存。
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static long lastCenterLog;
    private static long lastChunkLog;
    private static long lastDropLog;
    private static int chunkPacketsSinceLog;

    @Inject(method = "updateViewCenter", at = @At("HEAD"))
    private void immersivecinematics_logViewCenter(int x, int z, CallbackInfo ci) {
        if (!CameraManager.INSTANCE.isActive()) return;
        long now = System.currentTimeMillis();
        if (now - lastCenterLog >= 1000) {
            lastCenterLog = now;
            LOGGER.info("[chunk-cache] view center -> [{}, {}]", x, z);
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void immersivecinematics_logChunkPacket(int x, int z, FriendlyByteBuf buf, CompoundTag tag,
                                                    Consumer<?> consumer,
                                                    CallbackInfoReturnable<LevelChunk> cir) {
        if (!CameraManager.INSTANCE.isActive()) return;
        chunkPacketsSinceLog++;
        long now = System.currentTimeMillis();
        if (now - lastChunkLog >= 1000) {
            lastChunkLog = now;
            LOGGER.info("[chunk-cache] 1s内收到区块包 {} 个，最新 [{}, {}]", chunkPacketsSinceLog, x, z);
            chunkPacketsSinceLog = 0;
        }
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void immersivecinematics_logDrop(int x, int z, CallbackInfo ci) {
        if (!CameraManager.INSTANCE.isActive()) return;
        long now = System.currentTimeMillis();
        if (now - lastDropLog >= 1000) {
            lastDropLog = now;
            LOGGER.info("[chunk-cache] drop [{}, {}]", x, z);
        }
    }
}
