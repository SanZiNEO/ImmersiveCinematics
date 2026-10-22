package com.immersivecinematics.immersive_cinematics.client.lane;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 有限诊断会话：opt-in manifest（{@code <gameDir>/immersive_cinematics/diagnostics/f-round.json}）驱动的
 * 结构化事件、检查点图片与 CPU 提交区间采样。
 * 关闭零开销：manifest 不存在 / {@code enabled != true} 时 {@link #SESSION} 为 null，所有钩子只做一次静态
 * 判空，不取 nanoTime、不分配缓冲、不写盘。
 * 输出：{@code sessions/<时间戳>-<name>/} 下 {@code environment.json}、{@code events.jsonl}、
 * {@code images/}、{@code performance/<case>-run<N>-{frames,lanes,summary}.*}；图片与采样均有上限。
 * 计时口径：{@code game_render_ns} = GameRenderer.render HEAD→RETURN；{@code lane_*} = renderLane 的
 * beginLane→endLane；{@code composite_total_ns} = sink 调用区间；{@code frame_interval_ns} = 相邻有效帧间隔。
 * 全部是 CPU 提交区间，不是 GPU 时间、不含 GPU timer query、也不是含 vsync 的呈现时间。
 */
public final class LaneDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson EVENT_GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_LANES = 64;

    private static final Session SESSION = loadSession();

    private LaneDiagnostics() {
    }

    public static boolean isEnabled() {
        return SESSION != null;
    }

    public static void onFrameStart(Minecraft mc, boolean renderWorld) {
        Session s = SESSION;
        if (s != null) s.frameStart(mc, renderWorld);
    }

    public static void onFrameEnd(Minecraft mc) {
        Session s = SESSION;
        if (s != null) s.frameEnd(mc);
    }

    public static long beginLane(int index, String captureId, int effectiveDistance, RenderTarget target) {
        Session s = SESSION;
        return s != null ? s.beginLane(index, captureId, effectiveDistance, target) : 0L;
    }

    public static void endLane(int index, long startedNanos, boolean written) {
        Session s = SESSION;
        if (s != null) s.endLane(index, startedNanos, written);
    }

    public static long beginComposite(int index) {
        Session s = SESSION;
        return s != null ? s.beginComposite(index) : 0L;
    }

    public static void endComposite(int index, long startedNanos) {
        Session s = SESSION;
        if (s != null && startedNanos != 0L) s.endComposite(index, startedNanos);
    }

    public static boolean shouldCaptureLaneImage(String captureId) {
        Session s = SESSION;
        return s != null && s.shouldCaptureLaneImage(captureId);
    }

    public static boolean shouldCaptureCompositeImage(String captureId) {
        Session s = SESSION;
        return s != null && s.shouldCaptureCompositeImage(captureId);
    }

    public static boolean shouldCaptureWindowImage() {
        Session s = SESSION;
        return s != null && s.shouldCaptureWindowImage();
    }

    public static String laneImageName(String captureId, String kind) {
        Session s = SESSION;
        return s != null ? s.laneImageName(captureId, kind) : "unavailable.png";
    }

    public static String windowImageName() {
        Session s = SESSION;
        return s != null ? s.windowImageName() : "unavailable.png";
    }

    public static File imageDirectory() {
        Session s = SESSION;
        return s != null ? s.imagesDir.toFile() : null;
    }

    // ==================== manifest / 会话 ====================

    private static Session loadSession() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) return null;
            Path manifest = mc.gameDirectory.toPath()
                    .resolve("immersive_cinematics").resolve("diagnostics").resolve("f-round.json");
            if (!Files.isRegularFile(manifest)) return null;
            String raw = Files.readString(manifest, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                LOGGER.warn("[diagnostics] f-round.json 根不是对象，诊断关闭");
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement enabled = root.get("enabled");
            if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsBoolean()) return null;
            return new Session(mc, manifest, raw, root);
        } catch (Throwable t) {
            LOGGER.warn("[diagnostics] 读取/初始化 F5 诊断失败，诊断关闭：{}", t.toString());
            return null;
        }
    }

    /** manifest case。{@code perf == true} 时其余 perf 字段有效。 */
    private static final class Case {
        final String id;
        final String script;
        final int run;              // 0 = any (feature only)
        final double[] checkpoints;
        final boolean firstLast;    // false = all lanes
        final int maxImages;
        final boolean perf;
        final double warmupSeconds;
        final int frames;
        final int expectedLanes;
        final LaneRenderer.LaneContent content;

        Case(String id, String script, int run, double[] checkpoints, boolean firstLast, int maxImages,
             boolean perf, double warmupSeconds, int frames, int expectedLanes,
             LaneRenderer.LaneContent content) {
            this.id = id;
            this.script = script;
            this.run = run;
            this.checkpoints = checkpoints;
            this.firstLast = firstLast;
            this.maxImages = maxImages;
            this.perf = perf;
            this.warmupSeconds = warmupSeconds;
            this.frames = frames;
            this.expectedLanes = expectedLanes;
            this.content = content;
        }
    }

    private static final class Rt {
        final Case spec;
        PlaybackInstance instance;
        int run;
        int checkpointIndex;
        int images;
        PerfRun perf;
        boolean perfDone;

        Rt(Case spec) {
            this.spec = spec;
        }
    }

    private static final class Tr {
        final PlaybackInstance instance;
        long seenFrame;
        float elapsed;
        boolean stoppingLogged;

        Tr(PlaybackInstance instance) {
            this.instance = instance;
        }
    }

    /** 一次性能采样：定长数组，写盘在后台线程。 */
    private static final class PerfRun {
        final String caseId;
        final String fileBase;
        final String script;
        final String instanceId;
        final int run;
        final ResourceLocation dimension;
        final int target;
        final int expectedLanes;
        final LaneRenderer.LaneContent content;
        final double warmup;
        final int playerDistance;
        final int maxRows;
        final int[] valid;
        final String[] reason;
        final long[] gameNs;
        final long[] laneNs;
        final long[] laneMaxNs;
        final long[] compNs;
        final long[] intervalNs;
        final int[] laneCounts;
        final int[] distMin;
        final int[] distMax;
        final int laneCap;
        final int[] lFrame;
        final int[] lIndex;
        final String[] lId;
        final int[] lDist;
        final int[] lWritten;
        final long[] lNs;
        int recorded;
        int validCount;
        int laneRecorded;
        int laneFailFrames;
        int laneMismatch;
        int distanceChanges;
        boolean truncated;
        boolean finished;
        boolean firstFrame = true;
        long prevStart;
        boolean prevValid;

        PerfRun(Rt rt) {
            Case c = rt.spec;
            this.caseId = c.id;
            this.fileBase = safeFile(c.id) + "-run" + rt.run;
            this.script = c.script;
            this.instanceId = rt.instance != null ? rt.instance.instanceId() : "";
            this.run = rt.run;
            Minecraft mc = Minecraft.getInstance();
            this.dimension = mc != null && mc.level != null ? mc.level.dimension().location() : null;
            this.playerDistance = mc != null ? mc.options.getEffectiveRenderDistance() : -1;
            this.target = c.frames;
            this.expectedLanes = c.expectedLanes;
            this.content = c.content;
            this.warmup = c.warmupSeconds;
            this.maxRows = c.frames + 512;
            this.valid = new int[maxRows];
            this.reason = new String[maxRows];
            this.gameNs = new long[maxRows];
            this.laneNs = new long[maxRows];
            this.laneMaxNs = new long[maxRows];
            this.compNs = new long[maxRows];
            this.intervalNs = new long[maxRows];
            this.laneCounts = new int[maxRows];
            this.distMin = new int[maxRows];
            this.distMax = new int[maxRows];
            int perFrame = c.expectedLanes > 0 ? Math.min(c.expectedLanes, MAX_LANES) : c.expectedLanes == 0 ? 0 : 32;
            this.laneCap = Math.min(maxRows * Math.max(perFrame, 1), 400_000);
            this.lFrame = new int[laneCap];
            this.lIndex = new int[laneCap];
            this.lId = new String[laneCap];
            this.lDist = new int[laneCap];
            this.lWritten = new int[laneCap];
            this.lNs = new long[laneCap];
        }
    }

    private static final class Session {
        final Path dir;
        final Path imagesDir;
        final Path perfDir;
        final Path manifestPath;
        final List<Case> cases = new ArrayList<>();
        final List<Rt> runtimes = new ArrayList<>();
        final List<Tr> tracked = new ArrayList<>();
        final Map<String, Integer> starts = new HashMap<>();
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>(4096);
        final int maxImages;

        long frameStart;
        long frameSeq;
        boolean world;
        ResourceLocation dimension;
        int laneCount;
        int laneSlots;
        int failedLanes;
        long laneTotal;
        long laneMax;
        long compTotal;
        boolean overflow;
        int lastLaneCount;
        boolean envWritten;

        final int[] laneIdx = new int[MAX_LANES];
        final String[] laneIds = new String[MAX_LANES];
        final int[] laneDist = new int[MAX_LANES];
        final int[] laneWritten = new int[MAX_LANES];
        final long[] laneNanos = new long[MAX_LANES];

        boolean capture;
        Rt capRt;
        String capPrefix;
        boolean windowDone;
        int capOrdinal;
        int capLane;
        String capId;
        boolean capDecision;

        boolean sampling;
        Rt sampleRt;
        int images;
        boolean budgetWarned;
        int failEvents;
        long eventSeq;

        Session(Minecraft mc, Path manifestPath, String raw, JsonObject root) throws IOException {
            this.manifestPath = manifestPath;
            this.maxImages = clamp(intOr(root, "maxImages", 160), 1, 2000);
            int perCase = clamp(intOr(root, "maxImagesPerCase", 24), 1, maxImages);
            parseCases(root, perCase);
            if (cases.isEmpty()) throw new IOException("f-round.json 没有可用 case");
            for (Case c : cases) runtimes.add(new Rt(c));

            Path base = mc.gameDirectory.toPath().resolve("immersive_cinematics")
                    .resolve("diagnostics").resolve("sessions");
            Files.createDirectories(base);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            String name = safeFile(strOr(root, "sessionName", ""));
            Path sessionDir = base.resolve(stamp + (name.isEmpty() ? "" : "-" + name));
            for (int i = 2; Files.exists(sessionDir) && i < 100; i++) {
                sessionDir = base.resolve(stamp + (name.isEmpty() ? "" : "-" + name) + "-" + i);
            }
            this.dir = sessionDir;
            this.imagesDir = sessionDir.resolve("images");
            this.perfDir = sessionDir.resolve("performance");
            Files.createDirectories(imagesDir);
            Files.createDirectories(perfDir);
            Path copy = sessionDir.resolve("manifest.json");
            Util.ioPool().execute(() -> {
                try {
                    Files.writeString(copy, raw, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOGGER.warn("[diagnostics] manifest 副本写盘失败：{}", e.toString());
                }
            });

            Thread writer = new Thread(this::eventLoop, "IC-F5-Diagnostics-Events");
            writer.setDaemon(true);
            writer.start();
            LOGGER.info("[diagnostics] F5 诊断启用：session={} cases={}", sessionDir, cases.size());
            JsonObject start = ev("session_start");
            start.addProperty("session", sessionDir.toString());
            start.addProperty("manifest", manifestPath.toString());
            start.addProperty("cases", cases.size());
            start.addProperty("max_images", maxImages);
            send(start);
        }

        private void parseCases(JsonObject root, int perCase) {
            JsonElement element = root.get("cases");
            if (element == null || !element.isJsonArray()) {
                LOGGER.warn("[diagnostics] f-round.json 缺少 cases 数组");
                return;
            }
            JsonArray arr = element.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JsonObject c = arr.get(i).getAsJsonObject();
                    String id = strOr(c, "id", "");
                    String script = strOr(c, "script", "");
                    if (id.isEmpty() || script.isEmpty()) continue;
                    int run = Math.max(0, intOr(c, "run", 0));
                    double[] checkpoints = checkpoints(c);
                    boolean firstLast = "first-last".equalsIgnoreCase(strOr(c, "lanes", "all"));
                    int caseMax = clamp(intOr(c, "maxImages", perCase), 1, maxImages);
                    JsonElement pe = c.get("perf");
                    boolean perf = pe != null && pe.isJsonObject();
                    double warmup = 2.0;
                    int frames = 600;
                    int expected = -1;
                    LaneRenderer.LaneContent content = null;
                    if (perf) {
                        JsonObject p = pe.getAsJsonObject();
                        warmup = doubleOr(p, "warmupSeconds", 2.0);
                        if (!Double.isFinite(warmup) || warmup < 0) warmup = 2.0;
                        frames = clamp(intOr(p, "frames", 600), 1, 100_000);
                        expected = Math.max(-1, intOr(p, "expectedLanes", -1));
                        String mode = strOr(p, "content", "");
                        if ("FULL".equalsIgnoreCase(mode)) content = LaneRenderer.LaneContent.FULL;
                        else if ("WORLD_ONLY".equalsIgnoreCase(mode)) content = LaneRenderer.LaneContent.WORLD_ONLY;
                        if (run == 0) run = 1;
                    } else if (checkpoints.length == 0) {
                        continue;
                    }
                    cases.add(new Case(id, script, run, checkpoints, firstLast, caseMax,
                            perf, warmup, frames, expected, content));
                } catch (Throwable t) {
                    LOGGER.warn("[diagnostics] cases[{}] 解析失败，跳过：{}", i, t.toString());
                }
            }
        }

        private double[] checkpoints(JsonObject c) {
            JsonElement e = c.get("checkpoints");
            if (e == null || !e.isJsonArray()) return new double[0];
            JsonArray arr = e.getAsJsonArray();
            List<Double> values = new ArrayList<>();
            for (int i = 0; i < arr.size() && i < 32; i++) {
                try {
                    double v = arr.get(i).getAsDouble();
                    if (Double.isFinite(v) && v > 0) values.add(v);
                } catch (Throwable ignored) {
                }
            }
            values.sort(Double::compareTo);
            double[] out = new double[values.size()];
            for (int i = 0; i < out.length; i++) out[i] = values.get(i);
            return out;
        }

        // ---------- 帧 ----------

        void frameStart(Minecraft mc, boolean renderWorld) {
            frameStart = System.nanoTime();
            frameSeq++;
            world = renderWorld && mc != null && mc.level != null && mc.player != null;
            dimension = mc != null && mc.level != null ? mc.level.dimension().location() : null;
            laneCount = 0;
            laneSlots = 0;
            failedLanes = 0;
            laneTotal = 0L;
            laneMax = 0L;
            compTotal = 0L;
            overflow = false;
            capture = false;
            capRt = null;
            capPrefix = null;
            windowDone = false;
            capOrdinal = 0;
            capLane = -1;
            capId = null;
            capDecision = false;
            sampling = false;
            sampleRt = null;
            if (mc == null) return;
            track();
            if (!world) return;
            if (!envWritten) {
                envWritten = true;
                writeEnvironment(mc);
            }
            checkpoints();
            pickPerf();
        }

        void frameEnd(Minecraft mc) {
            if (sampling && sampleRt != null && sampleRt.perf != null) record(System.nanoTime());
            laneSetEvent();
            lastLaneCount = laneCount;
        }

        private void track() {
            List<PlaybackInstance> live = CameraManager.INSTANCE.instances();
            for (int i = 0; i < live.size(); i++) {
                PlaybackInstance inst = live.get(i);
                Tr tr = null;
                for (int k = 0; k < tracked.size(); k++) {
                    if (tracked.get(k).instance == inst) {
                        tr = tracked.get(k);
                        break;
                    }
                }
                if (tr == null) {
                    tr = new Tr(inst);
                    tracked.add(tr);
                    onStart(inst);
                }
                tr.seenFrame = frameSeq;
                tr.elapsed = inst.player().getElapsedSeconds();
                if (!tr.stoppingLogged && inst.isStopping()) {
                    tr.stoppingLogged = true;
                    JsonObject stop = ev("instance_stopping");
                    stop.addProperty("script", inst.scriptId());
                    stop.addProperty("instance", inst.instanceId());
                    stop.addProperty("elapsed", tr.elapsed);
                    stop.addProperty("reason", String.valueOf(inst.exitReason()));
                    send(stop);
                }
            }
            for (int i = tracked.size() - 1; i >= 0; i--) {
                Tr tr = tracked.get(i);
                if (tr.seenFrame != frameSeq) {
                    tracked.remove(i);
                    onEnd(tr);
                }
            }
        }

        private void onStart(PlaybackInstance inst) {
            JsonObject begin = ev("instance_start");
            begin.addProperty("script", inst.scriptId());
            begin.addProperty("instance", inst.instanceId());
            begin.addProperty("preview", inst.isPreview());
            send(begin);
            if (inst.isPreview()) return;
            int run = starts.merge(inst.scriptId(), 1, Integer::sum);
            JsonObject scriptStart = ev("script_start");
            scriptStart.addProperty("script", inst.scriptId());
            scriptStart.addProperty("instance", inst.instanceId());
            scriptStart.addProperty("run", run);
            send(scriptStart);
            for (int i = 0; i < runtimes.size(); i++) {
                Rt rt = runtimes.get(i);
                if (!rt.spec.script.equals(inst.scriptId())) continue;
                if (rt.spec.run != 0 && rt.spec.run != run) continue;
                if (rt.instance != null && rt.instance != inst) {
                    if (!rt.instance.player().isPlaying() || rt.instance.isStopping()) {
                        if (rt.perf != null) finishPerf(rt, "superseded");
                        rt.instance = null;
                        rt.run = 0;
                        rt.checkpointIndex = 0;
                        rt.images = 0;
                        rt.perf = null;
                        rt.perfDone = false;
                    } else {
                        continue;
                    }
                }
                rt.instance = inst;
                rt.run = run;
                rt.checkpointIndex = 0;
                rt.images = 0;
                rt.perf = null;
                rt.perfDone = false;
                JsonObject armed = ev("case_armed");
                armed.addProperty("case", rt.spec.id);
                armed.addProperty("script", rt.spec.script);
                armed.addProperty("run", run);
                send(armed);
            }
        }

        private void onEnd(Tr tr) {
            PlaybackInstance inst = tr.instance;
            JsonObject stop = ev("instance_stop");
            stop.addProperty("script", inst.scriptId());
            stop.addProperty("instance", inst.instanceId());
            stop.addProperty("elapsed", tr.elapsed);
            stop.addProperty("reason", String.valueOf(inst.exitReason()));
            send(stop);
            for (int i = 0; i < runtimes.size(); i++) {
                Rt rt = runtimes.get(i);
                if (rt.instance != inst) continue;
                if (rt.perf != null) finishPerf(rt, "instance_gone");
                else if (rt.spec.perf && !rt.perfDone) {
                    JsonObject aborted = ev("perf_aborted");
                    aborted.addProperty("case", rt.spec.id);
                    aborted.addProperty("reason", "ended_before_sampling");
                    send(aborted);
                }
                if (rt.checkpointIndex < rt.spec.checkpoints.length) {
                    JsonObject missed = ev("checkpoint_missed");
                    missed.addProperty("case", rt.spec.id);
                    missed.addProperty("remaining", rt.spec.checkpoints.length - rt.checkpointIndex);
                    send(missed);
                }
                rt.instance = null;
                rt.run = 0;
                rt.checkpointIndex = 0;
                rt.images = 0;
                rt.perf = null;
                rt.perfDone = false;
            }
        }

        private void checkpoints() {
            if (capture) return;
            for (int i = 0; i < runtimes.size(); i++) {
                Rt rt = runtimes.get(i);
                if (rt.instance == null || rt.checkpointIndex >= rt.spec.checkpoints.length) continue;
                double due = rt.spec.checkpoints[rt.checkpointIndex];
                if (rt.instance.player().getElapsedSeconds() + 0.001 < due) continue;
                rt.checkpointIndex++;
                if (rt.instance.isStopping()) continue;
                if (images >= maxImages || rt.images >= rt.spec.maxImages) {
                    if (!budgetWarned) {
                        budgetWarned = true;
                        JsonObject exhausted = ev("image_budget_exhausted");
                        exhausted.addProperty("limit", maxImages);
                        send(exhausted);
                    }
                    continue;
                }
                capture = true;
                capRt = rt;
                capPrefix = safeFile(rt.spec.id) + "_run" + rt.run + "_cp"
                        + String.format(Locale.ROOT, "%.2f", due);
                JsonObject cp = ev("checkpoint_capture");
                cp.addProperty("case", rt.spec.id);
                cp.addProperty("checkpoint", due);
                cp.addProperty("run", rt.run);
                send(cp);
                return;
            }
        }

        private void pickPerf() {
            for (int i = 0; i < runtimes.size(); i++) {
                Rt rt = runtimes.get(i);
                if (!rt.spec.perf || rt.instance == null || rt.perfDone) continue;
                if (rt.perf == null) {
                    if (rt.instance.player().getElapsedSeconds() < rt.spec.warmupSeconds) continue;
                    if (rt.instance.isStopping()) {
                        rt.perfDone = true;
                        JsonObject aborted = ev("perf_aborted");
                        aborted.addProperty("case", rt.spec.id);
                        aborted.addProperty("reason", "stopping_before_sampling");
                        send(aborted);
                        continue;
                    }
                    rt.perf = new PerfRun(rt);
                    LaneRenderer.setDiagnosticContentOverride(rt.spec.content);
                    LOGGER.info("[diagnostics] PERF START case={} run={} lanes={} content={} frames={}",
                            rt.spec.id, rt.run, rt.spec.expectedLanes,
                            rt.spec.content != null ? rt.spec.content : "production_default", rt.spec.frames);
                    JsonObject perfStart = ev("perf_start");
                    perfStart.addProperty("case", rt.spec.id);
                    perfStart.addProperty("script", rt.spec.script);
                    perfStart.addProperty("instance", rt.instance.instanceId());
                    perfStart.addProperty("run", rt.run);
                    perfStart.addProperty("expected_lanes", rt.spec.expectedLanes);
                    perfStart.addProperty("content", rt.spec.content != null
                            ? rt.spec.content.toString() : "production_default");
                    perfStart.addProperty("player_effective_distance", rt.perf.playerDistance);
                    perfStart.addProperty("target_frames", rt.spec.frames);
                    send(perfStart);
                }
                if (sampleRt != null && sampleRt != rt) continue;
                sampling = true;
                sampleRt = rt;
                return;
            }
        }

        // ---------- lane / 合成 ----------

        long beginLane(int index, String captureId, int effectiveDistance, RenderTarget target) {
            laneCount++;
            int slot = laneSlots;
            if (slot < MAX_LANES) {
                laneIdx[slot] = index;
                laneIds[slot] = captureId;
                laneDist[slot] = effectiveDistance;
                laneWritten[slot] = 1;
                laneNanos[slot] = 0L;
                laneSlots++;
            } else {
                overflow = true;
            }
            return sampling ? System.nanoTime() : 0L;
        }

        void endLane(int index, long started, boolean written) {
            int slot = -1;
            for (int i = laneSlots - 1; i >= 0; i--) {
                if (laneIdx[i] == index) {
                    slot = i;
                    break;
                }
            }
            if (!written) {
                failedLanes++;
                if (failEvents < 20) {
                    failEvents++;
                    JsonObject e = ev("lane_failed");
                    e.addProperty("lane", index);
                    e.addProperty("sampling", sampling);
                    if (slot >= 0) {
                        e.addProperty("capture_id", laneIds[slot]);
                        e.addProperty("distance", laneDist[slot]);
                    }
                    send(e);
                }
            }
            if (started == 0L || slot < 0) return;
            long ns = System.nanoTime() - started;
            laneNanos[slot] = ns;
            laneWritten[slot] = written ? 1 : 0;
            laneTotal += ns;
            if (ns > laneMax) laneMax = ns;
        }

        long beginComposite(int index) {
            return sampling ? System.nanoTime() : 0L;
        }

        void endComposite(int index, long started) {
            compTotal += System.nanoTime() - started;
        }

        private void laneSetEvent() {
            // 只在 lane 数跳变时写一条（脚本 / clip / 实例边界）；不逐帧拼字符串。
            if (laneSlots == lastLaneCount) return;
            JsonObject changed = ev("lanes_changed");
            changed.addProperty("count", laneCount);
            changed.addProperty("sampling", sampling);
            send(changed);
        }

        // ---------- 性能记录 ----------

        private void record(long end) {
            Rt rt = sampleRt;
            PerfRun run = rt.perf;
            if (run == null || run.finished) return;
            long renderNs = end - frameStart;
            boolean ok = true;
            String why = null;

            if (run.firstFrame) {
                run.firstFrame = false;
                ok = false;
                why = "startup";
            } else if (!world) {
                ok = false;
                why = "not_world";
            } else if (capture) {
                ok = false;
                why = "capture";
            } else if (rt.instance == null || !rt.instance.player().isPlaying()) {
                ok = false;
                why = "instance_gone";
            } else if (rt.instance.isStopping()) {
                ok = false;
                why = "stopping";
            } else if (dimension == null || !dimension.equals(run.dimension)) {
                ok = false;
                why = "dimension";
            } else if (overflow) {
                ok = false;
                why = "lane_overflow";
            } else if (run.expectedLanes >= 0 && laneCount != run.expectedLanes) {
                ok = false;
                why = "lane_count";
            } else if (failedLanes > 0) {
                ok = false;
                why = "lane_failed";
                run.laneFailFrames++;
            } else if (renderNs <= 0L) {
                ok = false;
                why = "bad_timing";
            } else if (Minecraft.getInstance() != null && Minecraft.getInstance().isPaused()) {
                ok = false;
                why = "paused";
            } else {
                Minecraft mc = Minecraft.getInstance();
                int d = mc != null ? mc.options.getEffectiveRenderDistance() : -1;
                if (d != run.playerDistance) {
                    ok = false;
                    why = "render_distance_changed";
                    run.distanceChanges++;
                }
            }

            long interval = run.prevValid ? frameStart - run.prevStart : 0L;
            run.prevValid = ok;
            run.prevStart = frameStart;

            if (run.recorded >= run.maxRows) {
                finishPerf(rt, "buffer_full");
                return;
            }
            int row = run.recorded;
            run.valid[row] = ok ? 1 : 0;
            run.reason[row] = why;
            run.gameNs[row] = renderNs;
            run.laneNs[row] = laneTotal;
            run.laneMaxNs[row] = laneMax;
            run.compNs[row] = compTotal;
            run.intervalNs[row] = interval > 0 ? interval : 0L;
            run.laneCounts[row] = laneCount;
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int i = 0; i < laneSlots; i++) {
                if (laneDist[i] < min) min = laneDist[i];
                if (laneDist[i] > max) max = laneDist[i];
            }
            run.distMin[row] = min == Integer.MAX_VALUE ? 0 : min;
            run.distMax[row] = max == Integer.MIN_VALUE ? 0 : max;

            if (ok) {
                for (int i = 0; i < laneSlots; i++) {
                    if (run.laneRecorded >= run.laneCap) {
                        run.truncated = true;
                        break;
                    }
                    int at = run.laneRecorded++;
                    run.lFrame[at] = row;
                    run.lIndex[at] = laneIdx[i];
                    run.lId[at] = laneIds[i];
                    run.lDist[at] = laneDist[i];
                    run.lWritten[at] = laneWritten[i];
                    run.lNs[at] = laneNanos[i];
                }
                run.validCount++;
            } else if ("lane_count".equals(why)) {
                run.laneMismatch++;
            } else {
                run.laneMismatch = 0;
            }
            run.recorded = row + 1;

            if (ok && run.validCount >= run.target) {
                finishPerf(rt, "complete");
            } else if ("dimension".equals(why)) {
                finishPerf(rt, "dimension_changed");
            } else if ("instance_gone".equals(why)) {
                finishPerf(rt, "instance_gone");
            } else if ("stopping".equals(why)) {
                finishPerf(rt, "stopping");
            } else if ("lane_count".equals(why) && run.laneMismatch >= 120) {
                finishPerf(rt, "lane_count_mismatch");
            } else if ("render_distance_changed".equals(why) && run.distanceChanges > 5) {
                finishPerf(rt, "render_distance_changed");
            } else if (run.recorded >= run.maxRows) {
                finishPerf(rt, "buffer_full");
            }
        }

        private void finishPerf(Rt rt, String reason) {
            PerfRun run = rt.perf;
            if (run == null || run.finished) return;
            run.finished = true;
            rt.perf = null;
            rt.perfDone = true;
            LaneRenderer.setDiagnosticContentOverride(null);
            boolean complete = "complete".equals(reason);
            LOGGER.info("[diagnostics] PERF {} case={} run={} valid={}/{} reason={}",
                    complete ? "COMPLETE" : "INCOMPLETE", run.caseId, run.run,
                    run.validCount, run.target, reason);
            if (complete) {
                try {
                    boolean stopped = CameraManager.INSTANCE.stopScriptById(run.script, run.instanceId);
                    LOGGER.info("[diagnostics] PERF COMPLETE 后{}测试脚本 {}",
                            stopped ? "自动停止" : "实例已结束，未再停止", run.script);
                } catch (Throwable t) {
                    LOGGER.warn("[diagnostics] 自动停止测试脚本失败（请手动 /icinematics stop {}）：{}",
                            run.script, t.toString());
                }
            }
            JsonObject perfEnd = ev("perf_end");
            perfEnd.addProperty("case", run.caseId);
            perfEnd.addProperty("run", run.run);
            perfEnd.addProperty("reason", reason);
            perfEnd.addProperty("valid_frames", run.validCount);
            perfEnd.addProperty("target_frames", run.target);
            perfEnd.addProperty("complete", complete);
            perfEnd.addProperty("insufficient", !complete || run.validCount < run.target);
            send(perfEnd);
            Util.ioPool().execute(() -> writePerf(run));
        }

        // ---------- 图片 ----------

        boolean shouldCaptureLaneImage(String captureId) {
            if (!capture || capRt == null) return false;
            int ordinal = capOrdinal++;
            if (capRt.spec.firstLast && lastLaneCount > 0
                    && ordinal != 0 && ordinal != lastLaneCount - 1) {
                capId = null;
                capDecision = false;
                return false;
            }
            if (!reserve(capRt)) {
                capId = null;
                capDecision = false;
                return false;
            }
            capId = captureId;
            capLane = ordinal;
            capDecision = true;
            return true;
        }

        boolean shouldCaptureCompositeImage(String captureId) {
            if (!capture || capRt == null || !capDecision) return false;
            boolean match = captureId == null ? capId == null : captureId.equals(capId);
            capDecision = false;
            capId = null;
            return match && reserve(capRt);
        }

        boolean shouldCaptureWindowImage() {
            if (!capture || capRt == null || windowDone) return false;
            if (!reserve(capRt)) return false;
            windowDone = true;
            return true;
        }

        private boolean reserve(Rt rt) {
            if (images >= maxImages || rt.images >= rt.spec.maxImages) {
                if (!budgetWarned) {
                    budgetWarned = true;
                    JsonObject exhausted = ev("image_budget_exhausted");
                    exhausted.addProperty("limit", maxImages);
                    send(exhausted);
                }
                return false;
            }
            images++;
            rt.images++;
            return true;
        }

        String laneImageName(String captureId, String kind) {
            String prefix = capPrefix != null ? capPrefix : "capture";
            int lane = capLane >= 0 ? capLane : 0;
            return prefix + "_l" + lane + "_" + safeFile(captureId) + "-" + kind + ".png";
        }

        String windowImageName() {
            return (capPrefix != null ? capPrefix : "capture") + "_window.png";
        }

        // ---------- 环境 ----------

        private void writeEnvironment(Minecraft mc) {
            JsonObject e = new JsonObject();
            e.addProperty("captured_at", Instant.now().toString());
            e.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
            e.addProperty("mod_version", modVersion("immersive_cinematics"));
            e.addProperty("oculus_version", modVersion("oculus"));
            e.addProperty("embeddium_version", modVersion("embeddium"));
            e.addProperty("java_version", System.getProperty("java.version"));
            e.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            RenderTarget main = mc.getMainRenderTarget();
            e.addProperty("framebuffer_width", main != null ? main.width : -1);
            e.addProperty("framebuffer_height", main != null ? main.height : -1);
            e.addProperty("window_screen_width", mc.getWindow().getScreenWidth());
            e.addProperty("window_screen_height", mc.getWindow().getScreenHeight());
            e.addProperty("render_distance", mc.options.renderDistance().get());
            e.addProperty("effective_render_distance", mc.options.getEffectiveRenderDistance());
            e.addProperty("graphics_mode", String.valueOf(mc.options.graphicsMode().get()));
            e.addProperty("vsync", mc.options.enableVsync().get());
            e.addProperty("max_fps", mc.options.framerateLimit().get());
            e.addProperty("gl_vendor", gl(GL11.GL_VENDOR));
            e.addProperty("gl_renderer", gl(GL11.GL_RENDERER));
            e.addProperty("gl_version", gl(GL11.GL_VERSION));
            e.addProperty("shader_pack_in_use", shaderInUse());
            e.addProperty("shader_mod_present", LaneRenderer.isShaderModPresent());
            String json = PRETTY_GSON.toJson(e);
            Util.ioPool().execute(() -> {
                try {
                    Files.writeString(dir.resolve("environment.json"), json, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    LOGGER.warn("[diagnostics] environment.json 写盘失败：{}", ex.toString());
                }
            });
        }

        private String gl(int name) {
            try {
                String v = GL11.glGetString(name);
                return v != null ? v : "";
            } catch (Throwable t) {
                return "";
            }
        }

        // ---------- 事件 ----------

        private JsonObject ev(String name) {
            JsonObject e = new JsonObject();
            e.addProperty("event", name);
            return e;
        }

        private void send(JsonObject event) {
            event.addProperty("seq", ++eventSeq);
            event.addProperty("time", Instant.now().toString());
            if (!events.offer(EVENT_GSON.toJson(event))) {
                // 队列满：丢弃，诊断不阻塞渲染线程
            }
        }

        private void eventLoop() {
            try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("events.jsonl"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                while (true) {
                    String line = events.take();
                    w.write(line);
                    w.newLine();
                    w.flush();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                LOGGER.warn("[diagnostics] events.jsonl 写入失败：{}", ex.toString());
            }
        }

        // ---------- CSV / 摘要 ----------

        private void writePerf(PerfRun run) {
            try {
                writeFrames(run);
                writeLanes(run);
                writeSummary(run);
            } catch (Throwable t) {
                LOGGER.warn("[diagnostics] performance 输出失败（{}）：{}", run.caseId, t.toString());
            }
        }

        private void writeFrames(PerfRun r) throws IOException {
            Path file = perfDir.resolve(r.fileBase + "-frames.csv");
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("frame,script,instance,run,lane_count,expected_lanes,valid,reason,"
                        + "game_render_ns,lane_total_ns,lane_max_ns,composite_total_ns,"
                        + "frame_interval_ns,dist_min,dist_max\n");
                for (int i = 0; i < r.recorded; i++) {
                    w.write(i + "," + csv(r.script) + "," + csv(r.instanceId) + "," + r.run + ","
                            + r.laneCounts[i] + "," + r.expectedLanes + "," + r.valid[i] + ","
                            + csv(r.reason[i]) + "," + r.gameNs[i] + "," + r.laneNs[i] + ","
                            + r.laneMaxNs[i] + "," + r.compNs[i] + "," + r.intervalNs[i] + ","
                            + r.distMin[i] + "," + r.distMax[i] + "\n");
                }
            }
        }

        private void writeLanes(PerfRun r) throws IOException {
            Path file = perfDir.resolve(r.fileBase + "-lanes.csv");
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("frame,lane_index,capture_id,effective_distance,written,render_ns\n");
                for (int i = 0; i < r.laneRecorded; i++) {
                    w.write(r.lFrame[i] + "," + r.lIndex[i] + "," + csv(r.lId[i]) + ","
                            + r.lDist[i] + "," + r.lWritten[i] + "," + r.lNs[i] + "\n");
                }
            }
        }

        private void writeSummary(PerfRun r) throws IOException {
            long[] game = valid(r.gameNs, r);
            long[] lane = valid(r.laneNs, r);
            long[] comp = valid(r.compNs, r);
            long[] interval = new long[r.validCount];
            int intervalCount = 0;
            for (int i = 0; i < r.recorded; i++) {
                if (r.valid[i] == 1 && r.intervalNs[i] > 0) interval[intervalCount++] = r.intervalNs[i];
            }
            long[] laneSamples = new long[r.laneRecorded];
            int laneSampleCount = 0;
            for (int i = 0; i < r.laneRecorded; i++) {
                if (r.lWritten[i] == 1) laneSamples[laneSampleCount++] = r.lNs[i];
            }
            int captureFrames = 0;
            int otherInvalid = 0;
            for (int i = 0; i < r.recorded; i++) {
                if (r.valid[i] == 1) continue;
                if ("capture".equals(r.reason[i])) captureFrames++;
                else otherInvalid++;
            }
            Path file = perfDir.resolve(r.fileBase + "-summary.md");
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("# F5 performance — " + r.caseId + " run " + r.run + "\n\n");
                w.write("- script: `" + r.script + "`\n");
                w.write("- content: " + (r.content != null ? r.content : "production default (FULL)") + "\n");
                w.write("- player effective distance: " + r.playerDistance + " chunks (lanes follow it)\n");
                w.write("- warmup: " + r.warmup + "s; target: " + r.target + " valid frames\n");
                w.write("- recorded: " + r.recorded + "; valid: " + r.validCount
                        + "; invalid: capture=" + captureFrames + ", other=" + otherInvalid + "\n");
                w.write("- lane rows: " + r.laneRecorded + (r.truncated ? " (TRUNCATED)" : "")
                        + "; lane failure frames: " + r.laneFailFrames + "\n");
                w.write("- data sufficiency: " + (r.validCount >= r.target ? "COMPLETE" : "INSUFFICIENT") + "\n\n");
                if (r.validCount < r.target) {
                    w.write("**INSUFFICIENT: collected " + r.validCount + " of " + r.target
                            + " valid frames. Do not treat this run as complete.**\n\n");
                }
                w.write("| metric (ns) | n | p50 | p95 | mean | min | max |\n");
                w.write("|---|---|---|---|---|---|---|\n");
                metric(w, "game_render", game, game.length);
                metric(w, "lane_total", lane, lane.length);
                metric(w, "composite_total", comp, comp.length);
                metric(w, "frame_interval", interval, intervalCount);
                metric(w, "lane_render", laneSamples, laneSampleCount);
                w.write("\nCPU submission windows only: not GPU time, no GPU timer query, "
                        + "not presented/vsync frame time. Capture/dimension/render-distance/lane-count/"
                        + "lane-failure/paused frames are excluded.\n");
            }
        }

        private long[] valid(long[] source, PerfRun r) {
            int n = 0;
            for (int i = 0; i < r.recorded; i++) if (r.valid[i] == 1) n++;
            long[] out = new long[n];
            int at = 0;
            for (int i = 0; i < r.recorded; i++) if (r.valid[i] == 1) out[at++] = source[i];
            return out;
        }

        private void metric(BufferedWriter w, String name, long[] v, int n) throws IOException {
            if (n <= 0) {
                w.write("| " + name + " | 0 | - | - | - | - | - |\n");
                return;
            }
            long[] copy = Arrays.copyOf(v, n);
            Arrays.sort(copy);
            long sum = 0;
            for (int i = 0; i < n; i++) sum += copy[i];
            w.write("| " + name + " | " + n + " | " + pct(copy, 0.50) + " | " + pct(copy, 0.95)
                    + " | " + (sum / n) + " | " + copy[0] + " | " + copy[n - 1] + " |\n");
        }

        private long pct(long[] sorted, double p) {
            int i = (int) Math.ceil(p * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
        }

        private String csv(String v) {
            if (v == null) return "";
            if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) return v;
            return '"' + v.replace("\"", "\"\"") + '"';
        }
    }

    // ==================== 小工具 ====================

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    private static int intOr(JsonObject o, String k, int d) {
        try {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : d;
        } catch (Throwable t) {
            return d;
        }
    }

    private static double doubleOr(JsonObject o, String k, double d) {
        try {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : d;
        } catch (Throwable t) {
            return d;
        }
    }

    private static String strOr(JsonObject o, String k, String d) {
        try {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : d;
        } catch (Throwable t) {
            return d;
        }
    }

    private static String safeFile(String v) {
        if (v == null || v.isEmpty()) return "unnamed";
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            b.append(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '_' || c == '.' || c == '-' ? c : '_');
        }
        return b.toString();
    }

    /** 模组版本：优先 Fabric，其次 Forge；都不在时返回 null。只查一个 id，不做通用模组列表。 */
    private static String modVersion(String id) {
        try {
            Class<?> l = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = l.getMethod("getInstance").invoke(null);
            Object opt = l.getMethod("getModContainer", String.class).invoke(loader, id);
            if (opt instanceof Optional<?> o && o.isPresent()) {
                Object meta = Class.forName("net.fabricmc.loader.api.ModContainer")
                        .getMethod("getMetadata").invoke(o.get());
                Object ver = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata")
                        .getMethod("getVersion").invoke(meta);
                return String.valueOf(Class.forName("net.fabricmc.loader.api.Version")
                        .getMethod("getFriendlyString").invoke(ver));
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> l = Class.forName("net.minecraftforge.fml.ModList");
            Object list = l.getMethod("get").invoke(null);
            Object opt = l.getMethod("getModContainerById", String.class).invoke(list, id);
            if (opt instanceof Optional<?> o && o.isPresent()) {
                Object info = Class.forName("net.minecraftforge.fml.ModContainer")
                        .getMethod("getModInfo").invoke(o.get());
                Object ver = Class.forName("net.minecraftforge.forgespi.language.IModInfo")
                        .getMethod("getVersion").invoke(info);
                return String.valueOf(ver);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Iris/Oculus API v0：只读“光影包是否启用”；未安装返回 null。 */
    private static Boolean shaderInUse() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            Object result = api.getMethod("isShaderPackInUse").invoke(inst);
            return result instanceof Boolean b ? b : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
