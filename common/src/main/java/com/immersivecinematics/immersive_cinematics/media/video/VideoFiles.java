package com.immersivecinematics.immersive_cinematics.media.video;

import java.util.Locale;
import java.util.Set;

/**
 * 视频文件判定与扩展名工具。
 *
 * 纯字符串判断，不访问磁盘：{@link #isVideo(String)} 只看扩展名，
 * 因此不存在的路径也会返回 {@code true}，文件存在性由调用方自行校验。
 */
public final class VideoFiles {

    /** 支持的容器扩展名（小写、无点）。 */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "mkv");

    private VideoFiles() {
    }

    /**
     * 路径扩展名是否为受支持的视频容器。
     *
     * @param path 文件路径（可为 {@code null}）
     * @return {@code null}/空串/其他扩展名返回 {@code false}；大小写不敏感
     */
    public static boolean isVideo(String path) {
        return VIDEO_EXTENSIONS.contains(extension(path));
    }

    /**
     * 取扩展名：小写、不含点。
     *
     * @param path 文件路径（可为 {@code null}）
     * @return 无扩展名、以点结尾、{@code null} 或空串时返回 {@code ""}
     */
    public static String extension(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
