package com.immersivecinematics.immersive_cinematics.media.audio;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 白名单音频格式（mp3 / aac / flac / m4a / opus / wma / oga / ogx）的整段外部解码：
 * ffmpeg 把整个文件一次性解成 {@code s16le / 48kHz / 立体声}，stdout 全量读入内存后交给调用方。
 *
 * 线程边界：{@link #decodeAsync(Path, Path)} 立即返回，解码在 daemon 工作线程完成；
 * 本类不接触 Minecraft / 渲染状态，完成回调由调用方自行决定线程归属。
 *
 * 失败（ffmpeg 缺失、进程起不来、非零退出码、空输出）一律 {@code completeExceptionally}，
 * 不吞异常、不降级为静音。
 */
public final class ExternalPcmDecode {

    /** 整段 PCM：s16le 小端交错，声道 / 采样率 / 时长均已确定。 */
    public record Pcm(ByteBuffer data, int channels, int sampleRate, float durationSeconds) {}

    /** 固定输出格式：与 WAV/OGG 内存解码路径一致，SoundEngine 无需重采样。 */
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 2;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int STDERR_TAIL_LINES = 4;
    private static final int INITIAL_BUFFER = 1 << 16;

    /** 白名单（小写、不带点）：其余扩展名一律不进 ffmpeg，避免误判二进制资源。 */
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("mp3", "aac", "flac", "m4a", "opus", "wma", "oga", "ogx");

    private ExternalPcmDecode() {
    }

    /**
     * 扩展名是否落在外部解码白名单内（大小写不敏感；无扩展名 / 点结尾一律 false）。
     *
     * @param fileName 文件名或路径（只看最后一个点之后的部分）
     */
    public static boolean isSupportedExtension(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 异步整段解码：立即返回 future，解码在 daemon 线程执行。
     *
     * @param ffmpeg ffmpeg 可执行文件（{@code null} = future 立即异常完成）
     * @param file   源音频文件（{@code null} = future 立即异常完成）
     * @return 解码结果 future；失败时 {@code completeExceptionally}
     */
    public static CompletableFuture<Pcm> decodeAsync(Path ffmpeg, Path file) {
        CompletableFuture<Pcm> future = new CompletableFuture<>();
        if (ffmpeg == null || file == null) {
            future.completeExceptionally(new IllegalArgumentException("缺少 ffmpeg 或音频文件"));
            return future;
        }
        Thread worker = new Thread(() -> decode(ffmpeg, file, future), "IC-AudioDecode-" + fileName(file));
        worker.setDaemon(true);
        worker.start();
        return future;
    }

    /** 工作线程主体：起进程 → 排空 stderr → 全量读 stdout → 等退出码 → 组装 {@link Pcm}。 */
    private static void decode(Path ffmpeg, Path file, CompletableFuture<Pcm> future) {
        Process process = null;
        try {
            process = new ProcessBuilder(buildCommand(ffmpeg, file)).start();
            Process running = process;

            ArrayDeque<String> stderrTail = new ArrayDeque<>(STDERR_TAIL_LINES + 1);
            Thread stderrThread = new Thread(() -> drainStderr(running, stderrTail),
                    "IC-AudioDecode-stderr-" + fileName(file));
            stderrThread.setDaemon(true);
            stderrThread.start();

            ByteArrayOutputStream sink = new ByteArrayOutputStream(INITIAL_BUFFER);
            try (InputStream stdout = running.getInputStream()) {
                stdout.transferTo(sink);
            }
            int exitCode = running.waitFor();
            joinQuietly(stderrThread, 500L);

            if (exitCode != 0) {
                future.completeExceptionally(new IOException(
                        "ffmpeg 退出码 " + exitCode + "：" + tailText(stderrTail)));
                return;
            }

            byte[] bytes = sink.toByteArray();
            int frameBytes = CHANNELS * BYTES_PER_SAMPLE;
            if (bytes.length < frameBytes) {
                future.completeExceptionally(new IOException(
                        "ffmpeg 未产出 PCM 数据：" + file + "（" + tailText(stderrTail) + "）"));
                return;
            }
            int frames = bytes.length / frameBytes;
            float duration = (float) frames / (float) SAMPLE_RATE;
            future.complete(new Pcm(ByteBuffer.wrap(bytes), CHANNELS, SAMPLE_RATE, duration));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy(process);
            future.completeExceptionally(new IOException("音频解码被中断：" + file, e));
        } catch (Exception e) {
            destroy(process);
            future.completeExceptionally(e);
        }
    }

    /** ffmpeg -v error -nostdin -i file -vn -f s16le -ac 2 -ar 48000 pipe:1 */
    private static List<String> buildCommand(Path ffmpeg, Path file) {
        List<String> command = new ArrayList<>(18);
        command.add(ffmpeg.toString());
        command.add("-v");
        command.add("error");
        command.add("-nostdin");
        command.add("-i");
        command.add(file.toAbsolutePath().toString());
        command.add("-vn");
        command.add("-f");
        command.add("s16le");
        command.add("-ac");
        command.add(Integer.toString(CHANNELS));
        command.add("-ar");
        command.add(Integer.toString(SAMPLE_RATE));
        command.add("pipe:1");
        return command;
    }

    /**
     * stderr 排空线程：必须读空，否则 ffmpeg 写满 stderr 管道后阻塞。
     * 内容默认丢弃，只保留尾部若干行用于非零退出码诊断。
     */
    private static void drainStderr(Process running, ArrayDeque<String> tail) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(running.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (tail) {
                    tail.addLast(line);
                    while (tail.size() > STDERR_TAIL_LINES) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程被销毁时读流中断，属正常关闭路径
        }
    }

    private static String tailText(ArrayDeque<String> tail) {
        synchronized (tail) {
            return String.join(" | ", tail);
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void destroy(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static String fileName(Path file) {
        if (file.getFileName() == null) {
            return "audio";
        }
        return file.getFileName().toString();
    }
}
