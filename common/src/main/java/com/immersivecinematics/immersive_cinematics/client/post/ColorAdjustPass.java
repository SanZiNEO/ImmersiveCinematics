package com.immersivecinematics.immersive_cinematics.client.post;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneCompositor;
import com.immersivecinematics.immersive_cinematics.script.ColorCurve;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
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

import java.io.IOException;

/**
 * 画面颜色调整的渲染 pass：把 {@link ColorAdjustParams} 的标量组一次全屏 pass 应用到画面纹理上。
 * 两条使用路径共用本类（同一份着色器 + 同一份 uniform 上传 + 同一个 {@link #applyTo}）：
 * <ul>
 *   <li><b>master</b>（{@link MasterColorAdjust} 发布）：作用于<b>合成输出</b>（架构图 RADJ 节点）——
 *       {@link #render} 在 lane 合成（MCOMP）之后、原版后处理链（RPOST）与 GUI 之前调一次。</li>
 *   <li><b>lane 级</b>（来自该 lane 的相机片段）：作用于<b>该 lane 的画面</b>，在 lane 渲染完成
 *       之后、合成之前（{@code client.lane.LaneRenderer#renderLane} 调 {@link #applyTo}，
 *       写进共享 adjustTarget 再交给合成层）。</li>
 * </ul>
 *
 * <h2>挂点</h2>
 * master：{@code GameRenderer.renderLevel} 返回之后、lane 合成（MCOMP，{@code LaneRenderer.render}）之后，
 * 原版后处理链（RPOST）与 GUI 之前 —— 即 {@code mixin/LaneRendererMixin} 的同一次注入里、
 * {@code LaneRenderer.render} 的下一行（顺序必须在同一次注入里保证：调色要作用在 lane 合成结果上）。
 * 后果（有意）：F2 截图带上调色（截图在原版那一步取）；GUI（字幕 / 黑边 / 跳过提示）不受调色影响。
 * <p>lane 级：{@code LaneRenderer.renderLane} 内，lane 的 {@code renderLevel} + {@code doEntityOutline}
 * 之后、{@link LaneCompositor#compose} 之前。</p>
 *
 * <h2>两个 pass（master 路径）</h2>
 * 不能同时读写主画面纹理，所以与「一个效果 = 效果 pass + blit 回 main」的原版链同构：
 * <ol>
 *   <li>主画面 → 中转缓冲（{@code ic_color_adjust}，全屏 quad，应用全部标量调整）；</li>
 *   <li>中转缓冲 → 主画面（整屏 blit，复用合成层的 {@link LaneCompositor#compose}）。</li>
 * </ol>
 * 成本 = 两次全屏 quad，与画面内容无关。lane 路径只有一次 pass（lane FBO → adjustTarget），
 * 随后的合成由 {@code LaneRenderer} 照常走 {@link LaneCompositor#compose}。
 *
 * <h2>alpha 契约（两条路径一致）</h2>
 * 调色只动 RGB：着色器 {@code fragColor.a = src.a} 逐位直通（见 {@code ic_color_adjust.fsh}），
 * 透明度只在合成层由 {@code opacity}（{@code ColorModulator.a}）调控。
 *
 * <h2>着色器资产（自建，非复用原版）</h2>
 * {@code assets/minecraft/shaders/core/ic_color_adjust.{json,vsh,fsh}}：程序 JSON 与 GLSL 走
 * <b>{@code minecraft} 命名空间</b>——原版 {@link ShaderInstance} 的构造只认
 * {@code shaders/core/<name>.json}（默认命名空间），这是沿用原版机制的前提
 * （与 {@code EffectInstance} 对 {@code shaders/program/<name>.json} 的约束同源）。
 * 顶点格式 = {@code DefaultVertexFormat.POSITION_TEX}，与合成层 / 原版 blit 同一套。
 * <p>十一个采样器：{@code Sampler0} = 画面（第一纹理单元）、{@code CurveLut} = RGB 复合曲线的 256×1 LUT、
 * {@code RCurveLut} / {@code GCurveLut} / {@code BCurveLut} = 每通道曲线的 256×1 LUT、
 * {@code HvHLut} / {@code HvSLut} / {@code HvLLut} / {@code LvSLut} / {@code SvSLut} / {@code SvLLut}
 * = 六条 hue 曲线的 256×1 LUT（第二 ~ 第十一纹理单元，见 {@link LutTexture}）。</p>
 *
 * <h2>默认零差异</h2>
 * 无调整时 {@link #render} 第一行返回；{@link #applyTo} 参数为空 / 恒等时第一行返回：
 * 不取着色器、不建中转缓冲、不切任何 GL 状态、不画任何东西。
 * 着色器是首次真正需要时才编译的（不用不编译），资源重载后重建。
 */
public final class ColorAdjustPass {

    /** 着色器名：{@code assets/minecraft/shaders/core/ic_color_adjust.{json,vsh,fsh}} */
    private static final String SHADER_NAME = "ic_color_adjust";

    /** 当前着色器实例；{@code null} = 尚未加载或该资源周期内加载失败。 */
    private static ShaderInstance shader;

    /** {@link #shader} 的来源资源管理器：换对象 = 资源重载 → 重建（旧实例连同 GL program 一起释放）。 */
    private static ResourceManager shaderSource;

    /** 中转缓冲（按主画面尺寸创建 / 重建；只在渲染线程访问）。 */
    private static RenderTarget swapTarget;

    /** RGB 复合曲线的 LUT 纹理槽（{@code CurveLut} 采样器）。 */
    private static final LutTexture CURVE_LUT = new LutTexture();

    /** R / G / B 每通道曲线的 LUT 纹理槽（{@code RCurveLut} / {@code GCurveLut} / {@code BCurveLut} 采样器）。 */
    private static final LutTexture R_CURVE_LUT = new LutTexture();
    private static final LutTexture G_CURVE_LUT = new LutTexture();
    private static final LutTexture B_CURVE_LUT = new LutTexture();

    /**
     * 六条 hue 曲线的 LUT 纹理槽（{@code HvHLut} / {@code HvSLut} / {@code HvLLut} /
     * {@code LvSLut} / {@code SvSLut} / {@code SvLLut} 采样器，顺序同上表）。
     */
    private static final LutTexture HV_H_LUT = new LutTexture();
    private static final LutTexture HV_S_LUT = new LutTexture();
    private static final LutTexture HV_L_LUT = new LutTexture();
    private static final LutTexture LV_S_LUT = new LutTexture();
    private static final LutTexture SV_S_LUT = new LutTexture();
    private static final LutTexture SV_L_LUT = new LutTexture();

    private ColorAdjustPass() {
    }

    /**
     * 在<b>合成输出</b>上应用本帧的 master 颜色调整（架构图 RADJ 节点）。
     *
     * <p>无调整（本帧没人发布 / 参数恒等）时不做任何事。</p>
     */
    public static void render(Minecraft mc) {
        ColorAdjustParams params = MasterColorAdjust.INSTANCE.consume();
        if (params == null) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        int width = main.width;
        int height = main.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        RenderTarget swap = swapTarget(width, height);
        // pass 1：主画面 → 中转缓冲（全屏 quad 铺满，无需 clear）
        if (!applyTo(main, swap, params, mc)) {
            return;   // 着色器不可用：宁可不调色，也不动画面
        }
        // pass 2：中转缓冲 → 主画面（整屏 blit，复用合成层的状态保存 / 还原与 UV 口径）
        LaneCompositor.compose(swap, LaneCompositor.Rect.FULL, LaneCompositor.Rect.FULL, 1.0F);
    }

    /**
     * 一次调色 pass：把 {@code src} 的颜色纹理经调色着色器写进 {@code dst}（全屏 quad，1:1）。
     *
     * <p><b>master 与 lane 级共用这一份实现</b>（着色器获取 + uniform 上传 + pass 执行都不分叉）：
     * master 路径是「主画面 → 中转缓冲」（{@link #render}），lane 路径是「lane FBO → adjustTarget」
     * （{@code client.lane.LaneRenderer#renderLane}）——两条路径的唯一差别是源 / 目标缓冲。</p>
     *
     * <p><b>alpha 契约</b>：只动 RGB，{@code fragColor.a = src.a} 逐位直通；透明度由合成层的
     * {@code opacity}（{@code ColorModulator.a}）单独调控，调色不承担任何透明度语义。</p>
     *
     * <p>状态保存 / 还原：进入时保存、退出时还原全局投影 + VertexSorting、shader 颜色与 0 号纹理、
     * 深度测试 / 深度写 / 颜色写 / 混合；退出时目标缓冲保持绑定态（调用方接着画）。</p>
     *
     * @param src    源画面（取颜色纹理作采样源；不得与 {@code dst} 同一张纹理）
     * @param dst    目标缓冲（视口会被设成全尺寸）
     * @param params 调色参数（{@code null} 或恒等 → 直接返回 {@code false}，不动任何 GL 状态）
     * @param mc     客户端实例（取资源管理器加载 / 重建着色器）
     * @return 是否真的跑了 pass（{@code false} = 参数为空/恒等、尺寸非法或着色器不可用）
     */
    public static boolean applyTo(RenderTarget src, RenderTarget dst, ColorAdjustParams params, Minecraft mc) {
        if (src == null || dst == null || params == null || params.isIdentity()) {
            return false;
        }
        int width = dst.width;
        int height = dst.height;
        if (width <= 0 || height <= 0) {
            return false;
        }
        ShaderInstance shaderInstance = shader(mc);
        if (shaderInstance == null) {
            return false;   // 着色器不可用：宁可不调色，也不动画面
        }

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

        upload(shaderInstance, params);

        Matrix4f prevProjection = RenderSystem.getProjectionMatrix();
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        int prevTexture = RenderSystem.getShaderTexture(0);
        float[] prevColor = RenderSystem.getShaderColor().clone();

        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.colorMask(true, true, true, true);

            // 全屏 quad 铺满目标缓冲，无需 clear
            dst.bindWrite(true);   // 绑 FBO + 视口 = FBO 全尺寸
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
        } finally {
            RenderSystem.setShaderColor(prevColor[0], prevColor[1], prevColor[2], prevColor[3]);
            RenderSystem.setShaderTexture(0, prevTexture);
            RenderSystem.setProjectionMatrix(prevProjection, prevSorting);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.colorMask(true, true, true, true);
        }
        return true;
    }

    /**
     * 参数 → uniform（逐帧覆盖；着色器 JSON 里已声明全部通道，缺省 0 = 无效果）。
     * <p>顺序与 {@link ColorAdjustParams} 的分量顺序、shader 的操作栈顺序一致
     * （曲线 LUT 走采样器 {@code CurveLut}，在 {@link #applyTo} 里绑定）。</p>
     */
    private static void upload(ShaderInstance shader, ColorAdjustParams p) {
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
        set(shader, "HvHStrength", p.hvHLut() == null ? 0.0F : p.hvHStrength());
        set(shader, "HvSStrength", p.hvSLut() == null ? 0.0F : p.hvSStrength());
        set(shader, "HvLStrength", p.hvLLut() == null ? 0.0F : p.hvLStrength());
        set(shader, "LvSStrength", p.lvSLut() == null ? 0.0F : p.lvSStrength());
        set(shader, "SvSStrength", p.svSLut() == null ? 0.0F : p.svSStrength());
        set(shader, "SvLStrength", p.svLLut() == null ? 0.0F : p.svLStrength());
        set(shader, "Grayscale", p.grayscale());
        set(shader, "Invert", p.invert());
    }

    /** 某一步实际要绑的 LUT：有曲线且强度非 0 → 曲线自己的 LUT，否则恒等 LUT（{@code y = x}）。 */
    private static float[] activeLut(float[] lut, float strength) {
        return lut != null && strength != 0.0F ? lut : ColorCurve.identityLut();
    }

    /**
     * 一个曲线 LUT 纹理槽：256×1、LINEAR 过滤（LUT 相邻项之间线性插值，等价于曲线上的细分采样）。
     *
     * <p>纹理只建一次；数组按<b>引用</b>比较——LUT 随 clip 缓存（{@code ColorCurve} 对象内持有），
     * 同一片段每帧拿到的是同一个数组，所以每换一次曲线才重传一次像素。</p>
     *
     * <p>绑定走 {@link ShaderInstance#setSampler}（着色器 JSON 的 {@code samplers} 次序 = 纹理单元：
     * {@code Sampler0} / {@code CurveLut} / {@code RCurveLut} / {@code GCurveLut} / {@code BCurveLut} /
     * {@code HvHLut} / {@code HvSLut} / {@code HvLLut} / {@code LvSLut} / {@code SvSLut} / {@code SvLLut}），
     * 由 {@code ShaderInstance.apply()} 在绘制前落实——不占 {@code RenderSystem} 的
     * {@code shaderTextures} 槽位（那是 {@code Sampler0..Sampler11} 与叠加色用的），
     * 因此没有额外的全局状态需要保存 / 还原。</p>
     *
     * <p>无曲线 / 强度为 0 时承载恒等 LUT——<b>采样器常绑</b>，不会出现未绑定的采样器
     * （着色器里强度 &gt; 0 才走该分支，恒等 LUT 不改变画面）。</p>
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

    /** 写一个 float uniform；着色器里没有该 uniform 时跳过（{@code getUniform} 返回 {@code null}）。 */
    private static void set(ShaderInstance shader, String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    /**
     * 取（必要时创建）着色器实例。资源重载（换资源包 / F3+T）后重建：
     * 旧实例 {@code close()} 会释放它持有的 GL program（原版 Program 缓存按名字复用，
     * 不释放就会拿到旧编译结果），随后按新资源重新编译。
     * <p>加载失败只记一次（同一资源周期内不重试、不刷屏），画面保持未调色。</p>
     */
    private static ShaderInstance shader(Minecraft mc) {
        ResourceManager resources = mc.getResourceManager();
        if (shaderSource != resources) {
            if (shader != null) {
                shader.close();
                shader = null;
            }
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
     * 中转缓冲（按主画面尺寸创建 / 重建）。
     * <p>不需要深度附件（只画一个全屏 quad、且深度测试关闭）；过滤保持默认 NEAREST——
     * 采样是 1:1 的，NEAREST 正是要的（合成层要缩放才需要 LINEAR）。</p>
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
