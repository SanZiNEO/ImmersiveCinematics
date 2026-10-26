package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端可见性诊断（C4 / C5 / C6）：每个渲染 pass 采样一次实体可见性分类，按 tick 节拍打印 ASCII 日志。
 *
 * 采样点 = {@code LevelRenderer.renderLevel} 的 RETURN（见 {@code LevelRendererMixin}）：此刻该 pass 的
 * {@code cullingFrustum} 已就绪、{@link LaneRenderer} 的 lane 上下文仍然有效，且每帧主 pass 与每个 lane pass
 * 各触发一次。只采样主 pass（pass 相机 == {@code GameRenderer.getMainCamera()}）与 lane pass，其余嵌套 pass
 * （如 Iris 阴影 pass）跳过。
 *
 * 口径：
 * <ul>
 *   <li>{@code cam} = 该 pass 的相机位置；{@code vc} = 该 pass 实际使用的视图中心
 *       （lane pass = 本 lane 相机；非 lane pass = 共享网格中心，取 {@link LaneRenderer#topLane()} 且该 lane
 *       跨维度时回退玩家中心——C4 边界：非末位 lane 的实体在该共享网格口径下可能记为 {@code uncompiled}）；
 *       {@code sent} = 服务端当前维度中心的上报源（{@link LaneRenderer#currentDimensionCenter(String)}，
 *       无当前维度 lane 时回落玩家位置；目标维度 view 不参与）。</li>
 *   <li>半径内分桶互斥且完整：{@code r128 = visible + distOut + uncompiled + frustumOut}，每个实体恰好计入一个桶。</li>
 *   <li>{@code uncompiled} / {@code frustumOut} 只对原版渲染器成立：Sodium / Rubidium / Embeddium 改写渲染管线时
 *       {@code isChunkCompiled} 与 {@code cullingFrustum} 不代表其可见性；{@code distOut} 与渲染器无关。</li>
 * </ul>
 * 门控：{@link Config#debugLogging} 关闭时不采样、不构造字符串、不打印；采样结果按 pass 保存，只在 tick 节拍输出。
 * 线程契约：仅客户端主线程（渲染与 tick 同线程）。
 */
final class ClientEntityProbe {

    /** 日志通道名固定为 {@code CameraManager}（日志格式契约）。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CameraManager");

    /** 主 pass 的样本键；其余键 = {@code captureId#laneIndex}。 */
    private static final String MAIN_PASS = "main";

    /** 诊断半径（方块）。 */
    private static final int R64 = 64;
    private static final int R96 = 96;
    private static final int R128 = 128;
    private static final double R64_SQ = (double) R64 * R64;
    private static final double R96_SQ = (double) R96 * R96;
    private static final double R128_SQ = (double) R128 * R128;

    /** 调试开启时的打印间隔（客户端 tick）。 */
    private static final int DEBUG_LOG_INTERVAL_TICKS = 20;
    /** 每 lane 每秒最多打印的主视角可见 / 相机不可见实体明细条数；超出只留汇总。 */
    private static final int DIFF_DETAIL_LIMIT = 4;
    /** 调试关闭时的打印间隔（客户端 tick）；此时无样本，实际不打印。 */
    private static final int NORMAL_LOG_INTERVAL_TICKS = 100;

    private final PlaybackRegistry registry;

    /** 最近一帧各 pass 的样本（按插入序：主 pass 在前）；主 pass 开始时清空，lane pass 依次追加。 */
    private final Map<String, PassSample> samples = new LinkedHashMap<>();

    /** 已采样 tick 计数。 */
    private int tickCounter = 0;

    ClientEntityProbe(PlaybackRegistry registry) {
        this.registry = registry;
    }

    /**
     * 每客户端 tick 打印一次最近一帧的样本（每个 pass 一行）；无活跃实例时丢弃样本。
     * 调试开启时 20 tick、关闭时 100 tick 为间隔（关闭时无样本，不会打印）。
     */
    void tick() {
        tickCounter++;
        if (!registry.isActive()) {
            samples.clear();
            return;
        }
        int interval = Config.debugLogging ? DEBUG_LOG_INTERVAL_TICKS : NORMAL_LOG_INTERVAL_TICKS;
        if (tickCounter % interval != 0) return;
        if (samples.isEmpty()) return;
        PassSample main = samples.get(MAIN_PASS);
        String perLane = main == null ? null : laneSummary();
        for (PassSample sample : samples.values()) {
            printSample(sample, sample == main ? perLane : null);
        }
        if (main != null) {
            for (PassSample sample : samples.values()) {
                if (sample != main) printDiff(main, sample);
            }
        }
    }

    /**
     * 渲染 pass 采样入口（{@code LevelRenderer.renderLevel} RETURN）。
     *
     * @param renderer 该 pass 使用的 {@code LevelRenderer}（{@code isChunkCompiled} 判定源）
     * @param frustum  该 pass 的剔除视锥（{@code cullingFrustum}）
     * @param camera   该 pass 的相机
     */
    void onRenderPass(LevelRenderer renderer, Frustum frustum, Camera camera) {
        if (!Config.debugLogging || !registry.isActive()) return;
        if (renderer == null || frustum == null || camera == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        boolean lanePass = LaneRenderer.isRenderingLane();
        // 非 lane pass 只认玩家相机（GameRenderer.getMainCamera）：Iris 阴影等嵌套 pass 的相机不同，
        // 采样会覆盖非 lane pass 的样本
        if (!lanePass && camera != mc.gameRenderer.getMainCamera()) return;

        String pass;
        if (lanePass) {
            String captureId = LaneRenderer.activeLaneCaptureId();
            // 同一条 CAMERA 轨道的上下两个 lane 共用 captureId，必须带 lane 序号才能区分
            pass = (captureId != null ? captureId : "lane") + "#" + LaneRenderer.activeLaneIndex();
        } else {
            pass = MAIN_PASS;
        }

        Vec3 cam = camera.getPosition();
        // lane pass：本 lane 相机；非 lane pass：默认玩家坐标（mixin 返回 null 时的口径），下面按共享网格中心覆盖
        Vec3 vc = lanePass ? cam : mc.player.position();
        // lane pass 内 mc.level 已被换到目标视图；玩家当前维度必须从玩家实体读（它的 level 不被换入换出）
        ClientLevel playerLevel = mc.player.level() instanceof ClientLevel level ? level : mc.level;
        String currentDimension = DimensionViews.currentDimensionId(playerLevel);
        if (!lanePass) {
            LaneRenderer.Lane top = LaneRenderer.INSTANCE.topLane();
            if (top != null && top.state() != null) {
                String dimension = top.dimension();
                if (dimension == null || dimension.isEmpty() || dimension.equals(currentDimension)) {
                    vc = top.state().position();
                }
            }
        }
        Vec3 sent = LaneRenderer.INSTANCE.currentDimensionCenter(currentDimension);
        if (sent == null) {
            sent = mc.player.position();
        }

        PassSample sample = new PassSample();
        sample.pass = pass;
        sample.gameTime = mc.level.getGameTime();
        sample.player = mc.player.getUUID();
        sample.cam = cam;
        sample.vc = vc;
        sample.sent = sent;
        sample.lanes = LaneRenderer.INSTANCE.activeLaneCount();
        sample.viewScale = Entity.getViewScale();
        sample.renderDistance = mc.options.getEffectiveRenderDistance();
        sample.entityDistanceScaling = mc.options.entityDistanceScaling().get();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity.isRemoved()) continue;
            sample.total++;
            double dx = entity.getX() - cam.x;
            double dy = entity.getY() - cam.y;
            double dz = entity.getZ() - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= R64_SQ) sample.r64++;
            if (d2 <= R96_SQ) sample.r96++;
            // 服务端配对距离是水平的（区块中心 x/z），单独给一个 2D 口径用于对账
            if (dx * dx + dz * dz <= R128_SQ) sample.r128xz++;
            if (d2 > R128_SQ) continue;
            sample.r128++;
            String classification;
            if (!entity.shouldRenderAtSqrDistance(d2)) {
                sample.distOut++;
                classification = "distOut";
            } else if (!renderer.isChunkCompiled(entity.blockPosition())) {
                sample.uncompiled++;
                classification = "uncompiled";
            } else if (!isInFrustum(entity, frustum)) {
                sample.frustumOut++;
                classification = "frustumOut";
            } else {
                sample.visible++;
                classification = "visible";
            }
            String type = String.valueOf(EntityType.getKey(entity.getType()));
            sample.entities.put(entity.getId(), new EntitySample(
                    entity.getId(), type, new Vec3(entity.getX(), entity.getY(), entity.getZ()),
                    d2, classification));
        }

        if (!lanePass) {
            // 新一帧的主 pass：上一帧的 lane 样本作废
            samples.clear();
        }
        samples.put(pass, sample);
    }

    /** 复刻 {@code EntityRenderer.shouldRender} 的视锥判定（含 NaN / 零尺寸包围盒兜底）。 */
    private static boolean isInFrustum(Entity entity, Frustum frustum) {
        if (entity.noCulling) return true;
        AABB box = entity.getBoundingBoxForCulling().inflate(0.5);
        if (box.hasNaN() || box.getSize() == 0.0) {
            box = new AABB(entity.getX() - 2.0, entity.getY() - 2.0, entity.getZ() - 2.0,
                    entity.getX() + 2.0, entity.getY() + 2.0, entity.getZ() + 2.0);
        }
        return frustum.isVisible(box);
    }

    /** 主 pass 行的 {@code perLane} 摘要；无 lane 样本时返回 {@code null}。 */
    private String laneSummary() {
        StringBuilder sb = new StringBuilder();
        for (PassSample sample : samples.values()) {
            if (MAIN_PASS.equals(sample.pass)) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(sample.pass).append(':')
                    .append(sample.total).append('/')
                    .append(sample.r128).append('/')
                    .append(sample.uncompiled).append('/')
                    .append(sample.frustumOut);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 打印主视角与 lane 的实体可见性差异；每秒最多 1 行汇总 + {@link #DIFF_DETAIL_LIMIT} 行明细。 */
    private static void printDiff(PassSample main, PassSample lane) {
        List<EntitySample> mainOnly = new ArrayList<>();
        int laneOnly = 0;
        int both = 0;
        for (EntitySample m : main.entities.values()) {
            EntitySample l = lane.entities.get(m.id);
            if (l == null) continue;
            boolean mainVisible = "visible".equals(m.classification);
            boolean laneVisible = "visible".equals(l.classification);
            if (mainVisible && !laneVisible) {
                mainOnly.add(m);
            } else if (!mainVisible && laneVisible) {
                laneOnly++;
            } else if (mainVisible) {
                both++;
            }
        }
        if (mainOnly.isEmpty() && laneOnly == 0) return;
        LOGGER.info("[camera-client] diff pass={} mainOnly={} laneOnly={} both={}",
                lane.pass, mainOnly.size(), laneOnly, both);
        mainOnly.sort(Comparator.comparingDouble(m -> lane.entities.get(m.id).d2()));
        int shown = Math.min(DIFF_DETAIL_LIMIT, mainOnly.size());
        for (int i = 0; i < shown; i++) {
            EntitySample m = mainOnly.get(i);
            EntitySample l = lane.entities.get(m.id);
            LOGGER.info("[camera-client] diff mainOnly id={} type={} pos={} d2main={} d2lane={} main={} lane={}",
                    m.id, m.type, vec(m.pos), dec1(m.d2), dec1(l.d2), m.classification, l.classification);
        }
    }

    /** 打印一个 pass 的样本；{@code perLane} 只对主 pass 行非 {@code null}。 */
    private static void printSample(PassSample s, String perLane) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("[camera-client] pass=").append(s.pass)
                .append(" gameTime=").append(s.gameTime)
                .append(" player=").append(s.player)
                .append(" cam=").append(vec(s.cam))
                .append(" vc=").append(vec(s.vc))
                .append(" sent=").append(vec(s.sent))
                .append(" total=").append(s.total)
                .append(" r64=").append(s.r64)
                .append(" r96=").append(s.r96)
                .append(" r128=").append(s.r128)
                .append(" r128xz=").append(s.r128xz)
                .append(" visible=").append(s.visible)
                .append(" distOut=").append(s.distOut)
                .append(" uncompiled=").append(s.uncompiled)
                .append(" frustumOut=").append(s.frustumOut)
                .append(" lanes=").append(s.lanes)
                .append(" viewScale=").append(dec2(s.viewScale))
                .append(" rd=").append(s.renderDistance)
                .append(" eds=").append(Double.toString(s.entityDistanceScaling));
        if (perLane != null) {
            sb.append(" perLane=[").append(perLane).append(']');
        }
        LOGGER.info(sb.toString());
    }

    private static String vec(Vec3 v) {
        return "(" + dec3(v.x) + "," + dec3(v.y) + "," + dec3(v.z) + ")";
    }

    private static String dec3(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String dec2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String dec1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 一个 pass 的采样结果（每帧每 pass 一个；debug 关闭时不创建）。 */
    private static final class PassSample {
        private final Map<Integer, EntitySample> entities = new LinkedHashMap<>();
        private String pass;
        private long gameTime;
        private UUID player;
        private Vec3 cam;
        private Vec3 vc;
        private Vec3 sent;
        private int total;
        private int r64;
        private int r96;
        private int r128;
        private int r128xz;
        private int visible;
        private int distOut;
        private int uncompiled;
        private int frustumOut;
        private int lanes;
        private double viewScale;
        private int renderDistance;
        private double entityDistanceScaling;
    }

    /** 单个实体在某个 pass 的可见性分类；仅 debug 开启时构造。 */
    private record EntitySample(int id, String type, Vec3 pos, double d2, String classification) {
    }
}
