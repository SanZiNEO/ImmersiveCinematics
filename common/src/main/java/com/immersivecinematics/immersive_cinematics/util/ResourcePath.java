package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 资源路径工具 — 统一管理音频、图片等外部资源的读取路径。
 * <p>
 * 所有资源文件位于 {@code <游戏目录>/immersive_cinematics/resource/} 目录下，
 * 防止从任意文件路径加载导致的安全问题。
 */
public final class ResourcePath {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ResourcePath");
    private static final String RESOURCE_DIR = "resource";

    private ResourcePath() {}

    /**
     * 获取资源根目录：{@code <gameDir>/immersive_cinematics/resource/}
     */
    public static Path getBasePath() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("immersive_cinematics")
                .resolve(RESOURCE_DIR);
    }

    /**
     * 解析文件名到资源目录下的完整路径
     *
     * @param fileName 文件名（如 {@code "bgm.ogg"}、{@code "overlay.png"}）
     * @return 资源目录下的完整路径
     */
    public static Path resolve(String fileName) {
        return getBasePath().resolve(fileName);
    }

    /**
     * 检查文件在资源目录下是否存在
     */
    public static boolean exists(String fileName) {
        return Files.exists(resolve(fileName));
    }

    /**
     * 确保资源目录存在，不存在则创建
     */
    public static void ensureDir() {
        try {
            Files.createDirectories(getBasePath());
        } catch (Exception e) {
            LOGGER.error("Failed to create resource directory: {}", getBasePath(), e);
        }
    }
}
