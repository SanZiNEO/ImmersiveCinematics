package com.immersivecinematics.immersive_cinematics.client.post;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneCompositor;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
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
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;

import java.io.IOException;

/**
 * master 画面颜色调整的渲染 pass（架构图 RADJ 节点）：把 {@link MasterColorAdjust} 里的标量组
 * 一次全屏 pass 应用到<b>合成输出</b>上。
 *
 * <h2>挂点</h2>
 * {@code GameRenderer.renderLevel} 返回之后、lane 合成（MCOMP，{@code LaneRenderer.render}）之后，
 * 原版后处理链（RPOST）与 GUI 之前 —— 即 {@code mixin/LaneRendererMixin} 的同一次注入里、
 * {@code LaneRenderer.render} 的下一行（顺序必须在同一次注入里保证：调色要作用在 lane 合成结果上）。
 * 后果（有意）：F2 截图带上调色（截图在原版那一步取）；GUI（字幕 / 黑边 / 跳过提示）不受调色影响。
 *
 * <h2>两个 pass</h2>
 * 不能同时读写主画面纹理，所以与「一个效果 = 效果 pass + blit 回 main」的原版链同构：
 * <ol>
 *   <li>主画面 → 中转缓冲（{@code ic_color_adjust}，全屏 quad，应用全部标量调整）；</li>
 *   <li>中转缓冲 → 主画面（整屏 blit，复用合成层的 {@link LaneCompositor#compose}）。</li>
 * </ol>
 * 成本 = 两次全屏 quad，与画面内容无关。
 *
 * <h2>着色器资产（自建，非复用原版）</h2>
 * {@code assets/minecraft/shaders/core/ic_color_adjust.{json,vsh,fsh}}：程序 JSON 与 GLSL 走
 * <b>{@code minecraft} 命名空间</b>——原版 {@link ShaderInstance} 的构造只认
 * {@code shaders/core/<name>.json}（默认命名空间），这是沿用原版机制的前提
 * （与 {@code EffectInstance} 对 {@code shaders/program/<name>.json} 的约束同源）。
 * 顶点格式 = {@code DefaultVertexFormat.POSITION_TEX}，与合成层 / 原版 blit 同一套。
 *
 * <h2>默认零差异</h2>
 * 无调整时 {@link #render} 第一行返回：不取着色器、不建中转缓冲、不切任何 GL 状态、不画任何东西。
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

    private ColorAdjustPass() {
    }

    /**
     * 在合成输出上应用本帧的颜色调整。
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
        ShaderInstance shaderInstance = shader(mc);
        if (shaderInstance == null) {
            return;   // 着色器不可用：宁可不调色，也不动画面
        }

        RenderTarget swap = swapTarget(width, height);
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

            // pass 1：主画面 → 中转缓冲（全屏 quad 铺满，无需 clear）
            swap.bindWrite(true);   // 绑 FBO + 视口 = FBO 全尺寸
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
                RenderSystem.setShaderTexture(0, main.getColorTextureId());
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
            RenderSystem.setProjectionMatrix(prevProjection, prevSorting);

            // pass 2：中转缓冲 → 主画面（整屏 blit，复用合成层的状态保存 / 还原与 UV 口径）
            LaneCompositor.compose(swap, LaneCompositor.Rect.FULL, LaneCompositor.Rect.FULL, 1.0F);
        } finally {
            RenderSystem.setShaderColor(prevColor[0], prevColor[1], prevColor[2], prevColor[3]);
            RenderSystem.setShaderTexture(0, prevTexture);
            RenderSystem.setProjectionMatrix(prevProjection, prevSorting);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.colorMask(true, true, true, true);
            main.bindWrite(true);   // 收尾把主画面留在绑定态（与合成层一致）
        }
    }

    /**
     * 参数 → uniform（逐帧覆盖；着色器 JSON 里已声明全部 15 个通道，缺省 0 = 无效果）。
     * <p>顺序与 {@link ColorAdjustParams} 的分量顺序、shader 的操作栈顺序一致。</p>
     */
    private static void upload(ShaderInstance shader, ColorAdjustParams p) {
        set(shader, "Exposure", p.exposure());
        set(shader, "Contrast", p.contrast());
        set(shader, "Highlights", p.highlights());
        set(shader, "Shadows", p.shadows());
        set(shader, "Whites", p.whites());
        set(shader, "Blacks", p.blacks());
        set(shader, "Saturation", p.saturation());
        set(shader, "Vibrance", p.vibrance());
        set(shader, "Temperature", p.temperature());
        set(shader, "Tint", p.tint());
        set(shader, "Red", p.red());
        set(shader, "Green", p.green());
        set(shader, "Blue", p.blue());
        set(shader, "Grayscale", p.grayscale());
        set(shader, "Invert", p.invert());
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
