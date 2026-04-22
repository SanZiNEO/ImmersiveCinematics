package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.Immersive_cinematics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class CinematicCameraEntity extends Entity {

    private float yaw;
    private float pitch;
    private float fov = 70.0f;

    public CinematicCameraEntity(EntityType<? extends CinematicCameraEntity> type, Level level) {
        super(type, level);
    }

    public CinematicCameraEntity(Level level, Vec3 position, float yaw, float pitch) {
        super(Immersive_cinematics.CINEMATIC_CAMERA.get(), level);
        setPos(position);
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public float getFov() {
        return fov;
    }

    public void setFov(float fov) {
        this.fov = fov;
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag compound) {
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag compound) {
    }
}