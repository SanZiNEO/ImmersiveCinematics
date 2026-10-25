package com.immersivecinematics.immersive_cinematics.client.dimension;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneCullSlots;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨维度视图登记表：按维度 id 惰性创建 / 复用 {@link DimensionView}（P0：每条 lane 一个目标视图，按维度共享）。
 *
 * 解析口径（{@link #acquire}）：
 * <ul>
 *   <li>维度为空 / 等于玩家当前维度 → {@link #MAIN}（不分配、不切换，主画面口径与原来逐位相同）；</li>
 *   <li>非法 id / 未连接 / 缺少维度类型 / FABULOUS（{@code Minecraft.useShaderTransparency()}）→ {@code null}，
 *       该 lane 本帧不上屏；同一维度 5s 内只告警一次（失败逐帧复现，不限频会刷屏）；</li>
 *   <li>其余 → 目标视图（首次创建：独立 {@link RenderBuffers} + {@link LevelRenderer} + {@link ClientLevel}
 *       + {@link LightTexture}，创建后立即把被 {@code setLevel} 改写的共享渲染分发器还原到玩家当前世界）。</li>
 * </ul>
 *
 * 线程：{@link #acquire} / {@link #processPending} / {@link #resize} / {@link #closeAll} 只在渲染线程
 * （客户端 tick 与渲染同线程）；{@link #requestResourceReload} / {@link #registerDimensionType} 可能来自
 * 资源重载或网络线程，只写 volatile 标志 / 并发 map，绝不直接碰 GL。
 *
 * 生命周期：目标视图跨帧持有，不因为某一帧没有 lane 使用而关闭；世界退出时 {@link #closeAll()} 统一释放
 * （否则区块网格 / 编译线程 / 描边链会跨存档泄漏）。
 */
public final class DimensionViews {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 玩家当前维度哨兵。 */
    public static final DimensionView MAIN = DimensionView.main();

    /** 已创建的目标视图（维度 id → 视图）；渲染线程读写。 */
    private static final Map<String, DimensionView> VIEWS = new HashMap<>();

    /** 服务端元数据登记：维度 id → 维度类型 id（后续服务端包的落点；当前只存不读以外的逻辑）。 */
    private static final Map<ResourceLocation, ResourceLocation> DIMENSION_TYPES = new ConcurrentHashMap<>();

    /**
     * 失败限频：维度 id → 允许重试 / 告警的时刻（{@link System#nanoTime()}）。
     * 并发 map：{@link #registerDimensionType} 可能来自网络线程（后续服务端元数据包），
     * 渲染线程只做 get / put / remove。
     */
    private static final Map<String, Long> FAILURE_RETRY_AT = new ConcurrentHashMap<>();

    /** 失败重试 / 告警间隔。 */
    private static final long FAILURE_RETRY_NANOS = 5_000_000_000L;

    /** 资源重载待处理标志（重载监听器只置位；渲染线程在帧首重建）。 */
    private static volatile boolean reloadPending;

    /** {@link #currentDimensionId} 的缓存（按世界实例身份失效）：避免逐 lane 逐帧拼维度 id 字符串。 */
    private static ClientLevel cachedLevel;
    private static String cachedDimensionId = "";

    private DimensionViews() {
    }

    /**
     * 玩家当前维度的 id 字符串（{@code namespace:path}），按世界实例缓存：
     * lane 解析 / 内容档判定 / 视图中心判定都拿它做比较，逐帧调用不再分配。
     * 只在渲染线程（客户端主线程）调用。
     */
    public static String currentDimensionId(ClientLevel level) {
        if (level == null) {
            return "";
        }
        if (level != cachedLevel) {
            cachedLevel = level;
            cachedDimensionId = level.dimension().location().toString();
        }
        return cachedDimensionId;
    }

    /**
     * 解析一条 lane 的视图。
     *
     * @param mc        客户端
     * @param dimension 片段 {@code dimension}；空串 / {@code null} = 玩家当前维度
     * @return {@link #MAIN}、目标视图，或 {@code null}（本帧不可用，lane 跳过）
     */
    public static DimensionView acquire(Minecraft mc, String dimension) {
        if (dimension == null || dimension.isEmpty()) {
            return MAIN;
        }
        ClientLevel current = mc.level;
        if (current == null) {
            return null;
        }
        if (dimension.equals(currentDimensionId(current))) {
            return MAIN;
        }
        // 失败限频：窗口内直接返回，不解析 id（ResourceLocation.tryParse 失败会分配异常）/ 不查注册表
        long now = System.nanoTime();
        Long retryAt = FAILURE_RETRY_AT.get(dimension);
        if (retryAt != null && now < retryAt) {
            return null;
        }
        DimensionView existing = VIEWS.get(dimension);
        if (existing != null) {
            return existing;
        }
        if (Minecraft.useShaderTransparency()) {
            return fail(dimension, "FABULOUS 画质 / 光影后处理链下不支持独立维度视图");
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return fail(dimension, "客户端尚未连接服务器，无法解析维度");
        }
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null) {
            return fail(dimension, "维度 id 非法");
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        // 维度类型：优先用服务端元数据登记的类型；没有登记时按同命名空间 / 同路径回退
        ResourceLocation typeId = DIMENSION_TYPES.getOrDefault(location, location);
        Holder<DimensionType> holder;
        try {
            Registry<DimensionType> types = connection.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
            holder = types.getHolder(ResourceKey.create(Registries.DIMENSION_TYPE, typeId)).orElse(null);
        } catch (Exception e) {
            return fail(dimension, "维度类型注册表不可用（" + e.getMessage() + "）");
        }
        if (holder == null) {
            return fail(dimension, "缺少维度类型 " + typeId + "（等待服务端元数据）");
        }
        DimensionView view = create(mc, connection, dimension, key, holder, current);
        if (view == null) {
            // 详细异常已在 create 内记录；这里只进入重试窗口，避免逐帧重建 / 刷屏
            FAILURE_RETRY_AT.put(dimension, System.nanoTime() + FAILURE_RETRY_NANOS);
            return null;
        }
        VIEWS.put(dimension, view);
        FAILURE_RETRY_AT.remove(dimension);
        return view;
    }

    /** 创建目标视图（渲染线程；失败返回 {@code null} 并记录一次详细异常）。 */
    private static DimensionView create(Minecraft mc, ClientPacketListener connection, String dimensionId,
                                        ResourceKey<Level> key, Holder<DimensionType> type,
                                        ClientLevel current) {
        LevelRenderer renderer = null;
        LightTexture lightTexture = null;
        try {
            // 独立 RenderBuffers：目标视图的区块 / 实体缓冲不得与主画面共用
            RenderBuffers buffers = new RenderBuffers();
            renderer = new LevelRenderer(mc, mc.getEntityRenderDispatcher(),
                    mc.getBlockEntityRenderDispatcher(), buffers);
            int viewDistance = mc.options.getEffectiveRenderDistance();
            int simulationDistance = current.getServerSimulationDistance();
            ClientLevel level = new ClientLevel(connection,
                    new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false),
                    key, type, viewDistance, simulationDistance, mc::getProfiler, renderer, false, 0L);
            lightTexture = new LightTexture(mc.gameRenderer, mc);
            // setLevel 会把共享的实体渲染分发器切到目标世界：立刻还原，主画面这一帧还没开始渲染
            renderer.setLevel(level);
            mc.getEntityRenderDispatcher().setLevel(current);
            mc.getBlockEntityRenderDispatcher().setLevel(current);
            // 与主渲染器同口径初始化描边链（构造器不建 PostChain）；否则目标视图的发光描边缺失
            renderer.onResourceManagerReload(mc.getResourceManager());
            LOGGER.info("[dimension] 已创建跨维度视图：{}（视距 {}，模拟距离 {}）",
                    dimensionId, viewDistance, simulationDistance);
            return new DimensionView(mc, key, level, renderer, lightTexture);
        } catch (Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            ErrorLog.logRateLimited("Render", "dimension-create:" + dimensionId, 5000L,
                    "跨维度视图创建失败（维度 " + dimensionId + "）：该 lane 本帧不上屏，主画面保持基画面", t);
            releaseFailed(mc, current, renderer, lightTexture);
            return null;
        }
    }

    /** 创建失败时释放已分配的 GL 资源，并把共享渲染分发器还原到玩家当前世界。 */
    private static void releaseFailed(Minecraft mc, ClientLevel current, LevelRenderer renderer,
                                      LightTexture lightTexture) {
        if (renderer != null) {
            try {
                renderer.setLevel(null);
            } catch (Throwable t) {
                FatalErrors.rethrowIfFatal(t);
            } finally {
                mc.getEntityRenderDispatcher().setLevel(current);
                mc.getBlockEntityRenderDispatcher().setLevel(current);
            }
            try {
                renderer.close();
            } catch (Throwable t) {
                FatalErrors.rethrowIfFatal(t);
            }
        }
        if (lightTexture != null) {
            try {
                lightTexture.close();
            } catch (Throwable t) {
                FatalErrors.rethrowIfFatal(t);
            }
        }
    }

    /** 记录一次失败（调用方已确认不在重试窗口内）：写窗口 + 一条告警。 */
    private static DimensionView fail(String dimension, String reason) {
        FAILURE_RETRY_AT.put(dimension, System.nanoTime() + FAILURE_RETRY_NANOS);
        LOGGER.warn("[dimension] 跨维度视图不可用：{} — {}（该 lane 本帧不上屏，{}s 后重试）",
                dimension, reason, FAILURE_RETRY_NANOS / 1_000_000_000L);
        return null;
    }

    /**
     * 登记维度的类型（服务端元数据包的落点）：后续没有元数据时按同命名空间 / 同路径回退。
     * 网络线程可调用：只写并发 map，不碰 GL。
     */
    public static void registerDimensionType(ResourceLocation dimension, ResourceLocation type) {
        if (dimension == null || type == null) {
            return;
        }
        DIMENSION_TYPES.put(dimension, type);
        // 之前因缺少维度类型失败过的维度：清掉窗口，下一次 acquire 立即按新元数据重试
        FAILURE_RETRY_AT.remove(dimension.toString());
    }

    /** 资源重载监听器调用（可能不在渲染线程）：只置位，GL 重建留给 {@link #processPending}。 */
    public static void requestResourceReload() {
        reloadPending = true;
    }

    /** 帧首（渲染线程）：资源重载后重建各目标渲染器的描边链并整帧重建区块网格。 */
    public static void processPending(Minecraft mc) {
        if (!reloadPending) {
            return;
        }
        reloadPending = false;
        for (Map.Entry<String, DimensionView> entry : VIEWS.entrySet()) {
            DimensionView view = entry.getValue();
            try {
                view.onResourceReload(mc);
                LevelRenderer renderer = view.renderer();
                if (renderer != null) {
                    renderer.allChanged();
                }
            } catch (Throwable t) {
                FatalErrors.rethrowIfFatal(t);
                LOGGER.warn("[dimension] 资源重载后重建目标视图失败：{}（该维度 lane 可能缺块，等待下次重载）",
                        entry.getKey(), t);
            }
        }
    }

    /** 窗口尺寸变化（渲染线程）：目标渲染器的描边 / 半透明链跟随重建。 */
    public static void resize(Minecraft mc, int width, int height) {
        for (DimensionView view : VIEWS.values()) {
            try {
                view.resize(width, height);
            } catch (Throwable t) {
                FatalErrors.rethrowIfFatal(t);
                LOGGER.warn("[dimension] 目标视图 resize 失败（尺寸 {}x{}），该视图画面可能仍按旧尺寸合成",
                        width, height, t);
            }
        }
    }

    /** 世界退出（客户端 tick）：释放全部目标视图；{@link #MAIN} 不受影响。 */
    public static void closeAll() {
        reloadPending = false;
        FAILURE_RETRY_AT.clear();
        // 缓存持有旧世界实例：不清理会把整个 ClientLevel 留到下一个存档
        cachedLevel = null;
        cachedDimensionId = "";
        if (VIEWS.isEmpty()) {
            return;
        }
        int closed = VIEWS.size();
        for (Map.Entry<String, DimensionView> entry : VIEWS.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Throwable t) {
                FatalErrors.rethrowIfFatal(t);
                LOGGER.warn("[dimension] 释放目标视图失败：{}（继续释放其余视图）", entry.getKey(), t);
            }
        }
        VIEWS.clear();
        // 槽可能留在池里等复用：断开对已关闭渲染器的强引用，否则独立 RenderBuffers 会一起留在内存里
        LaneCullSlots.dropOwners();
        LOGGER.info("[dimension] 已释放 {} 个跨维度视图", closed);
    }
}
