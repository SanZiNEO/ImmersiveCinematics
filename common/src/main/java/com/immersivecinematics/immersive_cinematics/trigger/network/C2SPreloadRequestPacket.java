package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ChunkPreloadManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** 区块预加载请求（C2S）：模式 PRELOAD(0) / PREWARM(1) / RELEASE(2)，携带相机中心坐标（方块）+ 窗口半径 */
public class C2SPreloadRequestPacket implements CinematicC2SPacket {

    public static final int MODE_PRELOAD = 0;
    public static final int MODE_PREWARM = 1;
    public static final int MODE_RELEASE = 2;

    /** RELEASE 等不需要相机刷怪信息的模式使用的默认值 */
    public static final boolean DEFAULT_CAMERA_MOB_SPAWN = false;
    public static final int DEFAULT_CAMERA_MOB_RADIUS = 2;
    public static final boolean DEFAULT_CAMERA_MOB_AI = false;
    public static final boolean DEFAULT_CAMERA_MODE = false;

    private final int mode;
    private final String scriptId;
    private final int x;
    private final int z;
    private final int radius;
    private final float yaw;
    private final int renderDistance;
    private final boolean cameraMode;
    private final boolean cameraMobSpawn;
    private final int cameraMobRadius;
    private final boolean cameraMobAi;

    public C2SPreloadRequestPacket(int mode, String scriptId, int x, int z, int radius, float yaw, int renderDistance,
                                   boolean cameraMode,
                                   boolean cameraMobSpawn, int cameraMobRadius, boolean cameraMobAi) {
        this.mode = mode;
        this.scriptId = scriptId;
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.yaw = yaw;
        this.renderDistance = renderDistance;
        this.cameraMode = cameraMode;
        this.cameraMobSpawn = cameraMobSpawn;
        this.cameraMobRadius = cameraMobRadius;
        this.cameraMobAi = cameraMobAi;
    }

    public C2SPreloadRequestPacket(FriendlyByteBuf buf) {
        this.mode = buf.readByte();
        this.scriptId = buf.readUtf();
        this.x = buf.readInt();
        this.z = buf.readInt();
        this.radius = buf.readInt();
        this.yaw = buf.readFloat();
        this.renderDistance = buf.readInt();
        this.cameraMode = buf.readBoolean();
        this.cameraMobSpawn = buf.readBoolean();
        this.cameraMobRadius = buf.readInt();
        this.cameraMobAi = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeByte(mode);
        buf.writeUtf(scriptId);
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeInt(radius);
        buf.writeFloat(yaw);
        buf.writeInt(renderDistance);
        buf.writeBoolean(cameraMode);
        buf.writeBoolean(cameraMobSpawn);
        buf.writeInt(cameraMobRadius);
        buf.writeBoolean(cameraMobAi);
    }

    @Override
    public void handle(ServerPlayer player) {
        // 平台网络层保证在主线程执行（addRegionTicket/removeRegionTicket/connection.send 必须主线程）
        ChunkPreloadManager.INSTANCE.handleRequest(player, mode, scriptId, x, z, radius, yaw, renderDistance,
                cameraMode,
                cameraMobSpawn, cameraMobRadius, cameraMobAi);
    }
}
