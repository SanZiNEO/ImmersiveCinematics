package com.immersivecinematics.immersive_cinematics.media.video;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * 视频帧纹理：两张 {@link DynamicTexture} 轮换，把解码出来的 RGBA8 帧直接 {@code glTexSubImage2D} 上传。
 *
 * <p><b>线程边界</b>：构造、{@link #upload(byte[])}、{@link #close()} 都必须在<b>渲染线程</b>调用
 * （全部涉及 GL 纹理对象与 {@link TextureManager}）；{@link #texture()} / {@link #width()} /
 * {@link #height()} 只读字段，任意线程可调。解码线程只负责产出 {@code byte[]}，不碰本类。</p>
 *
 * <p><b>像素契约</b>：入参是 {@code width * height * 4} 字节的 RGBA8，行序<b>自顶向下</b>
 * （第 0 行 = 屏幕顶部）。上传<b>不做垂直翻转</b>：OpenGL 纹理坐标原点在左下，而
 * {@code GuiGraphics.blit} 的 {@code v = 0} 取的是纹理<b>顶部</b>那一行，
 * 两者正好抵消——所以按原样上传即可，画面不会上下颠倒。</p>
 *
 * <p><b>双缓冲</b>：上传总是写 back 纹理，写完把 back 换成 front。绘制时用 {@link #texture()} 拿 front，
 * 因此同一帧内不会有"半个新帧 + 半个旧帧"的撕裂。</p>
 *
 * <p><b>生命周期</b>：{@link #close()} 幂等，重复调用只释放一次；关闭后 {@link #texture()} 返回 {@code null}，
 * {@link #upload(byte[])} 静默忽略（限频记一条日志）。</p>
 */
public final class VideoFrameTexture implements AutoCloseable {

    /** 日志分类（{@link ErrorLog} 的 category）。 */
    private static final String LOG_CATEGORY = "VIDEO";
    /** 尺寸不符 / 关闭后上传这类高频错误的限频窗口。 */
    private static final long LOG_INTERVAL_MS = 10_000L;

    private final int width;
    private final int height;
    /** 每帧期望字节数：{@code width * height * 4}。 */
    private final int expectedBytes;

    /** 复用的上传暂存区（直接内存，一次分配，逐帧 {@code clear/put/flip}）。 */
    private final ByteBuffer staging;

    /** 两张动态纹理：{@code [0]} 与 {@code [1]} 轮流当 back。 */
    private final DynamicTexture[] textures = new DynamicTexture[2];
    /** 与 {@link #textures} 一一对应的注册名；关闭后置 {@code null}。 */
    private final ResourceLocation[] locations = new ResourceLocation[2];

    /** 当前 front（可绘制）索引；{@code -1} 表示还没有上传过任何帧。 */
    private int front = -1;

    private boolean closed;

    /**
     * 创建两张空白动态纹理并注册到纹理管理器。
     *
     * @param width  帧宽（像素），必须 &gt; 0
     * @param height 帧高（像素），必须 &gt; 0
     * @throws IllegalArgumentException 尺寸非法或所需内存超过单数组上限
     */
    public VideoFrameTexture(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("视频帧尺寸非法: " + width + "x" + height);
        }
        long bytes = (long) width * (long) height * 4L;
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("视频帧过大: " + width + "x" + height + " = " + bytes + " 字节");
        }

        this.width = width;
        this.height = height;
        this.expectedBytes = (int) bytes;
        this.staging = BufferUtils.createByteBuffer(this.expectedBytes);

        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        String base = "video_frame_" + Integer.toHexString(System.identityHashCode(this));
        for (int i = 0; i < 2; i++) {
            // DynamicTexture(NativeImage) 会自己完成纹理对象创建与一次初始上传（内容为全零），
            // 之后我们只用 glTexSubImage2D 覆盖内容，不再走 NativeImage 逐像素写入。
            DynamicTexture texture = new DynamicTexture(new NativeImage(width, height, true));
            ResourceLocation location = new ResourceLocation(
                    "immersive_cinematics", base + "_" + i);
            textureManager.register(location, texture);
            textures[i] = texture;
            locations[i] = location;
        }
    }

    /**
     * 上传一帧 RGBA8 数据到 back 纹理，成功后把它切成 front。
     *
     * <p>长度必须是 {@code width * height * 4}；行序自顶向下，不翻转。
     * 尺寸不符或已关闭时忽略本次上传（限频记日志），保留当前纹理。</p>
     *
     * @param rgba 帧数据，长度必须等于 {@code width * height * 4}
     */
    public void upload(byte[] rgba) {
        if (closed) {
            ErrorLog.warnRateLimited(LOG_CATEGORY, "frame_texture_closed", LOG_INTERVAL_MS,
                    "视频帧纹理已关闭，忽略上传（调用方在 close 之后仍在出帧）");
            return;
        }
        if (rgba == null || rgba.length != expectedBytes) {
            ErrorLog.warnRateLimited(LOG_CATEGORY, "frame_texture_size", LOG_INTERVAL_MS,
                    "视频帧尺寸不符：期望 " + expectedBytes + " 字节（" + width + "x" + height + " RGBA8），实际 "
                            + (rgba == null ? "null" : rgba.length + " 字节") + "，跳过上传");
            return;
        }

        int back = (front + 1) & 1;
        DynamicTexture target = textures[back];

        staging.clear();
        staging.put(rgba);
        staging.flip();

        // 直接覆盖 back 纹理的 level 0：不重建纹理对象，也不经过 NativeImage 逐像素拷贝。
        // 绑定走 GlStateManager（内部就是 glBindTexture(GL_TEXTURE_2D, id)）：裸 GL 绑定不会更新
        // GlStateManager 的纹理绑定缓存，缓存与实际不同步会让后续 GuiGraphics.blit 的绑定被跳过。
        GlStateManager._bindTexture(target.getId());
        // 行对齐 1 字节、行跨度归零：宽不是 4 的倍数时默认 4 字节对齐会错行；MC 的 NativeImage.upload
        // 会把 ROW_LENGTH / SKIP_ROWS / SKIP_PIXELS 设成它自己的值，这里每次上传前显式覆盖。
        // 不做 glGetInteger 读回/恢复：同步 GL 查询可能触发管线 flush，1080p 每帧 4 次会明显掉帧；
        // 后续 NativeImage 上传本来就会重设这些状态，不需要我们恢复。
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, staging);

        front = back;
    }

    /**
     * 当前可绘制的纹理（最近一次成功上传的那张）。
     *
     * <p>还没上传过任何帧时返回第一张空白纹理；{@link #close()} 之后返回 {@code null}，
     * 调用方需自行判空跳过绘制。</p>
     *
     * @return 可绑定绘制的 {@link ResourceLocation}，或关闭后的 {@code null}
     */
    public ResourceLocation texture() {
        if (closed) {
            return null;
        }
        return locations[front < 0 ? 0 : front];
    }

    /** @return 帧宽（像素） */
    public int width() {
        return width;
    }

    /** @return 帧高（像素） */
    public int height() {
        return height;
    }

    /**
     * 释放两张纹理。幂等；必须在渲染线程调用。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        for (int i = 0; i < 2; i++) {
            ResourceLocation location = locations[i];
            if (location != null) {
                // release 会关闭 DynamicTexture 并连带关闭其 NativeImage。
                textureManager.release(location);
                locations[i] = null;
            }
            textures[i] = null;
        }
        front = -1;
    }
}
