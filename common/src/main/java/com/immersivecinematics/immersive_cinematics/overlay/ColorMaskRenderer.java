package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.script.ColorMaskSpec;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * fade 高级颜色遮罩的 GPU 渲染器（0.3.6 D4–D7）。
 *
 * <p>负责纯色 / 多 stops 线性与径向渐变、full / rect / circle 覆盖范围、normal / multiply /
 * screen / soft_light / overlay 五种混合。纯色 + full + normal 不走这里（{@link FadeLayer} 保留
 * {@link GuiGraphics#fill} 快路径）；其余形态由 {@code ic_color_mask} core shader 完成。</p>
 *
 * <p>每个高级 {@link FadeLayer} 持有一个实例；构造只登记共享资源引用计数，GL 资源按需创建。
 * {@link #close()} 释放本层 LUT，并在最后一个实例关闭时释放共享 shader / dummy / scratch。
 * 资源重载由 {@link #invalidate()} 接入（Fabric / Forge 的 reload listener），跨线程调用会
 * {@link RenderSystem#recordRenderCall} 排队到渲染线程，绝不在 reload 线程直接碰 GL。</p>
 *
 * <p>绘制状态契约：入口先 {@code guiGraphics.flush()}；随后保存 / 恢复 shader、纹理单元、
 * blend / equation、depth、colorMask、scissor、framebuffer / viewport；所有改动都在 finally 还原。
 * 主 framebuffer 的 alpha 始终不写（colorMask 关 alpha）。非 normal 先把它复制到同尺寸 scratch
 * （{@code glBlitFramebuffer}，同像素、不翻 y），shader 用 {@code texelFetch} 精确读取 base，
 * 不会同时读写同一纹理。</p>
 */
public final class ColorMaskRenderer {

    /** core shader 名：{@code assets/minecraft/shaders/core/ic_color_mask.{json,vsh,fsh}}。 */
    private static final String SHADER_NAME = "ic_color_mask";

    /** 渐变 LUT 宽度（端点对齐：t=0 → texel 0，t=1 → texel 1023）。 */
    private static final int LUT_SIZE = 1024;

    private static final int SHAPE_FULL = 0;
    private static final int SHAPE_RECT = 1;
    private static final int SHAPE_CIRCLE = 2;

    private static final int GRADIENT_NONE = 0;
    private static final int GRADIENT_LINEAR = 1;
    private static final int GRADIENT_RADIAL = 2;

    private static final int BLEND_NORMAL = 0;
    private static final int BLEND_MULTIPLY = 1;
    private static final int BLEND_SCREEN = 2;
    private static final int BLEND_SOFT_LIGHT = 3;
    private static final int BLEND_OVERLAY = 4;

    /** 共享 shader 必须提供的 uniform（缺任何一个 = 资源 / JSON 不一致，整层跳过并报错）。 */
    private static final String[] REQUIRED_UNIFORMS = {
            "ModelViewMat", "ProjMat",
            "MaskCenter", "MaskHalfSize", "MaskShape", "MaskRadius",
            "GradientType", "GradientDir", "GradientCenter", "GradientExtent",
            "Color", "Opacity", "BlendMode"
    };

    private final ColorMaskSpec spec;

    private int lutTexture;
    private int lutGeneration = -1;
    private boolean lutFailed;
    private int lutFailedGeneration = -1;
    private volatile boolean closed;

    // ===== 共享 GPU 资源（所有 ColorMaskRenderer 实例共用；最后一个实例关闭 / invalidate 时释放）=====

    private static ShaderInstance shader;
    private static Supplier<ShaderInstance> shaderSupplier;
    private static ResourceManager shaderResourceManager;
    private static boolean shaderFailed;
    private static int dummyTexture;

    private static int scratchTexture;
    private static int scratchFramebuffer;
    private static int scratchWidth;
    private static int scratchHeight;

    private static int rendererCount;
    /** 资源代次：invalidate 时 +1；每个 renderer 据此重建自己的 LUT。 */
    private static volatile int generation;

    private static ShaderInstance restoreShader;
    private static final Supplier<ShaderInstance> RESTORE_SHADER = () -> restoreShader;

    private static final BufferBuilder BUILDER = new BufferBuilder(256);
    private static final ByteBuffer LUT_BYTES = ByteBuffer.allocateDirect(LUT_SIZE * 4);
    private static final ByteBuffer DUMMY_BYTES = ByteBuffer.allocateDirect(4);
    private static final ByteBuffer MASK_BYTES = ByteBuffer.allocateDirect(4);
    private static final int[] VIEWPORT = new int[4];
    private static final int[] SCISSOR_BOX = new int[4];

    private static final Matrix4f SAVED_PROJECTION = new Matrix4f();
    private static final Matrix4f SAVED_MODELVIEW = new Matrix4f();
    private static final Matrix3f SAVED_NORMAL = new Matrix3f();
    private static final Matrix4f ORTHO = new Matrix4f();

    static {
        DUMMY_BYTES.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
        DUMMY_BYTES.flip();
    }

    public ColorMaskRenderer(ColorMaskSpec spec) {
        this.spec = spec;
        synchronized (ColorMaskRenderer.class) {
            rendererCount++;
        }
    }

    /**
     * 绘制一层高级颜色遮罩（调用方保证 spec / 几何已按当前帧关键帧填好）。
     *
     * @param color 纯色模式的 0xRRGGBB（渐变模式忽略；由 {@link ColorMaskSpec#sampleColor(float)} 采样）
     * @param opacity 有效不透明度（已乘退场系数），0~1
     * @param gradientAngle 线性渐变方向角（度；0 = 向右，90 = 向下）
     */
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight, ColorMaskSpec spec,
                       int color, float opacity,
                       float maskX, float maskY, float maskW, float maskH, float maskRadius,
                       float gradientAngle, float gradientCenterX, float gradientCenterY, float gradientRadius) {
        if (closed || guiGraphics == null || spec == null || screenWidth <= 0 || screenHeight <= 0) return;
        if (spec != this.spec) {
            reportSkip("传入的 ColorMaskSpec 与建层时不一致（layer=" + this.spec + "）");
            return;
        }
        if (!(opacity > 0f) || !Float.isFinite(opacity)) return;

        int shape = shapeCode(spec.maskShape());
        if (shape < 0) {
            reportSkip("未知 mask_shape '" + spec.maskShape() + "'");
            return;
        }
        int blend = blendCode(spec.blendMode());
        if (blend < 0) {
            reportSkip("未知 blend_mode '" + spec.blendMode() + "'");
            return;
        }

        float w = screenWidth;
        float h = screenHeight;
        float cx;
        float cy;
        float halfW;
        float halfH;
        float radiusPx = 0f;

        if (shape == SHAPE_CIRCLE) {
            if (!finiteNonNegative(maskRadius) || maskRadius <= 0f) return;   // 0 半径 = 不绘制
            radiusPx = maskRadius * Math.min(w, h);                          // 短边比例 → 真圆
            if (!(radiusPx > 0f)) return;
            cx = maskX * w;
            cy = maskY * h;
            if (!Float.isFinite(cx) || !Float.isFinite(cy)) return;
            halfW = radiusPx;
            halfH = radiusPx;
        } else if (shape == SHAPE_RECT) {
            if (!finiteNonNegative(maskW) || !finiteNonNegative(maskH) || maskW <= 0f || maskH <= 0f) return;
            cx = maskX * w;
            cy = maskY * h;
            if (!Float.isFinite(cx) || !Float.isFinite(cy)) return;
            halfW = maskW * w * 0.5f;
            halfH = maskH * h * 0.5f;
        } else {
            cx = w * 0.5f;
            cy = h * 0.5f;
            halfW = w * 0.5f;
            halfH = h * 0.5f;
        }

        // 绘制 quad = 遮罩包围盒 ∩ 窗口（区域外一个像素都不碰）
        float x0 = Math.max(0f, cx - halfW);
        float x1 = Math.min(w, cx + halfW);
        float y0 = Math.max(0f, cy - halfH);
        float y1 = Math.min(h, cy + halfH);
        if (!(x1 > x0) || !(y1 > y0)) return;

        int gradient = GRADIENT_NONE;
        float gradientCx = 0f;
        float gradientCy = 0f;
        float extent = 1f;
        float dirX = 1f;
        float dirY = 0f;
        if (spec.hasGradient()) {
            String gradientType = spec.gradientType();
            if ("radial".equals(gradientType)) {
                gradient = GRADIENT_RADIAL;
            } else if ("linear".equals(gradientType)) {
                gradient = GRADIENT_LINEAR;
            } else {
                reportSkip("未知 gradient type '" + gradientType + "'");
                return;
            }
            gradientCx = gradientCenterX * w;
            gradientCy = gradientCenterY * h;
            if (!Float.isFinite(gradientCx) || !Float.isFinite(gradientCy)) {
                reportSkip("gradient_center 非有限（" + gradientCenterX + ", " + gradientCenterY + "）");
                return;
            }
            if (gradient == GRADIENT_RADIAL) {
                if (!finiteNonNegative(gradientRadius) || gradientRadius <= 0f) {
                    reportSkip("radial gradient_radius 必须 > 0（当前 " + gradientRadius + "）");
                    return;
                }
                extent = gradientRadius * Math.min(w, h);
                if (!(extent > 0f)) return;
            } else {
                if (!Float.isFinite(gradientAngle)) {
                    reportSkip("gradient_angle 非有限（" + gradientAngle + "）");
                    return;
                }
                double radians = Math.toRadians(gradientAngle);
                dirX = (float) Math.cos(radians);
                dirY = (float) Math.sin(radians);
                extent = Math.abs(dirX) * w + Math.abs(dirY) * h;
                if (!(extent > 0f)) return;
            }
            if (!ensureLut()) return;   // LUT 失败已限频报错；整层跳过，不伪造效果
        }

        boolean needsBackdrop = blend != BLEND_NORMAL;

        // GuiGraphics 批次必须先落盘：后面要改 GL 状态 / 读主 framebuffer
        guiGraphics.flush();

        // ===== 保存外部 GL / RenderSystem 状态（所有改动都在 finally 还原）=====
        int prevReadFbo = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, SCISSOR_BOX);
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int prevSrcRgb = GlStateManager._getInteger(GL14.GL_BLEND_SRC_RGB);
        int prevDstRgb = GlStateManager._getInteger(GL14.GL_BLEND_DST_RGB);
        int prevSrcAlpha = GlStateManager._getInteger(GL14.GL_BLEND_SRC_ALPHA);
        int prevDstAlpha = GlStateManager._getInteger(GL14.GL_BLEND_DST_ALPHA);
        int prevEqRgb = GlStateManager._getInteger(GL20.GL_BLEND_EQUATION_RGB);
        int prevEqAlpha = GlStateManager._getInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean prevDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int prevDepthFunc = GlStateManager._getInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        MASK_BYTES.clear();
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, MASK_BYTES);
        boolean prevColorR = MASK_BYTES.get(0) != 0;
        boolean prevColorG = MASK_BYTES.get(1) != 0;
        boolean prevColorB = MASK_BYTES.get(2) != 0;
        boolean prevColorA = MASK_BYTES.get(3) != 0;

        int prevActiveTexture = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int prevTexture0 = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        int prevTexture1 = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._activeTexture(prevActiveTexture);

        ShaderInstance prevShader = RenderSystem.getShader();
        int prevShaderTexture0 = RenderSystem.getShaderTexture(0);
        int prevProgram = GlStateManager._getInteger(GL20.GL_CURRENT_PROGRAM);

        SAVED_PROJECTION.set(RenderSystem.getProjectionMatrix());
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        PoseStack modelView = RenderSystem.getModelViewStack();
        SAVED_MODELVIEW.set(modelView.last().pose());
        SAVED_NORMAL.set(modelView.last().normal());

        boolean building = false;
        try {
            if (!ensureShared()) return;

            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.getMainRenderTarget();
            if (main == null) {
                reportSkip("主 framebuffer 不存在");
                return;
            }
            main.bindWrite(true);   // 主 framebuffer + 全尺寸 viewport
            GlStateManager._viewport(0, 0, main.width, main.height);   // 与 scratch 像素一一对齐

            if (needsBackdrop) {
                if (!ensureScratch(main.width, main.height)) return;
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scratchFramebuffer);
                GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height,
                        0, 0, scratchWidth, scratchHeight,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                main.bindWrite(true);   // blit 后重新绑回主画面
                GlStateManager._viewport(0, 0, main.width, main.height);
            }

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.colorMask(true, true, true, false);   // 不写主 framebuffer alpha
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);

            ORTHO.setOrtho(0.0f, w, h, 0.0f, 1000.0f, 3000.0f);
            RenderSystem.setProjectionMatrix(ORTHO, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.setIdentity();
            modelView.translate(0.0f, 0.0f, -2000.0f);
            RenderSystem.applyModelViewMatrix();

            ShaderInstance activeShader = shader;
            set2(activeShader, "MaskCenter", cx, cy);
            set2(activeShader, "MaskHalfSize", halfW, halfH);
            set1(activeShader, "MaskShape", shape);
            set1(activeShader, "MaskRadius", radiusPx);
            set1(activeShader, "GradientType", gradient);
            set2(activeShader, "GradientDir", dirX, dirY);
            set2(activeShader, "GradientCenter", gradientCx, gradientCy);
            set1(activeShader, "GradientExtent", extent);
            set3(activeShader, "Color",
                    ((color >> 16) & 0xFF) / 255.0f,
                    ((color >> 8) & 0xFF) / 255.0f,
                    (color & 0xFF) / 255.0f);
            set1(activeShader, "Opacity", opacity);
            set1(activeShader, "BlendMode", blend);

            RenderSystem.setShader(shaderSupplier);
            RenderSystem.setShaderTexture(0, needsBackdrop ? scratchTexture : dummyTexture);
            activeShader.setSampler("GradientLut", gradient != GRADIENT_NONE ? lutTexture : dummyTexture);

            BufferBuilder builder = BUILDER;
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            building = true;
            builder.vertex(x0, y1, 0.0).uv(0.0f, 0.0f).endVertex();   // 左下
            builder.vertex(x1, y1, 0.0).uv(0.0f, 0.0f).endVertex();   // 右下
            builder.vertex(x1, y0, 0.0).uv(0.0f, 0.0f).endVertex();   // 右上
            builder.vertex(x0, y0, 0.0).uv(0.0f, 0.0f).endVertex();   // 左上
            BufferBuilder.RenderedBuffer rendered = builder.end();
            building = false;
            BufferUploader.drawWithShader(rendered);
        } finally {
            if (building) BUILDER.discard();

            RenderSystem.setProjectionMatrix(SAVED_PROJECTION, prevSorting);

            // 先把 shader 指针还原，再 applyModelViewMatrix——确保恢复的是 prevShader 的
            // ModelViewMat uniform，而不是把临时矩阵留在它身上。
            restoreShader = prevShader;
            RenderSystem.setShader(RESTORE_SHADER);
            GlStateManager._glUseProgram(prevProgram);
            RenderSystem.setShaderTexture(0, prevShaderTexture0);

            modelView.last().pose().set(SAVED_MODELVIEW);
            modelView.last().normal().set(SAVED_NORMAL);
            RenderSystem.applyModelViewMatrix();

            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(prevTexture0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE1);
            GlStateManager._bindTexture(prevTexture1);
            GlStateManager._activeTexture(prevActiveTexture);

            if (prevBlend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            // 先绕过 GlStateManager 缓存强制恢复实际 GL 因子，再调一次缓存 API 同步缓存
            // （shader 的 BlendMode.apply 用 glBlendFunc 只更新 RGB 缓存，alpha 缓存可能滞后）
            GL14.glBlendFuncSeparate(prevSrcRgb, prevDstRgb, prevSrcAlpha, prevDstAlpha);
            GlStateManager._blendFuncSeparate(prevSrcRgb, prevDstRgb, prevSrcAlpha, prevDstAlpha);
            GL20.glBlendEquationSeparate(prevEqRgb, prevEqAlpha);

            if (prevDepth) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            GlStateManager._depthFunc(prevDepthFunc);
            GlStateManager._depthMask(prevDepthMask);
            RenderSystem.colorMask(prevColorR, prevColorG, prevColorB, prevColorA);

            if (prevScissor) {
                GlStateManager._enableScissorTest();
            } else {
                GlStateManager._disableScissorTest();
            }
            GlStateManager._scissorBox(SCISSOR_BOX[0], SCISSOR_BOX[1], SCISSOR_BOX[2], SCISSOR_BOX[3]);

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        }
    }

    // ===== 生命周期 =====

    /** 释放本层持有的 LUT；最后一个 renderer 关闭时一并释放共享 shader / scratch。幂等。 */
    public void close() {
        if (closed) return;
        closed = true;
        if (RenderSystem.isOnRenderThread()) {
            closeOnRenderThread();
        } else {
            RenderSystem.recordRenderCall(this::closeOnRenderThread);
        }
    }

    private void closeOnRenderThread() {
        releaseLut();
        synchronized (ColorMaskRenderer.class) {
            if (rendererCount > 0) rendererCount--;
            if (rendererCount == 0) releaseShared();
        }
    }

    private void releaseLut() {
        if (lutTexture != 0) {
            GlStateManager._deleteTexture(lutTexture);
            lutTexture = 0;
        }
        lutGeneration = -1;
        lutFailed = false;
        lutFailedGeneration = -1;
    }

    /**
     * 资源重载（F3+T / 资源包切换）入口：递增代次并释放共享 GL 资源。
     * <p>reload listener 可能不在渲染线程——跨线程时排队到渲染线程，绝不当场调用 GL。
     * 存活 renderer 在下一帧按新代次重建 LUT；shader / dummy / scratch 按需重建。</p>
     */
    public static void invalidate() {
        generation++;
        if (RenderSystem.isOnRenderThread()) {
            releaseShared();
        } else {
            RenderSystem.recordRenderCall(ColorMaskRenderer::releaseShared);
        }
    }

    private static void releaseShared() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(ColorMaskRenderer::releaseShared);
            return;
        }
        if (shader != null) {
            if (RenderSystem.getShader() == shader) {
                restoreShader = null;
                RenderSystem.setShader(RESTORE_SHADER);
            }
            shader.close();
            shader = null;
        }
        shaderSupplier = null;
        shaderResourceManager = null;
        shaderFailed = false;
        if (dummyTexture != 0) {
            GlStateManager._deleteTexture(dummyTexture);
            dummyTexture = 0;
        }
        destroyScratch();
    }

    /** 惰性加载共享 shader / dummy（渲染线程）。失败只记一次并按资源代次重试。 */
    private static boolean ensureShared() {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        if (shader != null && shaderResourceManager != resources) {
            // 兜底：reload hook 漏接时也能发现资源管理器已更换
            releaseShared();
        }
        if (shader == null) {
            if (shaderFailed) return false;
            try {
                ShaderInstance instance = new ShaderInstance(resources,
                        SHADER_NAME, DefaultVertexFormat.POSITION_TEX);
                for (String name : REQUIRED_UNIFORMS) {
                    if (instance.getUniform(name) == null) {
                        instance.close();
                        shaderFailed = true;
                        ErrorLog.logRateLimited("Render", "color-mask-shader", 5000L,
                                "颜色遮罩着色器缺少 uniform '" + name
                                        + "'（检查 assets/minecraft/shaders/core/" + SHADER_NAME + ".*）→ 跳过该层");
                        return false;
                    }
                }
                shader = instance;
                shaderSupplier = () -> instance;
                shaderResourceManager = resources;
            } catch (IOException | RuntimeException e) {
                shaderFailed = true;
                ErrorLog.logRateLimited("Render", "color-mask-shader", 5000L,
                        "颜色遮罩着色器加载失败（" + SHADER_NAME + "）→ 跳过该层，不输出伪造 fallback", e);
                return false;
            }
        }
        if (dummyTexture == 0 && !ensureDummy()) return false;
        return true;
    }

    private static boolean ensureDummy() {
        int prevActive = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int prevBinding = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = 0;
        try {
            texture = GlStateManager._genTexture();
            if (texture == 0) throw new IllegalStateException("glGenTextures 返回 0");
            GlStateManager._bindTexture(texture);
            DUMMY_BYTES.position(0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, DUMMY_BYTES);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            dummyTexture = texture;
            return true;
        } catch (RuntimeException e) {
            if (texture != 0) GlStateManager._deleteTexture(texture);
            ErrorLog.logRateLimited("Render", "color-mask-dummy", 5000L,
                    "颜色遮罩 dummy 纹理创建失败 → 跳过该层", e);
            return false;
        } finally {
            GlStateManager._bindTexture(prevBinding);
            GlStateManager._activeTexture(prevActive);
        }
    }

    // ===== 渐变 LUT（每个 fade 只建一次；无渐变不建）=====

    private boolean ensureLut() {
        if (lutFailed && lutFailedGeneration == generation) return false;
        if (!spec.hasGradient()) return true;
        if (lutTexture != 0 && lutGeneration == generation) return true;

        releaseLut();
        float[] offsets = spec.stopOffsets();
        int[] colors = spec.stopColors();
        if (offsets.length < 2 || offsets.length != colors.length) {
            lutFailed = true;
            lutFailedGeneration = generation;
            reportSkip("渐变 stops 非法（offsets=" + offsets.length + ", colors=" + colors.length + "）");
            return false;
        }
        float previous = -1.0f;
        for (int i = 0; i < offsets.length; i++) {
            float offset = offsets[i];
            if (!Float.isFinite(offset) || offset < 0.0f || offset > 1.0f || offset <= previous) {
                lutFailed = true;
                lutFailedGeneration = generation;
                reportSkip("渐变 stop offset 非法（index=" + i + ", offset=" + offset + "）");
                return false;
            }
            previous = offset;
        }

        buildLut(offsets, colors);
        return lutTexture != 0;
    }

    private void buildLut(float[] offsets, int[] colors) {
        LUT_BYTES.clear();
        for (int i = 0; i < LUT_SIZE; i++) {
            float t = i / (float) (LUT_SIZE - 1);   // 端点对齐：首 texel = 0、末 texel = 1
            int rgb = sampleStops(offsets, colors, t);
            LUT_BYTES.put((byte) ((rgb >> 16) & 0xFF));
            LUT_BYTES.put((byte) ((rgb >> 8) & 0xFF));
            LUT_BYTES.put((byte) (rgb & 0xFF));
            LUT_BYTES.put((byte) 0xFF);
        }
        LUT_BYTES.flip();

        int prevActive = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int prevBinding = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = 0;
        try {
            texture = GlStateManager._genTexture();
            if (texture == 0) throw new IllegalStateException("glGenTextures 返回 0");
            GlStateManager._bindTexture(texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, LUT_SIZE, 1, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, LUT_BYTES);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            lutTexture = texture;
            lutGeneration = generation;
            lutFailed = false;
            lutFailedGeneration = -1;
        } catch (RuntimeException e) {
            if (texture != 0) GlStateManager._deleteTexture(texture);
            lutFailed = true;
            lutFailedGeneration = generation;
            reportSkip("渐变 LUT 上传失败: " + e.getMessage());
        } finally {
            GlStateManager._bindTexture(prevBinding);
            GlStateManager._activeTexture(prevActive);
        }
    }

    /** 段内逐 RGB 线性插值；范围外保持端点（stops 已校验严格递增）。 */
    private static int sampleStops(float[] offsets, int[] colors, float t) {
        int last = offsets.length - 1;
        if (t <= offsets[0]) return colors[0];
        if (t >= offsets[last]) return colors[last];

        int lo = 0;
        int hi = last;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (offsets[mid] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        float span = offsets[hi] - offsets[lo];
        float f = span > 0.0f ? (t - offsets[lo]) / span : 0.0f;
        int c0 = colors[lo];
        int c1 = colors[hi];
        int r = Math.round(((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) - ((c0 >> 16) & 0xFF)) * f);
        int g = Math.round(((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) - ((c0 >> 8) & 0xFF)) * f);
        int b = Math.round((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * f);
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (r << 16) | (g << 8) | b;
    }

    // ===== 共享 backdrop scratch（仅非 normal；窗口尺寸变化时重建）=====

    private static boolean ensureScratch(int width, int height) {
        if (scratchFramebuffer != 0 && scratchWidth == width && scratchHeight == height) return true;
        destroyScratch();
        if (width <= 0 || height <= 0) return false;

        int prevActive = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int prevBinding = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevReadFbo = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int texture = 0;
        int framebuffer = 0;
        boolean complete = false;
        try {
            texture = GlStateManager._genTexture();
            if (texture == 0) throw new IllegalStateException("glGenTextures 返回 0");
            GlStateManager._bindTexture(texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            framebuffer = GlStateManager.glGenFramebuffers();
            if (framebuffer == 0) throw new IllegalStateException("glGenFramebuffers 返回 0");
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            complete = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                    == GL30.GL_FRAMEBUFFER_COMPLETE;
            if (complete) {
                scratchTexture = texture;
                scratchFramebuffer = framebuffer;
                scratchWidth = width;
                scratchHeight = height;
                return true;
            }
            reportSkip("backdrop scratch framebuffer 不完整（" + width + "×" + height + "）");
            return false;
        } catch (RuntimeException e) {
            reportSkip("backdrop scratch 创建失败: " + e.getMessage());
            return false;
        } finally {
            if (!complete) {
                if (framebuffer != 0) GlStateManager._glDeleteFramebuffers(framebuffer);
                if (texture != 0) GlStateManager._deleteTexture(texture);
            }
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GlStateManager._bindTexture(prevBinding);
            GlStateManager._activeTexture(prevActive);
        }
    }

    private static void destroyScratch() {
        if (scratchFramebuffer != 0) {
            GlStateManager._glDeleteFramebuffers(scratchFramebuffer);
            scratchFramebuffer = 0;
        }
        if (scratchTexture != 0) {
            GlStateManager._deleteTexture(scratchTexture);
            scratchTexture = 0;
        }
        scratchWidth = 0;
        scratchHeight = 0;
    }

    // ===== 小工具 =====

    private static int shapeCode(String shape) {
        if (shape == null) return -1;
        switch (shape) {
            case "full":
                return SHAPE_FULL;
            case "rect":
                return SHAPE_RECT;
            case "circle":
                return SHAPE_CIRCLE;
            default:
                return -1;
        }
    }

    private static int blendCode(String mode) {
        if (mode == null) return -1;
        switch (mode) {
            case "normal":
                return BLEND_NORMAL;
            case "multiply":
                return BLEND_MULTIPLY;
            case "screen":
                return BLEND_SCREEN;
            case "soft_light":
                return BLEND_SOFT_LIGHT;
            case "overlay":
                return BLEND_OVERLAY;
            default:
                return -1;
        }
    }

    private static boolean finiteNonNegative(float value) {
        return Float.isFinite(value) && value >= 0.0f;
    }

    private static void reportSkip(String message) {
        ErrorLog.logRateLimited("Render", "color-mask-layer", 5000L,
                "颜色遮罩层跳过（该层不绘制，主画面保持原样）：" + message);
    }

    private static void set1(ShaderInstance shader, String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void set2(ShaderInstance shader, String name, float x, float y) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(x, y);
    }

    private static void set3(ShaderInstance shader, String name, float x, float y, float z) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(x, y, z);
    }
}
