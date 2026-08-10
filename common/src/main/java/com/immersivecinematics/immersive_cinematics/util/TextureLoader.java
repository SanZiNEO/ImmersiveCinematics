package com.immersivecinematics.immersive_cinematics.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 从 {@link ResourcePath} 目录加载 PNG 纹理到 Minecraft 纹理系统。
 * <p>
 * 纹理注册为 DynamicTexture，使用 {@code immersive_cinematics:<文件名>} 作为 ResourceLocation。
 * 加载过的纹理会被缓存，重复使用。
 */
public final class TextureLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/TextureLoader");
    private static final Map<String, ResourceLocation> textureCache = new HashMap<>();
    /** 纹理原始像素尺寸缓存（fileName → {width, height}），供 OVERLAY 按原图比例渲染 */
    private static final Map<String, int[]> sizeCache = new HashMap<>();

    private TextureLoader() {}

    /**
     * 从 resource/ 目录加载 PNG 文件，返回可用的 ResourceLocation。
     *
     * @param fileName 文件名（如 {@code "overlay.png"}）
     * @return ResourceLocation 用于渲染，加载失败返回 null
     */
    public static ResourceLocation loadTexture(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;

        // 缓存命中
        ResourceLocation cached = textureCache.get(fileName);
        if (cached != null) return cached;

        Path filePath = ResourcePath.resolve(fileName);
        if (!Files.exists(filePath)) {
            // debug 级：资源缺失不影响脚本播放，作者排查时开调试日志可见
            LOGGER.debug("Texture file not found: {}", filePath);
            return null;
        }

        try (InputStream is = Files.newInputStream(filePath)) {
            NativeImage image = NativeImage.read(is);
            DynamicTexture texture = new DynamicTexture(image);
            // 注册纹理，ID = immersive_cinematics:文件名（去掉扩展名）
            String texId = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            ResourceLocation loc = ResourceLocation.tryBuild("immersive_cinematics", texId);
            if (loc == null) {
                LOGGER.error("Invalid texture ID from filename: {}", fileName);
                return null;
            }
            Minecraft.getInstance().getTextureManager().register(loc, texture);
            textureCache.put(fileName, loc);
            sizeCache.put(fileName, new int[]{image.getWidth(), image.getHeight()});
            LOGGER.debug("Loaded texture: {} -> {} ({}x{})", fileName, loc, image.getWidth(), image.getHeight());
            return loc;
        } catch (Exception e) {
            LOGGER.debug("Failed to load texture: {}", filePath, e);
            return null;
        }
    }

    /**
     * 查询已加载纹理的原始像素尺寸（{width, height}）；未加载/失败返回 null。
     * 供 OVERLAY 层按原图分辨率 × scale 百分比乘数渲染。
     */
    public static int[] getTextureSize(String fileName) {
        return sizeCache.get(fileName);
    }

    /** 清空纹理缓存（在资源重载时调用） */
    public static void clearCache() {
        textureCache.clear();
        sizeCache.clear();
    }
}
