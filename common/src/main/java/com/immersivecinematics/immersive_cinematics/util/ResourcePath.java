package com.immersivecinematics.immersive_cinematics.util;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 资源路径工具 — 客户端按需读取播放所需资源（音频/图片）。
 * 资源统一放在游戏根目录 {@code <游戏目录>/immersive_cinematics/resource/}，
 * 单机/联机/编辑器共用同一份，不走服务器流量、不做文件同步；
 * 资源缺失时由各加载点记录日志，不阻塞脚本播放。
 */
public final class ResourcePath {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ResourcePath");
    private static final String RESOURCE_DIR = "resource";

    private ResourcePath() {}

    /** 资源根目录：{@code <gameDir>/immersive_cinematics/resource/} */
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
