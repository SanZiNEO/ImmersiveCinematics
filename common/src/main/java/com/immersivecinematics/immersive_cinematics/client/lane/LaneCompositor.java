package com.immersivecinematics.immersive_cinematics.client.lane;

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
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;

import java.io.IOException;

/**
 * 合成层：把一条 lane 的画面纹理按合成参数铺到屏幕（主 framebuffer），全部参数在同一次 quad 绘制内完成。
 *
 * 参数：{@code source} = 画面内取材区域（默认全幅，UV 子区域）；{@code dest} = 屏幕目标区域（默认全屏，quad 顶点）。
 * {@code opacity} = 叠放不透明度 0~1（默认 1，落混合 alpha）；{@code mask} = 遮罩 full / rect / circle（默认 full）。
 * {@code order} = 叠放顺序：合成即时进行，所有 lane 共用一张离屏缓冲、不能攒批排序，后调用者盖在先调用者之上。
 * {@code source} / {@code dest} 都是 0~1 归一化矩形，原点在左上角（x 向右、y 向下）；纹理 v 轴在内部翻转。
 *
 * alpha 契约：专用着色器 {@code ic_lane_blit} 只读 lane 画面的 RGB，写出 alpha = opacity × coverage
 * （定义点见其 fsh）；与 lane 纹理里的 a 无关——lane alpha 不干净会透底，不能沿用原版 {@code position_tex}。
 *
 * 遮罩按窗口物理像素判定覆盖率（硬边 0/1）：rect = 中心 / 半宽半高；circle 半径按短边缩放，非正方形窗口仍是真圆。
 * 零尺寸（rect 宽高 / circle 半径 = 0）= 不绘制（显露起点，静默跳过）；中心允许越界；mask 为 null 或 full = 全窗口。
 * 遮罩只改覆盖率：不读 lane 纹理 alpha，不改 source / dest 映射。
 *
 * 失败直通：画之前先过 {@link LaneComposeGate}，任一条不过 = 整个跳过这次绘制，主画面保持原样。
 * 非 full 而专用着色器不可用 → 报错跳过，绝不退回无遮罩的 {@code position_tex}；full 的兜底才退回它（代价是透底）。
 * 状态：入口保存、出口还原绑定 / 视口 / 投影 / 模型视图 / shader 颜色与纹理 / 深度 / 颜色写 / 混合；遮罩 uniform 每次全量覆盖。
 * 默认零差异：没有 lane 就没有调用。
 */
public final class LaneCompositor {

    /** 合成 blit 着色器名：{@code assets/minecraft/shaders/core/ic_lane_blit.{json,vsh,fsh}}。 */
    private static final String SHADER_NAME = "ic_lane_blit";

    /** 当前 blit 着色器实例；{@code null} = 尚未加载或该资源周期内加载失败（退回原版 {@code position_tex}）。 */
    private static ShaderInstance blitShader;

    /** {@link #blitShader} 的来源资源管理器：换对象 = 资源重载 → 重建（旧实例连同 GL program 一起释放）。 */
    private static ResourceManager blitShaderSource;

    private LaneCompositor() {
    }

    /**
     * 归一化矩形（各分量 0~1），原点在左上角：{@code source} 相对 lane 画面，{@code dest} 相对屏幕。
     */
    public record Rect(float x, float y, float w, float h) {

        /** 全幅（source）/ 全屏（dest）。 */
        public static final Rect FULL = new Rect(0.0F, 0.0F, 1.0F, 1.0F);
    }

    /**
     * 把 {@code texture}（一条 lane 的离屏画面）按合成参数铺到屏幕；必须当帧调用——缓冲随即被下一条 lane 覆盖。
     * {@code written = false}（渲染失败 / 未写）的画面不可用，由守门跳过。
     * 失败直通：画之前先过 {@link LaneComposeGate} 与遮罩几何判定，任一条不过 = 整个跳过，主画面保持原样；
     * {@code opacity ≤ 0} 与遮罩零尺寸是正常语义（叠化到 0 / 显露起点），静默跳过，其余失败限频告警。
     *
     * @return 是否真的把画面铺上了屏（{@code false} = 被守门跳过，主画面保持原样）
     */
    public static boolean compose(RenderTarget texture, Rect source, Rect dest, float opacity, boolean written,
                                  LaneMask mask) {
        // 守门：画面层（未写 / 无颜色纹理 / 不可见）+ 矩形层（非有限 / 退化）——判定见 LaneComposeGate
        if (texture == null || source == null || dest == null) {
            reportSkipped("画面 / 矩形为空（texture=" + texture + "）");
            return false;
        }
        LaneComposeGate.Skip skip = LaneComposeGate.checkBuffer(written, texture.getColorTextureId(), opacity);
        if (skip == LaneComposeGate.Skip.NONE) {
            skip = LaneComposeGate.checkRect(source.x(), source.y(), source.w(), source.h());
        }
        if (skip == LaneComposeGate.Skip.NONE) {
            skip = LaneComposeGate.checkRect(dest.x(), dest.y(), dest.w(), dest.h());
        }
        if (skip.skip()) {
            if (skip != LaneComposeGate.Skip.INVISIBLE) {   // opacity ≤ 0 = 正常叠化路径，静默
                reportSkipped(skip.reason() + "（纹理 = " + texture.getColorTextureId()
                        + ", source = " + source + ", dest = " + dest + ", opacity = " + opacity + "）");
            }
            return false;
        }
        // 遮罩层判定：零尺寸 = coverage 0（正常显露起点，静默跳过）；非有限 / 负值 / 未知 shape = 数据错误
        boolean fullMask = mask == null || mask.shape() == LaneMask.SHAPE_FULL;
        if (!fullMask) {
            MaskCheck check = checkMask(mask);
            if (check == MaskCheck.ZERO) {
                return false;
            }
            if (check == MaskCheck.BAD) {
                reportSkipped("遮罩几何非法（shape = " + mask.shape() + ", x = " + mask.x()
                        + ", y = " + mask.y() + ", width = " + mask.width() + ", height = " + mask.height()
                        + ", radius = " + mask.radius() + "）");
                return false;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        int screenW = main.width;
        int screenH = main.height;

        // dest：屏幕归一化矩形 → 像素（左上角原点、y 向下；屏幕正交投影下顶点 y 直接向下增长）
        float x0 = dest.x() * screenW;
        float x1 = (dest.x() + dest.w()) * screenW;
        float y0 = dest.y() * screenH;
        float y1 = (dest.y() + dest.h()) * screenH;
        // source：画面归一化矩形 → UV 子区域（画面 y 向下、纹理 v 向上 → v 取反）
        float u0 = source.x();
        float u1 = source.x() + source.w();
        float vTop = 1.0F - source.y();
        float vBottom = vTop - source.h();

        // 专用 blit 忽略 lane 纹理 alpha（见类注释 alpha 契约）；非 full 遮罩缺它必须跳过，不伪造无遮罩输出。
        ShaderInstance blit = blitShader(mc);
        if (!fullMask && blit == null) {
            reportSkipped("遮罩 lane 需要专用着色器 " + SHADER_NAME + "，当前不可用 → 跳过该 lane"
                    + "（不退回无遮罩的 position_tex）");
            return false;
        }
        // uniform 全量写入（含 full 复位）：在改任何 GL 状态之前完成；非 full 缺 uniform = 着色器不可用
        if (blit != null && !setMaskUniforms(blit, mask, fullMask, screenW, screenH)) {
            reportSkipped("专用着色器 " + SHADER_NAME + " 缺少遮罩 uniform（MaskShape / MaskCenter / "
                    + "MaskHalfSize / MaskRadius / ScreenSize）→ 跳过该 lane（不退回无遮罩的 position_tex）");
            return false;
        }

        Matrix4f prevProjection = RenderSystem.getProjectionMatrix();
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        float[] prevColor = RenderSystem.getShaderColor().clone();
        int prevTexture = RenderSystem.getShaderTexture(0);

        main.bindWrite(true);   // 主 framebuffer + 视口 = 屏幕尺寸
        RenderSystem.disableDepthTest();                    // 贴在已画好的主画面上，不受深度影响
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(true, true, true, false);    // 不写 alpha（与 blitToScreen 同）
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        try {
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, screenW, screenH, 0.0F, 1000.0F, 3000.0F),
                    VertexSorting.ORTHOGRAPHIC_Z);

            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            try {
                modelView.setIdentity();
                modelView.translate(0.0F, 0.0F, -2000.0F);   // 落在正交投影的 z 范围内
                RenderSystem.applyModelViewMatrix();

                if (blit != null) {
                    RenderSystem.setShader(() -> blit);
                } else {
                    // 兜底（仅 full）：着色器不可用 → 退回原版 position_tex；画面仍可见，代价是 lane 纹理 alpha 透底。
                    RenderSystem.setShader(GameRenderer::getPositionTexShader);
                }
                RenderSystem.setShaderTexture(0, texture.getColorTextureId());
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, opacity);   // ColorModulator.a = 不透明度

                BufferBuilder builder = new BufferBuilder(256);
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                builder.vertex(x0, y1, 0.0).uv(u0, vBottom).endVertex();   // 左下
                builder.vertex(x1, y1, 0.0).uv(u1, vBottom).endVertex();   // 右下
                builder.vertex(x1, y0, 0.0).uv(u1, vTop).endVertex();      // 右上
                builder.vertex(x0, y0, 0.0).uv(u0, vTop).endVertex();      // 左上
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

    /** 遮罩几何判定结果。 */
    private enum MaskCheck {
        /** 几何有效（宽高 / 半径 &gt; 0）。 */
        OK,
        /** 覆盖率为 0（rect 宽或高 = 0 / circle 半径 = 0）：正常显露起点，静默跳过。 */
        ZERO,
        /** 非法（未知 shape / 非有限 / 负尺寸）：报错跳过。 */
        BAD
    }

    /**
     * 非 full 遮罩的几何判定：零尺寸 = coverage 0（正常显露起点），非有限 / 负值 / 未知 shape = 数据错误。
     * 中心允许越界（数据语义），故不检查范围。
     */
    private static MaskCheck checkMask(LaneMask mask) {
        int shape = mask.shape();
        if (shape != LaneMask.SHAPE_RECT && shape != LaneMask.SHAPE_CIRCLE) {
            return MaskCheck.BAD;
        }
        if (!Float.isFinite(mask.x()) || !Float.isFinite(mask.y())) {
            return MaskCheck.BAD;
        }
        if (shape == LaneMask.SHAPE_RECT) {
            float w = mask.width();
            float h = mask.height();
            if (!Float.isFinite(w) || !Float.isFinite(h) || w < 0.0F || h < 0.0F) {
                return MaskCheck.BAD;
            }
            return w == 0.0F || h == 0.0F ? MaskCheck.ZERO : MaskCheck.OK;
        }
        float radius = mask.radius();
        if (!Float.isFinite(radius) || radius < 0.0F) {
            return MaskCheck.BAD;
        }
        return radius == 0.0F ? MaskCheck.ZERO : MaskCheck.OK;
    }

    /**
     * 写入遮罩 uniform：每 lane 全量覆盖（含 full 的显式复位，防上一 lane 的脏值）。
     * 几何全部按主 framebuffer 物理像素写入（{@code gl_FragCoord} 口径）：归一化中心 / 宽高 / 短边半径
     * 在此乘 {@code screenW / screenH / min(screenW, screenH)}，circle 在非正方形窗口仍是真圆。
     * full 时 shape=0，其余几何仍写回全窗口默认值；非 full 时另一形状的参数显式归位。
     *
     * @return {@code false} = 非 full 但着色器缺少必要 uniform（调用方报错跳过，不伪造无遮罩输出）
     */
    private static boolean setMaskUniforms(ShaderInstance shader, LaneMask mask, boolean fullMask,
                                           int screenW, int screenH) {
        Uniform shape = shader.getUniform("MaskShape");
        Uniform center = shader.getUniform("MaskCenter");
        Uniform halfSize = shader.getUniform("MaskHalfSize");
        Uniform radius = shader.getUniform("MaskRadius");
        Uniform screen = shader.getUniform("ScreenSize");
        if (!fullMask && (shape == null || center == null || halfSize == null
                || radius == null || screen == null)) {
            return false;
        }
        float w = screenW;
        float h = screenH;
        float shortSide = Math.min(w, h);
        if (fullMask) {
            // 全窗口默认值（shape=0 时 shader 不读，但显式归位，防槽位 / 上一 lane 的脏 uniform）
            if (shape != null) shape.set((float) LaneMask.SHAPE_FULL);
            if (center != null) center.set(w * 0.5F, h * 0.5F);
            if (halfSize != null) halfSize.set(w * 0.5F, h * 0.5F);
            if (radius != null) radius.set(shortSide * 0.5F);
        } else if (mask.shape() == LaneMask.SHAPE_RECT) {
            shape.set((float) LaneMask.SHAPE_RECT);
            center.set(mask.x() * w, mask.y() * h);
            halfSize.set(mask.width() * w * 0.5F, mask.height() * h * 0.5F);
            radius.set(shortSide * 0.5F);   // circle 专用，显式归位
        } else {
            shape.set((float) LaneMask.SHAPE_CIRCLE);
            center.set(mask.x() * w, mask.y() * h);
            halfSize.set(w * 0.5F, h * 0.5F);   // rect 专用，显式归位
            radius.set(mask.radius() * shortSide);
        }
        if (screen != null) {
            screen.set(w, h);
        }
        return true;
    }

    /**
     * 守门跳过的限频告警：同一失败点 5 秒一条，抑制次数合并到下一次输出（逐帧复现的失败不刷屏）。
     * 只报告「不该画」的失败；{@code opacity ≤ 0} 的叠化到 0 是正常路径，不经过本方法。
     */
    private static void reportSkipped(String what) {
        ErrorLog.logRateLimited("Render", "lane-compose-skip", 5000L,
                "lane 合成跳过（主画面保持基画面）：" + what);
    }

    /**
     * 取（必要时创建）合成 blit 着色器实例 {@code ic_lane_blit}；资源重载（换资源包等）后重建：
     * 旧实例 {@code close()} 释放其 GL program（原版 Program 缓存按名字复用），随后按新资源重新编译。
     * 加载失败只记一次（同一资源周期内不重试）：full 退回原版 {@code position_tex}，非 full 遮罩报错跳过。
     * 资产走 {@code minecraft} 命名空间（{@code assets/minecraft/shaders/core/}）：原版 {@link ShaderInstance}
     * 只认 {@code shaders/core/<name>.json}，与 {@code ic_color_adjust} 同源同约束。
     */
    private static ShaderInstance blitShader(Minecraft mc) {
        ResourceManager resources = mc.getResourceManager();
        if (blitShaderSource != resources) {
            if (blitShader != null) {
                blitShader.close();
                blitShader = null;
            }
            blitShaderSource = resources;
            try {
                blitShader = new ShaderInstance(resources, SHADER_NAME, DefaultVertexFormat.POSITION_TEX);
            } catch (IOException e) {
                ErrorLog.log("Render", "lane 合成着色器加载失败（" + SHADER_NAME + "）："
                        + "本资源周期内退回原版 position_tex（lane 纹理 alpha 会透底），"
                        + "检查 assets/minecraft/shaders/core/" + SHADER_NAME + ".*", e);
            }
        }
        return blitShader;
    }
}
