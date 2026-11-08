package com.immersivecinematics.immersive_cinematics.media.video;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 外部 ffmpeg / ffprobe 可执行文件查找：只扫描 {@code PATH}，不读配置、不做安装目录猜测。
 *
 * 查找结果按进程缓存（含「找不到」这一负结果），运行期不随 {@code PATH} 变化刷新；
 * 首次调用做一次目录遍历，之后只是字段读取。{@code null} 表示未安装，调用方据此降级。
 */
public final class Ffmpeg {

    private static final Object LOCK = new Object();

    private static volatile boolean ffmpegResolved;
    private static volatile Path ffmpegPath;

    private static volatile boolean ffprobeResolved;
    private static volatile Path ffprobePath;

    private Ffmpeg() {
    }

    /**
     * {@code PATH} 中的 ffmpeg 可执行文件。
     *
     * @return 绝对路径；未安装返回 {@code null}。结果缓存
     */
    public static Path ffmpeg() {
        if (!ffmpegResolved) {
            synchronized (LOCK) {
                if (!ffmpegResolved) {
                    ffmpegPath = find("ffmpeg");
                    ffmpegResolved = true;
                }
            }
        }
        return ffmpegPath;
    }

    /**
     * {@code PATH} 中的 ffprobe 可执行文件。
     *
     * @return 绝对路径；未安装返回 {@code null}。结果缓存
     */
    public static Path ffprobe() {
        if (!ffprobeResolved) {
            synchronized (LOCK) {
                if (!ffprobeResolved) {
                    ffprobePath = find("ffprobe");
                    ffprobeResolved = true;
                }
            }
        }
        return ffprobePath;
    }

    /** ffmpeg 是否可用（探测到 ffprobe 但缺 ffmpeg 仍算不可用，解码依赖 ffmpeg）。 */
    public static boolean available() {
        return ffmpeg() != null;
    }

    /** 按平台拼接可执行文件名后逐目录查找；Windows 只认 {@code .exe}。 */
    private static Path find(String base) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] names = windows ? new String[]{base + ".exe"} : new String[]{base};
        for (String entry : pathEnv.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Paths.get(entry, name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toAbsolutePath().normalize();
                    }
                } catch (InvalidPathException ignored) {
                    // PATH 中偶有非法片段，跳过即可
                }
            }
        }
        return null;
    }
}
