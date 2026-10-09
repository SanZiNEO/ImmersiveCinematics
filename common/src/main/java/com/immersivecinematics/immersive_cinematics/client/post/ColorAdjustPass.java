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
 * 画面颜色调整的渲染 pass：把 {@link ColorAdjustParams} 的标量组一次全屏 pass 应用到画面纹理上。
 * 两条使用路径共用本类（同一份着色器 + 同一份 uniform 上传 + 同一个 {@link #applyTo}）：
 * <ul>
 *   <li><b>master</b>（{@link MasterColorAdjust} 发布）：作用于<b>合成输出</b>（架构图 RADJ 节点）——
 *       {@link #render} 在 lane 合成（MCOMP）与原版后处理链（RPOST）之后、GUI 之前调一次。</li>
 *   <li><b>lane 级</b>（来自该 lane 的相机片段）：作用于<b>该 lane 的画面</b>，在 lane 渲染完成
 *       之后、合成之前（{@code client.lane.LaneRenderer#renderLane} 调 {@link #applyTo}，
 *       写进共享 adjustTarget 再交给合成层）。</li>
 * </ul>
 *
 * <h2>挂点</h2>
 * master：lane 合成（MCOMP，{@code LaneRenderer.render}，挂在 {@code GameRenderer.renderLevel} 的 RETURN）
 * 与原版后处理链（RPOST）之后、GUI 之前 —— 即 {@code mixin/GameRendererMixin.onWorldPostProcessed}：
 * {@code GameRenderer.render} 内 {@code postEffect.process(f)} 之后那一句
 * {@code getMainRenderTarget().bindWrite(true)} 的调用点上（2026-10-08 后移；此前在
 * {@code LaneRendererMixin} 里、紧接 lane 合成之后）。
 * <h3>截图（两条路径，行为不同）</h3>
 * 原版有两条截图路径，本挂点后移后二者行为不同（依 1.20.1 反编译源码）：
 * <ul>
 *   <li><b>F2 直抓</b>（{@code KeyboardHandler.keyPress} → {@code Screenshot.grab(gameDirectory,
 *       getMainRenderTarget(), ...)}）：在输入处理阶段读<b>上一帧</b>已画完的主画面（含 GUI 与调色），
 *       与挂点位置无关 —— 新旧挂点下<b>都含</b>调色。</li>
 *   <li><b>自动世界截图</b>（{@code GameRenderer.render} 内的 {@code tryTakeScreenshotIfNeeded()} →
 *       {@code takeAutoScreenshot} → {@code Screenshot.takeScreenshot(getMainRenderTarget())}）：
 *       调用点在 {@code renderLevel} 之后、本挂点<b>之前</b>（新挂点）—— 该路径截图<b>不含</b>调色；
 *       旧挂点（{@code renderLevel} 的 RETURN）下该调用点在本挂点之后，则含调色。</li>
 * </ul>
 * GUI（字幕 / 黑边 / 黑白场 / 跳过提示）不受调色影响。
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
 * <p><b>多实例叠加（A13'）</b>：每个实例一套参数，逐套重复第 1 步（目标缓冲在「主画面 / 中转缓冲」
 * 之间交替，前一套输出 = 后一套的基画面）；偶数套直接落在主画面上、奇数套末尾补第 2 步。
 * 成本 = 层数次全屏 quad（+ 至多一次 blit），单实例时与上面两条逐点一致。</p>
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
 * <p>十二个采样器（顶格 = MC 1.20.1 {@code GlStateManager} 的 12 个纹理单元 0~11，
 * 见 {@code GlStateManager.TEXTURE_COUNT}）：{@code Sampler0} = 画面（第一纹理单元）、
 * {@code CurveLut} = RGB 复合曲线的 256×1 LUT、
 * {@code RCurveLut} / {@code GCurveLut} / {@code BCurveLut} = 每通道曲线的 256×1 LUT、
 * {@code HvHLut} / {@code HvSLut} / {@code HvLLut} / {@code LvSLut} / {@code SvSLut} / {@code SvLLut}
 * = 六条 hue 曲线的 256×1 LUT（第二 ~ 第十一纹理单元，见 {@link LutTexture}）、
 * {@code Lut3D} = .cube 的<b>合成</b> 3D 表（S×S×S，{@link CubeLut#composed3D()}，
 * 见 {@link Lut3DTexture}；第十二 = 最后一个纹理单元，单元 11）——
 * 只在本帧参数带 LUT（ADJUST 轨写了 {@code lut} 且 {@code lut_strength} 非 0）时才被采样；
 * lane 路径恒为 {@code null}（LUT 只作用于整体画面）。</p>
 *
 * <p><b>为什么 LUT 只有一张表</b>：1D shaper 与 3D 曾各占一个采样器（13 个采样器 → 单元 0~12），
 * 而 {@code ShaderInstance.apply()} 按 JSON {@code samplers} 下标逐单元
 * {@code GlStateManager._bindTexture(unit)}，单元 12 越界（数组长度 12）→
 * {@code ArrayIndexOutOfBoundsException}。现在 1D 段与两段 DOMAIN 都在加载期烘焙进
 * {@link CubeLut#composed3D()} 的单张表，采样器回到 12 个顶格。</p>
 *
 * <h2>默认零差异</h2>
 * 无调整时 {@link #render} 第一行返回；{@link #applyTo} 参数为空 / 恒等时第一行返回：
 * 不取着色器、不建中转缓冲、不切任何 GL 状态、不画任何东西。
 * 着色器是首次真正需要时才编译的（不用不编译），资源重载后重建。
 *
 * <h2>层级混合（混合模式作用于调整层）</h2>
 * 操作栈全部步骤（曝光 ~ 反相）之后、输出之前，着色器再做一步<b>层级混合</b>：把调整层输出
 * （{@code c_adj}）与基画面（{@code Sampler0} 原图 {@code c_base}）按 {@code BlendMode}
 * （枚举编码 0 ~ 4）与 {@code BlendAmount}（0 ~ 1）混合——
 * {@code c = mix(c_base, blend(c_base, c_adj, mode), clamp(BlendAmount, 0, 1))}。
 * 缺省 {@code normal} + {@code 1} = 直替换 = 与混合功能落地前逐位一致（渲染侧该步整段跳过）；
 * 两个 uniform 只由 <b>ADJUST 轨（master）</b>取值，lane 路径恒为缺省。
 *
 * <h2>失败语义（fail-safe 直通）</h2>
 * 渲染错误一律<b>直通</b>：宁可不调色，也绝不把未写 / 未定义的缓冲铺上屏
 * （用户裁决 2026-10-09，见 {@code plans/0.3.6/multi-instance-black-screen.md} §五）。三层守卫：
 * <ol>
 *   <li><b>每层施加可失败</b>：{@link #applyTo} 绘制前查目标 FBO 完整、绘制后清查 GL 错误
 *       （进入前先清积压，只算本 pass 的错误；<b>无读回、无 {@code glFinish} 之类同步等待</b>），
 *       任一步不过 → 返回 {@code false} = 本层未施加；异常同样吞成失败。</li>
 *   <li><b>失败即停</b>：{@link #render} 的链式施加遇到失败立刻停下，当前有效内容（上一层输出或
 *       原始画面）保持不动——失败层的目标可能是半写 / 未写的，绝不再当输入或输出用。</li>
 *   <li><b>回写前验证「本帧已写」</b>：末尾整屏 blit 只在本帧确实写过中转缓冲时执行
 *       （{@link #swapWrittenThisFrame}）；没写过就不 blit，主画面自然保持原样
 *       （首层失败 = 整链直通原始画面）。</li>
 * </ol>
 * 失败按 {@link #FAILURE_LOG_INTERVAL_MS} 限频告警（{@link ErrorLog}，类别 {@code Render}）。
 * 正常路径零差异：这些检查只是状态查询，不改绘制语义 / 参数 / 纹理内容。
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

    /**
     * 「本帧中转缓冲已写」标记：{@link #render} 每次进入链式施加时清空，只有本帧<b>已验证</b>的写入
     * （{@link #applyTo} 返回 {@code true} 且目标 = 中转缓冲）才置位。末尾整屏 blit 以它为准——
     * 没写过就不回写，主画面自然保持原样（见类注释「失败语义」）。
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

    /**
     * {@code Lut3D} 采样器的纹理单元 = 它在着色器 JSON {@code samplers} 数组里的下标
     * （{@code Sampler0} + 10 个曲线 LUT 之后的<b>最后</b>一个 = 单元 11，总数 12 顶格）。
     * <p>3D 纹理不能走 {@link ShaderInstance#setSampler}（那条路一律按 {@code GL_TEXTURE_2D} 目标绑定），
     * 所以在绘制前直接按 {@code GL_TEXTURE_3D} 目标手动绑到这个单元（见 {@link Lut3DTexture#bind}）。</p>
     */
    private static final int LUT_3D_UNIT = 11;

    /** 3D LUT 的纹理槽（{@code Lut3D} 采样器）。 */
    private static final Lut3DTexture LUT_3D = new Lut3DTexture();

    private ColorAdjustPass() {
    }

    /**
     * 在<b>合成输出</b>上应用本帧的 master 颜色调整（架构图 RADJ 节点）。
     *
     * <p>多实例按层级叠加（A13'）：{@link MasterColorAdjust#consume} 给出按实例启动顺序排好的参数套
     * （先启动的在前 = 下层），逐套<b>顺序施加</b>——前一套的输出就是后一套的基画面（层级混合读的
     * {@code Sampler0}），目标缓冲在「主画面 / 中转缓冲」之间交替；结果停在中转缓冲时（层数为奇数）
     * 末尾再整屏 blit 回主画面。单实例（= 一套参数）的调用序列与叠加落地前逐字节一致：
     * 主画面 → 中转缓冲 →（整屏 blit）→ 主画面。</p>
     *
     * <p>无调整（本帧没人发布 / 参数恒等）时不做任何事；某层不可用 / 施加失败时停在该层之前已施加的
     * 输出上（宁可不调色，也不动画面）——失败层的目标缓冲可能是半写 / 未写的，绝不再当输入或输出用。</p>
     *
     * <p><b>fail-safe</b>：末尾整屏 blit 只在「本帧确实写过中转缓冲」时执行（{@link #swapWrittenThisFrame}）；
     * 没写过就不 blit，主画面保持原样——首层失败 = 整链直通原始画面，任何情况下都不会把未写 /
     * 未定义的缓冲铺上屏（见类注释「失败语义」）。</p>
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
        // 逐层链式施加：本层输入 = 上一层输出（current），输出写进另一个缓冲
        // （不能同时读写同一张纹理；每层自带混合模式 / 强度，混合的基画面 = 上一层输出）
        // fail-safe：applyTo 只在「目标 FBO 完整 + 本次施加无 GL 错误」时返回 true——失败即停，
        // current 停在最后一个已验证的写入上（可能是主画面 = 原始画面），半写 / 未写的缓冲不再当输入或输出用
        swapWrittenThisFrame = false;   // 「本帧已写」标记：只由本帧已验证的写入置位
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
            // pass 2：中转缓冲 → 主画面（整屏 blit，复用合成层的状态保存 / 还原与 UV 口径）。
            // 只有本帧确实写过中转缓冲才 blit：没写过就不回写（主画面保持原样），
            // 绝不把未写 / 未定义的缓冲铺上屏。blit 自身失败是安全的——它在主画面上混合覆盖，
            // 失败时主画面保持原内容（有效画面），不会变黑。
            if (swapWrittenThisFrame) {
                LaneCompositor.compose(current, LaneCompositor.Rect.FULL, LaneCompositor.Rect.FULL, 1.0F);
            } else {
                main.bindWrite(true);   // 不 blit：把主画面绑回来（调用方接着画）
                reportFailure("链末尾校验未通过：中转缓冲本帧未被写入，跳过回写（画面保持原样）", null);
            }
        }
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
     * <p><b>失败语义（fail-safe 直通）</b>：本方法只在「目标 FBO 完整 + 本次施加全程无 GL 错误」时返回
     * {@code true}；任何失败（含异常）都返回 {@code false} = 本层未施加——调用方必须丢弃 {@code dst}
     * 的内容（它可能是未写 / 半写的），保持此前已确认有效的画面。检查全是状态查询，无读回 / 无同步等待。</p>
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
        // 本 pass 的 GL 错误从零开始计：先清掉进入前的积压（别的模块 / 上一帧留下的），
        // 之后产生的错误才算本 pass 的（含采样器绑定 / LUT 上传 / uniform 上传 / 绘制）
        drainGlErrors();
        try {
            return pass(shaderInstance, src, dst, params);
        } catch (RuntimeException e) {
            reportFailure("调色 pass 执行异常（本层跳过，画面保持上一层 / 原始输出）", e);
            return false;
        }
    }

    /**
     * 一次调色 pass 的执行体：上传参数 → 绘制 → 校验「目标确实被本次施加写满」。
     *
     * <p><b>校验口径</b>（无读回、无 {@code glFinish} 之类同步等待）：目标 FBO 状态必须是
     * {@code GL_FRAMEBUFFER_COMPLETE}（不完整就连画都不画），且本次施加全程没有产生 GL 错误。</p>
     *
     * @return 目标缓冲是否被本次施加写满（{@code false} = 未写 / 半写 / 报错，调用方必须丢弃这个输出）
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

        // LUT（第 11 步）：无 LUT 或强度 0 → 强度置 0、整步跳过（逐位恒等）；
        // 只有一张合成表（1D shaper 与两段 DOMAIN 已在加载期烘焙进 CubeLut#composed3D），
        // 走 GL_TEXTURE_3D 手动绑定（见 LUT_3D_UNIT）
        CubeLut lut = params.lut() != null && params.lutStrength() != 0.0F ? params.lut() : null;
        shaderInstance.setSampler("Lut3D", 0);   // 占位：让 apply() 上传该采样器的纹理单元号（0 号 2D 纹理 = 不绑任何东西）
        LUT_3D.upload(lut);

        upload(shaderInstance, params);

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
                    LUT_3D.bind();   // 3D LUT 只能在绘制前按 GL_TEXTURE_3D 目标手动绑（见 LUT_3D_UNIT）

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
                // 施加全程无 GL 错误 = 目标确实被这次绘制写满（绘制报错时驱动会丢掉这次绘制，
                // 目标留下的内容不可信）；这里只查状态、不读回、不同步等待
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
     *
     * <p>用法固定成对：{@link #applyTo} 进入时先调一次「清积压」（此前别的模块 / 上一帧留下的错误
     * 不归本次施加），绘制后再调一次——这次读到错误 = 本次施加失败。</p>
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
     * 失败告警（限频）：失败会逐帧复现，所以两次告警至少间隔 {@link #FAILURE_LOG_INTERVAL_MS}，
     * 窗口内的次数合并到下一次输出里（不刷屏，也不丢「一直在失败」这个事实）。
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
        // LUT（第 11 步）：无 LUT 或强度 0 → 强度置 0（着色器整步跳过）；
        // 表尺寸 = 合成表边长（CubeLut#composed3D，1D 段与 DOMAIN 已烘焙进表）；
        // 输入域适配 = clip 级幂指数（无 LUT 时归 1 = 不变换，与「整步跳过」同口径）
        CubeLut lut = p.lut() != null && p.lutStrength() != 0.0F ? p.lut() : null;
        set(shader, "LutStrength", lut == null ? 0.0F : p.lutStrength());
        set(shader, "LutInputGamma", lut == null ? 1.0F : p.lutInputGamma());
        set(shader, "Lut3DSize", lut == null ? 0.0F : lut.composed3D().size());
        set(shader, "HvHStrength", p.hvHLut() == null ? 0.0F : p.hvHStrength());
        set(shader, "HvSStrength", p.hvSLut() == null ? 0.0F : p.hvSStrength());
        set(shader, "HvLStrength", p.hvLLut() == null ? 0.0F : p.hvLStrength());
        set(shader, "LvSStrength", p.lvSLut() == null ? 0.0F : p.lvSStrength());
        set(shader, "SvSStrength", p.svSLut() == null ? 0.0F : p.svSStrength());
        set(shader, "SvLStrength", p.svLLut() == null ? 0.0F : p.svLStrength());
        // PS 式六色带微调（第 17 步）：六条色带（红 / 黄 / 绿 / 青 / 蓝 / 品红）各一对 hue / sat 通道
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
        // 层级混合（第 20 步 = 全部操作栈步骤之后整体施加；只属 ADJUST 轨，lane 路径恒为 normal + 1）：
        // BlendMode = 枚举编码 0 ~ 4（normal / multiply / screen / soft_light / overlay），
        // BlendAmount = 混合强度 0 ~ 1（缺省 1 = 全量生效；着色器内 clamp）
        set(shader, "BlendMode", p.blendMode());
        set(shader, "BlendAmount", p.blendAmount());
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

    /**
     * 3D LUT 的纹理槽（{@code Lut3D} 采样器）：S×S×S、RGBA16F、NEAREST + CLAMP_TO_EDGE。
     * 表 = {@link CubeLut#composed3D()}（1D shaper 与两段 DOMAIN 已在加载期烘焙，
     * 采样坐标 {@code x ∈ [0,1]³} 直接对应网格）。
     *
     * <p>按 {@link CubeLut} 实例<b>引用</b>比较：同一 LUT 每帧拿到同一引用（{@link CubeLutLoader}
     * 按路径共享实例、{@code composed3D()} 自身缓存）→ 只上传一次像素，换 LUT 才重传。
     * 用 16 位浮点而不是 8 位：{@code .cube} 的数据是浮点、可超出 {@code [0,1]}（见 {@link CubeLut} 文档），
     * 8 位量化会把超出部分截掉、并在中性灰附近留下台阶。</p>
     *
     * <p><b>过滤 = NEAREST</b>：插值不交给硬件三线性，而是着色器用 {@code texelFetch} 取 8 个角点后按
     * <b>四面体</b>（tetrahedral）加权（业界默认口径，三线性在中性灰附近会偏色）。</p>
     *
     * <p><b>绑定</b>：3D 纹理不能走 {@link ShaderInstance#setSampler}（那条路一律按 {@code GL_TEXTURE_2D}
     * 目标绑定），所以这里按 {@code GL_TEXTURE_3D} 目标手动绑到 {@link #LUT_3D_UNIT}——
     * 采样器的纹理单元号仍由 {@code ShaderInstance.apply()} 按 JSON {@code samplers} 次序上传
     * （samplerMap 里放占位值即可，见 {@code applyTo}）。</p>
     */
    private static final class Lut3DTexture {

        /** {@code GL_MAX_3D_TEXTURE_SIZE}（首次查询后缓存；{@code -1} = 尚未查询）。 */
        private static int maxTextureSize = -1;

        private int textureId = -1;
        private CubeLut uploaded;

        /** 上传本 LUT 的合成表（同一实例只传一次）；无 LUT / 已上传 → 不动。 */
        void upload(CubeLut lut) {
            if (lut == null || lut == uploaded) {
                return;
            }
            CubeLut.Composed3D composed = lut.composed3D();
            int size = composed.size();
            // 尺寸守卫：合成表理论上 ≤ COMPOSED_MAX_SIZE，仍按驱动上限核一道——超出只告警、不阻断
            int maxSize = maxTextureSize();
            if (maxSize > 0 && size > maxSize) {
                ErrorLog.log("LUT", "合成表边长 " + size + " 超出 GL_MAX_3D_TEXTURE_SIZE（" + maxSize
                        + "）：本次仍按原尺寸上传，纹理将不完整（LUT 不生效）；检查 LUT 尺寸与 CubeLut 合成上限");
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
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            uploaded = lut;
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

        /** 把当前纹理绑到 {@link #LUT_3D_UNIT} 单元（绘制前调用；不改变其它单元与 2D 绑定）。 */
        void bind() {
            bindLut3D(textureId);
        }

        /** 解绑（绘制后恢复：该单元不留悬挂绑定）。 */
        void unbind() {
            bindLut3D(0);
        }
    }

    /** 在 {@link #LUT_3D_UNIT} 单元上按 {@code GL_TEXTURE_3D} 目标绑定一个纹理名（0 = 解绑）。 */
    private static void bindLut3D(int textureId) {
        int prevUnit = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + LUT_3D_UNIT);
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
