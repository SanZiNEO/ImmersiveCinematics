package com.immersivecinematics.immersive_cinematics.webui;

import com.immersivecinematics.immersive_cinematics.media.capture.CapturedFrame;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每会话 H.264 预览编码器：捕获帧 → ffmpeg(libx264) → Annex B access unit。
 *
 * <p>线程边界：渲染线程只调用 {@link #submit}（非阻塞，队列满丢最旧帧）；编码在两条 daemon
 * 线程上完成——stdinWriter 把帧写进 ffmpeg 的 {@code pipe:0}，stdoutReader 把 {@code pipe:1}
 * 的字节流交给 {@link H264AuSplitter} 并在配对输入时间戳后回调 {@link Sink}。stderr 由第三条
 * 线程排空（保留尾部若干行用于失败诊断），不排空会把 ffmpeg 卡死在错误输出上。</p>
 *
 * <p>时间戳：{@code ptsMicros = (ptsNanos - basePtsNanos) / 1000}，{@code basePtsNanos} 是本编码器
 * <b>成功写入的第一帧</b>的采集时刻；{@code frameId} 按提交顺序与输出 AU 一一配对（baseline 无 B 帧，
 * 一进一出）。</p>
 *
 * <p>失败处理：进程起不来、管道读写失败、进程提前退出都只置 {@link #isFailed()}，不向调用方抛异常；
 * 已入队未处理的帧全部 {@link CapturedFrame#release()}，回调随之停止。</p>
 *
 * <p>生命周期：{@link #close()} 幂等——关 stdin → destroy → 等 300ms → destroyForcibly →
 * 中断线程 → 清空队列并释放。关闭后 {@link #submit} 只释放传入引用。</p>
 */
public final class PreviewEncoder implements AutoCloseable {

    private static final String LOG_CATEGORY = "Preview";
    private static final String LOG_KEY = "preview-encoder";
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** stdin 与 ffmpeg 解耦的待写队列容量：1 帧。 */
    private static final int INPUT_QUEUE_CAPACITY = 1;

    /** stderr 保留的尾部行数（失败时附进日志）。 */
    private static final int STDERR_TAIL_LINES = 4;

    private static final long CLOSE_WAIT_MS = 300L;

    /** AU 消费者。 */
    public interface Sink {
        /**
         * 一个完整的 access unit。
         *
         * @param keyframe  是否含 IDR（NAL type 5）
         * @param frameId   对应的输入帧序号
         * @param ptsMicros 相对本编码器首帧的微秒数
         * @param annexB    Annex B 字节（独立数组）
         */
        void onAu(boolean keyframe, long frameId, long ptsMicros, byte[] annexB);
    }

    private final Sink sink;

    private final ArrayBlockingQueue<CapturedFrame> inputQueue = new ArrayBlockingQueue<>(INPUT_QUEUE_CAPACITY);

    /**
     * 只保护「入队 / 清队」这一对操作。
     *
     * <p>与 {@code this}（close/fail 的幂等状态）分开：{@link #close()} 会持 {@code this} 等进程退出
     * 最长两段 300ms，渲染线程的 {@link #submit} 不能因此被拖住。</p>
     */
    private final Object queueLock = new Object();

    /** 已写入 stdin、尚未被输出 AU 消费的时间戳；与 ffmpeg 输出一一配对。 */
    private final ConcurrentLinkedQueue<FrameStamp> stamps = new ConcurrentLinkedQueue<>();

    private final AtomicLong droppedFrames = new AtomicLong();

    /** 失败时读取的 stderr 尾部。 */
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>(STDERR_TAIL_LINES);
    private final Object stderrLock = new Object();

    private volatile boolean failed;
    private volatile boolean closed;

    /** 本编码器首帧的采集时刻；{@code baseSet} 为 false 时无意义。 */
    private volatile long basePtsNanos;
    private volatile boolean baseSet;

    private Process process;
    private OutputStream stdin;
    private InputStream stdout;
    private InputStream stderr;

    private Thread stdinWriter;
    private Thread stdoutReader;
    private Thread stderrDrainer;

    /**
     * 启动 ffmpeg 进程。参数非法抛 {@link IllegalArgumentException}（编程错误）；
     * 进程启动失败<b>不</b>抛，置 {@link #isFailed()}。
     *
     * @param ffmpeg                 ffmpeg 可执行文件
     * @param width                  输入宽度（像素，&gt; 0）
     * @param height                 输入高度（像素，&gt; 0）
     * @param fps                    输入帧率
     * @param keyframeIntervalSeconds 关键帧间隔（秒，&ge; 1）
     * @param sink                   AU 消费者
     */
    public PreviewEncoder(Path ffmpeg, int width, int height, int fps, int keyframeIntervalSeconds, Sink sink) {
        if (ffmpeg == null) {
            throw new IllegalArgumentException("ffmpeg must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid encoder size: " + width + "x" + height);
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("invalid fps: " + fps);
        }
        if (keyframeIntervalSeconds < 1) {
            throw new IllegalArgumentException("invalid keyframe interval: " + keyframeIntervalSeconds);
        }
        this.sink = Objects.requireNonNull(sink, "sink");

        int gop = Math.max(1, fps * keyframeIntervalSeconds);
        Process started = null;
        try {
            started = new ProcessBuilder(command(ffmpeg, width, height, fps, gop))
                    .redirectErrorStream(false)
                    .start();
            this.process = started;
            this.stdin = started.getOutputStream();
            this.stdout = started.getInputStream();
            this.stderr = started.getErrorStream();
        } catch (IOException | RuntimeException e) {
            this.failed = true;
            if (started != null) {
                started.destroy();
            }
            ErrorLog.logRateLimited(LOG_CATEGORY, LOG_KEY + "-start", LOG_INTERVAL_MS,
                    "预览编码器启动失败（" + width + "x" + height + " @ " + fps + "fps）：" + e.getMessage(), e);
            return;
        }
        startThreads();
    }

    /** 固定命令行（顺序与参数不可随手改：前端解码依赖 baseline + AUD 边界）。 */
    private static List<String> command(Path ffmpeg, int width, int height, int fps, int gop) {
        return List.of(
                ffmpeg.toString(),
                "-hide_banner",
                "-loglevel", "error",
                "-nostdin",
                "-f", "rawvideo",
                "-pix_fmt", "rgba",
                "-s", width + "x" + height,
                "-r", Integer.toString(fps),
                "-i", "pipe:0",
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-tune", "zerolatency",
                "-profile:v", "baseline",
                "-level", "5.1",
                "-crf", "23",
                "-g", Integer.toString(gop),
                "-keyint_min", "1",
                "-sc_threshold", "0",
                "-x264-params", "aud=1",
                "-pix_fmt", "yuv420p",
                "-f", "h264",
                "pipe:1");
    }

    private void startThreads() {
        stdinWriter = daemon(this::writeLoop, "IC-PreviewEnc-stdin");
        stdoutReader = daemon(this::readLoop, "IC-PreviewEnc-stdout");
        stderrDrainer = daemon(this::drainStderr, "IC-PreviewEnc-stderr");
        stdinWriter.start();
        stdoutReader.start();
        stderrDrainer.start();
    }

    private static Thread daemon(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    /** 渲染线程调用：提交一帧，接管其一份引用（调用方不再 release）。队列满丢最旧帧。 */
    public void submit(CapturedFrame frame) {
        if (frame == null) {
            return;
        }
        synchronized (queueLock) {
            if (closed || failed) {
                frame.release();
                return;
            }
            if (!inputQueue.offer(frame)) {
                CapturedFrame oldest = inputQueue.poll();
                if (oldest != null) {
                    oldest.release();
                    droppedFrames.incrementAndGet();
                }
                if (!inputQueue.offer(frame)) {
                    // 写入线程正好在这之间取走了旧帧却仍 offer 失败：极端情况下放弃本帧
                    frame.release();
                    droppedFrames.incrementAndGet();
                }
            }
        }
    }

    /** 编码进程是否已失败（起不来 / 管道断 / 提前退出）。失败后不再产生回调。 */
    public boolean isFailed() {
        return failed;
    }

    /** 因输入队列满而丢弃的帧数。 */
    public long droppedFrames() {
        return droppedFrames.get();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;

            if (stdin != null) {
                try {
                    stdin.close();
                } catch (IOException ignored) {
                    // 关管道失败无所谓：下面直接销毁进程
                }
            }
            Process p = process;
            if (p != null) {
                p.destroy();
                try {
                    if (!p.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly();
                        p.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                }
            }
            interruptThreads();
            drainQueue();
        }
    }

    /** stdinWriter：队列 → 进程 stdin，并把时间戳压进配对队列。 */
    private void writeLoop() {
        try {
            while (!closed && !failed) {
                CapturedFrame frame;
                try {
                    frame = inputQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed || failed) {
                    frame.release();
                    return;
                }
                try {
                    if (!baseSet) {
                        basePtsNanos = frame.ptsNanos();
                        baseSet = true;
                    }
                    // 先登记时间戳再写字节：ffmpeg 可能在我们执行下一行之前就吐出该帧的 AU，
                    // 顺序反过来会让输出线程配不到时间戳而丢帧。
                    stamps.add(new FrameStamp(frame.frameId(), frame.ptsNanos()));
                    stdin.write(frame.rgba());
                    stdin.flush();
                } finally {
                    frame.release();
                }
            }
        } catch (IOException e) {
            fail("预览编码器写入 ffmpeg 失败", e);
        } catch (RuntimeException e) {
            fail("预览编码器输入线程异常", e);
        }
    }

    /** stdoutReader：进程 stdout → AU 切分 → 配对时间戳 → 回调。 */
    private void readLoop() {
        H264AuSplitter splitter = new H264AuSplitter(this::emitAccessUnit);
        byte[] chunk = new byte[16 * 1024];
        try {
            int n;
            while ((n = stdout.read(chunk)) != -1) {
                if (closed || failed) {
                    return;
                }
                splitter.feed(chunk, 0, n);
            }
            if (!closed && !failed) {
                // 进程自行结束：最后半包 AU 不可信，不 flush，直接判失败
                fail("ffmpeg 输出提前结束（exit=" + exitCode() + "）", null);
            }
        } catch (IOException e) {
            if (!closed && !failed) {
                fail("预览编码器读取 ffmpeg 输出失败", e);
            }
        } catch (RuntimeException e) {
            if (!closed && !failed) {
                fail("预览编码器输出线程异常", e);
            }
        }
    }

    /** stderr 排空线程：只保留尾部若干行，避免管道写满把 ffmpeg 卡住。 */
    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (stderrLock) {
                    if (stderrTail.size() >= STDERR_TAIL_LINES) {
                        stderrTail.removeFirst();
                    }
                    stderrTail.addLast(line);
                }
            }
        } catch (IOException ignored) {
            // 进程结束/关闭时正常发生
        }
    }

    /** AU 回调：按 FIFO 配对输入时间戳；没有配对说明不是本编码器提交的帧，丢弃。 */
    private void emitAccessUnit(boolean keyframe, byte[] annexB) {
        if (closed || failed) {
            return;
        }
        FrameStamp stamp = stamps.poll();
        if (stamp == null) {
            ErrorLog.warnRateLimited(LOG_CATEGORY, LOG_KEY + "-pairing", LOG_INTERVAL_MS,
                    "预览编码输出缺少配对时间戳（丢弃该 AU，bytes=" + annexB.length + "）");
            return;
        }
        long ptsMicros = baseSet ? (stamp.ptsNanos - basePtsNanos) / 1000L : 0L;
        try {
            sink.onAu(keyframe, stamp.frameId, ptsMicros, annexB);
        } catch (RuntimeException e) {
            fail("预览编码器回调失败", e);
        }
    }

    /** 任一环节失败：置位、记日志、停线程、清队列；不再向调用方发回调。 */
    private void fail(String message, Throwable cause) {
        synchronized (this) {
            if (closed || failed) {
                return;
            }
            failed = true;
        }
        ErrorLog.logRateLimited(LOG_CATEGORY, LOG_KEY + "-failed", LOG_INTERVAL_MS,
                message + stderrSuffix(), cause);
        drainQueue();
        interruptThreads();
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }

    private void interruptThreads() {
        Thread self = Thread.currentThread();
        interrupt(stdinWriter, self);
        interrupt(stdoutReader, self);
        interrupt(stderrDrainer, self);
    }

    private static void interrupt(Thread thread, Thread self) {
        if (thread != null && thread != self && thread.isAlive()) {
            thread.interrupt();
        }
    }

    /** 清空输入队列并释放所有待处理帧。 */
    private void drainQueue() {
        synchronized (queueLock) {
            CapturedFrame frame;
            while ((frame = inputQueue.poll()) != null) {
                frame.release();
            }
        }
    }

    private int exitCode() {
        Process p = process;
        if (p == null || p.isAlive()) {
            return -1;
        }
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** 失败日志附带的 stderr 尾部。 */
    private String stderrSuffix() {
        StringBuilder sb = new StringBuilder();
        synchronized (stderrLock) {
            if (!stderrTail.isEmpty()) {
                sb.append("；ffmpeg 输出：");
                boolean first = true;
                for (String line : stderrTail) {
                    if (!first) {
                        sb.append(" | ");
                    }
                    sb.append(line);
                    first = false;
                }
            }
        }
        return sb.toString();
    }

    /** 已写入 stdin 的输入帧时间戳。 */
    private static final class FrameStamp {
        final long frameId;
        final long ptsNanos;

        FrameStamp(long frameId, long ptsNanos) {
            this.frameId = frameId;
            this.ptsNanos = ptsNanos;
        }
    }
}
