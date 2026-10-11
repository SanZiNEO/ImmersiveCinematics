package com.immersivecinematics.immersive_cinematics.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端实体数诊断：过场播放期间每 100 客户端 tick 打印一次相机半径内的实际实体数。
 *
 * 采样口径：有活跃播放实例且客户端有世界时，以当前写入缓冲的相机位置为中心、半径 64 格（方块）的
 * 立方体范围内全部实体计数；无活跃实例时不计次、不打印。读日志可判断实体跟踪 / 预加载范围是否正常。
 * 线程契约：仅客户端主线程；计数器随本实例存活，不复位。
 */
final class ClientEntityProbe {

    /** 日志通道名固定为拆分前值，保持日志输出逐行不变。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    /** 采样立方体半径（方块，三轴同值）。 */
    private static final int RADIUS = 64;

    /** 打印间隔（客户端 tick）。 */
    private static final int LOG_INTERVAL_TICKS = 100;

    private final PlaybackRegistry registry;
    private final CameraStateHolder stateHolder;

    /** 已采样 tick 计数。 */
    private int tickCounter = 0;

    ClientEntityProbe(PlaybackRegistry registry, CameraStateHolder stateHolder) {
        this.registry = registry;
        this.stateHolder = stateHolder;
    }

    /** 每客户端 tick 采样一次；无活跃实例 / 客户端无世界时跳过。 */
    void tick() {
        if (!registry.isActive()) return;
        tickCounter++;
        if (tickCounter % LOG_INTERVAL_TICKS != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 pos = stateHolder.path().getPosition();
        AABB box = new AABB(pos.x - RADIUS, pos.y - RADIUS, pos.z - RADIUS,
                pos.x + RADIUS, pos.y + RADIUS, pos.z + RADIUS);
        int count = mc.level.getEntities((Entity) null, box, e -> true).size();
        LOGGER.info("[camera-client] 实际实体数 radius={} count={} pos=({},{},{})",
                RADIUS, count,
                String.format("%.1f", pos.x), String.format("%.1f", pos.y), String.format("%.1f", pos.z));
    }
}
