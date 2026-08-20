package com.immersivecinematics.immersive_cinematics.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 {@link ResourcePath} 目录加载 PNG/GIF 纹理到 Minecraft 纹理系统。
 * <p>
 * 静态图注册为 DynamicTexture；GIF 拆帧后由 {@link GifAnimation} 持有帧序列，
 * 渲染时按全局时间轮播并只上传当前帧。加载过的纹理会被缓存，重复使用。
 */
public final class TextureLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/TextureLoader");
    private static final Map<String, ResourceLocation> textureCache = new HashMap<>();
    /** 纹理原始像素尺寸缓存（fileName → {width, height}），供 OVERLAY 按原图比例渲染 */
    private static final Map<String, int[]> sizeCache = new HashMap<>();
    /** GIF 帧序列缓存（fileName → GifAnimation） */
    private static final Map<String, GifAnimation> gifCache = new HashMap<>();

    private static final int MAX_GIF_FRAMES = 256;
    private static final int MAX_GIF_DIMENSION = 1024;

    private TextureLoader() {}

    /**
     * 从 resource/ 目录加载图片文件，返回可用的 ResourceLocation。
     * <p>
     * .gif 走拆帧 + 轮播路径；其余格式走 NativeImage 静态图路径。
     *
     * @param fileName 文件名（如 {@code "overlay.png"} 或 {@code "flame.gif"}）
     * @return ResourceLocation 用于渲染，加载失败返回 null
     */
    public static ResourceLocation loadTexture(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;

        ResourceLocation cached = textureCache.get(fileName);
        if (cached != null) return cached;

        Path filePath = ResourcePath.resolve(fileName);
        if (!Files.exists(filePath)) {
            LOGGER.debug("Texture file not found: {}", filePath);
            return null;
        }

        if (isGif(fileName)) {
            return loadGif(fileName, filePath);
        }
        return loadStatic(fileName, filePath);
    }

    private static ResourceLocation loadStatic(String fileName, Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            NativeImage image = NativeImage.read(is);
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation loc = buildLocation(fileName);
            if (loc == null) return null;
            Minecraft.getInstance().getTextureManager().register(loc, texture);
            textureCache.put(fileName, loc);
            sizeCache.put(fileName, new int[]{image.getWidth(), image.getHeight()});
            LOGGER.debug("Loaded texture: {} -> {} ({}x{})", fileName, loc, image.getWidth(), image.getHeight());
            return loc;
        } catch (Exception e) {
            LOGGER.warn("Failed to load texture: {}", filePath, e);
            return null;
        }
    }

    private static ResourceLocation loadGif(String fileName, Path filePath) {
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
            buffer.put(bytes).flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);
                IntBuffer z = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                PointerBuffer delaysPtr = stack.mallocPointer(1);

                ByteBuffer pixels = STBImage.stbi_load_gif_from_memory(buffer, delaysPtr, x, y, z, channels, 4);
                if (pixels == null) {
                    LOGGER.error("Failed to load GIF: {} reason={}", fileName, STBImage.stbi_failure_reason());
                    return null;
                }

                int width = x.get(0);
                int height = y.get(0);
                int frameCount = z.get(0);
                if (frameCount <= 0 || frameCount > MAX_GIF_FRAMES || width <= 0 || height <= 0
                        || width > MAX_GIF_DIMENSION || height > MAX_GIF_DIMENSION) {
                    STBImage.stbi_image_free(pixels);
                    LOGGER.error("GIF over limit or invalid: {} frames={} size={}x{}", fileName, frameCount, width, height);
                    return null;
                }

                long delaysAddr = delaysPtr.get(0);
                IntBuffer delayBuf = MemoryUtil.memIntBuffer(delaysAddr, frameCount);
                int[] delays = new int[frameCount];
                for (int i = 0; i < frameCount; i++) {
                    delays[i] = Math.max(1, delayBuf.get(i));
                }

                int frameSize = width * height * 4;
                List<NativeImage> frames = new ArrayList<>(frameCount);
                for (int f = 0; f < frameCount; f++) {
                    NativeImage frame = new NativeImage(width, height, true);
                    int base = f * frameSize;
                    for (int p = 0; p < width * height; p++) {
                        int off = base + p * 4;
                        int r = pixels.get(off) & 0xFF;
                        int g = pixels.get(off + 1) & 0xFF;
                        int b = pixels.get(off + 2) & 0xFF;
                        int a = pixels.get(off + 3) & 0xFF;
                        frame.setPixelRGBA(p % width, p / width, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                    frames.add(frame);
                }
                STBImage.stbi_image_free(pixels);

                NativeImage display = new NativeImage(width, height, true);
                display.copyFrom(frames.get(0));
                DynamicTexture texture = new DynamicTexture(display);

                ResourceLocation loc = buildLocation(fileName);
                if (loc == null) {
                    display.close();
                    for (NativeImage frame : frames) frame.close();
                    return null;
                }
                Minecraft.getInstance().getTextureManager().register(loc, texture);
                textureCache.put(fileName, loc);
                sizeCache.put(fileName, new int[]{width, height});
                gifCache.put(fileName, new GifAnimation(frames, delays, width, height, texture));
                LOGGER.debug("Loaded GIF: {} -> {} ({}x{} frames={})", fileName, loc, width, height, frameCount);
                return loc;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load GIF: {}", filePath, e);
            return null;
        }
    }

    private static boolean isGif(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    private static ResourceLocation buildLocation(String fileName) {
        String texId = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        ResourceLocation loc = ResourceLocation.tryBuild("immersive_cinematics", texId);
        if (loc == null) {
            LOGGER.error("Invalid texture ID from filename: {}", fileName);
        }
        return loc;
    }

    /**
     * 查询已加载纹理的原始像素尺寸（{width, height}）；未加载/失败返回 null。
     * 供 OVERLAY 层按原图分辨率 × scale 百分比乘数渲染。
     */
    public static int[] getTextureSize(String fileName) {
        return sizeCache.get(fileName);
    }

    /** 获取 GIF 动图数据；非 GIF/未加载返回 null */
    public static GifAnimation getGif(String fileName) {
        return gifCache.get(fileName);
    }

    /** 清空纹理缓存（在资源重载时调用） */
    public static void clearCache() {
        for (GifAnimation gif : gifCache.values()) {
            gif.close();
        }
        gifCache.clear();
        textureCache.clear();
        sizeCache.clear();
    }
}
