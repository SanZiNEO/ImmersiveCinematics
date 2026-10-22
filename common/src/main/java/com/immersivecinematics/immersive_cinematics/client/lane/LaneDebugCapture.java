package com.immersivecinematics.immersive_cinematics.client.lane;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL13;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 调试钩子：按相机 id 把每帧的 lane 画面读回写盘（PNG），供离屏分析。
 *
 * 每轮三类产出（lane 数量任意）：raw = lane 离屏 FBO 原始 RGBA（lane 级调色之前，附 alpha 灰度图）；
 * composited = 该相机贴进主画面之后的主 framebuffer，按 {@code dest} 裁剪；
 * 窗口终帧 = 整帧渲染结束后的主画面（alpha 强制 255）。
 * 文件名 = {@code <id>-raw} / {@code <id>-composited} / {@code frame} + {@code -r{k}} 轮次后缀。
 * id 由 {@code ScriptLaneDriver} 注册 lane 时给出（缺省 {@code lane<序号>}），文件名安全化后使用。
 * raw / composited 按保真 alpha 读回（不强制 255）：合成层已忽略 lane alpha，但 FBO 里的 alpha 仍是
 * 「未覆盖区 / 星星 / mipmap 边缘」的判据，保留它才能分析这些边界。
 * 与手动 F2 截图不同：{@code Screenshot} 走 {@code downloadTexture(0, true)}，那个 true 把 alpha 强制写成 255。
 *
 * 开关 = {@code -Dicinematics.capture} / 环境变量 {@code ICINEMATICS_CAPTURE}（与诊断会话开关独立），
 * 关闭时本开关路径第一行返回（零差异）；节奏 = 首帧一轮、此后每 {@link #INTERVAL_FRAMES} 帧一轮、最多 {@link #MAX_ROUNDS} 轮。
 * 线程：读回必须在渲染线程，PNG 编码与写盘交给 {@link Util#ioPool()}。
 */
public final class LaneDebugCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 开关：{@code -Dicinematics.capture} / 环境变量 {@code ICINEMATICS_CAPTURE}。 */
    private static final boolean ENABLED = parseEnabled();

    /** 每 N 帧抓一轮（首帧必抓）。 */
    private static final int INTERVAL_FRAMES = 200;
    /** 最多抓几轮（防刷屏；一轮 = 全部 lane + 一张窗口终帧）。 */
    private static final int MAX_ROUNDS = 5;
    /** 产出目录名（相对游戏目录）。 */
    private static final String DIR_NAME = "lane-captures";

    private static int frames;
    private static int rounds;
    private static boolean captureThisFrame;

    private LaneDebugCapture() {
    }

    /** 本钩子是否开启（开关外零差异的判据）。 */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 本帧开始、lane 渲染之前调用一次（{@code LaneRenderer.render} 的 lane 循环之前）：推进帧计数并判定
     * 本帧是否捕获。只在本帧确实有 lane 可渲染时被调用，故「窗口终帧」标志由 {@link #onFrameEnd} 消费一次，
     * 不会在没有 lane 的帧上重复出图。关闭时零差异。
     */
    public static void beginFrame() {
        if (!ENABLED) {
            return;
        }
        frames++;
        captureThisFrame = (frames == 1 || frames % INTERVAL_FRAMES == 0) && rounds < MAX_ROUNDS;
        if (captureThisFrame) {
            rounds++;
        }
    }

    /**
     * 每相机 raw：一条 lane 渲染完、lane 级调色之前调用（{@code LaneRenderer.renderLane} 内），读 lane 离屏
     * FBO 的原始结果（不含调色 / 合成），保真 alpha；关闭 / 非捕获帧时第一行返回。
     *
     * @param cameraId 该 lane 的相机 id（{@code null} / 空 = 由调用方给出的回退名）
     * @param target   lane 的离屏缓冲
     */
    public static void onLaneRendered(String cameraId, RenderTarget target) {
        // 诊断会话（独立开关）：按检查点与 lane 选择写有限 raw 图；未启用时 isEnabled 第一行返回。
        if (LaneDiagnostics.isEnabled() && target != null && target.width > 0 && target.height > 0
                && LaneDiagnostics.shouldCaptureLaneImage(cameraId)) {
            NativeImage diagnostic = download(target, false);   // 保真 alpha
            writeDiagnostic(diagnostic, LaneDiagnostics.laneImageName(cameraId, "raw"),
                    "相机 " + cameraId + " raw");
        }
        if (!ENABLED || !captureThisFrame || target == null || target.width <= 0 || target.height <= 0) {
            return;
        }
        NativeImage image = download(target, false);   // false = 保留原始 alpha
        writePair(image, safe(cameraId) + "-raw" + suffix(rounds), "相机 " + cameraId + " raw");
    }

    /**
     * 每相机 composited：该相机合成进主画面之后调用（{@code ScriptLaneDriver.compose} 内，紧随
     * {@link LaneCompositor#compose}）；读回主 framebuffer 的保真 alpha 并裁剪到该相机的 {@code dest} 矩形，
     * {@code dest} 退化（宽或高为 0）时跳过；关闭 / 非捕获帧时第一行返回。
     *
     * @param cameraId 该 lane 的相机 id（{@code null} / 空 = 由调用方给出的回退名）
     * @param dest     该相机的屏幕归一化目标矩形（原点左上，与 {@link LaneCompositor.Rect} 同口径）
     */
    public static void onLaneComposited(String cameraId, LaneCompositor.Rect dest) {
        // 诊断会话：raw 判定成功时，紧随合成读回主 framebuffer 并按 dest 裁切
        if (LaneDiagnostics.isEnabled() && dest != null && LaneDiagnostics.shouldCaptureCompositeImage(cameraId)) {
            RenderTarget diagnosticMain = Minecraft.getInstance().getMainRenderTarget();
            if (diagnosticMain != null && diagnosticMain.width > 0 && diagnosticMain.height > 0) {
                NativeImage full = download(diagnosticMain, false);
                NativeImage diagnostic;
                try {
                    diagnostic = crop(full, dest, diagnosticMain.width, diagnosticMain.height);
                } finally {
                    full.close();
                }
                if (diagnostic != null) {
                    writeDiagnostic(diagnostic, LaneDiagnostics.laneImageName(cameraId, "composited"),
                            "相机 " + cameraId + " composited");
                }
            }
        }
        if (!ENABLED || !captureThisFrame || dest == null) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (main == null || main.width <= 0 || main.height <= 0) {
            return;
        }
        NativeImage full = download(main, false);   // 保真 alpha：合成层不写主画面 alpha，这里量的是真实值
        NativeImage crop;
        try {
            crop = crop(full, dest, main.width, main.height);
        } finally {
            full.close();
        }
        if (crop == null) {
            return;   // dest 退化（宽或高为 0）：没有可裁的区域
        }
        writePair(crop, safe(cameraId) + "-composited" + suffix(rounds), "相机 " + cameraId + " composited");
    }

    /**
     * 窗口终帧：整帧渲染结束（GUI 之后）调用 —— {@code GameRenderer.render} 的 RETURN；读回主 framebuffer，
     * alpha 强制 255（与原版截图同口径），内容 = 玩家看到的窗口画面（原版 blitToScreen 在 render 返回后才
     * 把主画面拷到窗口，不改变内容）。本方法消费本帧捕获标志：一帧最多一张终帧，没有 lane 的帧不会重复出图；
     * 关闭 / 非捕获帧 / 未进世界时第一行返回。
     */
    public static void onFrameEnd(Minecraft mc) {
        // 诊断会话：检查点窗口终帧（本帧没有可渲染 lane 也写窗口图；raw / composited 由 lane 钩子负责）
        if (LaneDiagnostics.isEnabled() && mc != null && mc.level != null
                && LaneDiagnostics.shouldCaptureWindowImage()) {
            RenderTarget diagnosticMain = mc.getMainRenderTarget();
            if (diagnosticMain != null && diagnosticMain.width > 0 && diagnosticMain.height > 0) {
                NativeImage diagnostic = download(diagnosticMain, true);
                writeDiagnostic(diagnostic, LaneDiagnostics.windowImageName(), "窗口终帧");
            }
        }
        if (!ENABLED || !captureThisFrame) {
            return;
        }
        captureThisFrame = false;
        if (mc == null || mc.level == null) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.width <= 0 || main.height <= 0) {
            return;
        }
        NativeImage image = download(main, true);   // true = alpha 强制 255（与 Screenshot 同口径）
        writeSingle(image, "frame" + suffix(rounds), "窗口终帧");
    }

    // ===== 读回 =====

    /**
     * 读回一个 framebuffer 的颜色纹理（渲染线程）：{@code forceAlpha=true} 时 alpha 强制 255（与原版截图同口径）。
     * 只动两处状态并还原：0 号纹理绑定与当前纹理单元（显式切到 0 号，避免踩到光照贴图的 2 号单元）。
     */
    private static NativeImage download(RenderTarget target, boolean forceAlpha) {
        NativeImage image = new NativeImage(target.width, target.height, false);
        int prevTexture = RenderSystem.getShaderTexture(0);
        try {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, forceAlpha);
            image.flipY();   // glGetTexImage 行序自下而上
        } finally {
            RenderSystem.setShaderTexture(0, prevTexture);
        }
        return image;
    }

    /**
     * 按 {@code dest}（屏幕归一化矩形、原点左上）从整帧里裁出该相机所占的那块。
     * 矩形按像素取整并夹到画面内；宽或高为 0 时返回 {@code null}。
     */
    private static NativeImage crop(NativeImage src, LaneCompositor.Rect dest, int width, int height) {
        int x0 = clamp(Math.round(dest.x() * width), 0, width);
        int y0 = clamp(Math.round(dest.y() * height), 0, height);
        int x1 = clamp(Math.round((dest.x() + dest.w()) * width), 0, width);
        int y1 = clamp(Math.round((dest.y() + dest.h()) * height), 0, height);
        int cropWidth = x1 - x0;
        int cropHeight = y1 - y0;
        if (cropWidth <= 0 || cropHeight <= 0) {
            return null;
        }
        NativeImage out = new NativeImage(cropWidth, cropHeight, false);
        for (int y = 0; y < cropHeight; y++) {
            for (int x = 0; x < cropWidth; x++) {
                out.setPixelRGBA(x, y, src.getPixelRGBA(x0 + x, y0 + y));
            }
        }
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    // ===== 写盘 =====

    /** 原始图 + alpha 灰度图（raw / composited 用；编码写盘交给 IO 线程）。 */
    private static void writePair(NativeImage image, String name, String what) {
        File dir = captureDir();
        String alphaReport = alphaReport(image);
        LOGGER.info("[lane-capture] {} 第 {} 轮：{}x{} → {}/{}.png（alpha 统计：{}）",
                what, rounds, image.getWidth(), image.getHeight(), dir.getAbsolutePath(), name, alphaReport);
        Util.ioPool().execute(() -> {
            NativeImage alphaView = null;
            try {
                dir.mkdirs();
                image.writeToFile(new File(dir, name + ".png"));
                alphaView = alphaView(image);
                alphaView.writeToFile(new File(dir, name + "-alpha.png"));
            } catch (IOException e) {
                LOGGER.warn("[lane-capture] 写盘失败：{}", e.toString());
            } finally {
                if (alphaView != null) {
                    alphaView.close();
                }
                image.close();
            }
        });
    }

    /** 单张图（窗口终帧用；alpha 已强制 255，不需要灰度可视化）。 */
    private static void writeSingle(NativeImage image, String name, String what) {
        File dir = captureDir();
        LOGGER.info("[lane-capture] {} 第 {} 轮：{}x{} → {}/{}.png",
                what, rounds, image.getWidth(), image.getHeight(), dir.getAbsolutePath(), name);
        Util.ioPool().execute(() -> {
            try {
                dir.mkdirs();
                image.writeToFile(new File(dir, name + ".png"));
            } catch (IOException e) {
                LOGGER.warn("[lane-capture] 写盘失败：{}", e.toString());
            } finally {
                image.close();
            }
        });
    }

    /**
     * 诊断会话图片：写进会话目录的 {@code images/}（见 {@link LaneDiagnostics#imageDirectory()}），
     * 不写 alpha 灰度图；编码与写盘交给 IO 线程，目录不可用时只关图。
     */
    private static void writeDiagnostic(NativeImage image, String name, String what) {
        File dir = LaneDiagnostics.imageDirectory();
        if (dir == null) {
            image.close();
            return;
        }
        LOGGER.info("[diagnostics] {}：{}x{} → {}/{}",
                what, image.getWidth(), image.getHeight(), dir.getAbsolutePath(), name);
        Util.ioPool().execute(() -> {
            try {
                dir.mkdirs();
                image.writeToFile(new File(dir, name));
            } catch (IOException e) {
                LOGGER.warn("[diagnostics] 图片写盘失败：{}", e.toString());
            } finally {
                image.close();
            }
        });
    }

    /** 产出目录：{@code <gameDirectory>/lane-captures/}。 */
    private static File captureDir() {
        return new File(Minecraft.getInstance().gameDirectory, DIR_NAME);
    }

    // ===== 工具 =====

    /** 文件名安全化：{@code [A-Za-z0-9_.-]} 之外的字符一律换成 {@code _}。 */
    private static String safe(String cameraId) {
        String id = cameraId == null || cameraId.isEmpty() ? "lane" : cameraId;
        StringBuilder out = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean allowed = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '_' || c == '.' || c == '-';
            out.append(allowed ? c : '_');
        }
        return out.toString();
    }

    /** 第 2 轮起的文件名后缀（第 1 轮无后缀，不覆盖）。 */
    private static String suffix(int round) {
        return round <= 1 ? "" : "-r" + round;
    }

    /** alpha 通道灰度可视化：灰度 = alpha，alpha 本身写 255（PNG 查看器的透明背景不再干扰判断）。 */
    private static NativeImage alphaView(NativeImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        NativeImage out = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = src.getPixelRGBA(x, y) >>> 24 & 0xFF;
                out.setPixelRGBA(x, y, 0xFF000000 | alpha << 16 | alpha << 8 | alpha);
            }
        }
        return out;
    }

    /**
     * alpha 统计（写进日志）：整体 0/255/中间值占比 + 自上而下 10 条横带的 {@code alpha=0} 占比
     * （10 个数即「内容到第几条带为止」的分界线）。
     */
    private static String alphaReport(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int bands = 10;
        long zero = 0L;
        long full = 0L;
        long mid = 0L;
        int[] bandZero = new int[bands];
        int[] bandTotal = new int[bands];
        for (int y = 0; y < height; y++) {
            int band = Math.min(bands - 1, y * bands / height);
            for (int x = 0; x < width; x++) {
                int alpha = image.getPixelRGBA(x, y) >>> 24 & 0xFF;
                if (alpha == 0) {
                    zero++;
                    bandZero[band]++;
                } else if (alpha == 255) {
                    full++;
                } else {
                    mid++;
                }
                bandTotal[band]++;
            }
        }
        long total = (long) width * height;
        StringBuilder bandsText = new StringBuilder();
        for (int b = 0; b < bands; b++) {
            if (b > 0) {
                bandsText.append(' ');
            }
            bandsText.append(String.format(Locale.ROOT, "%.0f", bandTotal[b] == 0 ? 0.0F : 100.0F * bandZero[b] / bandTotal[b]));
        }
        return String.format(Locale.ROOT, "a=0 %.1f%% / a=255 %.1f%% / 中间 %.1f%%；横带(上→下) a=0 占比[%s]%%",
                100.0F * zero / total, 100.0F * full / total, 100.0F * mid / total, bandsText);
    }

    /**
     * 开关解析：系统属性 {@code icinematics.capture} 优先，其次环境变量 {@code ICINEMATICS_CAPTURE}；
     * {@code 1} / {@code true} 等即开，非法值按开处理。
     */
    private static boolean parseEnabled() {
        String value = System.getProperty("icinematics.capture");
        if (value == null || value.isEmpty()) {
            value = System.getenv("ICINEMATICS_CAPTURE");
        }
        if (value == null || value.isEmpty() || "false".equalsIgnoreCase(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        try {
            return Integer.parseInt(value.trim()) > 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
