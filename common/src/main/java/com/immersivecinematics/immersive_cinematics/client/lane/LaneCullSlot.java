package com.immersivecinematics.immersive_cinematics.client.lane;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一条 lane 的剔除状态箱：承载 {@code LevelRenderer} 上原本整帧单份的「候选集 / 可见集合 / 门闩 /
 * 相机移动判定 / 透明排序基准」字段值，由 {@code LaneCullStateMixin} 在 lane pass 前后与同名字段整体换入 / 换出。
 *
 * 字段逐项对应 {@code LevelRenderer} 同名字段，语义一致，作用域从「整帧唯一」缩到「本 lane 的 pass」：
 * storage = 候选集（BFS 产物）、visible = 视锥过滤后的可见集合、frustumDirty = 视锥门闩、
 * fullUpdate = 全量 BFS 门闩、nextFullUpdate = 全量 BFS 重试定时器、prevCam* = 上次全量 BFS 的相机位置
 * （8 格粒度）、prevCamRot* = 上次视锥刷新的朝向桶（2° 粒度）、transparentOld* = 上次透明排序的相机位置。
 * 前四项按引用搬运（换引用不换内容）；编译产物（{@code CompiledChunk} / 可见性图）与区块网格全局共享，不在箱内。
 *
 * compiled 是本类新增的输入队列（原版那份全局共享、每帧只被首个 pass 排空）：区块编译完成时经
 * {@link LaneCullSlots#mirrorCompiled} 逐 lane 投递，使每个 lane 的候选集都能按已编译区块做局部更新。
 *
 * 线程：storage / visible / frustumDirty / 标量只由渲染线程读写；compiled 另由区块编译完成回调写入
 * （线程不定），故用 {@code LinkedBlockingQueue}。分配：{@link #clear()} 复用既有对象，换入 / 换出路径不分配。
 */
public final class LaneCullSlot {

    /**
     * 候选集（对应 {@code LevelRenderer.renderChunkStorage}）；槽自带 {@code AtomicReference} 实例，
     * 值 {@code null} = 未播种，首次换入时取当前字段的「值」播种（取对象引用会让槽与原版槽共用一份候选集）。
     */
    public AtomicReference<Object> storage = new AtomicReference<>();

    /** 可见集合（对应 {@code LevelRenderer.renderChunksInFrustum}）。 */
    public ObjectArrayList<Object> visible = new ObjectArrayList<>(10000);

    /** 本 lane 的局部更新输入队列（对应原版 {@code recentlyCompiledChunks}，见类注释）。 */
    public BlockingQueue<ChunkRenderDispatcher.RenderChunk> compiled = new LinkedBlockingQueue<>();

    /** 视锥门闩（对应 {@code LevelRenderer.needsFrustumUpdate}）。 */
    public AtomicBoolean frustumDirty = new AtomicBoolean(false);

    /**
     * 全量 BFS 重试定时器（对应 {@code LevelRenderer.nextFullUpdateMillis}）：边界 / 邻居未就绪时
     * 定下的延迟重建时间戳（ms，{@code 0} = 无待办）。槽自带实例，避免一条 lane 的重试节流压住别的 pass。
     */
    public AtomicLong nextFullUpdate = new AtomicLong();

    /** 全量 BFS 门闩（对应 {@code LevelRenderer.needsFullRenderChunkUpdate}）；新槽缺省 true = 首次进入重建。 */
    public boolean fullUpdate = true;

    /** 上次全量 BFS 的相机位置（8 格粒度，对应 {@code LevelRenderer.prevCamX/Y/Z}）。 */
    public double prevCamX = Double.MIN_VALUE;
    public double prevCamY = Double.MIN_VALUE;
    public double prevCamZ = Double.MIN_VALUE;

    /** 上次视锥刷新的相机朝向桶（2° 粒度，对应 {@code LevelRenderer.prevCamRotX/Y}）。 */
    public double prevCamRotX = Double.MIN_VALUE;
    public double prevCamRotY = Double.MIN_VALUE;

    /** 上次透明排序的相机位置（对应 {@code LevelRenderer.xTransparentOld/y/z}）。 */
    public double transparentOldX;
    public double transparentOldY;
    public double transparentOldZ;

    /** 归还池 / 作废时清空：复用既有集合对象，候选集值置空表示下次换入重新播种。 */
    void clear() {
        storage.set(null);
        visible.clear();
        if (compiled == null) {
            compiled = new LinkedBlockingQueue<>();
        } else {
            compiled.clear();
        }
        frustumDirty.set(false);
        nextFullUpdate.set(0L);
        fullUpdate = true;
        prevCamX = Double.MIN_VALUE;
        prevCamY = Double.MIN_VALUE;
        prevCamZ = Double.MIN_VALUE;
        prevCamRotX = Double.MIN_VALUE;
        prevCamRotY = Double.MIN_VALUE;
        transparentOldX = 0.0;
        transparentOldY = 0.0;
        transparentOldZ = 0.0;
    }

    /** 视距变化 / 换世界后作废候选集与可见集合：标一次全量重建（旧 ViewArea 的区块引用不再被读）。 */
    void invalidate() {
        visible.clear();
        if (compiled != null) {
            compiled.clear();
        }
        nextFullUpdate.set(0L);
        fullUpdate = true;
    }
}
