package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

/**
 * 音频听者控制（0.3.5 第4轮 A）：
 * 脚本 meta.listener == "camera" → 由 CameraMixin 让原版用电影相机（天然成立）；
 * == "player"（默认）→ 由 {@code SoundManagerMixin} 每帧把 listener 设为玩家视角代理。
 */
public final class AudioListenerController {

    private static String cachedScriptId = "";
    private static String cachedListener = "player";

    private AudioListenerController() {}

    /** 是否需要在 SoundManager.updateSource 时把听者覆盖为玩家（仅过场激活且脚本声明 player） */
    public static boolean shouldOverride() {
        if (!CameraManager.INSTANCE.isActive()) return false;
        return !"camera".equals(listenerMode());
    }

    /** 是否听者=相机（用于环境音采样点重定向） */
    public static boolean isCameraListener() {
        return CameraManager.INSTANCE.isActive() && "camera".equals(listenerMode());
    }

    /** 当前听者世界坐标：camera → 镜头位置；player → 玩家位置 */
    public static net.minecraft.world.phys.Vec3 getListenerPosition() {
        if (isCameraListener() && CameraManager.INSTANCE.getPath() != null) {
            return CameraManager.INSTANCE.getPath().getPosition();
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

    private static String listenerMode() {
        CinematicScript script = CameraManager.INSTANCE.getScriptPlayer().getScript();
        if (script == null) return "player";
        String sid = script.getId();
        if (!sid.equals(cachedScriptId)) {
            cachedScriptId = sid;
            cachedListener = "player";
            Object raw = script.getRawJson();
            if (raw instanceof String s && !s.isEmpty()) {
                JsonObject root = JsonParser.parseString(s).getAsJsonObject();
                if (root.has("meta") && root.get("meta").isJsonObject()) {
                    JsonObject meta = root.getAsJsonObject("meta");
                    if (meta.has("listener")) {
                        cachedListener = meta.get("listener").getAsString();
                    }
                }
            }
        }
        return cachedListener;
    }
}
