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
 * 相机听者位置上报器：把脚本镜头位置同步给服务端音频听者。
 *
 * 仅在 {@link AudioListenerController#isCameraListener()} 为真（meta.listener=camera 且顶层实例
 * 有活跃 CAMERA clip）时上报；位置不变不重复发包。退出该状态时发一次 active=false 释放服务端状态。
 */
public final class AudioListenerReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/AudioListener");

    /** 由客户端 tick 驱动的唯一实例。 */
    public static final AudioListenerReporter INSTANCE = new AudioListenerReporter();

    private boolean active = false;
    /** 上次上报的世界坐标（方块）。 */
    private double x;
    private double y;
    private double z;

    private AudioListenerReporter() {}

    /**
     * 每客户端 tick 调用：首次进入相机听者或位置变化时上报，退出时释放。
     *
     * @param mc 客户端实例；{@code null} 或未进入世界按「不上报」处理
     */
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
