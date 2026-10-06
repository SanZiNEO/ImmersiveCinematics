package com.immersivecinematics.immersive_cinematics.proto;

import com.immersivecinematics.immersive_cinematics.camera.CameraPath;
import com.immersivecinematics.immersive_cinematics.camera.CameraProperties;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 多画面压力测试原型（一次性，测完即删）。
 *
 * <p>模式（{@code -Dicinematics.quadrant=...} / 环境变量 {@code ICINEMATICS_QUADRANT=...}）：</p>
 * <ul>
 *   <li>{@code 0} / 不设 —— 关闭：只跑原版单画面（与现状零差异）；</li>
 *   <li>{@code 1} / {@code baseline} —— 只采样，画面就是原版单画面（基线）；</li>
 *   <li>{@code ≥4} —— 取最近的<b>平方数</b>作为画面数（4 / 9 / 16 / 25 / 36 / 49 / 64…，**不设上限**），
 *       n×n 网格排布，每个画面一个相机、一个方向。</li>
 * </ul>
 *
 * <p>每个画面一个独立的"我们模组的相机"实例：原版 {@link Camera} + {@link CameraPath} 位置 +
 * {@link CameraProperties} 朝向/光学；从各自方位朝玩家推进、始终看着玩家。方位口径见
 * {@code docs/AI_SCRIPTING_GUIDE.md} §1.4（0=南(+z)、90=西(−x)、180=北(−z)、−90=东(+x)）；
 * 4 画面用四个正交方位，16 画面每 22.5° 一个方位（朝各种方向跑）。</p>
 *
 * <p>每次测试跑 {@link #RUN_MS} 毫秒：逐秒采样（帧数 / 平均帧间隔 / 每帧所有画面总耗时）形成性能曲线，
 * 结束后把**原始数据表**写到 {@code <gameDir>/quadrant-perf/perf-mode<模式>-<时间戳>.csv}，
 * 并出图（整屏一张 + 每画面裁一张）。</p>
 */
public final class QuadrantProto {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每次测试运行时长（毫秒）。 */
    public static final long RUN_MS = 30_000L;
    private static final long SECOND_MS = 1_000L;

    /** 模式：0=关；1=只采样（原版画面基线）；≥4=取最近的平方数（4/9/16/25/36/49/64…，不设上限）。 */
    private static final int MODE = parseMode();
    private static final int VIEWS = MODE >= 4 ? MODE : 0;
    private static final int GRID = MODE >= 4 ? (int) Math.round(Math.sqrt(MODE)) : 2;

    private static final long PERIOD_MS = 12_000L;          // 推进/后退往返周期
    private static final double D_NEAR = 12.0;              // 最近距离（格）
    private static final double D_FAR = 36.0;               // 最远距离（格）
    private static final double HEIGHT_ABOVE_EYE = 2.0;     // 相机高于玩家眼高
    private static final float DEFAULT_FOV = 70.0F;         // 模组相机默认 FOV

    /** 各画面的相机方位 yaw（口径见 docs/AI_SCRIPTING_GUIDE.md §1.4）。 */
    private static final float[] YAWS = buildYaws();

    private static final ProtoCamera[] CAMERAS = new ProtoCamera[Math.max(VIEWS, 1)];
    private static final Map<Camera, ProtoCamera> BY_CAMERA = new IdentityHashMap<>();

    /** 共用的离屏缓冲（各画面顺序渲染、用完即贴，不必每画面一张）。 */
    private static RenderTarget target;

    // ===== 运行统计（每次测试一段）=====
    private static long runStartMs = -1L;
    private static long lastFrameMs = -1L;
    private static long secondStartMs = -1L;
    private static long frames;
    private static long intervalSumMs;
    private static long intervalCount;
    private static long[] laneSumNs;
    private static long[] laneMinNs;
    private static long[] laneMaxNs;
    private static long laneTotalNs;
    private static long mainPassSumNs;
    private static long secondFrames;
    private static long secondIntervalMs;
    private static long secondLaneTotalNs;
    private static long secondMainPassNs;
    /** 逐秒曲线：每行 = {帧数, 帧间隔合计(ms), 该秒所有画面耗时合计(ns), 该秒主画面耗时合计(ns)}。 */
    private static final List<long[]> PER_SECOND = new ArrayList<>();
    private static boolean finished;
    private static Path reportPath;
    /** 是否正在做某个画面的 lane 渲染（供描边上屏分流与 setupRender 的相机判定）。 */
    private static boolean laneRendering;

    private QuadrantProto() {
    }

    private static int parseMode() {
        String value = System.getProperty("icinematics.quadrant");
        if (value == null || value.isEmpty()) {
            value = System.getenv("ICINEMATICS_QUADRANT");
        }
        if (value == null || value.isEmpty() || "false".equalsIgnoreCase(value)) {
            return 0;
        }
        if ("true".equalsIgnoreCase(value)) {
            return 4;
        }
        if ("baseline".equalsIgnoreCase(value)) {
            return 1;
        }
        try {
            int mode = Integer.parseInt(value.trim());
            if (mode <= 0) return 0;
            if (mode == 1) return 1;   // 只采样（原版画面基线）
            // 不设上限：取最近的平方数（4/9/16/25/36/49/64…）
            int n = Math.max(2, (int) Math.round(Math.sqrt(mode)));
            return n * n;
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    private static float[] buildYaws() {
        if (VIEWS <= 4) {
            return new float[]{90.0F, -90.0F, 180.0F, 0.0F};
        }
        // 16 画面：每 22.5° 一个方位，覆盖各种方向
        float[] yaws = new float[VIEWS];
        for (int i = 0; i < VIEWS; i++) {
            yaws[i] = -180.0F + i * (360.0F / VIEWS);
        }
        return yaws;
    }

    static {
        for (int i = 0; i < CAMERAS.length; i++) {
            ProtoCamera pc = new ProtoCamera();
            CAMERAS[i] = pc;
            BY_CAMERA.put(pc.camera(), pc);
        }
        laneSumNs = new long[CAMERAS.length];
        laneMinNs = new long[CAMERAS.length];
        laneMaxNs = new long[CAMERAS.length];
        for (int i = 0; i < CAMERAS.length; i++) {
            laneMinNs[i] = Long.MAX_VALUE;
        }
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

    public static int mode() {
        return MODE;
    }

    public static boolean isEnabled() {
        return MODE > 0;
    }

    public static int views() {
        return VIEWS;
    }

    /** 是否正在做某个画面的 lane 渲染（原型自己调用描边上屏的窗口期）。 */
    public static boolean isLaneRendering() {
        return laneRendering;
    }

    public static void setLaneRendering(boolean value) {
        laneRendering = value;
    }

    public static int grid() {
        return GRID;
    }

    /** 第 i 个画面的模组相机实例。 */
    public static ProtoCamera camera(int i) {
        return CAMERAS[i];
    }

    /** 原型相机实例 → 其模组相机状态；非原型相机返回 {@code null}（供 mixin 分支判断）。 */
    public static ProtoCamera cameraOf(Camera camera) {
        return BY_CAMERA.get(camera);
    }

    /** 第 i 个画面的相机名（出图文件名用）。 */
    public static String name(int i) {
        if (VIEWS <= 4) {
            return switch (i) {
                case 0 -> "plusX";
                case 1 -> "minusX";
                case 2 -> "plusZ";
                default -> "minusZ";
            };
        }
        return String.format(Locale.ROOT, "view-%02d", i);
    }

    /** 共用的离屏缓冲（按主画面尺寸创建/重建；只在渲染线程调用）。 */
    public static RenderTarget target(int width, int height) {
        if (target == null) {
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
        return target;
    }

    /** 每帧更新所有画面的模组相机状态（位置 + yaw/pitch/roll/fov/zoom）。 */
    public static void update(Player player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        double d = currentDistance();
        float pitch = (float) Math.toDegrees(Math.atan2(HEIGHT_ABOVE_EYE, d));   // 正=向下看
        for (int i = 0; i < VIEWS; i++) {
            float yaw = YAWS[i];
            // 相机方位 = yaw 的反方向（相机在东 → yaw 90 朝西看）；MC 朝向向量：yaw 0 → +z，yaw 90 → −x
            double rad = Math.toRadians(yaw);
            double dirX = Math.sin(rad);
            double dirZ = -Math.cos(rad);
            ProtoCamera pc = CAMERAS[i];
            pc.path().setPositionDirect(new Vec3(eye.x + dirX * d, eye.y + HEIGHT_ABOVE_EYE, eye.z + dirZ * d));
            pc.props().setAllDirect(yaw, pitch, 0.0F, DEFAULT_FOV, 1.0F);
        }
    }

    /** 当前推进距离 d(t)：12 → 36 → 12 往返。 */
    public static double currentDistance() {
        double phase = (double) (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
        return D_NEAR + (D_FAR - D_NEAR) * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2.0));
    }

    // ===== 运行统计 =====

    /** 每帧调用一次（在所有画面渲染之前）。 */
    public static void onFrameStart() {
        long now = System.currentTimeMillis();
        if (runStartMs < 0L) {
            runStartMs = now;
            lastFrameMs = now;
            secondStartMs = now;
            return;
        }
        long interval = now - lastFrameMs;
        lastFrameMs = now;
        intervalSumMs += interval;
        intervalCount++;
        secondIntervalMs += interval;
        frames++;
        secondFrames++;
        if (now - secondStartMs >= SECOND_MS) {
            PER_SECOND.add(new long[]{secondFrames, secondIntervalMs, secondLaneTotalNs, secondMainPassNs});
            secondFrames = 0L;
            secondIntervalMs = 0L;
            secondLaneTotalNs = 0L;
            secondMainPassNs = 0L;
            secondStartMs = now;
        }
    }

    /** 主画面（原版那遍 renderLevel）耗时。 */
    public static void onMainPass(long nanos) {
        mainPassSumNs += nanos;
        secondMainPassNs += nanos;
    }

    /** 每个画面渲染完调用一次。 */
    public static void onLaneRendered(int index, long nanos) {
        laneSumNs[index] += nanos;
        if (nanos < laneMinNs[index]) laneMinNs[index] = nanos;
        if (nanos > laneMaxNs[index]) laneMaxNs[index] = nanos;
        laneTotalNs += nanos;
        secondLaneTotalNs += nanos;
    }

    /** 本次测试（RUN_MS）是否已经跑满。 */
    public static boolean runFinished() {
        return !finished && runStartMs > 0L && System.currentTimeMillis() - runStartMs >= RUN_MS;
    }

    /** 打印统计并把原始数据表写盘。 */
    public static void logSummary() {
        finished = true;
        long elapsed = System.currentTimeMillis() - runStartMs;
        LOGGER.info("[quadrant] ===== 测试结束：mode={} views={} 时长={}ms 帧数={} =====",
                MODE, VIEWS, elapsed, frames);
        for (int i = 0; i < VIEWS; i++) {
            long avgUs = frames > 0 ? laneSumNs[i] / frames / 1000 : 0;
            long minUs = laneMinNs[i] == Long.MAX_VALUE ? 0 : laneMinNs[i] / 1000;
            long maxUs = laneMaxNs[i] / 1000;
            LOGGER.info("[quadrant] view[{}] yaw={} avg={}us min={}us max={}us",
                    i, String.format(Locale.ROOT, "%6.1f", YAWS[i]), avgUs, minUs, maxUs);
        }
        LOGGER.info("[quadrant] 主画面 avg={}ms；每帧所有画面总耗时 avg={}us；帧间隔 avg={}ms（{} 帧）",
                frames > 0 ? mainPassSumNs / frames / 1_000_000 : 0,
                frames > 0 ? laneTotalNs / frames / 1000 : 0,
                intervalCount > 0 ? intervalSumMs / intervalCount : 0,
                frames);
        writeReport();
    }

    /** 原始数据表：逐秒曲线 + 每画面汇总（CSV）。 */
    private static void writeReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("# QuadrantProto 压力测试原始数据\n");
        sb.append("# mode=").append(MODE).append(" views=").append(VIEWS)
                .append(" grid=").append(GRID).append('x').append(GRID)
                .append(" run_ms=").append(RUN_MS)
                .append(" frames=").append(frames).append('\n');
        sb.append("second,frames,avg_interval_ms,avg_main_ms,avg_frame_total_us\n");
        for (int s = 0; s < PER_SECOND.size(); s++) {
            long[] row = PER_SECOND.get(s);
            long framesInSecond = row[0];
            long avgInterval = framesInSecond > 0 ? row[1] / framesInSecond : 0L;
            long avgMainMs = framesInSecond > 0 ? row[3] / framesInSecond / 1_000_000L : 0L;
            long avgTotalUs = framesInSecond > 0 ? row[2] / framesInSecond / 1000L : 0L;
            sb.append(String.format(Locale.ROOT, "%d,%d,%d,%d,%d%n",
                    s + 1, framesInSecond, avgInterval, avgMainMs, avgTotalUs));
        }
        sb.append('\n');
        sb.append("view,yaw,avg_us,min_us,max_us\n");
        for (int i = 0; i < VIEWS; i++) {
            long avgUs = frames > 0 ? laneSumNs[i] / frames / 1000 : 0L;
            long minUs = laneMinNs[i] == Long.MAX_VALUE ? 0L : laneMinNs[i] / 1000L;
            sb.append(String.format(Locale.ROOT, "%d,%.1f,%d,%d,%d%n",
                    i, YAWS[i], avgUs, minUs, laneMaxNs[i] / 1000L));
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            Path dir = mc.gameDirectory.toPath().resolve("quadrant-perf");
            Files.createDirectories(dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            reportPath = dir.resolve("perf-mode" + MODE + "-" + stamp + ".csv");
            Files.writeString(reportPath, sb.toString());
            LOGGER.info("[quadrant] 性能表已写出: {}", reportPath.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("[quadrant] 性能表写出失败", e);
        }
    }

    /** 本次测试的数据表路径（可能为 null）。 */
    public static Path reportPath() {
        return reportPath;
    }
}
