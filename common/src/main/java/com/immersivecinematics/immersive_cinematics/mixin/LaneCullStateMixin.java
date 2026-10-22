package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneCullBridge;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneCullSlot;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneCullSlots;
import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每 lane 独立可见集合：把 {@code LevelRenderer} 上单份共享的「候选集 + 可见集合 + 剔除门闩 + 相机移动判定 +
 * 透明排序基准」换入 / 换出（字段级快照交换，状态箱见 {@link LaneCullSlot}）。lane pass 内这些字段只属于该 lane，
 * 各 lane 各自播种、各自遮挡剪枝、各自视锥过滤；编译产物（{@code CompiledChunk} / 可见性图）与区块网格继续共享。
 *
 * 配对不变量：{@link #ic$pushLaneCullState} / {@link #ic$popLaneCullState} 严格成对（栈式，深度通常 ≤ 1），
 * 异常路径由 {@link #ic$popAllLaneCullStates} 弹净；深度 0 = 字段持有原版自己的值（无 lane 时零差异）。
 *
 * 全量 BFS 归槽：lane pass 的那次在 lane 槽已换入时「同步」执行（写入落在发起它的槽）；主 pass 保持原版异步，
 * 但 lane 槽换入前先等它落地（{@link #ic$awaitPendingFullUpdate}）——异步任务写的是「写入时刻安装的槽」，
 * 不等就可能落进别的 lane。
 *
 * 线程：字段换入 / 换出只发生在渲染线程；{@link #ic$mirrorCompiledChunk} 可能来自区块编译完成回调（线程不定），
 * 只往各槽的并发队列投递。分配：换入 / 换出路径不分配（保存位预分配复用）。
 */
@Mixin(LevelRenderer.class)
public class LaneCullStateMixin implements LaneCullBridge {

    private static final Logger IC$LOGGER = LogUtils.getLogger();

    /** 同步执行全量 BFS 时返回的完成态 future（共享常量：调用方只读 {@code isDone()}）。 */
    private static final Future<?> IC$COMPLETED = CompletableFuture.completedFuture(null);

    @Shadow
    @Final
    private Minecraft minecraft;

    // ===== 换入 / 换出的 14 个字段（逐项对应见 LaneCullSlot）=====

    @Shadow
    @Final
    @Mutable
    private AtomicReference<Object> renderChunkStorage;
    @Shadow
    @Final
    @Mutable
    private ObjectArrayList<Object> renderChunksInFrustum;
    @Shadow
    @Final
    @Mutable
    private BlockingQueue<ChunkRenderDispatcher.RenderChunk> recentlyCompiledChunks;
    @Shadow
    @Final
    @Mutable
    private AtomicBoolean needsFrustumUpdate;
    @Shadow
    @Final
    @Mutable
    private AtomicLong nextFullUpdateMillis;
    @Shadow
    private boolean needsFullRenderChunkUpdate;
    @Shadow
    private double prevCamX;
    @Shadow
    private double prevCamY;
    @Shadow
    private double prevCamZ;
    @Shadow
    private double prevCamRotX;
    @Shadow
    private double prevCamRotY;
    @Shadow
    private double xTransparentOld;
    @Shadow
    private double yTransparentOld;
    @Shadow
    private double zTransparentOld;

    /** 原版那次全量 BFS 的 future（主 pass 提交、后台执行）；换入 lane 槽前必须等它落地。 */
    @Shadow
    private Future<?> lastFullRenderChunkUpdate;

    // ===== 换入 / 换出 =====

    @Override
    public void ic$pushLaneCullState(LaneCullSlot slot) {
        if (LaneCullSlots.depth() == 0) {
            ic$awaitPendingFullUpdate();
        }
        LaneCullSlot save = LaneCullSlots.beginPush(slot);
        if (slot.storage.get() == null) {
            // 首次播种：槽内候选集必须有值——未播种时局部更新路径会读到 null。取当前字段的「值」
            // （不是对象引用：槽自带 AtomicReference，共用引用会让本次 pass 的 BFS 写坏原版槽）。
            slot.storage.set(this.renderChunkStorage.get());
        }
        ic$copyFieldsTo(save);
        ic$copyFieldsFrom(slot);
    }

    @Override
    public void ic$popLaneCullState() {
        LaneCullSlot slot = LaneCullSlots.installed();
        LaneCullSlot save = LaneCullSlots.saved();
        if (!LaneCullSlots.endPop()) {
            return;
        }
        ic$copyFieldsTo(slot);
        ic$copyFieldsFrom(save);
    }

    @Override
    public void ic$popAllLaneCullStates() {
        while (LaneCullSlots.depth() > 0) {
            ic$popLaneCullState();
        }
    }

    // ===== 全量 BFS 归槽 =====

    /**
     * 全量 BFS 的提交口（原版 {@code Util.backgroundExecutor().submit(...)}）。
     *
     * lane pass 内同步执行：任务体写 {@code this.renderChunkStorage} / {@code this.needsFrustumUpdate}，
     * 同步 = 写入时刻安装的槽正是发起它的那个 lane 槽。主 pass 照原版异步提交（写入落在 vanilla 槽），
     * 其落地由 {@link #ic$awaitPendingFullUpdate} 在 lane 槽换入前保证。
     *
     * 若第三方改写了 {@code setupRender}（未实测的细节风险），注入点不存在时跳过而不是阻止启动；
     * lane 渲染不依赖本重定向。
     */
    @Redirect(method = "setupRender", require = 0, at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;"))
    private Future<?> ic$submitFullCullUpdate(ExecutorService executor, Runnable task) {
        if (LaneRenderer.isRenderingLane() && LaneRenderer.isPerLaneCullActive(this.minecraft)) {
            task.run();
            return IC$COMPLETED;
        }
        return executor.submit(task);
    }

    /** 等原版那次异步全量 BFS 落地（未在飞时零开销）；失败按「按当前可见集合继续」处理。 */
    @Unique
    private void ic$awaitPendingFullUpdate() {
        Future<?> pending = this.lastFullRenderChunkUpdate;
        if (pending == null || pending.isDone()) {
            return;
        }
        try {
            pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            IC$LOGGER.warn("[lane] 主 pass 的全量区块更新失败：lane 剔除按当前可见集合继续", e);
        }
    }

    // ===== 区块编译完成的逐 lane 投递 =====

    @Inject(method = "addRecentlyCompiledChunk", at = @At("HEAD"))
    private void ic$mirrorCompiledChunk(ChunkRenderDispatcher.RenderChunk renderChunk, CallbackInfo ci) {
        LaneCullSlots.mirrorCompiled(renderChunk);
    }

    // ===== 视距变化 / 换世界 / 立即全量重建 =====

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void ic$invalidateCullSlots(CallbackInfo ci) {
        LaneCullSlots.onAllChanged();
    }

    @Inject(method = "needsUpdate", at = @At("HEAD"))
    private void ic$onNeedsUpdate(CallbackInfo ci) {
        LaneCullSlots.onNeedsUpdate();
    }

    // ===== 字段搬运 =====

    @Unique
    private void ic$copyFieldsTo(LaneCullSlot box) {
        box.storage = this.renderChunkStorage;
        box.visible = this.renderChunksInFrustum;
        box.compiled = this.recentlyCompiledChunks;
        box.frustumDirty = this.needsFrustumUpdate;
        box.nextFullUpdate = this.nextFullUpdateMillis;
        box.fullUpdate = this.needsFullRenderChunkUpdate;
        box.prevCamX = this.prevCamX;
        box.prevCamY = this.prevCamY;
        box.prevCamZ = this.prevCamZ;
        box.prevCamRotX = this.prevCamRotX;
        box.prevCamRotY = this.prevCamRotY;
        box.transparentOldX = this.xTransparentOld;
        box.transparentOldY = this.yTransparentOld;
        box.transparentOldZ = this.zTransparentOld;
    }

    @Unique
    private void ic$copyFieldsFrom(LaneCullSlot box) {
        this.renderChunkStorage = box.storage;
        this.renderChunksInFrustum = box.visible;
        this.recentlyCompiledChunks = box.compiled;
        this.needsFrustumUpdate = box.frustumDirty;
        this.nextFullUpdateMillis = box.nextFullUpdate;
        this.needsFullRenderChunkUpdate = box.fullUpdate;
        this.prevCamX = box.prevCamX;
        this.prevCamY = box.prevCamY;
        this.prevCamZ = box.prevCamZ;
        this.prevCamRotX = box.prevCamRotX;
        this.prevCamRotY = box.prevCamRotY;
        this.xTransparentOld = box.transparentOldX;
        this.yTransparentOld = box.transparentOldY;
        this.zTransparentOld = box.transparentOldZ;
    }
}
