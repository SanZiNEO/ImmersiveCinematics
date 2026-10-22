package com.immersivecinematics.immersive_cinematics.client.post;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneCompositor;
import com.immersivecinematics.immersive_cinematics.script.ColorCurve;
import com.immersivecinematics.immersive_cinematics.script.CubeLut;
import com.immersivecinematics.immersive_cinematics.script.CubeLutLoader;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * 画面颜色调整 pass：把 {@link ColorAdjustParams} 的标量组一次全屏 pass 应用到画面纹理。
 * master（{@link MasterColorAdjust} 发布）作用于合成输出：lane 合成与原版后处理链之后、GUI 之前，
 * 挂点在 {@code GameRendererMixin} 的 {@code postEffect.process(f)} 之后。
 * lane 级作用于该 lane 画面：{@code LaneRenderer.renderLane} 之后、合成之前调 {@link #applyTo}；
 * 成本：master 两次全屏 quad（逐层每层一次，末尾至多一次整屏 blit），lane 一次。
 * 无调整 = 零 GL 状态改动、不编译着色器；截图边界：手动 F2 读上一帧已画完的主画面（含调色），自动世界截图在挂点之前（不含调色）。
 * 失败语义（fail-safe 直通）：宁可不调色，也绝不把未写 / 未定义的缓冲铺上屏。{@link #applyTo} 返回
 * {@code false} = 本层未施加；{@link #render} 链式施加遇败即停、保留上一层输出；末尾整屏 blit 仅在
 * 本帧确实写过中转缓冲时执行（检查全是状态查询，无读回 / 无同步等待）。
 * 十二个采样器（顶格 = {@code GlStateManager} 的 12 个纹理单元）与 LUT 绑定自检见 {@link LutBindGuard}。
 * 为什么 LUT 只有一张表：1D shaper 与 3D 各占一个采样器会到 13 个，而 {@code GlStateManager} 只有 12 个纹理单元，
 * 第 13 个在 {@code ShaderInstance.apply()} 按 JSON 下标绑定时越界崩溃；
 * 故加载期把 1D 段与两段 DOMAIN 烘焙进 {@link CubeLut#composed3D()} 的单张表。
 * alpha 契约：只动 RGB，{@code fragColor.a = src.a} 逐位直通；层级混合 {@code c = mix(c_base,
 * blend(c_base, c_adj, mode), clamp(amount, 0, 1))}，缺省 {@code normal} + {@code 1} = 直替换、整步跳过。
 */
public final class ColorAdjustPass {

    /** 着色器名：{@code assets/minecraft/shaders/core/ic_color_adjust.{json,vsh,fsh}}（默认命名空间）。 */
    private static final String SHADER_NAME = "ic_color_adjust";

    /** 当前着色器实例；{@code null} = 尚未加载或该资源周期内加载失败。 */
    private static ShaderInstance shader;

    /** {@link #shader} 的来源资源管理器：换对象 = 资源重载 → 重建（旧实例连同 GL program 一起释放）。 */
    private static ResourceManager shaderSource;

    /** 中转缓冲（按主画面尺寸创建 / 重建；只在渲染线程访问）。 */
    private static RenderTarget swapTarget;

    /**
     * 「本帧中转缓冲已写」标记：{@link #render} 每次进入链式施加时清空，只有确认过的写入（目标 = 中转缓冲）才置位。
     */
    private static boolean swapWrittenThisFrame;

    /** 失败告警的最小间隔（ms）：渲染路径逐帧复现，失败要限频，不刷屏。 */
    private static final long FAILURE_LOG_INTERVAL_MS = 5000L;

    /** 一次清 GL 错误队列的上限（防病态驱动下死循环）。 */
    private static final int MAX_DRAINED_ERRORS = 32;

    /** 上一次失败告警的时间（{@code 0} = 从未告警）。 */
    private static long lastFailureLogMs;

    /** 限频窗口内被合并的失败次数（随下一条告警一起输出）。 */
    private static int suppressedFailures;

    /** RGB 复合曲线的 LUT 纹理槽（{@code CurveLut} 采样器）。 */
    private static final LutTexture CURVE_LUT = new LutTexture();

    /** R / G / B 每通道曲线的 LUT 纹理槽（{@code RCurveLut} / {@code GCurveLut} / {@code BCurveLut} 采样器）。 */
    private static final LutTexture R_CURVE_LUT = new LutTexture();
    private static final LutTexture G_CURVE_LUT = new LutTexture();
    private static final LutTexture B_CURVE_LUT = new LutTexture();

    /** 六条 hue 曲线的 LUT 纹理槽（采样器名见 {@link LutBindGuard#DECLARED_SAMPLERS}）。 */
    private static final LutTexture HV_H_LUT = new LutTexture();
    private static final LutTexture HV_S_LUT = new LutTexture();
    private static final LutTexture HV_L_LUT = new LutTexture();
    private static final LutTexture LV_S_LUT = new LutTexture();
    private static final LutTexture SV_S_LUT = new LutTexture();
    private static final LutTexture SV_L_LUT = new LutTexture();

    /** 已解析过 {@code Lut3D} 实际单元的着色器实例（单元号链接后固定，随实例缓存；换实例 = 资源重载后重新解析）。 */
    private static ShaderInstance lut3DUnitResolvedFor;

    /** 缓存的 {@code Lut3D} 实际单元（{@link LutBindGuard#resolveUnit} 的结果；{@code -1} = 被优化掉）。 */
    private static int lut3DUnit = -1;

    /** 3D LUT 的纹理槽（{@code Lut3D} 采样器）。 */
    private static final Lut3DTexture LUT_3D = new Lut3DTexture();

    private ColorAdjustPass() {
    }

    /**
     * 在合成输出上应用本帧的 master 颜色调整：逐层顺序施加，前一层输出 = 后一层的基画面，
     * 目标缓冲在主画面 / 中转缓冲之间交替，结果停在中转缓冲时末尾整屏 blit 回主画面。
     * 无调整（没人发布 / 参数恒等）直接返回；某层施加失败即停，已施加的输出保持不动。
     * 末尾 blit 只在「本帧确实写过中转缓冲」时执行——首层失败 = 整链直通原始画面。
     */
    public static void render(Minecraft mc) {
        List<ColorAdjustParams> layers = MasterColorAdjust.INSTANCE.consume();
        if (layers.isEmpty()) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        int width = main.width;
        int height = main.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        RenderTarget swap = swapTarget(width, height);
        // 逐层链式施加：本层输入 = 上一层输出，写进另一个缓冲（不能同时读写同一张纹理）
        swapWrittenThisFrame = false;
        RenderTarget current = main;
        for (int i = 0; i < layers.size(); i++) {
            RenderTarget next = current == main ? swap : main;
            if (!applyTo(current, next, layers.get(i), mc)) {
                break;   // 未施加 / 施加失败：停在这一层之前（已施加的层保持）
            }
            if (next == swap) {
                swapWrittenThisFrame = true;
            }
            current = next;
        }
        if (current != main) {
            // pass 2：中转缓冲 → 主画面（整屏 blit，复用合成层的状态保存 / 还原与 UV 口径）；
            // 只有本帧写过才 blit，否则主画面保持原内容（blit 失败也不会变黑）。
            if (swapWrittenThisFrame) {
                // written 参数 = 本帧已写（守门据此放行）；mask = null → 合成按 full 处理。
                LaneCompositor.compose(current, LaneCompositor.Rect.FULL, LaneCompositor.Rect.FULL, 1.0F,
                        swapWrittenThisFrame, null);
            } else {
                main.bindWrite(true);   // 不 blit：把主画面绑回来（调用方接着画）
                reportFailure("链末尾校验未通过：中转缓冲本帧未被写入，跳过回写（画面保持原样）", null);
            }
        }
    }

    /**
     * 一次调色 pass：把 {@code src} 的颜色纹理经调色着色器写进 {@code dst}（全屏 quad，1:1）。
     * master 与 lane 级共用同一实现（着色器获取 / uniform 上传 / pass 执行都不分叉），唯一差别是源 / 目标缓冲。
     * 失败语义（fail-safe 直通）：只在「目标 FBO 完整 + 本 pass 施加全程无 GL 错误」时返回 {@code true}；
     * 任何失败（含异常）都返回 {@code false} = 本层未施加，调用方必须丢弃 {@code dst} 的内容。
     * 进入时保存、退出时还原全局投影 / VertexSorting / shader 颜色与 0 号纹理 / 深度与混合状态。
     *
     * @param src    源画面（取颜色纹理作采样源；不得与 {@code dst} 同一张纹理）
     * @param dst    目标缓冲（视口会被设成全尺寸）
     * @param params 调色参数（{@code null} 或恒等 → 直接返回 {@code false}，不动任何 GL 状态）
     * @param mc     客户端实例（取资源管理器加载 / 重建着色器）
     * @return 是否真的跑了 pass 并写满了 {@code dst}（{@code false} = 参数为空/恒等、尺寸非法、纹理无效、
     *         着色器不可用或施加失败——除前三种「未施加」外，{@code dst} 的内容都不可使用）
     */
    public static boolean applyTo(RenderTarget src, RenderTarget dst, ColorAdjustParams params, Minecraft mc) {
        if (src == null || dst == null || params == null || params.isIdentity()) {
            return false;
        }
        if (dst.width <= 0 || dst.height <= 0) {
            return false;
        }
        // 纹理名为 0 = 该缓冲没有颜色纹理：采样只会得到黑（且 GL 不报错），先于绘制拦下
        int srcTexture = src.getColorTextureId();
        int dstTexture = dst.getColorTextureId();
        if (srcTexture <= 0 || dstTexture <= 0) {
            reportFailure("源 / 目标缓冲没有颜色纹理（src=" + srcTexture + ", dst=" + dstTexture
                    + "）：本层跳过，画面保持上一层 / 原始输出", null);
            return false;
        }
        ShaderInstance shaderInstance = shader(mc);
        if (shaderInstance == null) {
            return false;   // 着色器不可用：宁可不调色，也不动画面
        }
        // 本 pass 的 GL 错误从零开始计：先清进入前的积压，之后产生的错误才算本 pass 的
        drainGlErrors();
        try {
            return pass(shaderInstance, src, dst, params);
        } catch (RuntimeException e) {
            reportFailure("调色 pass 执行异常（本层跳过，画面保持上一层 / 原始输出）", e);
            return false;
        }
    }

    /**
     * 一次调色 pass 的执行体：上传参数 → 绘制 → 校验「目标确实被本 pass 写满」。
     * 校验口径：目标 FBO 必须是 {@code GL_FRAMEBUFFER_COMPLETE}（不完整就连画都不画），且全程无 GL 错误；
     * 只查状态，无读回、无 {@code glFinish} 之类同步等待。
     *
     * @return 目标缓冲是否被本 pass 写满（{@code false} = 未写 / 半写 / 报错，调用方必须丢弃这个输出）
     */
    private static boolean pass(ShaderInstance shaderInstance, RenderTarget src, RenderTarget dst,
                               ColorAdjustParams params) {
        int width = dst.width;
        int height = dst.height;

        // 曲线 LUT：有曲线且强度非 0 → 该曲线的 LUT；否则恒等 LUT（采样器常绑，见 LutTexture）
        shaderInstance.setSampler("CurveLut", CURVE_LUT.bind(activeLut(params.curveLut(), params.curveStrength())));
        shaderInstance.setSampler("RCurveLut", R_CURVE_LUT.bind(activeLut(params.rCurveLut(), params.rCurveStrength())));
        shaderInstance.setSampler("GCurveLut", G_CURVE_LUT.bind(activeLut(params.gCurveLut(), params.gCurveStrength())));
        shaderInstance.setSampler("BCurveLut", B_CURVE_LUT.bind(activeLut(params.bCurveLut(), params.bCurveStrength())));
        shaderInstance.setSampler("HvHLut", HV_H_LUT.bind(activeLut(params.hvHLut(), params.hvHStrength())));
        shaderInstance.setSampler("HvSLut", HV_S_LUT.bind(activeLut(params.hvSLut(), params.hvSStrength())));
        shaderInstance.setSampler("HvLLut", HV_L_LUT.bind(activeLut(params.hvLLut(), params.hvLStrength())));
        shaderInstance.setSampler("LvSLut", LV_S_LUT.bind(activeLut(params.lvSLut(), params.lvSStrength())));
        shaderInstance.setSampler("SvSLut", SV_S_LUT.bind(activeLut(params.svSLut(), params.svSStrength())));
        shaderInstance.setSampler("SvLLut", SV_L_LUT.bind(activeLut(params.svLLut(), params.svLStrength())));

        // LUT：无 LUT 或强度 0 → 整步跳过（逐位恒等）；3D 表按 GL_TEXTURE_3D 手动绑（见 LutBindGuard）
        CubeLut lut = params.lut() != null && params.lutStrength() != 0.0F ? params.lut() : null;
        // 绑定自检不过 → 当「本帧没有 LUT」处理（uniform 上传把 LutStrength / Lut3DSize 归 0），只跳过 LUT 步
        if (lut != null && !lutStepReady(shaderInstance, lut)) {
            lut = null;
        }
        shaderInstance.setSampler("Lut3D", 0);   // 占位：让 apply() 上传该采样器的纹理单元号（0 号 2D 纹理 = 不绑任何东西）
        upload(shaderInstance, params, lut);

        Matrix4f prevProjection = RenderSystem.getProjectionMatrix();
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        int prevTexture = RenderSystem.getShaderTexture(0);
        float[] prevColor = RenderSystem.getShaderColor().clone();

        boolean written = false;
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.colorMask(true, true, true, true);

            // 全屏 quad 铺满目标缓冲，无需 clear
            dst.bindWrite(true);   // 绑 FBO + 视口 = FBO 全尺寸
            // 目标不可写（附件丢失 / 重建失败 / 尺寸非法）→ 连画都不画：驱动会丢弃这次绘制、只留下
            // 半写内容，而这次施加必须被当成失败（调用方据此跳过本层，见 render）
            if (GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
                RenderSystem.setProjectionMatrix(
                        new Matrix4f().setOrtho(0.0F, width, height, 0.0F, 1000.0F, 3000.0F),
                        VertexSorting.ORTHOGRAPHIC_Z);
                PoseStack modelView = RenderSystem.getModelViewStack();
                modelView.pushPose();
                try {
                    modelView.setIdentity();
                    modelView.translate(0.0F, 0.0F, -2000.0F);   // 落在正交投影的 z 范围内
                    RenderSystem.applyModelViewMatrix();

                    RenderSystem.setShader(() -> shaderInstance);
                    RenderSystem.setShaderTexture(0, src.getColorTextureId());
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    if (lut != null) {
                        // 3D LUT 必须在绘制前按 GL_TEXTURE_3D 目标手动绑（setSampler 一律按 2D 绑）
                        LUT_3D.bind();
                    }

                    // 屏幕正交投影下 y 向下增长；纹理 v 轴向上 → 底边取 v=0（与合成层同一口径，1:1 不翻转）
                    float x0 = 0.0F;
                    float x1 = (float) width;
                    float y0 = 0.0F;
                    float y1 = (float) height;
                    BufferBuilder builder = new BufferBuilder(256);
                    builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                    builder.vertex(x0, y1, 0.0).uv(0.0F, 0.0F).endVertex();   // 左下
                    builder.vertex(x1, y1, 0.0).uv(1.0F, 0.0F).endVertex();   // 右下
                    builder.vertex(x1, y0, 0.0).uv(1.0F, 1.0F).endVertex();   // 右上
                    builder.vertex(x0, y0, 0.0).uv(0.0F, 1.0F).endVertex();   // 左上
                    BufferUploader.drawWithShader(builder.end());
                } finally {
                    modelView.popPose();
                    RenderSystem.applyModelViewMatrix();
                }
                // 施加全程无 GL 错误 = 目标确实被这次绘制写满；只查状态、不读回、不同步等待
                written = !drainGlErrors();
            }
            if (!written) {
                reportFailure("调色 pass 未能写满目标缓冲（FBO 不完整或绘制报错）：本层跳过，"
                        + "画面保持上一层 / 原始输出", null);
            }
        } finally {
            LUT_3D.unbind();
            RenderSystem.setShaderColor(prevColor[0], prevColor[1], prevColor[2], prevColor[3]);
            RenderSystem.setShaderTexture(0, prevTexture);
            RenderSystem.setProjectionMatrix(prevProjection, prevSorting);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.colorMask(true, true, true, true);
        }
        return written;
    }

    /**
     * 清空 / 读取 GL 错误队列（至多 {@link #MAX_DRAINED_ERRORS} 条，防病态驱动下死循环）。
     * 用法固定成对：{@link #applyTo} 进入时清积压（进入前的错误不归本 pass），绘制后再调一次——读到错误 = 本 pass 失败。
     *
     * @return 是否读到过错误
     */
    private static boolean drainGlErrors() {
        boolean any = false;
        for (int i = 0; i < MAX_DRAINED_ERRORS; i++) {
            if (GL11.glGetError() == GL11.GL_NO_ERROR) {
                break;
            }
            any = true;
        }
        return any;
    }

    /**
     * 失败告警（限频）：两次告警至少间隔 {@link #FAILURE_LOG_INTERVAL_MS}，窗口内的次数合并到下一次输出。
     *
     * @param what 失败描述（不含「画面颜色调整失败：」前缀）
     * @param t    异常（无异常传 {@code null}）
     */
    private static void reportFailure(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogMs < FAILURE_LOG_INTERVAL_MS) {
            suppressedFailures++;
            return;
        }
        String merged = suppressedFailures == 0 ? "" : "（另有 " + suppressedFailures + " 次同类失败未逐条记录）";
        suppressedFailures = 0;
        lastFailureLogMs = now;
        String message = "画面颜色调整失败：" + what + merged;
        if (t == null) {
            ErrorLog.log("Render", message);
        } else {
            ErrorLog.log("Render", message, t);
        }
    }

    /**
     * LUT 步绑定自检（见 {@link LutBindGuard}）：采样器实际单元必须等于 {@link LutBindGuard#LUT_3D_UNIT}，
     * 且 3D 纹理 level 0 边长与要采样的表边长一致；单元结论随着色器实例缓存，不逐帧查 GL。
     * 任一不过 → 限频告警 + 返回 {@code false}：调用方把本帧当「没有 LUT」处理，只跳过 LUT 步。
     */
    private static boolean lutStepReady(ShaderInstance shader, CubeLut lut) {
        if (shader != lut3DUnitResolvedFor) {
            lut3DUnitResolvedFor = shader;
            lut3DUnit = LutBindGuard.resolveUnit(LutBindGuard.LUT_3D,
                    name -> Uniform.glGetUniformLocation(shader.getId(), name) >= 0);
            if (lut3DUnit != LutBindGuard.LUT_3D_UNIT) {
                reportFailure("LUT 步骤跳过（本层其余步骤照常，画面不受影响）：Lut3D 采样器单元错位——"
                        + "着色器实际单元 = " + lut3DUnit + "，Java 侧绑定 = " + LutBindGuard.LUT_3D_UNIT
                        + "；驱动 / GLSL 优化掉了声明次序里靠前的采样器", null);
                return false;
            }
        }
        if (lut3DUnit != LutBindGuard.LUT_3D_UNIT) {
            return false;   // 已告警过：只按缓存的结论跳过，不刷屏
        }
        // 纹理完整性：先上传（同一 LUT 实例只传一次），再按回读到的实际边长核尺寸
        LUT_3D.upload(lut);
        if (!LUT_3D.readyFor(lut.composed3D().size())) {
            reportFailure("LUT 步骤跳过（本层其余步骤照常，画面不受影响）：3D LUT 纹理不可用——纹理 = "
                    + LUT_3D.textureId + "，level 0 边长 = " + LUT_3D.textureSize
                    + "，期望 " + lut.composed3D().size(), null);
            return false;
        }
        return true;
    }

    /**
     * 参数 → uniform（逐帧覆盖；着色器 JSON 已声明全部通道，缺省 0 = 无效果）；
     * 顺序与 {@link ColorAdjustParams} 的分量顺序、着色器的操作栈顺序一致。
     *
     * @param lut 本帧 LUT 步实际要用的表（{@code null} = 没有 LUT / 强度 0 / 自检没过）：
     *            {@code LutStrength} / {@code Lut3DSize} 归 0、{@code LutInputGamma} 归 1，着色器整步跳过
     */
    private static void upload(ShaderInstance shader, ColorAdjustParams p, CubeLut lut) {
        set(shader, "Exposure", p.exposure());
        set(shader, "Contrast", p.contrast());
        set(shader, "Highlights", p.highlights());
        set(shader, "Shadows", p.shadows());
        set(shader, "Whites", p.whites());
        set(shader, "Blacks", p.blacks());
        set(shader, "Hue", p.hue());
        set(shader, "Saturation", p.saturation());
        set(shader, "Vibrance", p.vibrance());
        set(shader, "Lightness", p.lightness());
        set(shader, "Temperature", p.temperature());
        set(shader, "Tint", p.tint());
        set(shader, "Red", p.red());
        set(shader, "Green", p.green());
        set(shader, "Blue", p.blue());
        set(shader, "MixRR", p.mixRR());
        set(shader, "MixRG", p.mixRG());
        set(shader, "MixRB", p.mixRB());
        set(shader, "MixGR", p.mixGR());
        set(shader, "MixGG", p.mixGG());
        set(shader, "MixGB", p.mixGB());
        set(shader, "MixBR", p.mixBR());
        set(shader, "MixBG", p.mixBG());
        set(shader, "MixBB", p.mixBB());
        set(shader, "CurveStrength", p.curveLut() == null ? 0.0F : p.curveStrength());
        set(shader, "RCurveStrength", p.rCurveLut() == null ? 0.0F : p.rCurveStrength());
        set(shader, "GCurveStrength", p.gCurveLut() == null ? 0.0F : p.gCurveStrength());
        set(shader, "BCurveStrength", p.bCurveLut() == null ? 0.0F : p.bCurveStrength());
        set(shader, "LiftR", p.liftR());
        set(shader, "LiftG", p.liftG());
        set(shader, "LiftB", p.liftB());
        set(shader, "GammaR", p.gammaR());
        set(shader, "GammaG", p.gammaG());
        set(shader, "GammaB", p.gammaB());
        set(shader, "GainR", p.gainR());
        set(shader, "GainG", p.gainG());
        set(shader, "GainB", p.gainB());
        // LUT：lut == null（无 LUT / 强度 0 / 自检没过）→ 强度与尺寸归 0、输入 gamma 归 1（整步跳过）
        set(shader, "LutStrength", lut == null ? 0.0F : p.lutStrength());
        set(shader, "LutInputGamma", lut == null ? 1.0F : p.lutInputGamma());
        set(shader, "Lut3DSize", lut == null ? 0.0F : lut.composed3D().size());
        set(shader, "HvHStrength", p.hvHLut() == null ? 0.0F : p.hvHStrength());
        set(shader, "HvSStrength", p.hvSLut() == null ? 0.0F : p.hvSStrength());
        set(shader, "HvLStrength", p.hvLLut() == null ? 0.0F : p.hvLStrength());
        set(shader, "LvSStrength", p.lvSLut() == null ? 0.0F : p.lvSStrength());
        set(shader, "SvSStrength", p.svSLut() == null ? 0.0F : p.svSStrength());
        set(shader, "SvLStrength", p.svLLut() == null ? 0.0F : p.svLStrength());
        set(shader, "HueRed", p.hueRed());
        set(shader, "SatRed", p.satRed());
        set(shader, "HueYellow", p.hueYellow());
        set(shader, "SatYellow", p.satYellow());
        set(shader, "HueGreen", p.hueGreen());
        set(shader, "SatGreen", p.satGreen());
        set(shader, "HueCyan", p.hueCyan());
        set(shader, "SatCyan", p.satCyan());
        set(shader, "HueBlue", p.hueBlue());
        set(shader, "SatBlue", p.satBlue());
        set(shader, "HueMagenta", p.hueMagenta());
        set(shader, "SatMagenta", p.satMagenta());
        set(shader, "Grayscale", p.grayscale());
        set(shader, "Invert", p.invert());
        // 层级混合：枚举编码 0 ~ 4、强度 0 ~ 1（缺省 1，着色器内 clamp）；只属 ADJUST 轨，lane 路径恒为缺省
        set(shader, "BlendMode", p.blendMode());
        set(shader, "BlendAmount", p.blendAmount());
    }

    /** 某一步实际要绑的 LUT：有曲线且强度非 0 → 曲线自己的 LUT，否则恒等 LUT（{@code y = x}）。 */
    private static float[] activeLut(float[] lut, float strength) {
        return lut != null && strength != 0.0F ? lut : ColorCurve.identityLut();
    }

    /**
     * 一个曲线 LUT 纹理槽：256×1、LINEAR 过滤（相邻项之间线性插值 = 曲线上的细分采样）。
     * 纹理只建一次；数组按引用比较（LUT 随 clip 缓存，同一片段每帧同一个数组 → 换曲线才重传）。
     * 绑定走 {@link ShaderInstance#setSampler}（JSON {@code samplers} 次序 = 纹理单元），不占
     * {@code RenderSystem} 的 {@code shaderTextures} 槽位，无额外全局状态需要保存 / 还原。
     * 无曲线 / 强度 0 时承载恒等 LUT——采样器常绑，不出现未绑定的采样器。
     */
    private static final class LutTexture {

        private DynamicTexture texture;

        /** {@link #texture} 当前承载的 LUT 数组（按引用比较：同一个 clip 的 LUT 只上传一次）。 */
        private float[] uploaded;

        DynamicTexture bind(float[] lut) {
            if (texture == null) {
                texture = new DynamicTexture(ColorCurve.LUT_SIZE, 1, false);
                texture.setFilter(true, false);   // LINEAR、无 mipmap
            }
            if (lut != uploaded) {
                NativeImage pixels = texture.getPixels();
                if (pixels != null) {
                    for (int i = 0; i < ColorCurve.LUT_SIZE; i++) {
                        int v = Math.round(Math.max(0.0F, Math.min(1.0F, lut[i])) * 255.0F);
                        pixels.setPixelRGBA(i, 0, 0xFF000000 | (v << 16) | (v << 8) | v);
                    }
                    texture.upload();
                    uploaded = lut;
                }
            }
            return texture;
        }
    }

    /**
     * 3D LUT 的纹理槽（{@code Lut3D} 采样器）：S×S×S、RGBA16F、NEAREST + CLAMP_TO_EDGE；
     * 表 = {@link CubeLut#composed3D()}，采样坐标 {@code x ∈ [0,1]³} 直接对应网格。
     * 按 {@link CubeLut} 实例引用比较：同一 LUT 只上传一次，换 LUT 才重传（上传失败也记，不逐帧重试）。
     * 用 16 位浮点而非 8 位：{@code .cube} 数据是浮点且可超出 {@code [0,1]}（见 {@link CubeLut} 文档），
     * 8 位量化会截掉超出部分并在中性灰附近留下台阶。
     * NEAREST：插值不交给硬件，着色器 {@code texelFetch} 取 8 个角点后按四面体加权。
     * 完整性：上传后回读 level 0 三个维度，与表边长逐维一致才记 {@link #textureSize}（否则 0 = 不可用）。
     * 3D 纹理不能走 {@link ShaderInstance#setSampler}（那条路按 {@code GL_TEXTURE_2D} 绑定），
     * 这里按 {@code GL_TEXTURE_3D} 手动绑到 {@link LutBindGuard#LUT_3D_UNIT}。
     */
    private static final class Lut3DTexture {

        /** {@code GL_MAX_3D_TEXTURE_SIZE}（首次查询后缓存；{@code -1} = 尚未查询）。 */
        private static int maxTextureSize = -1;

        private int textureId = -1;

        /** 已处理过的 LUT（引用比较：同一个 LUT 实例只传一次；上传失败也记，避免逐帧重试）。 */
        private CubeLut uploaded;

        /** 纹理 level 0 的实际边长（上传后回读校验通过才置位；{@code 0} = 没有可用纹理）。 */
        private int textureSize;

        /** 上传本 LUT 的合成表（同一实例只传一次）；无 LUT / 已处理 → 不动。 */
        void upload(CubeLut lut) {
            if (lut == null || lut == uploaded) {
                return;
            }
            CubeLut.Composed3D composed = lut.composed3D();
            int size = composed.size();
            // 尺寸守卫：合成表理论上 ≤ COMPOSED_MAX_SIZE，仍按驱动上限核一道——超上限的上传必然不完整
            // （texelFetch 采到未定义值），故直接不上传、标记不可用，让调用方跳过 LUT 步
            int maxSize = maxTextureSize();
            if (maxSize > 0 && size > maxSize) {
                uploaded = lut;
                textureSize = 0;
                ErrorLog.log("LUT", "合成表边长 " + size + " 超出 GL_MAX_3D_TEXTURE_SIZE（" + maxSize
                        + "）：不上传（纹理必然不完整），LUT 步骤跳过；检查 LUT 尺寸与 CubeLut 合成上限");
                return;
            }
            float[] data = composed.data();
            int entries = size * size * size;
            FloatBuffer pixels = BufferUtils.createFloatBuffer(entries * 4);
            for (int i = 0; i < entries; i++) {
                pixels.put(data[i * 3]).put(data[i * 3 + 1]).put(data[i * 3 + 2]).put(1.0F);
            }
            pixels.flip();
            if (textureId == -1) {
                textureId = GL11.glGenTextures();
            }
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, textureId);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGBA16F, size, size, size, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
            // NEAREST：插值由着色器按四面体自己算（texelFetch 逐角点取，不依赖纹理过滤）
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            // 完整性守卫：回读 level 0 三个维度（必须趁纹理还绑着），与表边长逐维比
            int width = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL11.GL_TEXTURE_HEIGHT);
            int depth = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL12.GL_TEXTURE_DEPTH);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            uploaded = lut;
            textureSize = width == size && height == size && depth == size ? size : 0;
            if (textureSize == 0) {
                ErrorLog.log("LUT", "3D LUT 纹理不完整（回读 " + width + "x" + height + "x" + depth
                        + "，期望 " + size + "³，纹理名 " + textureId
                        + "）：LUT 步骤跳过；检查驱动上限与纹理上传");
            }
        }

        /** 纹理完整性守卫：纹理已创建且 level 0 边长与要采样的表边长一致。 */
        boolean readyFor(int expectedSize) {
            return LutBindGuard.textureReady(textureId, textureSize, expectedSize);
        }

        /** {@code GL_MAX_3D_TEXTURE_SIZE}（首次查询后缓存；{@code 0} = 未知 = 不检查）。 */
        private static int maxTextureSize() {
            int cached = maxTextureSize;
            if (cached < 0) {
                cached = GL11.glGetInteger(GL12.GL_MAX_3D_TEXTURE_SIZE);
                maxTextureSize = cached;
            }
            return cached;
        }

        /** 把当前纹理绑到 {@link LutBindGuard#LUT_3D_UNIT} 单元（绘制前调用；不改变其它单元与 2D 绑定）。 */
        void bind() {
            bindLut3D(textureId);
        }

        /** 解绑（绘制后恢复：该单元不留悬挂绑定）。 */
        void unbind() {
            bindLut3D(0);
        }
    }

    /** 在 {@link LutBindGuard#LUT_3D_UNIT} 单元上按 {@code GL_TEXTURE_3D} 目标绑定一个纹理名（0 = 解绑）。 */
    private static void bindLut3D(int textureId) {
        int prevUnit = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + LutBindGuard.LUT_3D_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, Math.max(textureId, 0));
        GlStateManager._activeTexture(prevUnit);
    }

    /** 写一个 float uniform；着色器里没有该 uniform 时跳过（{@code getUniform} 返回 {@code null}）。 */
    private static void set(ShaderInstance shader, String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    /** 写一个 vec3 uniform（JSON 里声明为 {@code type: "float", count: 3}）；着色器里没有该 uniform 时跳过。 */
    private static void set(ShaderInstance shader, String name, float[] xyz) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(xyz[0], xyz[1], xyz[2]);
        }
    }

    /**
     * 取（必要时创建）着色器实例；换资源管理器（资源重载）后重建——旧实例 {@code close()} 释放 GL program，
     * 否则原版 Program 缓存按名字复用会拿到旧编译结果。
     * 加载失败只记一次（同一资源周期内不重试），画面保持未调色。
     */
    private static ShaderInstance shader(Minecraft mc) {
        ResourceManager resources = mc.getResourceManager();
        if (shaderSource != resources) {
            if (shader != null) {
                shader.close();
                shader = null;
            }
            lut3DUnitResolvedFor = null;   // 旧实例的单元结论作废（新实例按新程序重新解析）
            shaderSource = resources;
            try {
                shader = new ShaderInstance(resources, SHADER_NAME, DefaultVertexFormat.POSITION_TEX);
            } catch (IOException e) {
                ErrorLog.log("Render", "画面颜色调整着色器加载失败（" + SHADER_NAME + "）："
                        + "本资源周期内不做颜色调整，检查 assets/minecraft/shaders/core/" + SHADER_NAME + ".*", e);
            }
        }
        return shader;
    }

    /**
     * 中转缓冲（按主画面尺寸创建 / 重建）；无深度附件（只画一个全屏 quad、深度测试关闭），
     * 过滤保持默认 NEAREST（采样是 1:1 的，缩放才需要 LINEAR）。
     */
    private static RenderTarget swapTarget(int width, int height) {
        if (swapTarget == null) {
            swapTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        } else if (swapTarget.width != width || swapTarget.height != height) {
            swapTarget.resize(width, height, Minecraft.ON_OSX);
        }
        return swapTarget;
    }
}
