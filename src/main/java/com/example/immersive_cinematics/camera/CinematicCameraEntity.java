package com.example.immersive_cinematics.camera;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.UUID;

import static com.example.immersive_cinematics.ImmersiveCinematics.MC;

public class CinematicCameraEntity extends LocalPlayer {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final ClientPacketListener NETWORK_HANDLER = new ClientPacketListener(
            MC,
            (Screen) null,
            new Connection(PacketFlow.CLIENTBOUND),
            (ServerData) null,
            new GameProfile(UUID.randomUUID(), "CinematicCamera"),
            (WorldSessionTelemetryManager) null) {
        @Override
        public void send(Packet<?> packet) {
            // 不发送任何数据包
        }
    };

    private float currentFov = 70.0f;

    public CinematicCameraEntity(int id) {
        super(MC,
                MC.level,
                NETWORK_HANDLER,
                MC.player.getStats(),
                MC.player.getRecipeBook(),
                false,
                false);

        setId(id);
        setPose(Pose.SWIMMING);
        getAbilities().flying = true;
        noPhysics = true; // 物理隔离 - 允许穿过方块
        input = new KeyboardInput(MC.options);
    }

    public float getCurrentFov() {
        return currentFov;
    }

    public void setCurrentFov(float currentFov) {
        this.currentFov = currentFov;
    }

    public void spawn() {
        try {
            // 使用反射调用私有的 addEntity 方法
            Method addEntityMethod = ClientLevel.class.getDeclaredMethod("addEntity", int.class, Entity.class);
            addEntityMethod.setAccessible(true);
            addEntityMethod.invoke(MC.level, getId(), this);
        } catch (Exception e) {
            LOGGER.error("Failed to spawn cinematic camera entity", e);
        }
    }

    public void despawn() {
        try {
            // 使用反射调用私有的 removeEntity 方法
            Method removeEntityMethod = ClientLevel.class.getDeclaredMethod("removeEntity", int.class, Entity.RemovalReason.class);
            removeEntityMethod.setAccessible(true);
            removeEntityMethod.invoke(MC.level, getId(), Entity.RemovalReason.DISCARDED);
        } catch (Exception e) {
            LOGGER.error("Failed to despawn cinematic camera entity", e);
        }
    }

    @Override
    public void aiStep() {
        // 清空 aiStep() 方法，确保不接收键盘或鼠标的物理位移指令
    }

    @Override
    protected void checkFallDamage(double heightDifference, boolean onGround, BlockState landedState, BlockPos landedPosition) {
        // 防止掉落伤害
    }

    @Override
    public boolean canCollideWith(Entity other) {
        return false;
    }

    @Override
    public boolean isUsingItem() {
        return MC.player.isUsingItem();
    }

    @Override
    public int getUseItemRemainingTicks() {
        return MC.player.getUseItemRemainingTicks();
    }

    @Override
    public float getAttackAnim(float tickDelta) {
        return MC.player.getAttackAnim(tickDelta);
    }
}