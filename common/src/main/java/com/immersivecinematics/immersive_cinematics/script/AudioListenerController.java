package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.mixin.CameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

/**
 * 音频听者控制（0.3.5 第4轮 A；0.3.6 主相机替换链退役后听者相机全部由代理提供）：
 * 脚本 meta.listener == "camera" → 听者相机 = 镜头代理（{@link #cameraListener()}，位置/朝向取自
 * 统一相机状态快照）；== "player"（默认）→ 听者相机 = 玩家代理（{@link #playerCamera()}）。
 * 两者都经 {@code SoundManagerMixin} 交给原版 {@code SoundEngine.updateSource}。
 */
public final class AudioListenerController {

    private static CinematicScript cachedScript;
    private static String cachedListener = "player";

    private AudioListenerController() {}

    /** 是否需要在 SoundManager.updateSource 时把听者覆盖为玩家（仅过场激活且有效听者不是相机） */
    public static boolean shouldOverride() {
        // 与 isCameraListener() 同一口径（顶层实例，§3.3），二者恒互补：
        // 有效听者不是相机 → 覆盖为玩家；是相机 → 覆盖为镜头代理（cameraListener）。
        return CameraManager.INSTANCE.isActive() && !isCameraListener();
    }

    /**
     * 是否听者=相机（用于环境音采样点重定向）。
     * <p>
     * 口径（§3.3 后来者居上）：听者由<b>顶层实例</b>（启动最晚的活跃实例）决定——既读它的
     * {@code meta.listener}，也只看它自身本帧是否有活跃 CAMERA clip
     * （{@link CameraManager#topInstanceHasActiveCameraClip()}），而不是全实例并集：
     * 并集会让下层实例的 CAMERA clip 把顶层自身没有 CAMERA clip 时的听者误判为 camera。
     * <p>
     * 必须同时有活跃 CAMERA clip：顶层实例没有 CAMERA clip 时其 {@code meta.listener=camera}
     * 也回落 player——与画面没有 lane 覆盖（= 原版玩家视角）时听者落在玩家一致。
     */
    public static boolean isCameraListener() {
        return CameraManager.INSTANCE.isActive()
                && CameraManager.INSTANCE.topInstanceHasActiveCameraClip()
                && "camera".equals(listenerMode());
    }

    /** 当前听者世界坐标：camera → 镜头位置；player → 玩家位置 */
    public static net.minecraft.world.phys.Vec3 getListenerPosition() {
        CameraState state = CameraManager.INSTANCE.getCameraState();
        if (isCameraListener() && state != null) {
            return state.position();
        }
        net.minecraft.client.Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.position() : net.minecraft.world.phys.Vec3.ZERO;
    }

    /** 构造玩家视角代理 Camera（位置=玩家、朝向=玩家视线） */
    public static Camera playerCamera() {
        Minecraft mc = Minecraft.getInstance();
        Camera proxy = new Camera();
        if (mc.level != null && mc.player != null) {
            proxy.setup(mc.level, mc.player, false, false, 0.0F);
        }
        return proxy;
    }

    /**
     * 构造镜头视角代理 Camera（位置/朝向 = 统一快照 = 顶层实例顶层活跃 clip 的相机状态）。
     * <p>
     * 主相机替换链退役后，原版主相机就是玩家相机（{@code Minecraft.tick} 把
     * {@code gameRenderer.getMainCamera()} 交给 {@code SoundEngine.updateSource}），
     * listener=camera 不再"天然成立"，必须显式把听者相机搬到镜头位置。
     * 无快照（本帧无活跃相机）时回落玩家代理，与 {@link #getListenerPosition()} 的回落一致。
     */
    public static Camera cameraListener() {
        CameraState state = CameraManager.INSTANCE.getCameraState();
        if (state == null) {
            return playerCamera();
        }
        Camera proxy = new Camera();
        CameraAccessor accessor = (CameraAccessor) (Object) proxy;
        net.minecraft.world.phys.Vec3 pos = state.position();
        accessor.ic$setPosition(pos.x, pos.y, pos.z);
        accessor.ic$setRotation(state.yaw(), state.pitch());
        // 原版 SoundEngine.updateSource 要求 isInitialized()（原由 Camera.setup 置位）
        accessor.ic$setInitialized(true);
        return proxy;
    }

    private static String listenerMode() {
        PlaybackInstance instance = CameraManager.INSTANCE.activeInstance();
        CinematicScript script = instance != null ? instance.script() : null;
        if (script == null) return "player";
        // 按脚本对象刷新：同一 id 的脚本被编辑器增量替换后，listener 变化也能生效
        if (script != cachedScript) {
            cachedScript = script;
            cachedListener = "player";
            String raw = script.getRawJson();
            if (raw != null && !raw.isEmpty()) {
                try {
                    JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                    if (root.has("meta") && root.get("meta").isJsonObject()) {
                        JsonObject meta = root.getAsJsonObject("meta");
                        if (meta.has("listener")) {
                            cachedListener = meta.get("listener").getAsString();
                        }
                    }
                } catch (RuntimeException ignored) {
                    // rawJson 异常时按默认 player 处理，不影响正常播放
                }
            }
        }
        return cachedListener;
    }
}
