package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
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
 * 合成层：把一条 lane 的画面纹理按合成参数铺到屏幕（主 framebuffer）。
 *
 * <p>{@link LaneRenderer} 渲染完一条 lane 后，经 {@link LaneRenderer.Sink} 把离屏画面交给本类；
 * 四个参数在<b>同一次 quad 绘制</b>里完成（见 plans/0.3.6/camera-composition.md §1）：</p>
 *
 * <table border="1">
 *   <caption>合成参数 → 绘制机制</caption>
 *   <tr><th>参数</th><th>含义</th><th>落点</th></tr>
 *   <tr><td>{@code source}</td><td>取材区域：画面内归一化矩形（默认全幅）</td><td>UV 子区域</td></tr>
 *   <tr><td>{@code dest}</td><td>目标区域：屏幕归一化矩形（默认全屏）</td><td>quad 顶点（像素）</td></tr>
 *   <tr><td>{@code opacity}</td><td>叠放不透明度 0~1（默认 1）</td><td>混合 alpha（{@code ColorModulator.a}）</td></tr>
 *   <tr><td>{@code order}</td><td>叠放顺序：后面的在上</td><td><b>绘制顺序</b>（见下）</td></tr>
 * </table>
 *
 * <h2>order = 绘制顺序</h2>
 * 合成即时进行、不缓存纹理——设计上就是「渲染一张 → 贴到屏幕 → 复用缓冲」（§1），
 * 所有 lane 共用一张离屏缓冲，纹理当帧即被下一条 lane 覆盖，所以合成器不能攒批排序：
 * <b>叠放顺序就是调用顺序</b>。调用方按「轨道层级 → 轨道内 clip 顺序（后面的在上）→ z_index」
 * 升序逐层调用 {@link #compose}，后调用者盖在先调用者之上。
 *
 * <h2>矩形口径</h2>
 * {@code source} / {@code dest} 都是 0~1 归一化矩形、<b>原点在左上角</b>（x 向右、y 向下）：
 * {@code source} 相对 lane 画面，{@code dest} 相对屏幕。纹理 v 轴在内部翻转
 * （FBO 纹理的 v=0 在画面底部，与 {@code RenderTarget.blitToScreen} 的 UV 口径一致）。
 *
 * <h2>绘制路径</h2>
 * 复刻原版 {@code RenderTarget._blitToScreen} 的屏幕空间画法：屏幕正交投影
 * （{@code setOrtho(0, w, h, 0, 1000, 3000)}）+ 模型视图平移到 z=−2000，用 4 顶点 quad 覆盖
 * {@code dest}；差别只在 ① 走本模组自建的 {@code ic_lane_blit}（见下「alpha 契约」；其 shader JSON
 * 自带 {@code srcalpha / 1-srcalpha} 混合，正是 opacity 需要的），② UV 取 {@code source} 子区域，
 * ③ 顶点按 {@code dest} 缩放。alpha 通道不写（与 {@code blitToScreen} 同：主画面 alpha 不归合成层管）。
 *
 * <h2>alpha 契约（lane 纹理的 alpha 不参与合成）</h2>
 * 合成只读 lane 画面的 <b>RGB</b>：专用着色器 {@code ic_lane_blit}
 * （{@code fragColor = vec4(texture(Sampler0, uv).rgb, 1.0) * ColorModulator}）把写出的 alpha 恒定为 1，
 * 不透明度<b>只</b>由 {@code ColorModulator.a}（{@code opacity}）承担，混合结果 =
 * {@code lane.rgb × opacity + main.rgb × (1 − opacity)}，与 lane 纹理里写下的 a 值无关。
 * <p>为什么不能沿用原版 {@code position_tex}：lane FBO 里写下的 alpha 不干净、也没人清理——原版从不
 * 显示它（{@code RenderTarget._colorMask(true,true,true,false)}），于是星星（a=127）、方块图集 mipmap
 * 边缘的 1px 暗缝（a=146~254）、cutout 植被等都留着 a&lt;255；而 {@code position_tex} 是
 * {@code color * ColorModulator} + srcalpha 混合，这些像素的混合权重被乘成 {@code a × opacity}
 * ⇒ 主画面（玩家视角）从 lane 的 a&lt;255 处透出来（隐约透底；实测这类像素占 0.15%~1.77%）。
 * <p>lane 侧配套：{@code LevelRendererMixin} 的 {@code laneClearAlpha} 仍把清屏 alpha 抬到 1
 * （未覆盖区 = 实心雾色），但它只影响 FBO 数据本身（调试读回 / 未来可能的 alpha 消费方），
 * <b>不再承担合成的正确性</b>。
 * <p>兜底：{@code ic_lane_blit} 加载失败（资源包覆盖 / 编译失败；只记一次错误）时退回原版
 * {@code position_tex}——画面仍可见，代价是重新引入上面那条 alpha 透底。
 *
 * <h2>状态保存 / 还原</h2>
 * 一次 {@link #compose} 会改动：绑定的 framebuffer 与视口、全局投影矩阵与 VertexSorting、
 * 全局模型视图矩阵、shader 颜色（{@code ColorModulator}）与 0 号 shader 纹理、深度测试 / 深度写 /
 * 颜色写 / 混合。本类在入口保存、出口还原；绘制结束把主 framebuffer 留在绑定态
 * （{@link LaneRenderer} 下一轮自己绑 lane FBO，收尾也自己绑主画面）。
 *
 * <h2>默认零差异</h2>
 * 本类只在被调用时动作：没有 lane 就没有调用（{@link LaneRenderer} 在无活跃 lane 时第一行返回），
 * 因此不装本模组与装了但不放 lane 都是零差异。
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
     * 归一化矩形（各分量 0~1），原点在左上角。
     *
     * <p>与脚本侧字段口径一致（camera-composition.md §7 步骤 1 的 {@code dest} / {@code source}
     * 对象 {@code {x,y,w,h}}）：{@code source} 相对 lane 画面，{@code dest} 相对屏幕。</p>
     */
    public record Rect(float x, float y, float w, float h) {

        /** 全幅（source）/ 全屏（dest）。 */
        public static final Rect FULL = new Rect(0.0F, 0.0F, 1.0F, 1.0F);
    }

    /**
     * 把 {@code texture}（一条 lane 的离屏画面）按合成参数铺到屏幕。
     *
     * <p><b>必须当帧调用</b>：lane 的离屏缓冲是共用的，调用返回后随时会被下一条 lane 覆盖
     * （见 {@link LaneRenderer.Sink}）。</p>
     *
     * @param texture lane 的离屏画面（取其颜色纹理作采样源）
     * @param source  取材区域（画面内归一化矩形，{@link Rect#FULL} = 全幅）
     * @param dest    目标区域（屏幕归一化矩形，{@link Rect#FULL} = 全屏）
     * @param opacity 叠放不透明度 0~1；{@code ≤0} 视为不可见，直接跳过
     */
    public static void compose(RenderTarget texture, Rect source, Rect dest, float opacity) {
        if (opacity <= 0.0F) {
            return;
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

        // 专用不透明 blit（忽略 lane 纹理 alpha，见类注释「alpha 契约」）；加载失败时退回原版 position_tex
        ShaderInstance blit = blitShader(mc);

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
                    // 兜底：ic_lane_blit 不可用（加载失败已记一次错误）→ 退回原版 position_tex。
                    // 画面仍可见（取材 / 目标区域 / opacity 照旧），代价是重新引入 lane 纹理 alpha 的透底。
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
    }

    /**
     * 取（必要时创建）合成 blit 着色器实例（{@code ic_lane_blit}）。
     *
     * <p>资源重载（换资源包 / F3+T）后重建：旧实例 {@code close()} 释放它持有的 GL program
     * （原版 Program 缓存按名字复用，不释放就会拿到旧编译结果），随后按新资源重新编译。
     * 加载失败只记一次（同一资源周期内不重试、不刷屏），{@link #compose} 退回原版 {@code position_tex}。</p>
     *
     * <p>资产走 {@code minecraft} 命名空间（{@code assets/minecraft/shaders/core/}）：原版
     * {@link ShaderInstance} 的构造只认 {@code shaders/core/<name>.json}（默认命名空间），
     * 与 {@code ColorAdjustPass} 的 {@code ic_color_adjust} 同源同约束。</p>
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
