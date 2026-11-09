package com.immersivecinematics.immersive_cinematics.media.video;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 外部 ffmpeg / ffprobe 可执行文件定位：固定 {@code <gameDir>/ffmpeg/}，不读 {@code PATH}、不做配置项。
 *
 * <p>目录布局：{@code <gameDir>/ffmpeg/ffmpeg.exe}、{@code <gameDir>/ffmpeg/ffprobe.exe}
 * （Windows 带 {@code .exe}，其他平台不带）。{@code gameDir} 取
 * {@link Minecraft#getInstance()}{@code .gameDirectory}；没有客户端实例（如专用服务器）
 * 或访问失败时返回 {@code null}，不抛异常。</p>
 *
 * <p>查找结果按 {@code gameDir} 缓存（含「文件不存在」这一负结果），仅当 {@code gameDir} 变化时重查。
 * 首次调用做两次 {@code Files.isRegularFile}，之后只是字段读取。</p>
 */
public final class Ffmpeg {

    /** 可执行文件在游戏目录下的固定子目录名。 */
    private static final String BIN_DIR = "ffmpeg";

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final Object LOCK = new Object();

    /** 上次解析所用的游戏目录；{@code null} 表示上次没拿到目录（下次调用会重试）。 */
    private static volatile Path cachedGameDir;
    private static volatile Path ffmpegPath;
    private static volatile Path ffprobePath;

    private Ffmpeg() {
    }

    /**
     * {@code <gameDir>/ffmpeg/} 下的 ffmpeg 可执行文件。
     *
     * @return 绝对路径；不存在返回 {@code null}。结果缓存
     */
    public static Path ffmpeg() {
        resolveIfNeeded();
        return ffmpegPath;
    }

    /**
     * {@code <gameDir>/ffmpeg/} 下的 ffprobe 可执行文件。
     *
     * @return 绝对路径；不存在返回 {@code null}。结果缓存
     */
    public static Path ffprobe() {
        resolveIfNeeded();
        return ffprobePath;
    }

    /** 完整媒体能力：ffmpeg 与 ffprobe 都在（解码、探测、编码均可用）。 */
    public static boolean available() {
        return ffmpeg() != null && ffprobe() != null;
    }

    /** 编码能力：只需 ffmpeg（预览 H.264 编码、音频外部解码用）。 */
    public static boolean encoderAvailable() {
        return ffmpeg() != null;
    }

    /**
     * 纯函数：拼出 {@code <gameDir>/ffmpeg/<base>[.exe]}，存在则返回绝对路径，否则 {@code null}。
     *
     * <p>不加载 {@link Minecraft}，供探针与测试直接调用。</p>
     *
     * @param gameDir 游戏目录；{@code null} 返回 {@code null}
     * @param base    可执行文件名（{@code "ffmpeg"} / {@code "ffprobe"}）
     * @return 绝对路径或 {@code null}
     */
    public static Path resolve(Path gameDir, String base) {
        if (gameDir == null || base == null || base.isEmpty()) {
            return null;
        }
        String name = WINDOWS ? base + ".exe" : base;
        try {
            Path candidate = gameDir.resolve(BIN_DIR).resolve(name).toAbsolutePath().normalize();
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 按当前客户端游戏目录解析一次；目录没变则直接复用缓存。 */
    private static void resolveIfNeeded() {
        Path dir = gameDir();
        if (dir == null) {
            cachedGameDir = null;
            ffmpegPath = null;
            ffprobePath = null;
            return;
        }
        if (dir.equals(cachedGameDir)) {
            return;
        }
        synchronized (LOCK) {
            if (dir.equals(cachedGameDir)) {
                return;
            }
            ffmpegPath = resolve(dir, "ffmpeg");
            ffprobePath = resolve(dir, "ffprobe");
            cachedGameDir = dir;
        }
    }

    /** 当前游戏目录；无客户端/无目录/类不可用（专用服务器）时返回 {@code null}。 */
    private static Path gameDir() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return null;
            }
            File dir = mc.gameDirectory;
            return dir == null ? null : dir.toPath();
        } catch (Throwable t) {
            // 专用服务器 / 极早期初始化：Minecraft 类不可用，视为没有目录
            return null;
        }
    }
}
