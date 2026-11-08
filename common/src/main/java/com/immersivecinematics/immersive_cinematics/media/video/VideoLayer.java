package com.immersivecinematics.immersive_cinematics.media.video;

import com.immersivecinematics.immersive_cinematics.media.ErrorTexture;
import com.immersivecinematics.immersive_cinematics.overlay.CanvasTransform;
import com.immersivecinematics.immersive_cinematics.overlay.ImageGeometry;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayLayer;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.ResourcePath;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;

/**
 * 视频覆盖层 —— {@code layer_type=image} 且 {@code path} 是视频容器时，替代图片层渲染视频。
 *
 * <p>几何 / 位置 / 锚点 / 缩放 / 取材 / 不透明度与图片层同一套：几何交给
 * {@link ImageGeometry#compute}（同一套 k 与 anchor 数学），参数由 {@code OverlayTrackPlayer} 逐帧写入。
 * 素材尺寸取视频源分辨率（{@code meta.width/height}），取材按素材归一化裁切。</p>
 *
 * <p><b>时间驱动</b>：{@link #setTime(double)} 由建层方每帧送入片段局部时间；暂停时时间不动 → 画面停帧，
 * seek / 循环回卷时时间跳变 → 直接按目标帧号重开解码进程。
 * 音频由 {@link VideoAudioTrack} 跟随同一时间轴（暂停停、seek 重建）。</p>
 *
 * <p><b>线程边界</b>：构造、{@link #setTime(double)}、{@link #render}、{@link #reset()}、{@link #close()}
 * 全部在<b>渲染线程</b>调用（涉及 GL 纹理、{@link net.minecraft.client.renderer.texture.TextureManager}
 * 与 SoundEngine）；解码本身在 {@link VideoDecoder} 的 daemon 线程里跑，本类只做非阻塞取帧。</p>
 *
 * <p><b>懒启动</b>：首次 {@link #render} 才探测元数据并拉起解码进程，脚本里没播到这一层就不付代价。
 * 缺 ffmpeg → 不显示也不占位（无解码能力，不是素材的问题）；有 ffmpeg 但探测 / 解码失败（坏文件）
 * → 画共享错误纹理 {@link ErrorTexture}。</p>
 *
 * <p><b>生命周期</b>：{@link #close()} 幂等，依次关闭解码器 / 帧纹理 / 音轨，不释放共享错误纹理；
 * {@link #reset()} 只把不透明度归零，不释放资源（层可能还会被重新驱动）。</p>
 */
public final class VideoLayer implements OverlayLayer {

    /** {@link ErrorLog} 分类。 */
    private static final String LOG_CATEGORY = "Video";
    /** 失败日志限频窗口（毫秒）：探测 / 解码失败逐帧复现，不限频会刷屏。 */
    private static final long LOG_INTERVAL_MS = 5_000L;
    /** 不透明度低于此值视为不可见（与图片层同口径）。 */
    private static final float VISIBLE_EPSILON = 0.001F;

    /** 层归属（不透明 token = 建层方的播放实例引用）。 */
    private final Object owner;
    /** 脚本里的素材相对路径（相对 {@code immersive_cinematics/resource/}）。 */
    private final String path;

    /** 字段初值 0：未被关键帧驱动 = 不可见（脚本缺省 opacity = 1.0，由 OverlayTrackPlayer 补齐）。 */
    private float opacity = 0f;
    /** 窗口归一化位置（0~1，未缩放基准矩形中心）。 */
    private float x = CanvasTransform.DEFAULT_POSITION;
    private float y = CanvasTransform.DEFAULT_POSITION;
    /** 缩放绕点（元素自身归一化）。 */
    private float anchorX = CanvasTransform.DEFAULT_ANCHOR;
    private float anchorY = CanvasTransform.DEFAULT_ANCHOR;
    /** 相对素材原始像素尺寸的缩放倍数。 */
    private float scaleX = CanvasTransform.DEFAULT_SCALE;
    private float scaleY = CanvasTransform.DEFAULT_SCALE;
    /** 取材（素材归一化 0~1），缺省整幅。 */
    private float sourceX = 0f;
    private float sourceY = 0f;
    private float sourceW = 1f;
    private float sourceH = 1f;
    /** 编辑基准分辨率（像素，k 的分母口径）。 */
    private float baseWidth = CanvasTransform.DEFAULT_BASE_WIDTH;
    private float baseHeight = CanvasTransform.DEFAULT_BASE_HEIGHT;
    private int zIndex = CanvasTransform.DEFAULT_Z_INDEX;

    /** 片段局部时间（秒）：由 {@link #setTime(double)} 每帧写入。 */
    private double localTime = 0.0;

    /** 懒启动已尝试（无论成败，不再重试）。 */
    private boolean started;
    /** 坏素材：画错误纹理。 */
    private boolean error;
    /** 缺 ffmpeg：不显示、不占位（render 直接返回）。 */
    private boolean unavailable;
    private boolean closed;

    private VideoMetadata meta;
    private VideoDecoder decoder;
    private VideoFrameTexture texture;
    private VideoAudioTrack audio;
    /** 上一次成功上传的全局帧号；-1 = 还没上传过。 */
    private long lastUploadedIndex = -1L;
    /** 上一帧的局部时间；用于识别倒退 / 大跳。 */
    private double lastLocalTime = Double.NaN;
    /** 时间倒退容差（秒）：循环回卷 / seek 回退超过它才重启解码。 */
    private static final double SEEK_BACK_TOLERANCE = 0.05;
    /** 时间向前大跳容差（秒）：正常播放每帧远小于它，超过才视为剪辑跳转。 */
    private static final double SEEK_FORWARD_TOLERANCE = 1.0;

    /**
     * @param owner 层归属 token（建层方的播放实例引用）
     * @param path  素材相对路径（相对 {@code immersive_cinematics/resource/}，如 {@code "clip.mp4"}）
     */
    public VideoLayer(Object owner, String path) {
        this.owner = owner;
        this.path = path;
    }

    @Override
    public Object owner() {
        return owner;
    }

    /**
     * 送入片段局部时间（秒）：画面取帧与音轨对齐都以它为唯一时钟。
     * 每帧调用；时间不变 = 暂停，跳变 = seek / 循环回卷。
     */
    @Override
    public void setTime(double localTime) {
        this.localTime = localTime;
        VideoAudioTrack track = audio;
        if (track != null) {
            track.syncToTime(localTime);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (closed || opacity <= VISIBLE_EPSILON) {
            return;
        }
        ensureStarted();
        if (unavailable) {
            // 缺 ffmpeg：不显示、不占位（画个空框只会误导作者）
            return;
        }
        if (!error) {
            updateFrame();
        }

        // 素材：正常路径 = 视频源分辨率；错误路径 = 错误纹理的虚拟分辨率（1920×1080）
        ResourceLocation textureId;
        float materialWidth;
        float materialHeight;
        if (error) {
            textureId = ErrorTexture.texture();
            materialWidth = ErrorTexture.WIDTH;
            materialHeight = ErrorTexture.HEIGHT;
        } else {
            if (meta == null || texture == null) {
                return;
            }
            textureId = texture.texture();
            if (textureId == null) {
                return;
            }
            materialWidth = meta.width();
            materialHeight = meta.height();
        }

        // 取材：素材归一化 → 素材像素（越界钳制到素材内）
        float u0 = clamp01(sourceX);
        float v0 = clamp01(sourceY);
        float srcW = (clamp01(sourceX + sourceW) - u0) * materialWidth;
        float srcH = (clamp01(sourceY + sourceH) - v0) * materialHeight;
        if (srcW <= 0f || srcH <= 0f) {
            return;
        }

        // 元素框：与图片层同一套数学（k = 分辨率转义，位置 = 窗口百分比，缩放绕 anchor）
        ImageGeometry.Rect box = ImageGeometry.compute(x, y, anchorX, anchorY,
                materialWidth, materialHeight, scaleX, scaleY,
                baseWidth, baseHeight, screenWidth, screenHeight);
        if (box.width() <= 0f || box.height() <= 0f) {
            return;
        }

        // 最终绘制：唯一一次落到像素
        int dispW = Math.round(box.width());
        int dispH = Math.round(box.height());
        if (dispW <= 0 || dispH <= 0) {
            return;
        }

        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, opacity);
        // 亚像素平滑：pose 浮点平移（blit 只收 int，直接传浮点坐标会量化成阶梯移动）
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(box.x(), box.y(), 0);
        guiGraphics.blit(textureId,
                0, 0,
                dispW, dispH,
                u0 * materialWidth, v0 * materialHeight,
                (int) srcW, (int) srcH,
                (int) materialWidth, (int) materialHeight);
        pose.popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /** 懒启动：探测元数据 → 建帧纹理 / 解码器 / 音轨 → 从当前局部时间起播。只执行一次。 */
    private void ensureStarted() {
        if (started || closed) {
            return;
        }
        started = true;

        if (path == null || path.isEmpty()) {
            markError("视频层缺少 path");
            return;
        }
        if (!Ffmpeg.available() || Ffmpeg.ffprobe() == null) {
            // 工具链不全（缺 ffmpeg 或 ffprobe）：不是素材的问题，不显示也不占位
            unavailable = true;
            ErrorLog.warnRateLimited(LOG_CATEGORY, "video-no-ffmpeg", LOG_INTERVAL_MS,
                    "未在 PATH 中找到 ffmpeg/ffprobe，视频层不显示：" + path);
            return;
        }

        Path file = ResourcePath.resolve(path);
        VideoMetadata probed = VideoProbe.probe(file);
        if (probed == null) {
            markError("视频探测失败（文件缺失或损坏）：" + file);
            return;
        }

        int[] decodeSize = fit(probed.width(), probed.height());
        try {
            this.texture = new VideoFrameTexture(decodeSize[0], decodeSize[1]);
            this.decoder = new VideoDecoder(file, probed, decodeSize[0], decodeSize[1]);
            this.audio = new VideoAudioTrack(file, probed.hasAudio());
        } catch (RuntimeException e) {
            closeResources();
            markError("视频层初始化失败：" + file + " — " + e);
            return;
        }
        this.meta = probed;
        decoder.start(Math.max(0.0, localTime));
        audio.syncToTime(localTime);
    }

    /** 每渲染帧消费：直接按目标帧号从队列取，取不到就保持当前纹理；只有时间跳变才 seek。 */
    private void updateFrame() {
        if (decoder == null || texture == null || meta == null) {
            return;
        }

        double time = Math.max(0.0, localTime);
        // 时间倒退（循环回卷 / seek）或向前大跳（剪辑跳转）才重启解码；
        // 正常推进与解码抖动一律不 seek，避免「重启 → 清队列 → 首帧慢」的循环。
        if (!Double.isNaN(lastLocalTime)
                && (time < lastLocalTime - SEEK_BACK_TOLERANCE
                    || time > lastLocalTime + SEEK_FORWARD_TOLERANCE)) {
            decoder.seek(time);
            lastUploadedIndex = -1L;
        }
        lastLocalTime = time;

        long target = (long) Math.floor(time * meta.fps() + 1e-6);
        if (meta.durationSeconds() > 0.0) {
            long lastFrame = Math.max(0L, (long) Math.floor(meta.durationSeconds() * meta.fps()) - 1L);
            target = Math.min(target, lastFrame);
        }

        DecodedFrame frame = decoder.pollAtOrBefore(target);
        if (frame != null) {
            texture.upload(frame.rgba());
            lastUploadedIndex = frame.index();
        }
        checkDecodeFailure();
    }

    /**
     * 解码失败判定：有 ffmpeg、元数据正常，时间还在片内却一帧都没出过且解码已结束
     * → 视为坏文件，切错误纹理（资源就地释放，避免留下空转的进程）。
     */
    private void checkDecodeFailure() {
        if (error || decoder == null || lastUploadedIndex >= 0 || !decoder.isEnded()) {
            return;
        }
        double duration = meta.durationSeconds();
        if (duration > 0.0 && localTime >= duration) {
            // 时间本来就落在片尾之后：没帧可出属正常，不是坏文件
            return;
        }
        closeResources();
        markError("视频解码未产出任何帧（坏文件或编码不支持）：" + path);
    }

    /** 进入错误态：之后固定画错误纹理（资源已在调用点释放或本来就没建成）。 */
    private void markError(String message) {
        error = true;
        ErrorLog.warnRateLimited(LOG_CATEGORY, "video-error:" + path, LOG_INTERVAL_MS, message);
    }

    /** 解码尺寸上限：4K（4096×2160）。源不超过上限时按源分辨率解码，超过才等比缩到上限内。 */
    private static final int MAX_DECODE_WIDTH = 4096;
    private static final int MAX_DECODE_HEIGHT = 2160;

    /**
     * 解码尺寸：按视频自身分辨率解码（不按屏幕钳制、不放大），仅超过 4K 上限时等比缩小。
     * 显示大小 / 位置 / 裁切 / 缩放全部由图层参数（x/y、anchor、scale、source、opacity、z_index）决定。
     */
    private static int[] fit(int videoWidth, int videoHeight) {
        double scale = Math.min(1.0, Math.min(
                MAX_DECODE_WIDTH / (double) videoWidth,
                MAX_DECODE_HEIGHT / (double) videoHeight));
        int width = Math.max(2, (int) Math.round(videoWidth * scale));
        int height = Math.max(2, (int) Math.round(videoHeight * scale));
        return new int[]{width, height};
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    @Override
    public boolean isVisible() {
        return opacity > VISIBLE_EPSILON;
    }

    /** 诊断用：当前字段不透明度，不参与渲染取值。 */
    @Override
    public float opacityForLog() {
        return opacity;
    }

    @Override
    public int getZIndex() {
        return zIndex;
    }

    /** 重置到默认状态（只归零不透明度），不释放资源 —— 与图片层同口径。 */
    @Override
    public void reset() {
        opacity = 0f;
    }

    /** 幂等关闭：依次释放解码器 / 帧纹理 / 音轨；共享错误纹理不归本层所有。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeResources();
    }

    /** 释放本层资源（字段置空，重复调用无副作用）。 */
    private void closeResources() {
        VideoDecoder currentDecoder = decoder;
        decoder = null;
        if (currentDecoder != null) {
            currentDecoder.close();
        }
        VideoFrameTexture currentTexture = texture;
        texture = null;
        if (currentTexture != null) {
            currentTexture.close();
        }
        VideoAudioTrack currentAudio = audio;
        audio = null;
        if (currentAudio != null) {
            currentAudio.close();
        }
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    /** 设置窗口归一化位置（0~1，未缩放基准矩形中心）。 */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** 设置缩放绕点（元素自身归一化，0.5 = 绕元素中心缩放）。 */
    public void setAnchor(float anchorX, float anchorY) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    /** 设置相对素材原始像素尺寸的缩放倍数（1 = 素材 1:1 像素）。 */
    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    /** 设置取材（素材归一化 0~1，裁出子矩形）：缺省 {0, 0, 1, 1} 整幅。 */
    public void setSource(float x, float y, float width, float height) {
        this.sourceX = x;
        this.sourceY = y;
        this.sourceW = width;
        this.sourceH = height;
    }

    /** 设置编辑基准分辨率（像素）：k 的 W基/H基，≤ 0 = 退化（本层不绘制）。 */
    public void setBaseResolution(float width, float height) {
        this.baseWidth = width;
        this.baseHeight = height;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }
}
