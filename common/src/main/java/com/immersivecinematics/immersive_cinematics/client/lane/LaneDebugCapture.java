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
 * 调试钩子：把每条 lane 渲染完的离屏纹理按<b>原始 RGBA</b>读回，写成 PNG，供离屏分析。
 *
 * <p><b>为什么需要它</b>：{@link LaneCompositor} 用 {@code position_tex} 的
 * {@code srcalpha / 1-srcalpha} 混合把 lane 画面贴上屏，而 {@code position_tex.fsh} 里还有
 * {@code if (color.a == 0.0) discard;}——也就是说 <b>lane 纹理的 alpha 通道直接决定屏幕上看得见什么</b>：
 * alpha=0 的像素会透出主 framebuffer（原版主画面）。而 lane FBO 的背景是
 * {@code FogRenderer.setupColor} 的 {@code RenderSystem.clearColor(fogR, fogG, fogB, 0.0f)}
 * 清出来的——<b>alpha=0</b>。旧原型用 {@code glBlitFramebuffer} 上屏（不做混合、屏幕不看 alpha），
 * 所以同一个 FBO 内容在旧链路上看不出问题；本钩子就是把这个 alpha 通道拿出来看。</p>
 *
 * <p><b>与 MC 自己的截图不同</b>：{@code Screenshot.takeScreenshot} 走
 * {@code downloadTexture(0, true)}，那个 {@code true} 会把 alpha <b>强制写成 255</b>
 * （见 {@code NativeImage.downloadTexture} 实现）——所以 F2 截图与旧原型的
 * {@code quadrant-captures} 都<b>看不到真实 alpha</b>。本钩子传 {@code false}，保留原始 alpha。</p>
 *
 * <h2>开关与产出</h2>
 * <ul>
 *   <li>开关 = 调试驱动同一开关（{@code -Dicinematics.quadrant} / {@code ICINEMATICS_QUADRANT}，
 *       见 {@link LaneDebugDriver}）。关闭时 {@link #onLaneRendered} 第一行即返回：不读回、不分配、
 *       不写盘、不切任何 GL 状态——<b>零差异</b>。</li>
 *   <li>产出目录：{@code <gameDirectory>/lane-captures/}（开发环境 = {@code fabric/run/lane-captures/}）。</li>
 *   <li>文件：{@code lane-{n}.png} = 该 lane 的原始 RGBA（<b>保留 alpha</b>，可直接看透明区域）；
 *       {@code lane-{n}-alpha.png} = 同一张图的 alpha 通道灰度可视化（alpha 当灰度、alpha 本身写 255）
 *       ——PNG 查看器多半把透明当背景色，灰度图用来<b>量</b> alpha=0 的区域。</li>
 *   <li>节奏：第一帧抓一轮，此后每 {@link #INTERVAL_FRAMES} 帧一轮，最多 {@link #MAX_ROUNDS} 轮；
 *       第 2 轮起文件名带 {@code -r{k}} 后缀，不覆盖第一轮。每轮 4 条 lane = 8 个文件。</li>
 *   <li>日志：每次读回打一行 {@code [lane-capture] ...}，含 alpha 的 0/255/中间值占比与
 *       <b>自上而下 10 条横带的 alpha=0 占比</b>（用来直接定位"上半有内容、下半透明"的分界线）。</li>
 * </ul>
 *
 * <h2>线程与状态</h2>
 * 读回必须在渲染线程（{@link #onLaneRendered} 在 {@code LaneRenderer.render} 的 lane 循环里被调用，
 * 天然在渲染线程）；PNG 编码与写盘丢给 {@link Util#ioPool()}。读回只动两处状态并还原：
 * 0 号纹理绑定（{@code RenderSystem.bindTexture} 与 {@code Screenshot} 同一取法）与当前纹理单元
 * （显式切到 0 号，避免踩到光照贴图的 2 号单元）。
 */
public final class LaneDebugCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 与 {@link LaneDebugDriver} 同一开关（{@code -Dicinematics.quadrant} / {@code ICINEMATICS_QUADRANT}）。 */
    private static final boolean ENABLED = parseEnabled();

    /** 每 N 帧抓一轮（首帧必抓）。 */
    private static final int INTERVAL_FRAMES = 200;
    /** 最多抓几轮（防刷屏；一轮 = 全部 lane）。 */
    private static final int MAX_ROUNDS = 5;

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
     * 一条 lane 渲染完、合成之前调用（{@code LaneRenderer.render} 的 lane 循环内）。
     *
     * <p>{@code index == 0} 时推进帧计数并决定本帧是否捕获；本帧捕获时对所有 lane 都读回。
     * 关闭时零差异。</p>
     */
    public static void onLaneRendered(int index, RenderTarget target) {
        if (!ENABLED) {
            return;
        }
        if (index == 0) {
            frames++;
            captureThisFrame = (frames == 1 || frames % INTERVAL_FRAMES == 0) && rounds < MAX_ROUNDS;
            if (captureThisFrame) {
                rounds++;
            }
        }
        if (!captureThisFrame) {
            return;
        }
        readBack(index, target, rounds);
    }

    /**
     * 本帧全部 lane 合成完之后调用（{@code LaneRenderer.render} 的 lane 循环之后）：
     * 把<b>主 framebuffer 的最终画面</b>（= 屏幕上看到的合成结果）读回写盘，供与旧原型的主图对比。
     *
     * <p>与 lane 读回的差别：主画面的 alpha 不由合成层写（{@code colorMask} 关掉 alpha），
     * 且屏幕上看到的是"不透明"的画面，所以这里走 {@code downloadTexture(0, true)}——与
     * {@code Screenshot.takeScreenshot} / 旧原型 {@code Screenshot.grab} 同一口径（alpha 强制 255）。</p>
     *
     * <p>关闭时零差异（第一行返回）。</p>
     */
    public static void onFrameComposed(RenderTarget main) {
        if (!ENABLED || !captureThisFrame) {
            return;
        }
        int width = main.width;
        int height = main.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        NativeImage image = new NativeImage(width, height, false);
        int prevTexture = RenderSystem.getShaderTexture(0);
        try {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(main.getColorTextureId());
            image.downloadTexture(0, true);   // true = alpha 强制 255（与 Screenshot 同口径）
            image.flipY();
        } finally {
            RenderSystem.setShaderTexture(0, prevTexture);
        }

        int round = rounds;
        String name = round <= 1 ? "screen" : "screen-r" + round;
        File dir = new File(Minecraft.getInstance().gameDirectory, "lane-captures");
        LOGGER.info("[lane-capture] 主画面 第 {} 轮：{}x{} → {}/{}.png", round, width, height,
                dir.getAbsolutePath(), name);
        Util.ioPool().execute(() -> {
            try {
                dir.mkdirs();
                image.writeToFile(new File(dir, name + ".png"));
            } catch (IOException e) {
                LOGGER.warn("[lane-capture] 主画面写盘失败：{}", e.toString());
            } finally {
                image.close();
            }
        });
    }

    /** 读回一条 lane 的离屏纹理（渲染线程）：原始 RGBA + alpha 灰度图，编码写盘交给 IO 线程。 */
    private static void readBack(int index, RenderTarget target, int round) {
        int width = target.width;
        int height = target.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        NativeImage image = new NativeImage(width, height, false);
        int prevTexture = RenderSystem.getShaderTexture(0);
        try {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(target.getColorTextureId());
            // false = 保留原始 alpha（Screenshot.takeScreenshot 传 true，会把 alpha 强制写成 255）
            image.downloadTexture(0, false);
            image.flipY();   // glGetTexImage 行序自下而上
        } finally {
            RenderSystem.setShaderTexture(0, prevTexture);
        }

        String suffix = round <= 1 ? "" : "-r" + round;
        String name = "lane-" + index + suffix;
        File dir = new File(Minecraft.getInstance().gameDirectory, "lane-captures");
        String alphaReport = alphaReport(image);
        LOGGER.info("[lane-capture] lane {} 第 {} 轮：{}x{} → {}/{}.png（alpha 统计：{}）",
                index, round, width, height, dir.getAbsolutePath(), name, alphaReport);
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

    /** 开关解析：与 {@link LaneDebugDriver#parseMode()} 同一套来源与语义（正数 / true 即开）。 */
    private static boolean parseEnabled() {
        String value = System.getProperty("icinematics.quadrant");
        if (value == null || value.isEmpty()) {
            value = System.getenv("ICINEMATICS_QUADRANT");
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
            return true;   // 与驱动一致：非法值按 4 处理（= 开）
        }
    }
}
