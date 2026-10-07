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
 * 调试钩子：按<b>相机 id</b>把每帧的 lane 画面读回写盘（PNG），供离屏分析。
 *
 * <p>三类产出（每轮各一份，lane 数量任意、无「4 条 lane / 8 个文件」的假设）：</p>
 * <ul>
 *   <li><b>每相机 raw</b>（{@link #onLaneRendered}，{@code LaneRenderer.renderLane} 内、lane 级调色之前）：
 *       该 lane 离屏 FBO 的原始 RGBA（{@code downloadTexture(0, false)}，<b>保真 alpha</b>）→
 *       {@code <id>-raw[-r{k}].png} + {@code <id>-raw[-r{k}]-alpha.png}（alpha 灰度可视化）。</li>
 *   <li><b>每相机 composited</b>（{@link #onLaneComposited}，该相机 {@link LaneCompositor#compose} 之后）：
 *       主 framebuffer 的保真 alpha 读回（{@code downloadTexture(0, false)}），裁剪到该相机的 {@code dest}
 *       矩形 → {@code <id>-composited[-r{k}].png} + {@code <id>-composited[-r{k}]-alpha.png}。</li>
 *   <li><b>窗口终帧</b>（{@link #onFrameEnd}，{@code GameRenderer.render} 的 RETURN）：整帧渲染结束
 *       （世界 → lane 合成 → master 调色 → GUI / 屏幕 / 提示）之后的主画面 → {@code frame[-r{k}].png}
 *       （alpha 强制 255，与 F2 截图同口径；内容 = 玩家看到的窗口画面）。</li>
 * </ul>
 *
 * <h2>为什么 raw 要看 alpha</h2>
 * <p>lane 纹理的 alpha 通道历史上直接决定屏幕上看得见什么（{@code position_tex} 的
 * {@code srcalpha / 1-srcalpha} 混合 + {@code if (color.a == 0.0) discard;}），而 lane FBO 的背景是
 * {@code FogRenderer.setupColor} 的清屏色——<b>alpha=0</b>（{@code LevelRendererMixin} 的
 * {@code laneClearAlpha} 现已把清屏 alpha 抬到 1，只影响 FBO 数据本身）。合成层改用
 * {@code ic_lane_blit}（忽略 lane alpha）后这条依赖已解除，但 FBO 里的 alpha 仍是「未覆盖区 /
 * 星星 / mipmap 边缘」的判据，所以 raw 与 composited 都按保真 alpha 读回。</p>
 *
 * <h2>与 MC 自己的截图不同</h2>
 * <p>{@code Screenshot.takeScreenshot} 走 {@code downloadTexture(0, true)}，那个 {@code true} 会把
 * alpha <b>强制写成 255</b>（见 {@code NativeImage.downloadTexture} 实现）——所以 F2 截图看不到真实
 * alpha。raw / composited 读回传 {@code false} 保留原始 alpha；窗口终帧按 F2 同口径传 {@code true}。</p>
 *
 * <h2>相机 id 与文件名</h2>
 * <p>id = {@code <scriptId>_cam<相机轨序号>}（{@code ScriptLaneDriver} 注册 lane 时传入，
 * 见 {@link LaneRenderer#setLane}）；没有 id 的 lane 回退 {@code lane<序号>}。文件名安全化：
 * {@code [A-Za-z0-9_.-]} 之外的字符一律换成 {@code _}（脚本 id 可能含 {@code :} 等）。</p>
 *
 * <h2>开关与节奏</h2>
 * <ul>
 *   <li>开关 = {@code -Dicinematics.capture} / 环境变量 {@code ICINEMATICS_CAPTURE}（{@code 1} / {@code true}
 *       等即开，见 {@link #parseEnabled}）。关闭时所有钩子第一行即返回：不读回、不分配、不写盘、
 *       不切任何 GL 状态——<b>零差异</b>。</li>
 *   <li>产出目录：{@code <gameDirectory>/lane-captures/}（开发环境 = {@code fabric/run/lane-captures/}）。</li>
 *   <li>节奏：首帧抓一轮（{@link #beginFrame}），此后每 {@link #INTERVAL_FRAMES} 帧一轮，最多
 *       {@link #MAX_ROUNDS} 轮；第 2 轮起文件名带 {@code -r{k}} 后缀，不覆盖第一轮。
 *       一轮 = 每条 lane 2 个 raw 文件 + 2 个 composited 文件，加 1 张窗口终帧。</li>
 *   <li>日志：每次读回打一行 {@code [lane-capture] ...}，含文件名与 alpha 的 0/255/中间值占比、
 *       <b>自上而下 10 条横带的 alpha=0 占比</b>（用来直接定位「上半有内容、下半透明」的分界线）。</li>
 * </ul>
 *
 * <h2>线程与状态</h2>
 * <p>读回必须在渲染线程（三个钩子都在渲染线程被调用）；PNG 编码与写盘丢给 {@link Util#ioPool()}。
 * 读回只动两处状态并还原：0 号纹理绑定（{@code RenderSystem.bindTexture} 与 {@code Screenshot} 同一取法）
 * 与当前纹理单元（显式切到 0 号，避免踩到光照贴图的 2 号单元）。</p>
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
     * 本帧开始、lane 渲染之前调用一次（{@code LaneRenderer.render} 的 lane 循环之前）：推进帧计数并决定
     * 本帧是否捕获。关闭时零差异。
     *
     * <p>只在本帧确实有 lane 可渲染时被调用——所以「窗口终帧」的捕获标志由 {@link #onFrameEnd} 消费一次，
     * 不会在没有 lane 的帧上重复出图。</p>
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
     * <b>a. 每相机 raw</b>：一条 lane 渲染完、lane 级调色之前调用（{@code LaneRenderer.renderLane} 内）。
     *
     * <p>读的是 lane 离屏 FBO 的原始渲染结果（不含 lane 级调色、不含合成），保真 alpha。关闭 / 非捕获帧
     * 时第一行返回。</p>
     *
     * @param cameraId 该 lane 的相机 id（{@code null} / 空 = 由调用方给出的回退名）
     * @param target   lane 的离屏缓冲
     */
    public static void onLaneRendered(String cameraId, RenderTarget target) {
        if (!ENABLED || !captureThisFrame || target.width <= 0 || target.height <= 0) {
            return;
        }
        NativeImage image = download(target, false);   // false = 保留原始 alpha
        writePair(image, safe(cameraId) + "-raw" + suffix(rounds), "相机 " + cameraId + " raw");
    }

    /**
     * <b>b. 每相机 composited（带 alpha）</b>：该相机合成进主画面<b>之后</b>调用
     * （{@code ScriptLaneDriver.compose} 内，紧随 {@link LaneCompositor#compose}）。
     *
     * <p>读回主 framebuffer 的保真 alpha（{@code downloadTexture(0, false)}），裁剪到该相机的 {@code dest}
     * 矩形——即「这台相机在窗口画面里占的那块、合成后的样子」。{@code dest} 为空 / 退化（宽或高为 0）时跳过。
     * 关闭 / 非捕获帧时第一行返回。</p>
     *
     * @param cameraId 该 lane 的相机 id（{@code null} / 空 = 由调用方给出的回退名）
     * @param dest     该相机的屏幕归一化目标矩形（原点左上，与 {@link LaneCompositor.Rect} 同口径）
     */
    public static void onLaneComposited(String cameraId, LaneCompositor.Rect dest) {
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
     * <b>c. 窗口终帧</b>：整帧渲染结束（GUI 之后）调用 —— {@code GameRenderer.render} 的 RETURN。
     *
     * <p>读回主 framebuffer → {@code frame[-r{k}].png}，alpha 强制 255（与 {@code Screenshot.takeScreenshot}
     * / F2 截图同口径）。此刻本帧画面已全部画完，内容 = 玩家看到的窗口画面：原版 {@code blitToScreen}
     * 在 {@code Minecraft.runTick} 里、{@code GameRenderer.render} 返回<b>之后</b>才调用，只是把主画面整幅
     * 拷到窗口，不改变主画面内容。</p>
     *
     * <p>本方法<b>消费</b>本帧的捕获标志：一帧最多一张终帧，没有 lane 的帧（{@link #beginFrame} 未被调用）
     * 不会重复出图。关闭 / 非捕获帧 / 未进世界时第一行返回。</p>
     */
    public static void onFrameEnd(Minecraft mc) {
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

    /** 读回一个 framebuffer 的颜色纹理（渲染线程）：{@code forceAlpha=true} 时 alpha 强制 255（F2 截图口径）。 */
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
     * alpha 统计（写进日志，省得主代理非要去开图）：整体 0/255/中间值占比 + 自上而下 10 条横带的
     * {@code alpha=0} 占比（10 个数就是"内容到第几条带为止"的分界线）。
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

    /** 开关解析（{@code 1} / {@code true} 等即开）：系统属性 {@code icinematics.capture}，再环境变量 {@code ICINEMATICS_CAPTURE}。 */
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
            return true;   // 非法值按开处理（沿用原语义）
        }
    }
}
