package com.immersivecinematics.immersive_cinematics.media.video;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 外部 ffmpeg 进程解码器：{@code rawvideo + rgba} 出帧，固定尺寸、有界队列背压。
 *
 * 线程边界：
 * <ul>
 *   <li>{@link #start(double)} / {@link #seek(double)} / {@link #close()} 由单一持有线程调用（同步方法），
 *       内部会阻塞至多约 1 秒等待旧解码线程退出；</li>
 *   <li>{@link #poll()} / {@link #pollAtOrBefore(long)} / {@link #isEnded()} / {@link #baseFrameIndex()}
 *       可从任意线程调用，均不加锁、不阻塞；</li>
 *   <li>解码线程与 stderr 消费线程均为 daemon，随进程销毁退出，不持有外部资源。</li>
 * </ul>
 *
 * 帧契约：RGBA8，{@code rgba.length == decodeWidth * decodeHeight * 4}，行序自顶向下（第 0 行 = 画面顶部）。
 * 帧号为全局序号：{@code round(localTimeSeconds * fps) + 本轮第 n 帧}。
 *
 * 背压：队列容量 3，解码线程在队列满时阻塞于 {@code put}，因此 ffmpeg 会自然停在管道写端，
 * 不会无界吃内存；上层不及时取帧只会让解码暂停，不会丢帧。
 */
public final class VideoDecoder implements AutoCloseable {

    private static final int QUEUE_CAPACITY = 3;
    private static final int STDERR_TAIL_LINES = 8;
    private static final long LOG_INTERVAL_MS = 60_000L;
    private static final double FALLBACK_FPS = 25.0;
    /** 管道读取分块：Windows ProcessPipeInputStream 对大请求吞吐极差，必须小分块。 */
    private static final int READ_CHUNK = 64 * 1024;

    private final Path path;
    private final int decodeWidth;
    private final int decodeHeight;
    private final int sourceWidth;
    private final int sourceHeight;
    private final int frameSize;
    private final double fps;
    /** 当前队列（每轮 start 换新实例）。 */
    private volatile BlockingQueue<DecodedFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private volatile long baseFrameIndex;
    private volatile boolean ended;
    private volatile boolean closed;
    /** 轮次计数：旧线程据此判断自己是否已被 seek/close 取代，不再改写状态。 */
    private volatile long generation;

    /** 以下三个字段只在 synchronized 方法内读写。 */
    private Process process;
    private Thread decodeThread;
    private Thread stderrThread;

    /**
     * @param path         视频文件路径（原样传给 ffmpeg）
     * @param meta         探测得到的元数据，提供 fps；分辨率由调用方另行给出
     * @param decodeWidth  解码输出宽（> 0）
     * @param decodeHeight 解码输出高（> 0）
     */
    public VideoDecoder(Path path, VideoMetadata meta, int decodeWidth, int decodeHeight) {
        if (path == null) {
            throw new IllegalArgumentException("path == null");
        }
        if (meta == null) {
            throw new IllegalArgumentException("meta == null");
        }
        if (decodeWidth <= 0 || decodeHeight <= 0) {
            throw new IllegalArgumentException("非法解码尺寸：" + decodeWidth + "x" + decodeHeight);
        }
        long bytes = (long) decodeWidth * (long) decodeHeight * 4L;
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("解码尺寸过大：" + decodeWidth + "x" + decodeHeight);
        }
        this.path = path;
        this.decodeWidth = decodeWidth;
        this.decodeHeight = decodeHeight;
        this.sourceWidth = meta.width();
        this.sourceHeight = meta.height();
        this.frameSize = (int) bytes;
        this.fps = Double.isFinite(meta.fps()) && meta.fps() > 0 ? meta.fps() : FALLBACK_FPS;
    }

    /**
     * 从 {@code localTimeSeconds} 起启动解码（幂等：当前进程仍存活则直接返回）。
     *
     * 已 {@link #close()} 后调用无效。启动失败（缺 ffmpeg / 进程起不来）时直接进入 ended 状态并限频告警。
     */
    public synchronized void start(double localTimeSeconds) {
        if (closed) {
            return;
        }
        Process running = process;
        if (running != null && running.isAlive()) {
            return;
        }
        BlockingQueue<DecodedFrame> sink = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        queue = sink;
        ended = false;
        double seconds = Math.max(0.0, localTimeSeconds);
        long base = Math.round(seconds * fps);
        baseFrameIndex = base;
        long gen = ++generation;

        Path executable = Ffmpeg.ffmpeg();
        if (executable == null) {
            ended = true;
            ErrorLog.warnRateLimited("Video", "decode-ffmpeg-missing", LOG_INTERVAL_MS,
                    "未在 PATH 中找到 ffmpeg，无法解码：" + path);
            return;
        }
        Process started;
        try {
            started = new ProcessBuilder(buildCommand(executable, seconds)).start();
        } catch (IOException e) {
            ended = true;
            ErrorLog.warnRateLimited("Video", "decode-start:" + path, LOG_INTERVAL_MS,
                    "启动 ffmpeg 失败：" + path + " — " + e);
            return;
        }
        process = started;

        String name = fileName(path);
        Thread decoder = new Thread(() -> decodeLoop(started, sink, base, gen), "IC-VideoDecode-" + name);
        decoder.setDaemon(true);
        Thread errors = new Thread(() -> stderrLoop(started, gen), "IC-VideoDecode-stderr-" + name);
        errors.setDaemon(true);
        decodeThread = decoder;
        stderrThread = errors;
        errors.start();
        decoder.start();
    }

    /**
     * 跳转到 {@code localTimeSeconds}：停止当前进程（不进入 closed）→ 清队列 → 重新启动。
     *
     * 已 {@link #close()} 后调用无效。
     */
    public synchronized void seek(double localTimeSeconds) {
        if (closed) {
            return;
        }
        stopInternal();
        start(localTimeSeconds);
    }

    /**
     * 非阻塞取帧。
     *
     * @return 下一帧；队列为空返回 {@code null}
     */
    public DecodedFrame poll() {
        return queue.poll();
    }

    /**
     * 取走并返回 {@code index <= targetIndex} 的最新帧。
     *
     * 队列最旧帧已超过 {@code targetIndex} 时返回 {@code null}，且不消费未来帧（留给后续调用）。
     * 与 {@link #poll()} 共用同一队列，假定上层只有单消费者线程。
     *
     * @param targetIndex 目标全局帧号
     * @return 不晚于目标帧的最新一帧；没有可取的帧返回 {@code null}
     */
    public DecodedFrame pollAtOrBefore(long targetIndex) {
        BlockingQueue<DecodedFrame> source = queue;
        DecodedFrame best = null;
        while (true) {
            DecodedFrame head = source.peek();
            if (head == null || head.index() > targetIndex) {
                break;
            }
            source.poll();
            best = head;
        }
        return best;
    }

    /** 本轮 {@link #start(double)} / {@link #seek(double)} 的起始全局帧号。 */
    public long baseFrameIndex() {
        return baseFrameIndex;
    }

    /** 解码进程已到 EOF（或启动失败）且队列已取空。 */
    public boolean isEnded() {
        return ended && queue.isEmpty();
    }

    /** 幂等关闭：中断解码线程、销毁进程、清空队列。重复调用无副作用。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        stopInternal();
        ended = true;
        queue.clear();
    }

    /** 停止当前进程并回收线程；不置 {@code closed}，供 {@link #seek(double)} 复用。 */
    private void stopInternal() {
        Process running = process;
        Thread decoder = decodeThread;
        process = null;
        decodeThread = null;
        stderrThread = null;
        // 轮次自增：旧线程即使晚一步退出，也不会把新一轮的 ended 置真
        generation++;
        if (decoder != null) {
            decoder.interrupt();
        }
        if (running != null) {
            try {
                running.getInputStream().close();
            } catch (IOException ignored) {
                // 已关闭或进程已退出
            }
            running.destroy();
        }
        if (decoder != null) {
            try {
                decoder.join(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (running != null && running.isAlive()) {
            running.destroyForcibly();
        }
        if (decoder != null && decoder.isAlive()) {
            try {
                decoder.join(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // stderr 线程是 daemon：随流关闭自行退出，不在渲染线程等它。
        queue.clear();
    }

    /** 逐参数构造命令：ffmpeg -v error -nostdin [-ss t] -i path -an -vf ... -f rawvideo -pix_fmt rgba pipe:1 */
    private List<String> buildCommand(Path executable, double seconds) {
        List<String> command = new ArrayList<>(18);
        command.add(executable.toString());
        command.add("-v");
        command.add("error");
        command.add("-nostdin");
        if (seconds > 0.001) {
            command.add("-ss");
            command.add(String.format(Locale.ROOT, "%.6f", seconds));
        }
        command.add("-i");
        command.add(path.toAbsolutePath().toString());
        command.add("-an");
        command.add("-vf");
        String filter = "fps=" + String.format(Locale.ROOT, "%.6f", fps);
        if (decodeWidth != sourceWidth || decodeHeight != sourceHeight) {
            filter += ",scale=" + decodeWidth + ":" + decodeHeight + ":flags=bilinear";
        }
        command.add(filter);
        command.add("-f");
        command.add("rawvideo");
        command.add("-pix_fmt");
        command.add("rgba");
        command.add("pipe:1");
        return command;
    }

    /** 解码线程：每帧新缓冲读满 → 入队（阻塞背压）→ EOF 收尾。 */
    private void decodeLoop(Process running, BlockingQueue<DecodedFrame> sink, long base, long gen) {
        long offset = 0L;
        // 管道读取必须小分块：Windows 的 ProcessPipeInputStream 对超大单次请求只吐 8KB 量级，
        // 整帧 8.3MB 一次请求会把 1080p 拖到 4fps；64KB 分块实测可跑满 30fps。
        byte[] chunk = new byte[READ_CHUNK];
        try (InputStream in = running.getInputStream()) {
            while (!closed && gen == generation && !Thread.currentThread().isInterrupted()) {
                byte[] buffer = new byte[frameSize];
                if (!readFully(in, buffer, chunk)) {
                    break;
                }
                sink.put(new DecodedFrame(base + offset, buffer));
                offset++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!closed && gen == generation) {
                ErrorLog.warnRateLimited("Video", "decode-io:" + path, LOG_INTERVAL_MS,
                        "解码读取异常：" + path + " — " + e);
            }
        } finally {
            if (gen == generation) {
                ended = true;
            }
        }
    }

    /** stderr 消费线程：必须读空，否则子进程在写满 stderr 管道时阻塞；只保留尾部若干行用于报错。 */
    private void stderrLoop(Process running, long gen) {
        ArrayDeque<String> tail = new ArrayDeque<>(STDERR_TAIL_LINES + 1);
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
            // 进程被销毁时读流中断，属正常关闭/重启路径
        }
        if (closed || gen != generation) {
            return;
        }
        int exitCode;
        try {
            exitCode = running.exitValue();
        } catch (IllegalThreadStateException e) {
            return;
        }
        if (exitCode != 0) {
            String detail;
            synchronized (tail) {
                detail = String.join(" | ", tail);
            }
            ErrorLog.warnRateLimited("Video", "decode-exit:" + path, LOG_INTERVAL_MS,
                    "ffmpeg 退出码 " + exitCode + "：" + path + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    /** 读满缓冲区：小分块读取（管道对大请求吞吐极差）；流在帧中途 EOF 视为半帧，返回 false。 */
    private static boolean readFully(InputStream in, byte[] frame, byte[] chunk) throws IOException {
        int filled = 0;
        while (filled < frame.length) {
            int want = Math.min(chunk.length, frame.length - filled);
            int read = in.read(chunk, 0, want);
            if (read < 0) {
                return false;
            }
            System.arraycopy(chunk, 0, frame, filled, read);
            filled += read;
        }
        return true;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "video" : name.toString();
    }
}
