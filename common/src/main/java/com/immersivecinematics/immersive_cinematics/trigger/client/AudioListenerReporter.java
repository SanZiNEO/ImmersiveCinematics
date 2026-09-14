package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.script.AudioListenerController;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SAudioListenerPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 相机听者位置上报器。
 * <p>
 * 仅在 {@link AudioListenerController#isCameraListener()} 为真（脚本 meta.listener=camera
 * 且当前有活跃 CAMERA clip）时，把镜头位置发给服务端；位置不变不重复发。
 * 退出相机听者时发一次 active=false 释放服务端状态。
 */
public final class AudioListenerReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/AudioListener");

    public static final AudioListenerReporter INSTANCE = new AudioListenerReporter();

    private boolean active = false;
    private double x;
    private double y;
    private double z;

    private AudioListenerReporter() {}

    public void tick(Minecraft mc) {
        boolean shouldReport = mc != null
                && mc.level != null
                && mc.player != null
                && AudioListenerController.isCameraListener();

        if (!shouldReport) {
            if (active) {
                active = false;
                LOGGER.info("[audio-listener] release");
                send(false, 0.0, 0.0, 0.0);
            }
            return;
        }

        Vec3 pos = AudioListenerController.getListenerPosition();
        if (!active) {
            active = true;
            x = pos.x;
            y = pos.y;
            z = pos.z;
            LOGGER.info("[audio-listener] camera pos=({},{},{})",
                    String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z));
            send(true, x, y, z);
        } else if (pos.x != x || pos.y != y || pos.z != z) {
            x = pos.x;
            y = pos.y;
            z = pos.z;
            send(true, x, y, z);
        }
    }

    private static void send(boolean active, double x, double y, double z) {
        NetworkGuard.sendToServer("C2SAudioListener",
                () -> NetworkHandler.sendToServer(new C2SAudioListenerPacket(active, x, y, z)));
    }
}
