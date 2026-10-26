package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionView;
import com.immersivecinematics.immersive_cinematics.client.dimension.DimensionViews;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustPass;
import com.immersivecinematics.immersive_cinematics.mixin.GameRendererAccessor;
import com.immersivecinematics.immersive_cinematics.mixin.MinecraftAccessor;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 lane 渲染底层：每个 lane 一个独立相机姿态 + 内容开关，复用原版 {@code LevelRenderer.renderLevel}
 * 整尺寸渲进共用的离屏缓冲（顺序复用）；GUI / 手 / HUD 在 {@code LevelRenderer.renderLevel} 之外，副画面天然不含。
 * 每 lane 流程：{@code mainRenderTarget} 临时指向 lane FBO → 相机 / 该 lane 的投影 / 视锥 → renderLevel
 * → doEntityOutline（lane 内描边）→ 恢复 mainRenderTarget → lane 级调色（可选）→ 交给 {@link Sink} 合成。
 * lane 级调色顺序固定：lane 渲染 → lane 调色 → 合成（opacity / dest / source）→ 全部 lane 完成后 → master 调色。
 * alpha 契约：lane 级调色只动 RGB、alpha 逐位直通（定义点见 {@code ic_color_adjust.fsh}）。
 * 参数为空 / 恒等 / 着色器不可用时不跑调色，交合成的仍是 lane FBO——默认零差异。
 * 状态：lane 块入口保存、出口还原全局投影 + VertexSorting、逆视图旋转矩阵、mainRenderTarget 与绑定 / 视口。
 * 性能：共用一张离屏缓冲（按主画面尺寸创建 / 跟随窗口重建），显存不随 lane 数增长，成本 ≈ 每 lane 一遍世界渲染。
 * 兼容：不按第三方渲染模组的存在性分支，原版与优化模组环境都照常渲染；
 * 只有本 lane 渲染本身失败时才按 {@code written = false} 跳过（合成层守门）。
 * 状态所有权：雾 / 深度 / 混合状态由 {@code renderLevel} 自身收敛，视锥每帧重建——这些不需要额外还原。
 * 跨维度：lane 的片段 {@code dimension} 非空且不是玩家当前维度时，该 lane 的 pass 内临时把全局
 * {@code Minecraft.level} / {@code levelRenderer} / 两个渲染分发器指向目标视图，并把
 * {@code GameRenderer.lightTexture} 字段换成目标视图的 {@code LightTexture}（{@link DimensionView}，
 * 独立 {@code ClientLevel} + {@code LevelRenderer} + {@code LightTexture}），pass 结束在 finally 还原；
 * 玩家本身不跨维度，玩家自身视角继续用玩家当前世界。空串 / 当前维度 = {@link DimensionViews#MAIN}，逐位保持原行为。
 */
public final class LaneRenderer {

    /** 一次失败判定的 GL 错误排空上限（防病态驱动下死循环；超过就按「有错误」处理）。 */
    private static final int MAX_DRAINED_ERRORS = 16;

    /** lane 渲染失败的限频间隔（毫秒）：失败逐帧复现，不限频会刷屏。 */
    private static final long FAILURE_LOG_INTERVAL_MS = 5000L;

    /** 全局单例接入点（渲染线程访问）。 */
    public static final LaneRenderer INSTANCE = new LaneRenderer();

    /** 光影模组存在性（Iris / Oculus 都提供 {@code IrisApi}）：只用于诊断，不做任何注入。 */
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    /**
     * Iris {@code MixinRenderTarget} 加在 {@link RenderTarget} 上的私有 int：深度重挂版本。
     * {@code RenderTargets.resizeIfNeeded} 只在它与 Iris 缓存不同时重挂深度纹理。
     */
    private static final String IRIS_DEPTH_VERSION_FIELD = "iris$depthBufferVersion";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 反射缓存的字段；{@code null} = 无光影 / 字段不存在 / 反射失败（之后每次静默跳过）。只解析一次。 */
    private static Field irisDepthVersionField;
    /** 字段是否已尝试解析（无论成败都只尝试一次）。 */
    private static boolean irisDepthVersionResolved;
    /** 首次成功递增时打一行 INFO（只一次，不按 lane 刷屏）。 */
    private static boolean irisDepthVersionBumpLogged;

    /**
     * lane 内容开关。默认全关（{@link #WORLD_ONLY}）——副画面只要世界地形；
     * 实体 / 粒子 / 天空 / 天气按需打开。
     */
    public record LaneContent(boolean entities, boolean particles, boolean sky, boolean weather) {

        /** 只有地形（+ 雾色背景）：默认档。 */
        public static final LaneContent WORLD_ONLY = new LaneContent(false, false, false, false);

        /** 全开：与主画面同内容（调试 / 压测用）。 */
        public static final LaneContent FULL = new LaneContent(true, true, true, true);
    }

    /**
     * 一条活跃 lane 的非空维度视图快照（只读）：客户端 tick 据此向服务端上报「该维度需要在相机附近
     * 流式加载区块 / 实体」。只包含 {@code dimension} 非空的 lane（空串 = 玩家当前维度，走原版连接）。
     *
     * @param viewId    视图 id：有租约 = {@link CameraLease#viewKey()}（槽位 + 代际 + 维度）；
     *                  无租约 = {@code captureId@dimension}（captureId 缺失时回退 {@code lane<序号>}）
     * @param dimension 该 lane 声明的维度 id（{@code namespace:path}；调用方按需排除玩家当前维度）
     * @param position  该 lane 本帧的相机眼位
     */
    public record LaneView(String viewId, String dimension, Vec3 position) {}

    /**
     * 每条 lane 渲染完成后的回调——合成层在这里把画面贴到屏幕（实现见 {@link LaneCompositor}）。
     * 必须当帧消费：所有 lane 共用同一张离屏缓冲，回调返回后缓冲会被下一条 lane 覆盖。
     */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param index   该 lane 的序号（合成顺序）
         * @param target  该 lane 本帧的画面（合成层取其颜色纹理上屏）
         * @param written 本帧是否被写满；{@code false} = 渲染失败 / 未写，合成层必须跳过该 lane
         */
        void laneRendered(int index, RenderTarget target, boolean written);
    }

    /** 一个 lane 槽位：独立原版 {@link Camera} 实例 + 本帧相机状态 + 内容开关 + lane 级调色 + 渲染维度 + 调试用相机 id。 */
    public static final class Lane {

        private final Camera camera = new Camera();
        private CameraState state;
        private LaneContent content = LaneContent.WORLD_ONLY;
        private ColorAdjustParams adjust;
        private String dimension;
        private String captureId;
        /** 本帧该 lane 的视图：{@link DimensionViews#MAIN} = 玩家当前维度；{@code null} = 目标视图不可用。 */
        private DimensionView view;
        /** 本帧视图解析失败（{@link DimensionViews#acquire} 返回 {@code null}）：渲染时按失败跳过该 lane。 */
        private boolean viewFailed;
        /** 本帧视角作用域键：有租约 = {@link CameraLease#viewKey()}；回退 = {@code captureId}（目标视图带 {@code @维度}）。 */
        private String viewKey;
        /** 本帧相机租约（片段级身份 + 剔除槽）；{@code null} = 无租约（链输出等回退路径）。 */
        private CameraLease lease;
        /** 无租约时的回退剔除槽（惰性创建、跨帧持有、停播归还池）；有租约时由租约持有。 */
        private LaneCullSlot cull;

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
         * 本帧该 lane 的渲染维度（片段 {@code dimension}）；空串 / {@code null} = 玩家当前维度。
         * 非空 = 从该维度渲染这条 lane（世界 / 实体数据由服务端流式提供），玩家本身不跨维度。
         */
        public String dimension() {
            return dimension;
        }

        /**
         * 本帧该 lane 的相机 id（调试捕获用文件名口径，见 {@link LaneDebugCapture}）；
         * {@code null} = 生产者没给 id，捕获侧回退 {@code lane<序号>}。
         */
        public String captureId() {
            return captureId;
        }

        /**
         * 本帧的 lane 级调色参数（来自该 lane 的相机片段）；{@code null} = 无，渲染完直接进合成，不跑调色 pass。
         */
        public ColorAdjustParams adjust() {
            return adjust;
        }

        /** 本帧该 lane 的视图（{@link DimensionViews#MAIN} = 玩家当前维度）；未解析 / 解析失败时为 {@code null}。 */
        public DimensionView view() {
            return view;
        }

        /** 本帧视图解析是否失败（目标视图不可用：FABULOUS / 维度缺失 / 非法 id）；诊断用。 */
        public boolean viewFailed() {
            return viewFailed;
        }
    }

    private final List<Lane> lanes = new ArrayList<>();
    private final Map<Camera, Lane> byCamera = new IdentityHashMap<>();
    private int activeCount;
    private Sink sink;
    private RenderTarget target;
    /** lane 级调色的输出缓冲（有 lane 级调色时才创建；与 {@link #target} 同尺寸 / 同重建口径）。 */
    private RenderTarget adjustTarget;
    /** 光影模组（Iris / Oculus）存在性；{@code null} = 未检测。只用于诊断。 */
    private Boolean shaderModPresent;

    /** 当前正在渲染的 lane 的内容开关；lane pass 之外为 {@code null}（主画面不受内容开关影响）。 */
    private static LaneContent activeContent;

    /**
     * 当前正在渲染的 lane 的相机；lane pass 之外为 {@code null}。
     * {@code GameRenderer.getMainCamera()} 在 lane pass 内返回它（不写 {@code mainCamera} 字段），
     * 让 Iris 与原版通过 getter 读相机的代码都拿到 lane 视角。
     */
    private static Camera activeLaneCamera;

    /** 当前 lane 的调试 id（阶段探针文件命名用）；lane pass 之外为 {@code null}。 */
    private static String activeLaneCaptureId;

    /**
     * 当前 lane 的序号；lane pass 之外为 {@code -1}。诊断用：同一条 CAMERA 轨道的上下两个 lane 共用
     * {@link #activeLaneCaptureId}，只有序号能区分。
     */
    private static int activeLaneIndex = -1;

    /** 当前 lane 的相机状态（阶段探针元数据用）；lane pass 之外为 {@code null}。 */
    private static CameraState activeLaneState;

    /**
     * 诊断用的内容档覆盖（性能对比 FULL / WORLD_ONLY）：{@code null} = 用生产者传入的正式档
     * （正式路径恒 {@code FULL}）；只由诊断 manifest 用例设置 / 复位，不改变生产默认。
     */
    private static LaneContent diagnosticContentOverride;

    /**
     * 是否正处在主画面自己那一次 {@code doEntityOutline} 调用内：lane pass 内外用 {@link #activeContent} 区分，
     * 主画面那次与渲染之后原版那次都在 lane pass 之外，只能靠本标记区分（见 {@code LevelRendererMixin}）。
     */
    private static boolean mainOutlinePass;

    /**
     * 本帧实际换入剔除状态的 bridge：跨维度 lane 用的是目标视图的 {@code LevelRenderer}，与
     * {@code mc.levelRenderer}（pass 结束后已还原成主渲染器）不是同一个实例；异常兜底只能弹它。
     */
    private static LaneCullBridge activeCullBridge;

    /**
     * 是否正处在逐 lane 渲染循环内（第一条 lane 的 {@code ViewScope.enter} 之前置位、循环结束复位）。
     * {@code ViewScope.enter} 据此跳过光影 pipeline 刷新：刷新会清空视角栈，把本帧已配对的 lane 作用域打散。
     */
    private static boolean laneLoopActive;

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
     * 激活 / 更新一条 lane；{@code state == null} = 停用该槽位。
     *
     * @param content   内容开关（{@code null} 视为 {@link LaneContent#WORLD_ONLY}）
     * @param adjust    lane 级调色参数（{@code null} = 无：渲染完直接进合成，不跑调色 pass）
     * @param dimension 该 lane 的渲染维度（片段 {@code dimension}；空串 / {@code null} = 玩家当前维度，
     *                  非空 = 从该维度渲染这条 lane，玩家本身不跨维度）
     * @param captureId 相机 id（调试捕获文件名口径；{@code null} = 回退 {@code lane<序号>}）
     * @param lease     该 lane 的相机租约（片段级视角身份 + 剔除槽）；{@code null} = 无租约，走旧回退路径
     */
    public void setLane(int index, CameraState state, LaneContent content, ColorAdjustParams adjust,
                        String dimension, String captureId, CameraLease lease) {
        Lane lane = lane(index);
        if (state == null) {
            deactivate(lane);
            return;
        }
        // 槽位从「无租约回退」切到「有租约」：旧回退剔除槽立即归还，避免两套槽同时占着。
        // 租约槽不在这里归还：它归 CameraLeasePool 所有，deactivate 时已断开引用。
        if (lease != null && lane.lease == null && lane.cull != null) {
            LaneCullSlots.release(lane.cull);
            lane.cull = null;
        }
        lane.state = state;
        LaneContent override = diagnosticContentOverride;
        lane.content = override != null ? override
                : (content != null ? content : LaneContent.WORLD_ONLY);
        lane.adjust = adjust;
        lane.dimension = dimension;
        lane.captureId = captureId;
        lane.lease = lease;
        if (byCamera.put(lane.camera, lane) == null) {
            activeCount++;
        }
    }

    /**
     * 诊断用例的内容档覆盖（性能对比 FULL / WORLD_ONLY）：{@code null} = 恢复正常生产者档。
     * 只应在诊断 manifest 用例的起止设置 / 复位；生产路径恒为 {@code null}。
     */
    public static void setDiagnosticContentOverride(LaneContent content) {
        diagnosticContentOverride = content;
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
            lane.dimension = null;
            lane.captureId = null;
            lane.view = null;
            lane.viewFailed = false;
            // 视角身份随租约走：停用即清，下一帧 prepareViews 按新租约重算
            lane.viewKey = null;
            // 租约槽归 CameraLeasePool：这里只断开引用，由租约归还时统一 release，避免双重归还
            if (lane.lease != null) {
                lane.cull = null;
            }
            lane.lease = null;
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
     * 渲染本帧的全部 lane（挂在 {@code GameRenderer.renderLevel} 的 RETURN：主画面渲染之后、
     * 原版描边 / 后处理 / GUI 之前）。没有活跃 lane 时不做任何事。
     */
    public void render(Minecraft mc, float partialTick, long nanoTime) {
        if (activeCount == 0) {
            releaseAllCullSlots();
            return;
        }
        if (mc.level == null || mc.player == null) {
            releaseAllCullSlots();
            return;
        }

        // 逐 lane 解析视图（空串 / 当前维度 = MAIN 哨兵，零分配；跨维度 = 目标视图或失败）。
        // 解析失败只记在本帧 lane 上：渲染时按失败跳过该 lane，不影响其他 lane 与主画面。
        prepareViews(mc);

        RenderTarget main = mc.getMainRenderTarget();
        int width = main.width;
        int height = main.height;
        RenderTarget fbo;

        Matrix4f prevProjection = RenderSystem.getProjectionMatrix();
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        Matrix3f prevInverseViewRotation = RenderSystem.getInverseViewRotationMatrix();
        GameRendererAccessor gameRendererAccessor = (GameRendererAccessor) mc.gameRenderer;
        boolean prevRenderHand = gameRendererAccessor.ic$getRenderHand();
        int currentLane = -1;
        String currentCaptureId = null;

        try {
            fbo = offscreenTarget(width, height);
            // 主画面自己的描边必须先落地：lane 会覆盖共享 entityTarget，原版渲染之后那次已被屏蔽；
            // 用标记与本类区分（两次都在 lane pass 之外，isRenderingLane() 区分不了）。
            main.bindWrite(false);
            mainOutlinePass = true;
            try {
                mc.levelRenderer.doEntityOutline();
            } finally {
                mainOutlinePass = false;
            }

            // 调试捕获：推进帧计数、判定本帧是否读回（ICINEMATICS_CAPTURE 门控；关闭时零差异）
            LaneDebugCapture.beginFrame();

            // 关 renderHand 只关 Iris 的手（原版手本就在 renderLevel 之外）；整块关一次、块末还原。
            mc.gameRenderer.setRenderHand(false);

            Sink laneSink = this.sink;
            // lane 循环内不刷新光影 pipeline（ViewScope.enter 据此跳过 ShaderCompat.refresh）
            laneLoopActive = true;
            for (int i = 0; i < lanes.size(); i++) {
                Lane lane = lanes.get(i);
                if (lane.state == null) {
                    releaseCullSlot(lane);
                    continue;
                }
                // 调试捕获的相机 id：生产者没给时回退 lane<序号>（文件名安全化在 LaneDebugCapture 内做）
                String captureId = lane.captureId != null ? lane.captureId : "lane" + i;
                currentLane = i;
                activeLaneIndex = i;
                currentCaptureId = captureId;
                // 诊断口径 = 玩家当前有效视距（lane 无独立视距覆盖）
                int diagnosticDistance = mc.options.getEffectiveRenderDistance();
                // 视角作用域键：目标视图带 @维度 后缀，同名相机的不同维度不互相覆盖历史快照
                String viewKey = lane.viewKey != null ? lane.viewKey : captureId;
                ViewScope.enter(viewKey);
                try {
                    // disabled 时 beginLane 返回 0、零分配 / 零 nanoTime
                    long laneStart = LaneDiagnostics.beginLane(i, captureId, diagnosticDistance, fbo);
                    LaneOutput output;
                    try {
                        output = renderLane(mc, lane, captureId, fbo, main, partialTick, nanoTime);
                    } catch (Throwable t) {
                        LaneDiagnostics.endLane(i, laneStart, false);
                        throw t;
                    }
                    LaneDiagnostics.endLane(i, laneStart, output.written());
                    if (laneSink != null) {
                        // 失败（written=false）：合成层守门跳过该 lane，主画面保持基画面
                        long compositeStart = LaneDiagnostics.beginComposite(i);
                        try {
                            laneSink.laneRendered(i, output.target(), output.written());
                        } finally {
                            LaneDiagnostics.endComposite(i, compositeStart);
                        }
                    }
                } finally {
                    ViewScope.exit(viewKey);
                }
            }
        } catch (Exception e) {
            FatalErrors.rethrowIfFatal(e);
            ErrorLog.logRateLimited("Render", "lane-render", FAILURE_LOG_INTERVAL_MS,
                    "lane 渲染循环异常（lane=" + currentLane + ", capture=" + currentCaptureId
                            + ", lanes=" + lanes.size() + "）：本帧停止后续 lane，主画面保持基画面", e);
        } finally {
            laneLoopActive = false;
            activeContent = null;
            activeLaneCamera = null;
            activeLaneCaptureId = null;
            activeLaneIndex = -1;
            activeLaneState = null;
            // 还原 lane pass 期间被临时改写的全局状态
            mc.gameRenderer.setRenderHand(prevRenderHand);
            ((MinecraftAccessor) mc).ic$setMainRenderTarget(main);
            RenderSystem.setProjectionMatrix(prevProjection, prevSorting);
            RenderSystem.setInverseViewRotationMatrix(prevInverseViewRotation);
            main.bindWrite(false);
            RenderSystem.viewport(0, 0, width, height);
            // 异常兜底：弹的是本帧实际换入过的 bridge（跨维度 lane 的目标渲染器与 mc.levelRenderer 不同），
            // 保证槽全部弹净、字段回到原版值（正常路径已在各自 finally 配对弹出）
            LaneCullBridge cullBridge = activeCullBridge;
            if (cullBridge != null) {
                activeCullBridge = null;
                cullBridge.ic$popAllLaneCullStates();
            }
        }
    }

    /**
     * 逐 lane 解析本帧视图并缓存到 lane 上：空串 / 玩家当前维度 = {@link DimensionViews#MAIN}（零分配、零切换）；
     * 其他维度 = 目标视图（首次创建）或 {@code null}（本帧该 lane 跳过）。
     * 目标视图的区块缓存中心按该 lane 相机逐帧推进——视图中心是单份共享状态，不同维度各有一份。
     */
    private void prepareViews(Minecraft mc) {
        int viewDistance = mc.options.getEffectiveRenderDistance();
        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            if (lane.state == null) {
                continue;
            }
            DimensionView view;
            try {
                view = DimensionViews.acquire(mc, lane.dimension());
            } catch (Exception e) {
                // 解析在渲染边界内：单条 lane 的异常不得打断整帧（该 lane 本帧跳过，其他 lane 照常）
                FatalErrors.rethrowIfFatal(e);
                ErrorLog.logRateLimited("Render", "dimension-acquire", FAILURE_LOG_INTERVAL_MS,
                        "跨维度视图解析异常（lane=" + i + "，维度=" + lane.dimension() + "）：该 lane 本帧跳过", e);
                view = null;
            }
            lane.view = view;
            lane.viewFailed = view == null;
            String captureId = lane.captureId != null ? lane.captureId : "lane" + i;
            // 有租约：视角键 = 槽位 + 代际（+ 维度），片段结束即失效；无租约：沿用 captureId 回退
            lane.viewKey = lane.lease != null ? lane.lease.viewKey() : viewKey(lane, captureId);
            if (view != null && !view.isMain()) {
                view.updateViewCenter(lane.state.position(), viewDistance);
            }
        }
    }

    /** 视角作用域键：目标视图追加 {@code @维度}（同名相机在不同维度各自保存一份历史快照）。 */
    private static String viewKey(Lane lane, String captureId) {
        DimensionView view = lane.view;
        return view == null || view.isMain() ? captureId : captureId + "@" + lane.dimension();
    }

    /** 该 lane 的剔除槽：有租约 = 租约独占槽；无租约 = 惰性创建的回退槽。没有 bridge 时不会被调用。 */
    private static LaneCullSlot cullSlot(Lane lane) {
        if (lane.lease != null) {
            return lane.lease.cull();
        }
        LaneCullSlot slot = lane.cull;
        if (slot == null) {
            slot = LaneCullSlots.acquire();
            lane.cull = slot;
        }
        return slot;
    }

    /** 停播归还回退槽（有租约时槽位归租约池，由 {@code CameraLeasePool.release} 归还）。 */
    private static void releaseCullSlot(Lane lane) {
        if (lane.lease != null) {
            return;
        }
        LaneCullSlot slot = lane.cull;
        if (slot != null) {
            lane.cull = null;
            LaneCullSlots.release(slot);
        }
    }

    /** 停播 / 世界卸载：归还全部 lane 的剔除槽。 */
    private void releaseAllCullSlots() {
        for (int i = 0; i < lanes.size(); i++) {
            releaseCullSlot(lanes.get(i));
        }
    }

    /**
     * 一条 lane 的完整渲染（整尺寸进离屏缓冲）。
     * 失败判定（fail-safe 直通）：只在「缓冲有颜色纹理 + FBO 完整 + 全程无 GL 错误（含异常）」时返回
     * {@code written = true}；否则 {@code written = false}（{@code target} 内容不可用）并限频告警——
     * 合成层据此跳过该 lane，主画面保持基画面。检查全是状态查询，无读回 / 无同步等待。
     *
     * @return 交给合成层的画面：无 lane 级调色 = {@code fbo}；有 = 调色后的 {@link #adjustTarget(int, int)}
     */
    private LaneOutput renderLane(Minecraft mc, Lane lane, String captureId,
                                  RenderTarget fbo, RenderTarget main, float partialTick, long nanoTime) {
        Camera camera = lane.camera;
        CameraState state = lane.state;
        GameRenderer gameRenderer = mc.gameRenderer;
        GameRendererAccessor gameRendererAccessor = (GameRendererAccessor) gameRenderer;

        // 本帧视图：MAIN = 玩家当前维度（走原来的 mc.level / mc.levelRenderer 口径）；
        // 目标视图 = 独立 ClientLevel / LevelRenderer / LightTexture；解析失败 = 本 lane 跳过。
        DimensionView view = lane.view;
        if (view == null) {
            reportLaneFailure(captureId, lane.viewFailed
                    ? "跨维度视图不可用（维度 " + lane.dimension() + "）"
                    : "lane 视图未解析（内部状态）", null);
            return new LaneOutput(fbo, false);
        }
        boolean crossDimension = !view.isMain();
        ClientLevel prevLevel = mc.level;
        LevelRenderer prevRenderer = mc.levelRenderer;
        ClientLevel viewLevel = crossDimension ? view.level() : mc.level;
        LevelRenderer renderer = crossDimension ? view.renderer() : mc.levelRenderer;
        LightTexture lightTexture = crossDimension ? view.lightTexture() : gameRenderer.lightTexture();

        // 缓冲没有颜色纹理：采样只会得到黑（GL 不报错），渲染与合成都没有意义
        if (fbo.getColorTextureId() <= 0) {
            reportLaneFailure(captureId, "lane 缓冲没有颜色纹理（纹理名 = 0）", null);
            return new LaneOutput(fbo, false);
        }
        // 本 lane 的 GL 错误从零开始计：先清掉进入前的积压（别的模块 / 上一条 lane 留下的）。
        drainGlErrors();

        // 渲染期间让原版所有 getMainRenderTarget() 引用都落在该 lane 的 FBO 上：renderLevel 内部
        // （entityTarget.clear() 等）会按该 target 重置视口，之后回绑的也是 getMainRenderTarget()（不回滚视口）。
        ((MinecraftAccessor) mc).ic$setMainRenderTarget(fbo);
        activeContent = lane.content;
        activeLaneCaptureId = captureId;
        activeLaneState = state;
        // lane pass 内 GameRenderer.getMainCamera() 返回本 lane 相机（不写 private final 字段）：
        // Iris 的 CameraUniforms / 流体判定、原版天空色 / 粒子都走该 getter。
        activeLaneCamera = camera;

        // per-lane 可见集合：bridge 在位时换入该 lane 的候选集 / 可见集合 / 门闩；缺位时不换状态，继续同一路径渲染。
        // 判定必须用本 pass 的渲染器（跨维度 lane 的目标渲染器与 mc.levelRenderer 不同）。
        LaneCullBridge cullBridge = renderer instanceof LaneCullBridge bridge ? bridge : null;
        if (cullBridge != null) {
            cullBridge.ic$pushLaneCullState(cullSlot(lane));
            activeCullBridge = cullBridge;
        }
        // 跨维度：地形 / 实体采样走 RenderStateShard.LightmapStateShard → GameRenderer.lightTexture()，
        // renderLevel 的参数只覆盖天气 / 粒子；目标 lightmap 必须临时换进字段，finally 无条件按标志还原。
        LightTexture previousLightTexture = null;
        boolean lightTextureSwapped = false;
        try {
            fbo.bindWrite(true);   // 绑定 FBO + 视口 = FBO 全尺寸
            // FBO 不完整（附件丢失 / 重建失败 / 尺寸非法）→ 驱动会丢掉绘制、留下未写内容：连画都不画，直接判失败。
            if (GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                reportLaneFailure(captureId, "lane FBO 不完整（GL_FRAMEBUFFER_COMPLETE 校验未过）", null);
                return new LaneOutput(fbo, false);
            }

            if (crossDimension) {
                // 目标视图：整段 pass 把全局 level / levelRenderer / 两个渲染分发器指向目标维度。
                // 原版渲染链里 Minecraft.level / levelRenderer / getMainRenderTarget 的读取点太多，
                // 在边界上换一次、finally 还原（异常路径同样还原），比逐点改写可靠。
                mc.level = view.level();
                ((MinecraftAccessor) mc).ic$setLevelRenderer(view.renderer());
                mc.getEntityRenderDispatcher().setLevel(view.level());
                mc.getBlockEntityRenderDispatcher().setLevel(view.level());
                // 目标维度的光照贴图：主画面每帧由 GameRenderer.tick 置脏；目标视图没有 tick，这里补一次
                // （updateLightTexture 只在置脏后生效，否则贴图停在初始全白）。
                view.lightTexture().tick();
                view.lightTexture().updateLightTexture(partialTick);
                // 换字段：地形 / 实体的 lightmap 采样点只认该字段（renderLevel 的参数到不了这里）。
                // 先置标志再写字段：setter 若抛异常，finally 仍会按 previousLightTexture 还原。
                previousLightTexture = gameRenderer.lightTexture();
                lightTextureSwapped = true;
                gameRendererAccessor.ic$setLightTexture(view.lightTexture());
            }

            // 我们的相机接管：Camera.setup → CameraMixin 的 lane 分支读该实例的 CameraState。
            // 跨维度 lane 强制 detached：相机不跟随玩家实体，只用该 lane 自己的位置 / 朝向。
            if (crossDimension) {
                camera.setup(viewLevel, mc.player, true, false, partialTick);
            } else {
                camera.setup(mc.level, mc.player,
                        !mc.options.getCameraType().isFirstPerson(),
                        mc.options.getCameraType().isMirrored(),
                        partialTick);
            }

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
            // 全局投影必须设成该 lane 的投影：renderLevel 自己不设（原版靠调用方），而实体 / 粒子 /
            // 方块实体 / 天气等走全局矩阵的绘制都会吃到上一层 pass 遗留的正交矩阵而被裁掉。
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            Matrix4f cullProjection = gameRendererAccessor.ic$getProjectionMatrix(
                    Math.max(fov, (double) mc.options.fov().get()));
            Vec3 cameraPos = camera.getPosition();
            renderer.prepareCullFrustum(poseStack, cameraPos, cullProjection);

            // Iris 深度重挂键是版本号而非纹理 id：版本与 Iris 缓存同值（通常都是 0）时不重挂，
            // depthtex0/1/2 / gdepthtex / centerDepthSampler 会继续采样非 lane pass 的深度——递增一次强制重挂。
            bumpIrisDepthBufferVersion(fbo, main);

            // 该 lane 自己的视锥 + 相机姿态 → 原版 8 格膨胀剔除 3~5 步收敛，不需要自建剔除方案。
            // 目标视图用自己的渲染器 / 光照贴图 / 世界；主画面仍是原来的 mc.levelRenderer 口径。
            renderer.renderLevel(poseStack, partialTick, nanoTime, false, camera, gameRenderer,
                    lightTexture, projection);

            // lane 自包含：描边后处理已合成进共享 entityTarget，此刻 lane FBO 还绑着 → 立刻贴进本 lane 画面。
            renderer.doEntityOutline();
            // 描边后必须还原全局投影：doEntityOutline → blitToScreen 会把它改成正交矩阵（与进入前那次成对）。
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        } catch (RuntimeException e) {
            // 渲染中途抛异常 = 本 lane 的画面未写 / 半写：判失败（合成层跳过），别把半截画面铺上屏
            reportLaneFailure(captureId, "lane 渲染异常（画面未写 / 半写）", e);
            return new LaneOutput(fbo, false);
        } finally {
            // 先还原 lightmap 字段：即使后续 level / 分发器还原抛异常，也不会把目标 lightmap 留给主画面。
            if (lightTextureSwapped) {
                gameRendererAccessor.ic$setLightTexture(previousLightTexture);
            }
            if (cullBridge != null) {
                cullBridge.ic$popLaneCullState();
                if (activeCullBridge == cullBridge) {
                    activeCullBridge = null;
                }
            }
            if (crossDimension) {
                // 还原全局 level / levelRenderer / 两个分发器（先于 active* 复位：后续逻辑按主世界口径）。
                // mainRenderTarget 不在这里还原——外层 finally 统一回绑。
                mc.level = prevLevel;
                ((MinecraftAccessor) mc).ic$setLevelRenderer(prevRenderer);
                mc.getEntityRenderDispatcher().setLevel(prevLevel);
                mc.getBlockEntityRenderDispatcher().setLevel(prevLevel);
            }
            activeContent = null;
            activeLaneCamera = null;
            activeLaneCaptureId = null;
            activeLaneIndex = -1;
            activeLaneState = null;
            ((MinecraftAccessor) mc).ic$setMainRenderTarget(main);
        }

        // 全程无 GL 错误 = 画面确实被这次渲染写满（绘制报错时驱动会丢掉绘制，缓冲内容不可信）；只查状态，不读回。
        if (drainGlErrors()) {
            reportLaneFailure(captureId, "lane 渲染产生 GL 错误（绘制可能被驱动丢弃，画面不可信）", null);
            return new LaneOutput(fbo, false);
        }

        // 调试捕获（每相机 raw）：lane 渲染完成、调色之前读回原始 RGBA（ICINEMATICS_CAPTURE 门控，
        // 关闭时第一行即返回）；失败 lane 已在上一行返回，未写内容不捕获。
        LaneDebugCapture.onLaneRendered(captureId, fbo);

        // lane 级调色：lane 渲染完成之后、合成之前过一次调色 pass（只动 RGB、alpha 直通）。
        // 结果写进共享 adjustTarget，合成读它而不是 lane FBO；无需调整或调色失败 → 原样返回 lane FBO。
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
     * 用法固定成对：{@link #renderLane} 进入时清积压（不归本 lane 的错误），渲染后再读——读到即失败。
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
     * lane 渲染失败的限频告警：同一失败点 {@link #FAILURE_LOG_INTERVAL_MS} 一条，窗口内次数合并到下一次输出。
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
     * 不需要深度附件（只画一个全屏 quad、深度测试关闭）；过滤 LINEAR——合成层会缩放 / 取局部。
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

    // ===== 兼容 / 诊断 =====

    /**
     * per-lane 剔除本帧是否生效：有活跃 lane + {@link LaneCullBridge} 在位。
     * bridge 缺位只表示不换原版剔除字段，lane 仍然照常渲染（见 {@link #renderLane}）。
     */
    public static boolean isPerLaneCullActive(LevelRenderer renderer) {
        return INSTANCE.activeCount > 0 && renderer instanceof LaneCullBridge;
    }

    /** 是否检测到光影模组（Iris / Oculus 都提供 {@code IrisApi}；只用于诊断，不注入）。 */
    public static boolean isShaderModPresent() {
        LaneRenderer renderer = INSTANCE;
        if (renderer.shaderModPresent == null) {
            renderer.shaderModPresent = classPresent(IRIS_API);
        }
        return renderer.shaderModPresent;
    }

    /** 类存在性（{@code initialize = false}；只用于诊断探测，不参与 mixin 决策）。 */
    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, LaneRenderer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * 把 lane FBO 的 Iris 深度重挂版本 +1，迫使该 lane pass 的 {@code RenderTargets.resizeIfNeeded}
     * 把 {@code depthtex0/1/2}、{@code gdepthtex}、{@code centerDepthSampler} 重挂到 lane 自己的深度纹理。
     * 回主画面无需手动恢复：Iris 缓存停在本 lane 版本上，下一次主 pass 的 resizeIfNeeded 因版本不同自动重挂。
     * 无光影 / 字段不存在 / 反射失败：静默跳过（无光影路径零影响）。
     */
    private static void bumpIrisDepthBufferVersion(RenderTarget laneTarget, RenderTarget mainTarget) {
        Field field = irisDepthVersionField();
        if (field == null) {
            return;
        }
        try {
            int laneVersion = field.getInt(laneTarget);
            int mainVersion = mainTarget != null ? field.getInt(mainTarget) : Integer.MIN_VALUE;
            int next = laneVersion + 1;
            // 第一帧第一条 lane：lane FBO 初始 0，+1 可能正好等于主 FBO 版本（Iris 缓存），
            // resizeIfNeeded 会判定“没变化”而不重挂。跳过主版本，保证第一次也真正重挂。
            if (next == mainVersion) {
                next++;
            }
            field.setInt(laneTarget, next);
            if (!irisDepthVersionBumpLogged) {
                irisDepthVersionBumpLogged = true;
                LOGGER.info("[lane] Iris 深度重挂已接管：lane pass 内 depthtex0/1/2 / gdepthtex / "
                        + "centerDepthSampler 采样 lane FBO 自己的深度（{}）", IRIS_DEPTH_VERSION_FIELD);
            }
        } catch (Throwable ignored) {
            // 字段语义变化 / 访问被拒：降级为原行为（屏幕空间效果仍采样非 lane pass 的深度），渲染本身不受影响
        }
    }

    /** 反射解析一次 {@code iris$depthBufferVersion}；无光影或失败时返回 {@code null}（之后不再尝试）。 */
    private static Field irisDepthVersionField() {
        if (!irisDepthVersionResolved) {
            irisDepthVersionResolved = true;
            if (isShaderModPresent()) {
                try {
                    Field field = RenderTarget.class.getDeclaredField(IRIS_DEPTH_VERSION_FIELD);
                    field.setAccessible(true);
                    irisDepthVersionField = field;
                } catch (Throwable ignored) {
                    // 字段不存在 / 无反射权限：保持 null，之后每次静默跳过
                }
            }
        }
        return irisDepthVersionField;
    }

    // ===== Mixin 接入点 =====

    /** {@code Camera} 实例 → 其 lane 槽位；不是 lane 相机则 {@code null}。 */
    public static Lane laneOf(Camera camera) {
        LaneRenderer renderer = INSTANCE;
        return renderer.activeCount == 0 ? null : renderer.byCamera.get(camera);
    }

    /** 当前正在渲染的 lane 相机；不在 lane pass 内时为 {@code null}（{@code GameRenderer.getMainCamera} 用）。 */
    public static Camera activeLaneCamera() {
        return activeLaneCamera;
    }

    /** 当前 lane 的调试 id；不在 lane pass 内时为 {@code null}。 */
    public static String activeLaneCaptureId() {
        return activeLaneCaptureId;
    }

    /** 当前 lane 的序号；不在 lane pass 内时为 {@code -1}（诊断分 pass 用，见 {@link #activeLaneCaptureId()}）。 */
    public static int activeLaneIndex() {
        return activeLaneIndex;
    }

    /** 当前 lane 的相机状态；不在 lane pass 内时为 {@code null}。 */
    public static CameraState activeLaneState() {
        return activeLaneState;
    }

    /**
     * 本帧合成顺序末位（序号最大且有状态）的 lane；无活跃 lane 时为 {@code null}。
     *
     * <p>它只用于「非 lane pass 的共享区块网格中心」这一实现约束：可见区块网格（{@code ViewArea} /
     * {@code renderChunkStorage}）整帧只有一份，逐 pass 换中心会让 {@code ViewArea.repositionCamera}
     * 反复搬动整张网格（区块全部置脏重建、画面缺块），因此非 lane pass 取这一条 lane 的相机位置作为
     * 共享网格中心。合成顺序末位不等于「主相机」：每条 lane 在 lane pass 内仍用自己的
     * {@link #activeLaneState()} 完整渲染；服务端当前维度中心由 {@link #currentDimensionCenter(String)}
     * 单独选择，跨维度视图由 {@code CameraViewReporter} 按 view 独立上报。
     *
     * <p>跨维度 lane 不能拖动共享网格中心：非 lane pass 侧只在「该 lane 不跨维度」时才用它
     * （见 {@code LevelRendererVanillaMixin.cinematicViewCenter}）。
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

    /**
     * 本帧当前维度的相机中心：按 lane 序号取第一条 {@code dimension} 为空 / 空串 / 等于
     * {@code currentDimension} 的活跃 lane 的相机眼位。
     *
     * <p>这是服务端 {@code CameraCenterManager} 的「当前维度中心」上报源，不是「主相机」：每条 CAMERA
     * lane 都是独立相机，合成顺序只决定叠放。优先取序号最小的当前维度 lane（最底层 / 背景画面），
     * 避免 0s 起 {@code opacity=0} 的跨维度预取景 lane 把服务端中心带到目标维度；目标维度 lane 由
     * {@code CameraViewReporter} 按 view 独立上报，不参与这里的选择。
     *
     * @param currentDimension 玩家当前维度 id（{@link DimensionViews#currentDimensionId}）；空串 = 未知
     * @return 相机眼位；没有当前维度活跃 lane 时为 {@code null}
     */
    public Vec3 currentDimensionCenter(String currentDimension) {
        if (activeCount == 0) {
            return null;
        }
        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            if (lane.state == null) {
                continue;
            }
            String dimension = lane.dimension;
            if (dimension == null || dimension.isEmpty() || dimension.equals(currentDimension)) {
                return lane.state.position();
            }
        }
        return null;
    }

    /** 是否正在某个 lane 的 pass 内（lane 自包含分流 + 内容开关判定用）。 */
    public static boolean isRenderingLane() {
        return activeContent != null;
    }

    /**
     * 是否正处在逐 lane 渲染循环内：{@code ViewScope.enter} 据此跳过光影 pipeline 刷新
     * （刷新会清空视角栈，把本帧已配对的 lane 作用域打散）。
     */
    public static boolean isLaneLoopActive() {
        return laneLoopActive;
    }

    /**
     * 是否正在主画面自己那一次 {@code doEntityOutline} 调用内：原版渲染之后那次整屏描边要被屏蔽，
     * 这一次必须放行（见 {@code LevelRendererMixin}）。
     */
    public static boolean isMainOutlinePass() {
        return mainOutlinePass;
    }

    /** 本帧是否有活跃 lane（原版整屏描边是否要让位给 lane 内描边）。 */
    public boolean hasActiveLanes() {
        return activeCount > 0;
    }

    /** 本帧活跃 lane 数（只读；诊断用）。 */
    public int activeLaneCount() {
        return activeCount;
    }

    /**
     * 本帧活跃的非空维度视图快照（客户端 tick 上报用）：只收 {@code dimension} 非空的 lane
     * （空维度 = 玩家当前维度，不在这里），有租约时 {@code viewId = lease.viewKey()}（槽位 + 代际 + 维度），
     * 无租约回退 {@code captureId@dimension}（captureId 缺失回退 {@code lane<序号>}）。
     * 同一 viewId 仍按去重并保留最后一条，避免同源 lane 位置不同时逐帧来回移动服务端 ticket / 区块差集。
     * 当前维度中心不在这里（空维度 lane 被过滤）：服务端当前维度中心用
     * {@link #currentDimensionCenter(String)}。
     */
    public List<LaneView> activeViews() {
        if (activeCount == 0) {
            return List.of();
        }
        List<LaneView> views = new ArrayList<>(activeCount);
        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            if (lane.state == null) {
                continue;
            }
            String dimension = lane.dimension;
            if (dimension == null || dimension.isEmpty()) {
                continue;
            }
            String captureId = lane.captureId != null ? lane.captureId : "lane" + i;
            String viewId = lane.lease != null ? lane.lease.viewKey() : captureId + "@" + dimension;
            LaneView view = new LaneView(viewId, dimension, lane.state.position());
            boolean replaced = false;
            for (int j = 0; j < views.size(); j++) {
                if (views.get(j).viewId().equals(view.viewId())) {
                    views.set(j, view);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                views.add(view);
            }
        }
        return views;
    }

    /** 本帧活跃 lane 的 captureId（调试捕获文件名口径，跨维度 lane 也不带维度后缀）。 */
    public List<String> captureIds() {
        List<String> ids = new ArrayList<>(activeCount);
        for (Lane lane : lanes) {
            if (lane.state != null && lane.captureId != null) {
                ids.add(lane.captureId);
            }
        }
        return ids;
    }

    /**
     * 调试用只读签名：本帧活跃 lane 的 {@code captureId=viewKey}，按 lane 序号空格分隔。
     * 有租约时 viewKey 取 {@link CameraLease#viewKey()}；链输出回退 lane 为 {@code chain=chain}。
     * 只在 {@code Config.debugLogging} 下由 {@link ScriptLaneDriver} 调用；不缓存、不参与渲染热路径。
     */
    public String debugLaneSignature() {
        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            if (lane.state == null) {
                continue;
            }
            if (signature.length() > 0) {
                signature.append(' ');
            }
            String captureId = lane.captureId != null ? lane.captureId : "lane" + i;
            String viewKey = lane.lease != null ? lane.lease.viewKey() : captureId;
            signature.append(captureId).append('=').append(viewKey);
        }
        return signature.toString();
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
