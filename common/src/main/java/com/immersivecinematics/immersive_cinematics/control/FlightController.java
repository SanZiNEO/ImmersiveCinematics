package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * 飞行取景操控模式：键盘在 tick 里读原版 Options 键位，鼠标由 MouseHandler.onMove 注入 delta。
 *
 * 写侧经 {@link CameraManager} 门面写六参数（写完立即刷新统一快照），禁止直写内部对象。
 */
public class FlightController {

    /** 全局单例：会话状态全部实例字段，无并发访问假设。 */
    public static final FlightController INSTANCE = new FlightController();

    /** 长按连续微调：两段式加速度（刚按小步长 → 平滑加速 → 封顶最大速率） */
    private static final float RAMP_TIME = 0.8f;
    private static final float FOV_MIN_RATE = 1.0f;
    private static final float FOV_MAX_RATE = 8.0f;
    private static final float ZOOM_MIN_RATE = 0.05f;
    private static final float ZOOM_MAX_RATE = 0.4f;
    private static final float ROLL_MIN_RATE = 1.0f;
    private static final float ROLL_MAX_RATE = 8.0f;

    /** A+B 混合：A 的 t² 曲线算目标速率，B 的一阶滞后负责平滑起步/收尾 */
    private static final float LAG_TAU = 0.08f;

    private boolean active = false;
    private Vec3 pos = Vec3.ZERO;
    private Vec3 startPos = Vec3.ZERO;
    private float yaw;
    private float pitch;
    private float roll;
    private float fov;
    private float zoom;
    private float startYaw;
    private float startPitch;
    private float startRoll;
    private float startFov;
    private float startZoom;
    private boolean modeAbsolute;
    private long lastNanos;
    private boolean keyUp;
    private boolean keyDown;
    private boolean keyLeft;
    private boolean keyRight;
    private boolean keyJump;
    private boolean keyShift;
    private boolean keySlow;
    private boolean keyFovIn;
    private boolean keyFovOut;
    private boolean keyZoomIn;
    private boolean keyZoomOut;
    private boolean keyRollLeft;
    private boolean keyRollRight;
    private float holdFov;
    private float holdZoom;
    private float holdRoll;
    private float currentFovRate;
    private float currentZoomRate;
    private float currentRollRate;

    private FlightController() {}

    /** 飞控会话是否激活。 */
    public boolean isActive() {
        return active;
    }

    /** 当前相机位置（世界坐标）。 */
    public Vec3 getPos() {
        return pos;
    }

    /** 当前 yaw（度）。 */
    public float getYaw() {
        return yaw;
    }

    /** 当前 pitch（度）；鼠标移动时钳制 ±90。 */
    public float getPitch() {
        return pitch;
    }

    /** 当前 roll（度）。 */
    public float getRoll() {
        return roll;
    }

    /** 当前 FOV（度）；连续微调时钳制 30~110。 */
    public float getFov() {
        return fov;
    }

    /** 当前 zoom 倍率（钳制 0.5~100）。 */
    public float getZoom() {
        return zoom;
    }

    /** 相机参数是否按绝对坐标解释（否则相对模式）；由飞行模式键切换。 */
    public boolean isModeAbsolute() {
        return modeAbsolute;
    }

    /** 是否按住慢速键（Ctrl）。 */
    public boolean isSlowDown() {
        return keySlow;
    }

    /**
     * 进入飞控：以给定六参数为起点并立即写入相机，同时记录为取消时的恢复点。
     *
     * 起始 zoom 越界钳制到 [0.5, 100]；按键状态全部清零（避免沿用上一会话的按下标记）。
     */
    public void enter(Vec3 startPos, float startYaw, float startPitch,
                      float startRoll, float startFov, float startZoom,
                      boolean startModeAbsolute) {
        this.active = true;
        this.pos = startPos;
        this.startPos = startPos;
        this.yaw = startYaw;
        this.pitch = startPitch;
        this.roll = startRoll;
        this.fov = startFov;
        this.zoom = Math.max(0.5f, Math.min(100f, startZoom));
        this.startYaw = startYaw;
        this.startPitch = startPitch;
        this.startRoll = startRoll;
        this.startFov = startFov;
        this.startZoom = this.zoom;
        this.modeAbsolute = startModeAbsolute;
        this.lastNanos = System.nanoTime();
        resetKeys();
        applyToCamera();
    }

    /** 结束飞控会话，保留当前相机参数（不写回、不恢复）。 */
    public void exit() {
        this.active = false;
        resetKeys();
    }

    /** 取消飞控：恢复进入时的六参数并写回相机，再结束会话。 */
    public void cancel() {
        this.pos = startPos;
        this.yaw = startYaw;
        this.pitch = startPitch;
        this.roll = startRoll;
        this.fov = startFov;
        this.zoom = startZoom;
        applyToCamera();
        this.active = false;
        resetKeys();
    }

    /** 只重置 FOV / Zoom / Roll 到进入飞行时的初始值，不影响位置/Yaw/Pitch */
    public void resetOptics() {
        if (!active) return;
        this.roll = startRoll;
        this.fov = startFov;
        this.zoom = startZoom;
        holdFov = 0f;
        holdZoom = 0f;
        holdRoll = 0f;
        currentFovRate = 0f;
        currentZoomRate = 0f;
        currentRollRate = 0f;
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

        if (key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            keySlow = down;
        }

        if (CinematicKeyBindings.EDITOR_FLIGHT_MODE.matches(key, scanCode) && action == 1) {
            modeAbsolute = !modeAbsolute;
        }
        if (CinematicKeyBindings.EDITOR_FLIGHT_RESET_OPTICS.matches(key, scanCode) && action == 1) {
            resetOptics();
        }
        if (CinematicKeyBindings.EDITOR_FLIGHT_FOV_IN.matches(key, scanCode)) {
            keyFovIn = down;
        }
        if (CinematicKeyBindings.EDITOR_FLIGHT_FOV_OUT.matches(key, scanCode)) {
            keyFovOut = down;
        }
        if (CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_IN.matches(key, scanCode)) {
            keyZoomIn = down;
        }
        if (CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_OUT.matches(key, scanCode)) {
            keyZoomOut = down;
        }
        if (CinematicKeyBindings.EDITOR_FLIGHT_ROLL_LEFT.matches(key, scanCode)) {
            keyRollLeft = down;
        }
        if (CinematicKeyBindings.EDITOR_FLIGHT_ROLL_RIGHT.matches(key, scanCode)) {
            keyRollRight = down;
        }
    }

    /** 每帧读键盘，驱动位移 */
    public void tick() {
        if (!active) return;

        long now = System.nanoTime();
        float deltaTime = Math.min(0.05f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        float forward = (keyUp ? 1f : 0f) - (keyDown ? 1f : 0f);
        float strafe = (keyLeft ? 1f : 0f) - (keyRight ? 1f : 0f);
        float speed = keySlow ? 3f : 10f;

        double yawRad = Math.toRadians(yaw);
        Vec3 forwardVec = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 rightVec = new Vec3(Math.cos(yawRad), 0, Math.sin(yawRad));
        Vec3 move = forwardVec.scale(forward).add(rightVec.scale(strafe)).scale(speed * deltaTime);
        if (keyJump) move = move.add(0, speed * deltaTime, 0);
        if (keyShift) move = move.add(0, -speed * deltaTime, 0);

        pos = pos.add(move);

        // A+B 混合：hold 时间驱动 t² 目标速率，再经一阶滞后平滑逼近
        if (keyFovIn || keyFovOut) holdFov = Math.min(RAMP_TIME, holdFov + deltaTime);
        else holdFov = 0f;
        if (keyZoomIn || keyZoomOut) holdZoom = Math.min(RAMP_TIME, holdZoom + deltaTime);
        else holdZoom = 0f;
        if (keyRollLeft || keyRollRight) holdRoll = Math.min(RAMP_TIME, holdRoll + deltaTime);
        else holdRoll = 0f;

        float targetFovRate = (keyFovIn || keyFovOut) ? rampRate(holdFov, FOV_MIN_RATE, FOV_MAX_RATE) : 0f;
        float targetZoomRate = (keyZoomIn || keyZoomOut) ? rampRate(holdZoom, ZOOM_MIN_RATE, ZOOM_MAX_RATE) : 0f;
        float targetRollRate = (keyRollLeft || keyRollRight) ? rampRate(holdRoll, ROLL_MIN_RATE, ROLL_MAX_RATE) : 0f;
        float lag = 1f - (float) Math.exp(-deltaTime / LAG_TAU);
        currentFovRate += (targetFovRate - currentFovRate) * lag;
        currentZoomRate += (targetZoomRate - currentZoomRate) * lag;
        currentRollRate += (targetRollRate - currentRollRate) * lag;

        if (keyFovIn) fov = Math.min(110f, fov + currentFovRate * deltaTime);
        if (keyFovOut) fov = Math.max(30f, fov - currentFovRate * deltaTime);
        if (keyZoomIn) zoom = Math.min(100f, zoom * (1f + currentZoomRate * deltaTime));
        if (keyZoomOut) zoom = Math.max(0.5f, zoom / (1f + currentZoomRate * deltaTime));
        if (keyRollLeft) roll -= currentRollRate * deltaTime;
        if (keyRollRight) roll += currentRollRate * deltaTime;

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

    /** A+B 混合中的 A 部分：t² 曲线算出目标速率，t 从 0→1，rate 从 min 快速爬向 max */
    private static float rampRate(float hold, float minRate, float maxRate) {
        float t = Math.min(1f, hold / RAMP_TIME);
        return minRate + (maxRate - minRate) * t * t;
    }

    private void resetKeys() {
        keyUp = false;
        keyDown = false;
        keyLeft = false;
        keyRight = false;
        keyJump = false;
        keyShift = false;
        keySlow = false;
        keyFovIn = false;
        keyFovOut = false;
        keyZoomIn = false;
        keyZoomOut = false;
        keyRollLeft = false;
        keyRollRight = false;
        holdFov = 0f;
        holdZoom = 0f;
        holdRoll = 0f;
        currentFovRate = 0f;
        currentZoomRate = 0f;
        currentRollRate = 0f;
    }

    private void applyToCamera() {
        if (!active) return;
        // 写侧收口：经 CameraManager 门面写六参数（写完立即刷新统一快照），禁止直写内部对象
        CameraManager.INSTANCE.setCameraDirect(pos, yaw, pitch, roll, fov, zoom);
    }
}
