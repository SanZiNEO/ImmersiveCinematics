package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.phys.Vec3;

/**
 * 飞行取景操控模式（0.3.5 第5轮）。
 * <p>
 * 输入由 InputRouter/Mixin 路由到本控制器：键盘在 tick 里读原版 Options 键位，
 * 鼠标由 MouseHandler.onMove 注入 delta。相机直接驱动 CameraManager 的 POJO 状态。
 */
public class FlightController {

    public static final FlightController INSTANCE = new FlightController();

    private boolean active = false;
    private Vec3 pos = Vec3.ZERO;
    private Vec3 startPos = Vec3.ZERO;
    private float yaw;
    private float pitch;
    private float startYaw;
    private float startPitch;
    private long lastNanos;
    private boolean keyUp;
    private boolean keyDown;
    private boolean keyLeft;
    private boolean keyRight;
    private boolean keyJump;
    private boolean keyShift;

    private FlightController() {}

    public boolean isActive() {
        return active;
    }

    public Vec3 getPos() {
        return pos;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void enter(Vec3 startPos, float startYaw, float startPitch) {
        this.active = true;
        this.pos = startPos;
        this.startPos = startPos;
        this.yaw = startYaw;
        this.pitch = startPitch;
        this.startYaw = startYaw;
        this.startPitch = startPitch;
        this.lastNanos = System.nanoTime();
        resetKeys();
        applyToCamera();
    }

    public void exit() {
        this.active = false;
        resetKeys();
    }

    public void cancel() {
        this.pos = startPos;
        this.yaw = startYaw;
        this.pitch = startPitch;
        this.active = false;
        resetKeys();
        applyToCamera();
    }

    /** 中转层把原始键盘事件喂进来，飞控维护自己的按键状态 */
    public void onKeyEvent(int key, int scanCode, int action) {
        if (!active) return;
        boolean down = action == 1 || action == 2;
        Options opts = Minecraft.getInstance().options;
        if (opts.keyUp.matches(key, scanCode)) keyUp = down;
        else if (opts.keyDown.matches(key, scanCode)) keyDown = down;
        else if (opts.keyLeft.matches(key, scanCode)) keyLeft = down;
        else if (opts.keyRight.matches(key, scanCode)) keyRight = down;
        else if (opts.keyJump.matches(key, scanCode)) keyJump = down;
        else if (opts.keyShift.matches(key, scanCode)) keyShift = down;
    }

    /** 每帧读键盘，驱动位移 */
    public void tick() {
        if (!active) return;

        long now = System.nanoTime();
        float deltaTime = Math.min(0.05f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        float forward = (keyUp ? 1f : 0f) - (keyDown ? 1f : 0f);
        float strafe = (keyLeft ? 1f : 0f) - (keyRight ? 1f : 0f);
        float speed = 10f;

        double yawRad = Math.toRadians(yaw);
        Vec3 forwardVec = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 rightVec = new Vec3(Math.cos(yawRad), 0, Math.sin(yawRad));
        Vec3 move = forwardVec.scale(forward).add(rightVec.scale(strafe)).scale(speed * deltaTime);
        if (keyJump) move = move.add(0, speed * deltaTime, 0);
        if (keyShift) move = move.add(0, -speed * deltaTime, 0);

        pos = pos.add(move);
        applyToCamera();
    }

    /** 原版 MouseHandler.turnPlayer 灵敏度公式（含 FreeCam 的 0.15 缩放） */
    public void onMouseMove(double dx, double dy) {
        if (!active) return;

        Options opts = Minecraft.getInstance().options;
        double d4 = opts.sensitivity().get() * 0.6D + 0.2D;
        double d6 = d4 * d4 * d4 * 8.0D;
        double d2 = dx * d6 * 0.15D;
        double d3 = dy * d6 * 0.15D;
        int invert = opts.invertYMouse().get() ? -1 : 1;

        yaw += (float) d2;
        pitch += (float) (d3 * invert);
        pitch = Math.max(-90f, Math.min(90f, pitch));
        applyToCamera();
    }

    private void resetKeys() {
        keyUp = false;
        keyDown = false;
        keyLeft = false;
        keyRight = false;
        keyJump = false;
        keyShift = false;
    }

    private void applyToCamera() {
        if (!active) return;
        CameraManager.INSTANCE.getPath().setPositionDirect(pos);
        CameraManager.INSTANCE.getProperties().setYawDirect(yaw);
        CameraManager.INSTANCE.getProperties().setPitchDirect(pitch);
    }
}
