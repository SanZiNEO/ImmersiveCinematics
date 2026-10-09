package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustPass;
import com.immersivecinematics.immersive_cinematics.mixin.GameRendererAccessor;
import com.immersivecinematics.immersive_cinematics.mixin.LevelRendererAccessor;
import com.immersivecinematics.immersive_cinematics.mixin.MinecraftAccessor;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 lane 渲染底层 —— 每个 lane 一个独立相机姿态，整尺寸渲染进<b>共用</b>的离屏缓冲，顺序复用。
 *
 * <p>一条 lane = {@link CameraState}（六参数相机快照）+ {@link LaneContent}（内容开关）。
 * lane 的渲染完全复用原版世界渲染管线（{@code LevelRenderer.renderLevel}），只是换了一个相机实例、
 * 一份独立投影、一个离屏 framebuffer——原版的 GUI / 第一人称手 / HUD 都在
 * {@code LevelRenderer.renderLevel} 之外，所以副画面天然不含它们。</p>
 *
 * <h2>每 lane 渲染流程</h2>
 * <pre>
 * mainRenderTarget 临时指向 lane FBO → bindWrite(true)（视口 = FBO 全尺寸）
 *   → camera.setup(...)（走 CameraMixin 的 lane 分支，读该 lane 的 CameraState）
 *   → 视图 PoseStack（XP=xRot、YP=yRot+180）→ roll → setInverseViewRotationMatrix
 *   → 该 lane 自己的 fov → 投影 → setProjectionMatrix（全局投影 = 该 lane 的投影）
 *   → prepareCullFrustum（该 lane 的 pose + 相机位置）
 *   → applyFrustum（该 lane 的视锥，绕开原版朝向门闩强制重刷：可见集合必须由本 lane 姿态算出）
 *   → renderLevel → doEntityOutline（lane 内描边）→ 还原全局投影
 *   → 恢复 mainRenderTarget → （lane 级调色 pass：lane 画面 → adjustTarget）
 *   → 交给 {@link Sink}（合成层；有 lane 级调色时给的是 adjustTarget，否则是 lane FBO）
 * </pre>
 *
 * <h2>lane 级调色（来自该 lane 的相机片段）</h2>
 * 该 lane 的调色参数由脚本侧（该 lane 的相机片段采样）经 {@link ScriptLaneDriver} 传进
 * {@link Lane#adjust()}。参数非空且非恒等时，本类在 lane 渲染完成（含描边）、<b>合成之前</b>调
 * {@link ColorAdjustPass#applyTo} 跑一次调色 pass，
 * 结果写进共享的 {@link #adjustTarget(int, int)}，并把该缓冲交给合成层（而不是 lane FBO）——
 * 顺序因此固定为 <b>lane 渲染 → lane 级调色 → 合成（opacity/dest/source）→ 全部 lane 完成后 → master 调色</b>。
 * <p><b>alpha 契约</b>：lane 级调色只动 RGB（着色器 {@code fragColor.a = src.a}），alpha 逐位直通；
 * 透明度只在合成层由 {@code opacity}（{@code ColorModulator.a}）调控——与 master 调色同一契约
 * （见 {@code ColorAdjustPass} / {@code ic_color_adjust.fsh}）。</p>
 * <p>参数为空（相机片段没写调色 / 参数恒等 / 着色器不可用）时<b>不建缓冲、不跑 pass</b>，
 * 交给合成的仍是 lane FBO —— 与不带该功能的路径完全一致（默认零差异）。</p>
 *
 * <h2>三条落地要点（原型实证，见 plans/0.3.6/quadrant-prototype-results.md §3.1–3.3）</h2>
 * <ol>
 *   <li><b>lane 期间主画面指向</b>：渲染期间 {@code Minecraft.mainRenderTarget} 指向该 lane 的 FBO——
 *       原版 {@code renderLevel} 内部（{@code entityTarget.clear()} 等）会把 GL viewport 重置成
 *       该 target 的尺寸，之后回绑的也是 {@code getMainRenderTarget()}（且不回滚 viewport）。</li>
 *   <li><b>lane 自包含</b>：lane 的 {@code renderLevel} <b>加上所有 lane 级后处理</b>（发光描边
 *       {@code doEntityOutline()}）都必须在 lane 的 FBO 内完成；原版在 {@code renderLevel} 之后那次
 *       整屏调用被 {@code LevelRendererMixin} 屏蔽（用 {@link #isRenderingLane()} 区分）。</li>
 *   <li><b>全局投影（进 / 出各一次）</b>：{@code LevelRenderer.renderLevel} <b>自己不设</b>全局投影矩阵
 *       （原版由 {@code GameRenderer.renderLevel} 的 {@code resetProjectionMatrix} 设好），而 lane 里走
 *       全局矩阵的绘制（实体 / 粒子 / 方块实体 / 太阳月亮 / 雨雪 / 世界边界）都要吃它——所以进
 *       {@code renderLevel} 之前必须把全局投影设成该 lane 的投影，否则会吃到上一层 pass 遗留的投影
 *       （lane 块开头那次 {@code doEntityOutline → blitToScreen} 留下的是正交矩阵）而被裁掉；
 *       出 lane 前那次 {@code doEntityOutline()} 又会把全局投影改成正交（且不还原），所以之后还要还原。</li>
 * </ol>
 *
 * <h2>状态保存 / 恢复</h2>
 * 一个 lane 的 pass 会改动：{@code mainRenderTarget}（见上）、当前 framebuffer 与视口、全局投影矩阵
 * （{@code doEntityOutline} 会把它改成正交）、全局逆视图旋转矩阵、雾（{@code renderLevel} 末尾
 * {@code setupNoFog}）。本类在 lane 块入口保存、出口还原：<b>全局投影 + VertexSorting</b>、
 * <b>全局逆视图旋转矩阵</b>、<b>{@code mainRenderTarget} 与绑定的 framebuffer / 视口</b>；
 * 雾、深度与混合状态由 {@code renderLevel} 自己收敛到「渲染结束」态（与原版主画面同态），
 * 视锥每帧由各 pass 重建，都不需要额外还原。
 *
 * <h2>默认零差异</h2>
 * 没有注册任何 lane 时，{@link #render} 第一行即返回——不渲染、不分配、不切换任何状态。
 *
 * <h2>性能</h2>
 * 共用一张离屏缓冲（按主画面尺寸创建 / 跟随窗口重建），顺序复用 → 显存不随 lane 数增长
 * （原型实测：1 张共用缓冲跑完 25 个画面）。lane 数不设上限，成本 ≈ 每 lane 一遍世界渲染。
 *
 * <h2>兼容</h2>
 * 运行时检测到 Sodium / Embeddium（Rubidium）时禁用 lane 渲染并 warn 一次：它们 {@code @Overwrite}
 * 原版区块渲染与 {@code setupRender}，副画面第二遍裸调原版区块 API 不可靠
 * （见 plans/0.3.6/multi-camera-rendering.md §7.1）。检测走类名存在性，不引入硬依赖。
 */
public final class LaneRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 一次失败判定的 GL 错误排空上限（防病态驱动下死循环；超过就按「有错误」处理）。 */
    private static final int MAX_DRAINED_ERRORS = 16;

    /** lane 渲染失败的限频间隔（毫秒）：失败逐帧复现，不限频会刷屏。 */
    private static final long FAILURE_LOG_INTERVAL_MS = 5000L;

    /** 全局单例接入点（渲染线程访问）。 */
    public static final LaneRenderer INSTANCE = new LaneRenderer();

    /** 渲染优化模组的存在性标记类（Sodium 家族：Fabric Sodium / Embeddium / Rubidium 各自的入口类）。 */
    private static final String[] RENDER_OPTIMIZER_MARKERS = {
            "net.caffeinemc.mods.sodium.client.SodiumClientMod",   // Sodium（Fabric，0.5.x）
            "me.jellysquid.mods.sodium.client.SodiumClientMod",    // Embeddium / Rubidium（Forge，1.20.1）
    };

    /**
     * lane 内容开关。默认全关（{@link #WORLD_ONLY}）——副画面只要世界地形；
     * 实体 / 粒子 / 天空 / 天气按需打开（设计见 plans/0.3.6/render-routes.md §2）。
     */
    public record LaneContent(boolean entities, boolean particles, boolean sky, boolean weather) {

        /** 只有地形（+ 雾色背景）：默认档。 */
        public static final LaneContent WORLD_ONLY = new LaneContent(false, false, false, false);

        /** 全开：与主画面同内容（调试 / 压测用）。 */
        public static final LaneContent FULL = new LaneContent(true, true, true, true);
    }

    /**
     * 每条 lane 渲染完成后的回调 —— 合成层在这里把画面贴到屏幕（实现见 {@link LaneCompositor}）。
     *
     * <p><b>必须当帧消费</b>：所有 lane 共用同一张离屏缓冲，回调返回后缓冲会被下一条 lane 覆盖。</p>
     */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param index   该 lane 的序号（合成顺序）
         * @param target  该 lane 本帧的画面（合成层取其颜色纹理上屏）
         * @param written 该画面本帧是否被写满；{@code false} = 渲染失败 / 未写（失败原因已由
         *                {@link #renderLane} 限频告警）——合成层必须跳过该 lane，主画面保持基画面
         */
        void laneRendered(int index, RenderTarget target, boolean written);
    }

    /** 一个 lane 槽位：独立原版 {@link Camera} 实例 + 本帧相机状态 + 内容开关 + lane 级调色 + 调试用相机 id。 */
    public static final class Lane {

        private final Camera camera = new Camera();
        private CameraState state;
        private LaneContent content = LaneContent.WORLD_ONLY;
        private ColorAdjustParams adjust;
        private String captureId;

        /** 该 lane 的独立原版相机实例（生命周期 = 槽位，跨帧复用）。 */
        public Camera camera() {
            return camera;
        }

        /** 本帧相机状态；槽位未激活时为 {@code null}。 */
        public CameraState state() {
            return state;
        }

        /** 本帧内容开关。 */
        public LaneContent content() {
            return content;
        }

        /**
         * 本帧该 lane 的相机 id（调试捕获用文件名口径，见 {@link LaneDebugCapture}）；
         * {@code null} = 生产者没给 id —— 捕获侧回退 {@code lane<序号>}。
         */
        public String captureId() {
            return captureId;
        }

        /**
         * 本帧的 lane 级调色参数（来自该 lane 的相机片段，由 {@code ScriptPlayer} 采样而来）；
         * {@code null} = 无 —— 该 lane 渲染完直接进合成，不跑调色 pass（默认零差异路径）。
         */
        public ColorAdjustParams adjust() {
            return adjust;
        }
    }

    private final List<Lane> lanes = new ArrayList<>();
    private final Map<Camera, Lane> byCamera = new IdentityHashMap<>();
    private int activeCount;
    private Sink sink;
    private RenderTarget target;
    /** lane 级调色的输出缓冲（有 lane 级调色时才创建；与 {@link #target} 同尺寸 / 同重建口径）。 */
    private RenderTarget adjustTarget;
    /** 渲染优化模组检测结果；{@code null} = 尚未检测。 */
    private Boolean renderOptimizerPresent;

    /** 当前正在渲染的 lane 的内容开关；lane pass 之外为 {@code null}（主画面不受内容开关影响）。 */
    private static LaneContent activeContent;

    /**
     * 是否正处在"主画面自己的描边"那一次 {@code doEntityOutline} 调用内（{@link #render} 在 lane 块
     * 开头发起）。lane pass 内外的区分用 {@link #activeContent}，主画面那次与渲染之后原版那次都在
     * lane pass 之外，只能靠这个标记区分——见 {@code LevelRendererMixin} 的描边屏蔽判定。
     */
    private static boolean mainOutlinePass;

    private LaneRenderer() {
    }

    // ===== lane 注册（由脚本 lane 驱动调用）=====

    /**
     * 取（必要时创建）第 {@code index} 个 lane 槽位。
     * 槽位与其相机实例跨帧复用——停用再激活不会换相机实例。
     */
    public Lane lane(int index) {
        while (lanes.size() <= index) {
            lanes.add(new Lane());
        }
        return lanes.get(index);
    }

    /**
     * 激活 / 更新一条 lane。
     *
     * @param state     lane 的相机状态；{@code null} = 停用该槽位
     * @param content   内容开关（{@code null} 视为 {@link LaneContent#WORLD_ONLY}）
     * @param adjust    lane 级调色参数（{@code null} = 无：渲染完直接进合成，不跑调色 pass）
     * @param captureId 该 lane 的相机 id（调试捕获用文件名口径，见 {@link LaneDebugCapture}；
     *                  {@code null} = 无 id，捕获侧回退 {@code lane<序号>}）
     */
    public void setLane(int index, CameraState state, LaneContent content, ColorAdjustParams adjust,
                        String captureId) {
        Lane lane = lane(index);
        if (state == null) {
            deactivate(lane);
            return;
        }
        lane.state = state;
        lane.content = content != null ? content : LaneContent.WORLD_ONLY;
        lane.adjust = adjust;
        lane.captureId = captureId;
        if (byCamera.put(lane.camera, lane) == null) {
            activeCount++;
        }
    }

    /** 停用全部 lane（保留槽位与相机实例）。生产者本帧没有 lane 时调用；无活跃 lane 时零开销。 */
    public void clear() {
        if (activeCount == 0) {
            return;
        }
        for (Lane lane : lanes) {
            deactivate(lane);
        }
    }

    private void deactivate(Lane lane) {
        if (lane.state != null) {
            lane.state = null;
            lane.adjust = null;
            lane.captureId = null;
            byCamera.remove(lane.camera);
            activeCount--;
        }
    }

    /** 设置合成回调（{@code null} = 不合成）。 */
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    // ===== 每帧驱动 =====

    /**
     * 渲染本帧的全部 lane（挂在 {@code GameRenderer.renderLevel} 的 RETURN：
     * 主画面渲染之后、{@code doEntityOutline} / {@code postEffect} / GUI 之前）。
     * 没有活跃 lane 时不做任何事。
     */
    public void render(Minecraft mc, float partialTick, long nanoTime) {
        if (activeCount == 0) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (isUnavailable()) {
            return;
        }

        RenderTarget main = mc.getMainRenderTarget();
        int width = main.width;
        int height = main.height;
        RenderTarget fbo = offscreenTarget(width, height);

        Matrix4f prevProjection = RenderSystem.getProjectionMatrix();
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        Matrix3f prevInverseViewRotation = RenderSystem.getInverseViewRotationMatrix();

        try {
            // 主画面自己的发光描边先落地：lane 会覆盖共享的 entityTarget，原版那次整屏调用已被屏蔽。
            // 这次调用必须放行（否则主画面的发光描边整帧丢失）——用标记把它和"渲染之后原版那次"分开
            // （两次都在 lane pass 之外，isRenderingLane() 区分不了），见 LevelRendererMixin 的判定。
            main.bindWrite(false);
            mainOutlinePass = true;
            try {
                mc.levelRenderer.doEntityOutline();
            } finally {
                mainOutlinePass = false;
            }

            // 调试捕获：推进帧计数、决定本帧是否读回（ICINEMATICS_CAPTURE 门控；关闭时零差异）
            LaneDebugCapture.beginFrame();

            Sink laneSink = this.sink;
            for (int i = 0; i < lanes.size(); i++) {
                Lane lane = lanes.get(i);
                if (lane.state == null) {
                    continue;
                }
                // 调试捕获用的相机 id：生产者没给时回退 lane<序号>（文件名安全化在 LaneDebugCapture 内做）
                String captureId = lane.captureId != null ? lane.captureId : "lane" + i;
                LaneOutput output = renderLane(mc, lane, captureId, fbo, main, partialTick, nanoTime);
                if (laneSink != null) {
                    // written = false（渲染失败 / 未写）：合成层守门会跳过该 lane，主画面保持基画面
                    laneSink.laneRendered(i, output.target(), output.written());
                }
            }
        } finally {
            activeContent = null;
            ((MinecraftAccessor) mc).ic$setMainRenderTarget(main);
            RenderSystem.setProjectionMatrix(prevProjection, prevSorting);
            RenderSystem.setInverseViewRotationMatrix(prevInverseViewRotation);
            main.bindWrite(false);
            RenderSystem.viewport(0, 0, width, height);
        }
    }

    /**
     * 一条 lane 的完整渲染（整尺寸进离屏缓冲）。
     *
     * <p><b>失败判定（fail-safe 直通）</b>：只在「lane 缓冲有颜色纹理 + FBO 完整 + 本次渲染全程无 GL 错误
     * （含异常）」时返回 {@code written = true}；否则 {@code written = false}（{@code target} 的内容不可用）
     * 并限频告警——合成层据此跳过该 lane，主画面保持基画面，绝不把未写 / 半写的画面铺上屏。
     * 检查全是状态查询（{@code glCheckFramebufferStatus} / {@code glGetError}），无读回 / 无同步等待。</p>
     *
     * @param captureId 该 lane 的相机 id（只用于调试捕获的文件名口径）
     * @return 交给合成层的画面纹理 + 是否可用：无 lane 级调色 = lane 自己的 FBO（{@code fbo}）；
     *         有 = 调色后的共享 {@link #adjustTarget(int, int)}
     */
    private LaneOutput renderLane(Minecraft mc, Lane lane, String captureId, RenderTarget fbo, RenderTarget main,
                                  float partialTick, long nanoTime) {
        Camera camera = lane.camera;
        CameraState state = lane.state;
        GameRenderer gameRenderer = mc.gameRenderer;
        GameRendererAccessor gameRendererAccessor = (GameRendererAccessor) gameRenderer;

        // 缓冲没有颜色纹理：采样只会得到黑（GL 不报错），渲染与合成都没有意义
        if (fbo.getColorTextureId() <= 0) {
            reportLaneFailure(captureId, "lane 缓冲没有颜色纹理（纹理名 = 0）", null);
            return new LaneOutput(fbo, false);
        }
        // 本 lane 的 GL 错误从零开始计：先清掉进入前的积压（别的模块 / 上一条 lane 留下的），
        // 之后产生的错误才算本 lane 的
        drainGlErrors();

        // 渲染期间让原版所有 getMainRenderTarget() 引用都落在该 lane 的 FBO 上（落地要点 ①）
        ((MinecraftAccessor) mc).ic$setMainRenderTarget(fbo);
        activeContent = lane.content;
        try {
            fbo.bindWrite(true);   // 绑定 FBO + 视口 = FBO 全尺寸
            // 目标不可写（附件丢失 / 重建失败 / 尺寸非法）→ 驱动会丢掉这次绘制、只留下未写内容：
            // 连画都不画，本 lane 直接判失败（合成层跳过，主画面保持基画面）
            if (GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                reportLaneFailure(captureId, "lane FBO 不完整（GL_FRAMEBUFFER_COMPLETE 校验未过）", null);
                return new LaneOutput(fbo, false);
            }

            // 我们的相机接管：Camera.setup → CameraMixin 的 lane 分支读该实例的 CameraState
            camera.setup(mc.level, mc.player,
                    !mc.options.getCameraType().isFirstPerson(),
                    mc.options.getCameraType().isMirrored(),
                    partialTick);

            // 视图矩阵：复刻 GameRenderer.renderLevel
            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
            poseStack.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
            float roll = state.roll();
            if (roll != 0.0F) {
                // 绕相机视线轴旋转：任何朝向下 roll>0 均为屏幕空间顺时针（画面向右倒）
                poseStack.mulPose(Axis.of(camera.getLookVector()).rotationDegrees(-roll));
            }
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(poseStack.last().normal()).invert());

            // 该 lane 自己的光学参数：走 GameRendererMixin 的 lane 分支（fov/zoom → 生效 FOV，含畸变保护）
            double fov = gameRendererAccessor.ic$getFov(camera, partialTick, true);
            Matrix4f projection = gameRendererAccessor.ic$getProjectionMatrix(fov);
            // 全局投影 = 该 lane 的投影（镜像原版 GameRenderer.resetProjectionMatrix 的语义与时机）：
            // LevelRenderer.renderLevel 自己不设全局投影矩阵（原版靠调用方设），而 lane 里所有走全局矩阵的
            // 绘制（实体 / 方块实体 / 粒子 / 太阳月亮 / 雨雪 / 世界边界）都吃它——不设就会吃到上一层 pass
            // 遗留的投影（lane 块开头那次 doEntityOutline → blitToScreen 留下的是正交矩阵），被裁掉。
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            Matrix4f cullProjection = gameRendererAccessor.ic$getProjectionMatrix(
                    Math.max(fov, (double) mc.options.fov().get()));
            Vec3 cameraPos = camera.getPosition();
            mc.levelRenderer.prepareCullFrustum(poseStack, cameraPos, cullProjection);
            // 可见集合按本 lane 的视锥强制刷一次：原版 applyFrustum 有朝向门闩（needsFrustumUpdate 或
            // floor(xRot/2)/floor(yRot/2) 变化才触发），同朝向桶的 lane 会沿用上一 pass 的可见集合
            // （= 别条 lane 的视锥过滤结果）→ 该 lane 画面缺块。这里在 renderLevel（→ setupRender）之前
            // 按原版口径刷一次：视锥与 prepareCullFrustum 同源（同 pose + 同剔除投影），再向外扩 8 格
            // 以包含相机所在区块（与 setupRender 里 new Frustum(frustum).offsetToFullyIncludeCameraCube(8)
            // 完全一致）。setupRender 若因门闩打开自己再刷一次，用的是同一份视锥，结果相同。
            Frustum laneFrustum = new Frustum(poseStack.last().pose(), cullProjection);
            laneFrustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);
            ((LevelRendererAccessor) mc.levelRenderer)
                    .ic$applyFrustum(laneFrustum.offsetToFullyIncludeCameraCube(8));

            // 该 lane 自己的视锥 + 相机姿态 → 原版 8 格膨胀剔除 3~5 步收敛，不需要自建剔除方案
            mc.levelRenderer.renderLevel(poseStack, partialTick, nanoTime, false, camera, gameRenderer,
                    gameRenderer.lightTexture(), projection);

            // lane 自包含（落地要点 ②）：renderLevel 里的后处理链已把描边合成进共享 entityTarget，
            // 此刻 lane 的 FBO 还绑着 → 立刻贴进本 lane 的画面
            mc.levelRenderer.doEntityOutline();
            // 落地要点 ③（出）：doEntityOutline → blitToScreen 会把全局投影改成正交矩阵，必须还原
            // （与进 renderLevel 之前那次 setProjectionMatrix 成对；这里还原成 lane 自己的投影）
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        } catch (RuntimeException e) {
            // 渲染中途抛异常 = 本 lane 的画面未写 / 半写：判失败（合成层跳过），别把半截画面铺上屏
            reportLaneFailure(captureId, "lane 渲染异常（画面未写 / 半写）", e);
            return new LaneOutput(fbo, false);
        } finally {
            activeContent = null;
            ((MinecraftAccessor) mc).ic$setMainRenderTarget(main);
        }

        // 施加全程无 GL 错误 = 本 lane 的画面确实被这次渲染写满（绘制报错时驱动会丢掉这次绘制，
        // 缓冲留下的内容不可信）；这里只查状态、不读回、不同步等待
        if (drainGlErrors()) {
            reportLaneFailure(captureId, "lane 渲染产生 GL 错误（绘制可能被驱动丢弃，画面不可信）", null);
            return new LaneOutput(fbo, false);
        }

        // 调试捕获 a（每相机 raw）：lane 渲染完成、lane 级调色之前，把该 lane 的离屏纹理原始 RGBA 读回写盘
        // （ICINEMATICS_CAPTURE 门控；关闭时第一行即返回——不读回、不分配、不切 GL 状态，零差异）。
        // 读的是 lane 的原始渲染结果（不含 lane 级调色、不含合成）。见 LaneDebugCapture。
        // 只捕获判失败之前的画面：失败的 lane 在上一行已经返回（未写的内容没有捕获价值）。
        LaneDebugCapture.onLaneRendered(captureId, fbo);

        // lane 级调色（来自该 lane 的相机片段）：lane 渲染完成之后、合成之前，把该 lane 的画面
        // 过一次调色 pass（只动 RGB、alpha 直通；着色器与 uniform 上传与 master 共用同一份实现）。
        // 结果写进共享 adjustTarget（尺寸跟随窗口，与 offscreenTarget 同处理），合成读它而不是 lane FBO。
        // 无需调整（片段没写调色 / 参数恒等 / 着色器不可用）→ 原样返回 lane FBO，行为与不带该功能完全一致。
        // 调色自身失败（applyTo 返回 false）→ 原样返回 lane FBO：lane 画面有效，只是没调色，仍可上屏。
        ColorAdjustParams adjust = lane.adjust;
        if (adjust != null && !adjust.isIdentity()) {
            RenderTarget adjusted = adjustTarget(fbo.width, fbo.height);
            if (ColorAdjustPass.applyTo(fbo, adjusted, adjust, mc)) {
                return new LaneOutput(adjusted, true);
            }
        }
        return new LaneOutput(fbo, true);
    }

    /** 一条 lane 本帧的渲染结果：{@code written = false} = 渲染失败 / 未写（{@code target} 不可用）。 */
    private record LaneOutput(RenderTarget target, boolean written) {
    }

    /**
     * 清空 / 读取 GL 错误队列（至多 {@link #MAX_DRAINED_ERRORS} 条，防病态驱动下死循环）。
     *
     * <p>用法固定成对：{@link #renderLane} 进入时先调一次「清积压」（此前别的模块 / 上一条 lane 留下的
     * 错误不归本 lane），渲染后再调一次——这次读到错误 = 本 lane 渲染失败。</p>
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
     * lane 渲染失败的限频告警（失败逐帧复现，同一失败点 {@link #FAILURE_LOG_INTERVAL_MS} 一条，
     * 窗口内的次数合并到下一次输出里——不刷屏，也不丢「一直在失败」这个事实）。
     *
     * @param captureId 该 lane 的相机 id（合成层定位用；{@code null} 原样打印）
     * @param what      失败描述
     * @param t         异常（无异常传 {@code null}）
     */
    private static void reportLaneFailure(String captureId, String what, Throwable t) {
        String message = "lane 渲染失败（该 lane 本帧不上屏，主画面保持基画面）：相机 " + captureId + " — " + what;
        ErrorLog.logRateLimited("Render", "lane-render-failure", FAILURE_LOG_INTERVAL_MS, message, t);
    }

    /**
     * lane 级调色的输出缓冲（按主画面尺寸创建 / 重建；只在渲染线程调用）。
     * <p>不需要深度附件（只画一个全屏 quad、且深度测试关闭）；过滤 LINEAR——合成层会缩放 / 取局部，
     * 与 {@link #offscreenTarget} 同口径。</p>
     */
    private RenderTarget adjustTarget(int width, int height) {
        if (adjustTarget == null) {
            adjustTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            adjustTarget.setFilterMode(GL11.GL_LINEAR);
        } else if (adjustTarget.width != width || adjustTarget.height != height) {
            adjustTarget.resize(width, height, Minecraft.ON_OSX);
            adjustTarget.setFilterMode(GL11.GL_LINEAR);   // resize 重建缓冲会把过滤重置回 NEAREST
        }
        return adjustTarget;
    }

    /** 共用的离屏缓冲（按主画面尺寸创建 / 重建；只在渲染线程调用）。 */
    private RenderTarget offscreenTarget(int width, int height) {
        if (target == null) {
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);   // 合成层要缩放 / 取局部，缓冲默认 NEAREST 会锯齿
        } else if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);   // resize 重建缓冲会把过滤重置回 NEAREST
        }
        return target;
    }

    // ===== 兼容：渲染优化模组 =====

    /** lane 渲染是否因渲染优化模组（Sodium / Embeddium）而不可用。 */
    public boolean isUnavailable() {
        if (renderOptimizerPresent == null) {
            String marker = findRenderOptimizer();
            renderOptimizerPresent = marker != null;
            if (marker != null) {
                LOGGER.warn("[lane] 检测到渲染优化模组（{}）：多 lane 副画面渲染已禁用"
                        + "（它们 @Overwrite 原版区块渲染与 setupRender）。关闭该模组后重启即可使用多相机画面。", marker);
            }
        }
        return renderOptimizerPresent;
    }

    private static String findRenderOptimizer() {
        ClassLoader loader = LaneRenderer.class.getClassLoader();
        for (String marker : RENDER_OPTIMIZER_MARKERS) {
            try {
                Class.forName(marker, false, loader);
                return marker;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // 不存在 → 继续探测下一个
            }
        }
        return null;
    }

    // ===== Mixin 接入点 =====

    /** {@code Camera} 实例 → 其 lane 槽位；不是 lane 相机则 {@code null}。 */
    public static Lane laneOf(Camera camera) {
        LaneRenderer renderer = INSTANCE;
        return renderer.activeCount == 0 ? null : renderer.byCamera.get(camera);
    }

    /**
     * 本帧<b>最上层</b> lane（lane 表里序号最大且有状态的槽位）；无活跃 lane 时为 {@code null}。
     * <p>视图中心（{@code LevelRendererMixin}）取它的相机位置——可见区块网格（{@code ViewArea} +
     * {@code renderChunkStorage}）是<b>单份共享状态</b>，<b>整帧只能有一个中心</b>：
     * 逐 pass 用不同中心会让 {@code ViewArea.repositionCamera} 在每个 pass 把整张网格搬到新位置
     * （{@code RenderChunk.setOrigin} → {@code reset()} → 区块全部置脏重建），一帧内主 pass + N 个 lane
     * 反复来回搬 = 持续重建、画面缺块。所以取「玩家看到的最上层画面」的那台相机做整帧中心
     * （与退役前"以顶层实例相机为中心"逐点等价）。
     * <p>主 pass 读到的是<b>本帧</b>的 lane 表：帧驱动 + lane 注册（{@code CameraManager.onRenderFrame()} /
     * {@code ScriptLaneDriver.tick}）挂在 {@code GameRenderer.render} 的 HEAD（世界渲染之前，见
     * {@code GameRendererMixin.onRenderFrameStart}），中心与画面同帧，无滞后。</p>
     */
    public Lane topLane() {
        for (int i = lanes.size() - 1; i >= 0; i--) {
            Lane lane = lanes.get(i);
            if (lane.state != null) {
                return lane;
            }
        }
        return null;
    }

    /** 是否正在某个 lane 的 pass 内（lane 自包含分流 + 内容开关判定用）。 */
    public static boolean isRenderingLane() {
        return activeContent != null;
    }

    /**
     * 是否正在"主画面自己的描边"那一次 {@code doEntityOutline} 调用内（{@link #render} 在 lane 块开头
     * 发起）。原版渲染之后那次整屏描边要被屏蔽，这一次必须放行——见 {@code LevelRendererMixin}。
     */
    public static boolean isMainOutlinePass() {
        return mainOutlinePass;
    }

    /** 本帧是否有活跃 lane（原版整屏描边是否要让位给 lane 内描边）。 */
    public boolean hasActiveLanes() {
        return activeCount > 0;
    }

    /** 当前 pass 是否要画天空：非 lane pass 恒真（主画面不受内容开关影响）。 */
    public static boolean shouldRenderSky() {
        LaneContent content = activeContent;
        return content == null || content.sky();
    }

    /** 当前 pass 是否要画天气（雨 / 雪）。 */
    public static boolean shouldRenderWeather() {
        LaneContent content = activeContent;
        return content == null || content.weather();
    }

    /** 当前 pass 是否要画实体（{@code LevelRenderer.renderEntity}：生物 / 玩家 / 掉落物 / 展示框…）。 */
    public static boolean shouldRenderEntities() {
        LaneContent content = activeContent;
        return content == null || content.entities();
    }

    /** 当前 pass 是否要画粒子。 */
    public static boolean shouldRenderParticles() {
        LaneContent content = activeContent;
        return content == null || content.particles();
    }
}
