package com.immersivecinematics.immersive_cinematics.media.video;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe 元数据探测：分辨率 / 帧率 / 时长 / 是否含音频。
 *
 * 结果按 {@code (绝对路径, 文件大小, 修改时间)} 缓存，文件被替换后自动重探；
 * 探测失败（缺 ffprobe、超时、解析不出分辨率）返回 {@code null} 并限频告警，不写缓存。
 * 探测为同步阻塞调用（含 5 秒超时），只应在加载阶段或非渲染线程调用。
 */
public final class VideoProbe {

    private static final long LOG_INTERVAL_MS = 60_000L;
    private static final long TIMEOUT_SECONDS = 5L;
    private static final double FALLBACK_FPS = 25.0;

    /** 缓存键 = 绝对路径；值为当时的大小/修改时间与元数据。 */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private record Entry(long size, long mtimeMillis, VideoMetadata metadata) {
    }

    private VideoProbe() {
    }

    /**
     * 探测视频元数据（同步）。
     *
     * @param path 视频文件路径
     * @return 元数据；{@code null} 表示文件不可读、缺 ffprobe、超时或格式无法解析
     */
    public static VideoMetadata probe(Path path) {
        if (path == null) {
            return null;
        }
        Path absolute;
        try {
            absolute = path.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        long size;
        long mtime;
        try {
            size = Files.size(absolute);
            mtime = Files.getLastModifiedTime(absolute).toMillis();
        } catch (IOException e) {
            return fail(absolute, "无法读取文件属性：" + e);
        }
        String key = absolute.toString();
        Entry cached = CACHE.get(key);
        if (cached != null && cached.size() == size && cached.mtimeMillis() == mtime) {
            return cached.metadata();
        }
        VideoMetadata metadata = doProbe(absolute);
        if (metadata != null) {
            CACHE.put(key, new Entry(size, mtime, metadata));
        }
        return metadata;
    }

    /** 清空缓存（例如资源包整体重载后）。 */
    public static void clearCache() {
        CACHE.clear();
    }

    private static VideoMetadata doProbe(Path absolute) {
        Path ffprobe = Ffmpeg.ffprobe();
        if (ffprobe == null) {
            return fail(absolute, "未在 PATH 中找到 ffprobe");
        }
        String videoOutput = run(ffprobe, List.of(
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate,duration",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1",
                absolute.toString()));
        if (videoOutput == null) {
            return fail(absolute, "ffprobe 执行失败或超过 " + TIMEOUT_SECONDS + " 秒超时");
        }
        // durations[0] = 视频流时长（首条），durations[1] = 容器时长（format 段在后，末条）
        double[] durations = {-1.0, -1.0};
        Map<String, String> values = parseKeyValues(videoOutput, durations);

        int width = parseInt(values.get("width"));
        int height = parseInt(values.get("height"));
        if (width <= 0 || height <= 0) {
            return fail(absolute, "未解析到有效分辨率");
        }
        double fps = parseRate(values.get("avg_frame_rate"));
        if (fps <= 0) {
            fps = parseRate(values.get("r_frame_rate"));
        }
        if (fps <= 0) {
            fps = FALLBACK_FPS;
        }
        double duration = durations[1] > 0 ? durations[1] : Math.max(0.0, durations[0]);

        String audioOutput = run(ffprobe, List.of(
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_type",
                "-of", "csv=p=0",
                absolute.toString()));
        boolean hasAudio = audioOutput != null && !audioOutput.trim().isEmpty();

        return new VideoMetadata(absolute, width, height, fps, duration, hasAudio);
    }

    /** 解析 {@code key=value} 行；重复的 {@code duration} 按出现顺序记入 {@code durations}。 */
    private static Map<String, String> parseKeyValues(String output, double[] durations) {
        Map<String, String> values = new HashMap<>();
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if ("duration".equals(key)) {
                double parsed = parseDouble(value);
                if (parsed > 0) {
                    if (durations[0] < 0) {
                        durations[0] = parsed;
                    }
                    durations[1] = parsed;
                }
                continue;
            }
            values.put(key, value);
        }
        return values;
    }

    /** 解析 {@code num/den} 或纯数字帧率；无效（0/0、den=0、N/A、非数字）返回 -1。 */
    private static double parseRate(String text) {
        if (text == null || text.isEmpty() || "N/A".equals(text)) {
            return -1;
        }
        try {
            int slash = text.indexOf('/');
            double rate;
            if (slash >= 0) {
                double numerator = Double.parseDouble(text.substring(0, slash).trim());
                double denominator = Double.parseDouble(text.substring(slash + 1).trim());
                if (!(numerator > 0) || !(denominator > 0)) {
                    return -1;
                }
                rate = numerator / denominator;
            } else {
                rate = Double.parseDouble(text.trim());
            }
            return Double.isFinite(rate) && rate > 0 ? rate : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseInt(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 执行一次 ffprobe 并返回 stdout 文本。
     *
     * @return 正常退出时的输出；启动失败、IO 异常或超时返回 {@code null}
     */
    private static String run(Path executable, List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable.toString());
        command.addAll(args);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            Process running = process;
            ByteArrayOutputStream sink = new ByteArrayOutputStream(256);
            // 并发读走 stdout：输出很小，但避免「先 waitFor 后读」在管道写满时互锁
            Thread reader = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    in.transferTo(sink);
                } catch (IOException ignored) {
                    // 进程被销毁导致读流中断，属超时路径
                }
            }, "IC-VideoProbe");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                return null;
            }
            reader.join(1000L);
            return sink.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static VideoMetadata fail(Path path, String reason) {
        ErrorLog.warnRateLimited("Video", "probe:" + path, LOG_INTERVAL_MS,
                "视频探测失败：" + path + " — " + reason);
        return null;
    }
}
