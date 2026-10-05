package com.immersivecinematics.immersive_cinematics.proto;

import com.immersivecinematics.immersive_cinematics.camera.CameraPath;
import com.immersivecinematics.immersive_cinematics.camera.CameraProperties;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 四象限同屏原型（一次性，测完即删）。
 *
 * <p>2×2 四个象限，每象限一个<b>独立的"我们模组的相机"实例</b>：
 * 一个原版 {@link Camera} + 一份独立的模组相机状态（{@link CameraPath} 位置 +
 * {@link CameraProperties} yaw/pitch/roll/fov/zoom）。四个相机分别从 +x / −x / +z / −z
 * 四个方向<b>朝玩家推进、始终看着玩家</b>；推进到"一半"（d=24）时自动出图：
 * <b>每象限一张 + 游戏主图一张</b>——用来评定"我们的相机"对方块层 + 实体层是否都取景正确
 * （玩家实体应在四个象限里都正常出现）。</p>
 *
 * <p>相机接管走模组自己的路径：{@code Camera.setup()} → {@code CameraMixin}（原型分支读取
 * 本实例的模组相机状态，与生产分支同一套接管逻辑）；fov/zoom 走
 * {@code GameRendererMixin.getFov} 的原型分支。</p>
 *
 * <p>开关：{@code -Dicinematics.quadrant=true} 或环境变量 {@code ICINEMATICS_QUADRANT=1}；
 * 默认关，关闭时与现状零差异。</p>
 *
 * <p>yaw 口径（docs/AI_SCRIPTING_GUIDE.md §1.4）：0=南(+z)、90=西(−x)、180=北(−z)、−90=东(+x)；
 * 相机在 +x 侧 → yaw 90（朝西看），−x 侧 → −90，+z 侧 → 180，−z 侧 → 0。</p>
 */
public final class QuadrantProto {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final boolean ENABLED =
            Boolean.getBoolean("icinematics.quadrant") || System.getenv("ICINEMATICS_QUADRANT") != null;

    /** 象限顺序：右上 +x（yaw 90）/ 左上 −x（yaw −90）/ 右下 +z（yaw 180）/ 左下 −z（yaw 0）。 */
    public static final double[][] DIRS = {{1.0, 0.0}, {-1.0, 0.0}, {0.0, 1.0}, {0.0, -1.0}};
    public static final float[] YAWS = {90.0F, -90.0F, 180.0F, 0.0F};
    public static final String[] NAMES = {"plusX", "minusX", "plusZ", "minusZ"};

    private static final long PERIOD_MS = 12_000L;          // 推进/后退往返周期
    private static final double D_NEAR = 12.0;              // 最近距离（格）
    private static final double D_FAR = 36.0;               // 最远距离（格）
    private static final double D_MID = (D_NEAR + D_FAR) / 2.0;   // 一半 = 24 格
    private static final double HEIGHT_ABOVE_EYE = 2.0;     // 相机高于玩家眼高
    private static final long WARMUP_MS = 10_000L;          // 预热：前 10 秒不截
    private static final float DEFAULT_FOV = 70.0F;         // 模组相机默认 FOV

    private static final ProtoCamera[] CAMERAS = new ProtoCamera[4];
    private static final Map<Camera, ProtoCamera> BY_CAMERA = new IdentityHashMap<>();
    /** 每象限一张离屏缓冲：整尺寸渲染该象限的相机画面，再缩放贴到屏幕象限里。 */
    private static final RenderTarget[] TARGETS = new RenderTarget[4];

    static {
        for (int i = 0; i < 4; i++) {
            ProtoCamera pc = new ProtoCamera();
            CAMERAS[i] = pc;
            BY_CAMERA.put(pc.camera(), pc);
        }
    }

    private static long startMs = -1L;
    private static double prevD = Double.MAX_VALUE;
    private static boolean captured;
    private static boolean testEntitiesSpawned;
    /** 是否正在做某个象限的 lane 渲染（供描边上屏分流：我们自己调的那次 vs 原版整屏那次）。 */
    private static boolean laneRendering;

    /**
     * 临时测试实体（一次性，随原型一起删）：在玩家周围召唤，验证"实体层在四象限里是否渲染正确"。
     * 被动生物保留 AI（会走动），敌对生物一律 {@code NoAI:1b}（不追不打不炸，只验证渲染）。
     */
    private static final String[][] TEST_ENTITIES = {
            {"minecraft:cow", "{}"},
            {"minecraft:pig", "{}"},
            {"minecraft:sheep", "{}"},
            {"minecraft:chicken", "{}"},
            {"minecraft:horse", "{}"},
            {"minecraft:zombie", "{NoAI:1b}"},
            {"minecraft:skeleton", "{NoAI:1b}"},
            {"minecraft:creeper", "{NoAI:1b}"},
            {"minecraft:spider", "{NoAI:1b}"},
            {"minecraft:enderman", "{NoAI:1b}"},
            {"minecraft:witch", "{NoAI:1b}"},
            {"minecraft:armor_stand", "{ShowArms:1b,NoGravity:1b}"},
            {"minecraft:item", "{Item:{id:\"minecraft:diamond\",Count:1b}}"},
    };

    private QuadrantProto() {
    }

    /**
     * 一个"我们模组的相机"实例：独立原版 {@link Camera} + 独立模组相机状态。
     * 状态读取走 {@link #path()} / {@link #props()}，应用走 {@code CameraMixin} 原型分支。
     */
    public static final class ProtoCamera {
        private final Camera camera = new Camera();
        private final CameraPath path = new CameraPath();
        private final CameraProperties props = new CameraProperties();

        public Camera camera() {
            return camera;
        }

        public CameraPath path() {
            return path;
        }

        public CameraProperties props() {
            return props;
        }
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** 是否正在做某个象限的 lane 渲染（原型自己调用描边上屏的窗口期）。 */
    public static boolean isLaneRendering() {
        return laneRendering;
    }

    public static void setLaneRendering(boolean value) {
        laneRendering = value;
    }

    /** 第 i 个象限的模组相机实例。 */
    public static ProtoCamera camera(int i) {
        return CAMERAS[i];
    }

    /** 原型相机实例 → 其模组相机状态；非原型相机返回 {@code null}（供 mixin 分支判断）。 */
    public static ProtoCamera cameraOf(Camera camera) {
        return BY_CAMERA.get(camera);
    }

    /** 第 i 个象限的离屏缓冲（按主画面尺寸创建/重建；只在渲染线程调用）。 */
    public static RenderTarget target(int i, int width, int height) {
        RenderTarget target = TARGETS[i];
        if (target == null) {
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            TARGETS[i] = target;
        } else if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
        return target;
    }

    /** 每帧更新四个相机的模组相机状态（位置 + yaw/pitch/roll/fov/zoom）。 */
    public static void update(Player player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        double d = currentDistance();
        float pitch = (float) Math.toDegrees(Math.atan2(HEIGHT_ABOVE_EYE, d));   // 正=向下看
        for (int i = 0; i < 4; i++) {
            double[] dir = DIRS[i];
            ProtoCamera pc = CAMERAS[i];
            pc.path().setPositionDirect(new Vec3(eye.x + dir[0] * d, eye.y + HEIGHT_ABOVE_EYE, eye.z + dir[1] * d));
            pc.props().setAllDirect(YAWS[i], pitch, 0.0F, DEFAULT_FOV, 1.0F);
        }
    }

    /**
     * 临时测试准备（一次性，随原型一起删）：玩家切创造模式 + 在玩家周围召唤各种实体。
     *
     * <p>经集成服务端以控制台权限（level 4）执行命令，因此不依赖世界是否开作弊；
     * 命令调度到服务端线程执行（{@code server.execute}）。</p>
     */
    public static void setupTestEntities(Minecraft mc) {
        if (testEntitiesSpawned) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || server.getPlayerList().getPlayers().isEmpty()) {
            return;   // 还没进世界/服务端还没就绪，下一帧再试
        }
        testEntitiesSpawned = true;

        String playerName = mc.player.getGameProfile().getName();
        Vec3 origin = mc.player.position();
        server.execute(() -> {
            Commands commands = server.getCommands();
            CommandSourceStack source = server.createCommandSourceStack();
            commands.performPrefixedCommand(source, "gamemode creative " + playerName);
            int count = TEST_ENTITIES.length;
            for (int i = 0; i < count; i++) {
                double angle = (Math.PI * 2.0 / count) * i;
                double radius = 3.5 + (i % 2) * 2.5;   // 交错 3.5 / 6.0 格
                double x = origin.x + Math.cos(angle) * radius;
                double z = origin.z + Math.sin(angle) * radius;
                String[] entry = TEST_ENTITIES[i];
                commands.performPrefixedCommand(source, String.format(Locale.ROOT,
                        "summon %s %.2f %.2f %.2f %s", entry[0], x, origin.y, z, entry[1]));
            }
            // 给骷髅上发光 → 顺带验证描边（entityTarget）那条路径
            commands.performPrefixedCommand(source, "effect give @e[type=minecraft:skeleton] minecraft:glowing 1000000 0 true");
            // 用户指定：玩家南侧 6 格的"高血量发光骷髅"（站桩、不消失，用来单独看发光描边）
            commands.performPrefixedCommand(source, String.format(Locale.ROOT,
                    "summon minecraft:skeleton %.2f %.2f %.2f {NoAI:1b,PersistenceRequired:1b,"
                            + "Attributes:[{Name:\"generic.max_health\",Base:1024.0}],Health:1024.0f,"
                            + "ActiveEffects:[{Id:14b,Amplifier:0b,Duration:1000000,ShowParticles:0b}]}",
                    origin.x, origin.y, origin.z + 6.0));
            LOGGER.info("[quadrant] test entities summoned: {} kinds around player", count);
        });
    }

    /** 当前推进距离 d(t)：12 → 36 → 12 往返。 */
    public static double currentDistance() {
        double phase = (double) (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
        return D_NEAR + (D_FAR - D_NEAR) * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2.0));
    }

    /** 是否该出图：相机**朝玩家推进**、越过一半距离时触发一次（预热期内不触发）。 */
    public static boolean shouldCapture() {
        if (startMs < 0L) {
            startMs = System.currentTimeMillis();
        }
        double d = currentDistance();
        boolean crossed = prevD > D_MID && d <= D_MID;
        prevD = d;
        if (captured || !crossed) {
            return false;
        }
        if (System.currentTimeMillis() - startMs < WARMUP_MS) {
            return false;
        }
        captured = true;
        return true;
    }

    public static void logCaptured() {
        LOGGER.info("[quadrant] captured at halfway: main + 4 quadrants (d={} blocks)", String.format("%.1f", currentDistance()));
    }
}
