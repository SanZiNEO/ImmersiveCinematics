package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.immersivecinematics.immersive_cinematics.mixin.GameRendererAccessor;
import com.immersivecinematics.immersive_cinematics.mixin.MinecraftAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
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
 *   → renderLevel → doEntityOutline（lane 内描边）→ 还原全局投影
 *   → 恢复 mainRenderTarget → 交给 {@link Sink}（合成层）
 * </pre>
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
        void laneRendered(int index, RenderTarget target);
    }

    /** 一个 lane 槽位：独立原版 {@link Camera} 实例 + 本帧相机状态 + 内容开关。 */
    public static final class Lane {

        private final Camera camera = new Camera();
        private CameraState state;
        private LaneContent content = LaneContent.WORLD_ONLY;

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
    }

    private final List<Lane> lanes = new ArrayList<>();
    private final Map<Camera, Lane> byCamera = new IdentityHashMap<>();
    private int activeCount;
    private Sink sink;
    private RenderTarget target;
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

    // ===== lane 注册（由并行播放 / 调试驱动调用）=====

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
     * @param state   lane 的相机状态；{@code null} = 停用该槽位
     * @param content 内容开关（{@code null} 视为 {@link LaneContent#WORLD_ONLY}）
     */
    public void setLane(int index, CameraState state, LaneContent content) {
        Lane lane = lane(index);
        if (state == null) {
            deactivate(lane);
            return;
        }
        lane.state = state;
        lane.content = content != null ? content : LaneContent.WORLD_ONLY;
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

            Sink laneSink = this.sink;
            for (int i = 0; i < lanes.size(); i++) {
                Lane lane = lanes.get(i);
                if (lane.state == null) {
                    continue;
                }
                renderLane(mc, lane, fbo, main, partialTick, nanoTime);
                // 调试钩子：合成之前把该 lane 的离屏纹理原始 RGBA 读回写盘（ICINEMATICS_QUADRANT 门控；
                // 开关外第一行即返回——不读回、不分配、不切 GL 状态，零差异）。见 LaneDebugCapture。
                LaneDebugCapture.onLaneRendered(i, fbo);
                if (laneSink != null) {
                    laneSink.laneRendered(i, fbo);
                }
            }
            // 调试钩子：本帧全部 lane 合成完之后，把最终屏幕画面读回写盘（ICINEMATICS_QUADRANT 门控；
            // 开关外第一行即返回——零差异）。见 LaneDebugCapture.onFrameComposed。
            LaneDebugCapture.onFrameComposed(main);
        } finally {
            activeContent = null;
            ((MinecraftAccessor) mc).ic$setMainRenderTarget(main);
            RenderSystem.setProjectionMatrix(prevProjection, prevSorting);
            RenderSystem.setInverseViewRotationMatrix(prevInverseViewRotation);
            main.bindWrite(false);
            RenderSystem.viewport(0, 0, width, height);
        }
    }

    /** 一条 lane 的完整渲染（整尺寸进离屏缓冲）。 */
    private void renderLane(Minecraft mc, Lane lane, RenderTarget fbo, RenderTarget main,
                            float partialTick, long nanoTime) {
        Camera camera = lane.camera;
        CameraState state = lane.state;
        GameRenderer gameRenderer = mc.gameRenderer;
        GameRendererAccessor gameRendererAccessor = (GameRendererAccessor) gameRenderer;

        // 渲染期间让原版所有 getMainRenderTarget() 引用都落在该 lane 的 FBO 上（落地要点 ①）
        ((MinecraftAccessor) mc).ic$setMainRenderTarget(fbo);
        activeContent = lane.content;
        try {
            fbo.bindWrite(true);   // 绑定 FBO + 视口 = FBO 全尺寸

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
            mc.levelRenderer.prepareCullFrustum(poseStack, camera.getPosition(), cullProjection);

            // 该 lane 自己的视锥 + 相机姿态 → 原版 8 格膨胀剔除 3~5 步收敛，不需要自建剔除方案
            mc.levelRenderer.renderLevel(poseStack, partialTick, nanoTime, false, camera, gameRenderer,
                    gameRenderer.lightTexture(), projection);

            // lane 自包含（落地要点 ②）：renderLevel 里的后处理链已把描边合成进共享 entityTarget，
            // 此刻 lane 的 FBO 还绑着 → 立刻贴进本 lane 的画面
            mc.levelRenderer.doEntityOutline();
            // 落地要点 ③（出）：doEntityOutline → blitToScreen 会把全局投影改成正交矩阵，必须还原
            // （与进 renderLevel 之前那次 setProjectionMatrix 成对；这里还原成 lane 自己的投影）
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        } finally {
            activeContent = null;
            ((MinecraftAccessor) mc).ic$setMainRenderTarget(main);
        }
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
     * <p>主 pass（lane 注册之前）读到的是<b>上一帧</b>的 lane 表（{@code ScriptLaneDriver.tick} 在
     * 主 pass 之后才清空重填），即中心最多滞后一帧——与退役前读上一帧快照同源同滞后。
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

    /** 本帧是否有任一 lane 相机处在实心方块里（遮挡剔除的整帧统一决策用，见 {@code CinematicOcclusion}）。 */
    public boolean isAnyCameraInsideSolidBlock(Minecraft mc) {
        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            if (lane.state == null) {
                continue;
            }
            BlockPos pos = lane.camera.getBlockPosition();
            if (mc.level.getBlockState(pos).isSolidRender(mc.level, pos)) {
                return true;
            }
        }
        return false;
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
