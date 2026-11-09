package com.immersivecinematics.immersive_cinematics.media.video;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedReader;
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

/**
 * 视频音轨的 PCM 流：外部 ffmpeg 解码为 {@code s16le / 48kHz / 立体声}，经 stdout 管道按需读取。
 *
 * 线程边界：
 * <ul>
 *   <li>构造与 {@link #close()} 在渲染线程调用（close 会阻塞至多约 700ms 等进程退出）；</li>
 *   <li>{@link #read(int)} 由原版 SoundEngine 的流读取线程调用，会阻塞等待 ffmpeg 产出数据；</li>
 *   <li>stderr 排空线程为 daemon，只读不写，随进程销毁退出。</li>
 * </ul>
 *
 * 启动失败（<gameDir>/ffmpeg/ 无 ffmpeg / 进程起不来）不抛异常：流进入已关闭状态，{@link #read(int)} 一律返回空缓冲，
 * 原版 SoundEngine 据此直接结束该声音，画面播放不受影响。
 */
public final class ProcessPcmStream implements AudioStream {

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BITS = 16;
    private static final int SCRATCH_SIZE = 8192;
    private static final int STDERR_TAIL_LINES = 4;
    private static final long LOG_INTERVAL_MS = 60_000L;

    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0);

    private final AudioFormat format = new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false);
    /** 复用的中转缓冲：read 由单一线程顺序调用，无需每次分配。 */
    private final byte[] scratch = new byte[SCRATCH_SIZE];

    private final Path path;
    private final Process process;
    private final InputStream stdout;
    private final Thread stderrThread;

    private volatile boolean closed;

    /**
     * 启动 ffmpeg 并从 {@code startSeconds} 起解码音轨。
     *
     * @param path         视频文件路径（原样传给 ffmpeg）
     * @param startSeconds 起播位置（秒，负数按 0 处理；&gt; 0.001 时才加 {@code -ss}）
     */
    public ProcessPcmStream(Path path, double startSeconds) {
        this.path = path;
        double seconds = Double.isFinite(startSeconds) ? Math.max(0.0, startSeconds) : 0.0;

        Process started = null;
        InputStream pipe = null;
        Thread drain = null;

        Path executable = Ffmpeg.ffmpeg();
        if (executable == null) {
            closed = true;
            ErrorLog.warnRateLimited("VideoAudio", "pcm-ffmpeg-missing", LOG_INTERVAL_MS,
                    "未在 <gameDir>/ffmpeg/ 中找到 ffmpeg，视频音轨不可用：" + path);
        } else {
            Process launched = null;
            try {
                launched = new ProcessBuilder(buildCommand(executable, path, seconds)).start();
                Process handle = launched;
                pipe = handle.getInputStream();
                Thread stderrDrain = new Thread(() -> drainStderr(handle),
                        "IC-VideoAudio-stderr-" + fileName(path));
                stderrDrain.setDaemon(true);
                stderrDrain.start();
                drain = stderrDrain;
                started = handle;
            } catch (IOException e) {
                if (launched != null) {
                    launched.destroy();
                }
                started = null;
                pipe = null;
                drain = null;
                closed = true;
                ErrorLog.warnRateLimited("VideoAudio", "pcm-start:" + path, LOG_INTERVAL_MS,
                        "启动 ffmpeg 音轨解码失败：" + path + " — " + e);
            }
        }

        this.process = started;
        this.stdout = pipe;
        this.stderrThread = drain;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * 从 ffmpeg stdout 读取至多 {@code size} 字节。
     *
     * @param size 期望字节数（原版传 16384）
     * @return 已 flip 的直接缓冲；EOF / 已关闭 / 无进程时为空缓冲（原版据此结束声音）
     */
    @Override
    public ByteBuffer read(int size) {
        if (size <= 0 || closed || stdout == null) {
            return EMPTY;
        }
        ByteBuffer out = BufferUtils.createByteBuffer(size);
        int total = 0;
        try {
            while (total < size && !closed) {
                int want = Math.min(scratch.length, size - total);
                int read = stdout.read(scratch, 0, want);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    // 管道非阻塞返回 0 属异常路径，避免忙等
                    break;
                }
                out.put(scratch, 0, read);
                total += read;
            }
        } catch (IOException e) {
            if (!closed) {
                ErrorLog.warnRateLimited("VideoAudio", "pcm-io:" + path, LOG_INTERVAL_MS,
                        "读取 ffmpeg 音轨数据失败：" + path + " — " + e);
            }
        }
        out.flip();
        return out;
    }

    /**
     * 幂等关闭：先 {@code destroy()}，最多等 500ms，仍存活则 {@code destroyForcibly()}。
     * 重复调用无副作用；构造失败时也安全。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        Process running = process;
        if (running != null) {
            try {
                InputStream pipe = stdout;
                if (pipe != null) {
                    pipe.close();
                }
            } catch (IOException ignored) {
                // 进程已退出或管道已关闭
            }
            running.destroy();
            // 不等待进程退出：close 可能由 SoundManager.stop 在客户端线程调用，500ms 等待会冻结界面。
            // 先 destroy，未退出则立即 destroyForcibly；stderr 线程是 daemon，自行收尾。
            if (running.isAlive()) {
                running.destroyForcibly();
            }
        }

        Thread drain = stderrThread;
        if (drain != null) {
            drain.interrupt();
        }
    }

    /** 逐参数构造命令：ffmpeg -v error -nostdin [-ss t] -i path -vn -f s16le -ac 2 -ar 48000 pipe:1 */
    private static List<String> buildCommand(Path executable, Path path, double seconds) {
        List<String> command = new ArrayList<>(16);
        command.add(executable.toString());
        command.add("-v");
        command.add("error");
        command.add("-nostdin");
        if (seconds > 0.001) {
            command.add("-ss");
            command.add(String.format(Locale.ROOT, "%.6f", seconds));
        }
        command.add("-i");
        command.add(path == null ? "" : path.toAbsolutePath().toString());
        command.add("-vn");
        command.add("-f");
        command.add("s16le");
        command.add("-ac");
        command.add("2");
        command.add("-ar");
        command.add("48000");
        command.add("pipe:1");
        return command;
    }

    /**
     * stderr 排空线程：必须读空，否则 ffmpeg 写满 stderr 管道后会阻塞。
     * 内容默认丢弃，仅保留尾部若干行用于非零退出码诊断。
     */
    private void drainStderr(Process running) {
        ArrayDeque<String> tail = new ArrayDeque<>(STDERR_TAIL_LINES + 1);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(running.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
                while (tail.size() > STDERR_TAIL_LINES) {
                    tail.removeFirst();
                }
            }
        } catch (IOException ignored) {
            // 进程被销毁时读流中断，属正常关闭路径
        }
        if (closed) {
            return;
        }
        int exitCode;
        try {
            exitCode = running.exitValue();
        } catch (IllegalThreadStateException e) {
            return;
        }
        if (exitCode != 0) {
            ErrorLog.warnRateLimited("VideoAudio", "pcm-exit:" + path, LOG_INTERVAL_MS,
                    "ffmpeg 音轨解码退出码 " + exitCode + "：" + path + " — " + String.join(" | ", tail));
        }
    }

    private static String fileName(Path path) {
        if (path == null || path.getFileName() == null) {
            return "video";
        }
        return path.getFileName().toString();
    }
}
