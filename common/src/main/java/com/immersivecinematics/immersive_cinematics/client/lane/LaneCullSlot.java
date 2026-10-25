package com.immersivecinematics.immersive_cinematics.client.lane;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一条 lane 的剔除状态箱：承载 {@code LevelRenderer} 上整帧单份的「候选集 / 可见集合 / 门闩 /
 * 相机移动判定 / 透明排序基准」字段值，由 {@code LaneCullStateMixin} 在 lane pass 前后与同名字段整体换入 / 换出。
 *
 * 各字段逐项对应 {@code LevelRenderer} 同名字段（语义一致），作用域缩到本 lane 的 pass：候选集 / 可见集合 /
 * 编译输入队列 / 视锥与全量重建门闩 / 全量 BFS 重试定时器（ms）/ 上次相机位置（8 格粒度）/ 上次朝向桶（2° 粒度）/
 * 上次透明排序位置。前四项按引用搬运（换引用不换内容）；编译产物（{@code CompiledChunk} / 可见性图）与
 * 区块网格全局共享，不在箱内。候选集值 {@code null} = 未播种，首次换入时以当前字段值播种。
 *
 * compiled 是本 lane 自己的输入队列：区块编译完成时经 {@link LaneCullSlots#mirrorCompiled} 逐 lane 投递，
 * 使每个 lane 的候选集都能按已编译区块做局部更新。
 *
 * 线程：除 compiled 外只由渲染线程读写；compiled 由区块编译完成回调写入（线程不定），故用
 * {@code LinkedBlockingQueue}。分配：{@link #clear()} 复用既有对象，换入 / 换出路径不分配。
 */
public final class LaneCullSlot {

    /**
     * 当前槽内字段值属于哪个 {@code LevelRenderer}（身份比较）；{@code null} = 未播种。
     * 跨维度 lane 的目标渲染器与主渲染器共用同一批槽（按 lane 序号复用）：换渲染器时必须整箱作废、
     * 按新渲染器的字段重新播种，否则槽里的候选集会指向另一个维度的 {@code ViewArea} 区块。
     * 写入只发生在渲染线程，读取可能来自区块编译回调线程，故 volatile。
     */
    public volatile Object owner;

    public AtomicReference<Object> storage = new AtomicReference<>();

    public ObjectArrayList<Object> visible = new ObjectArrayList<>(10000);

    public BlockingQueue<ChunkRenderDispatcher.RenderChunk> compiled = new LinkedBlockingQueue<>();

    public AtomicBoolean frustumDirty = new AtomicBoolean(false);

    public AtomicLong nextFullUpdate = new AtomicLong();

    public boolean fullUpdate = true;

    public double prevCamX = Double.MIN_VALUE;
    public double prevCamY = Double.MIN_VALUE;
    public double prevCamZ = Double.MIN_VALUE;

    public double prevCamRotX = Double.MIN_VALUE;
    public double prevCamRotY = Double.MIN_VALUE;

    public double transparentOldX;
    public double transparentOldY;
    public double transparentOldZ;

    /**
     * 换渲染器（主画面 ↔ 跨维度视图）时整箱作废：槽内字段值属于旧渲染器的 {@code ViewArea}，
     * 清空后按新渲染器的字段重新播种，并记录新的归属。
     *
     * @param owner 即将换入本槽的 {@code LevelRenderer}
     */
    public void reseedFor(Object owner) {
        clear();
        this.owner = owner;
    }

    /** 归还池 / 作废时清空：复用既有集合对象，候选集值置空表示下次换入重新播种。 */
    void clear() {
        owner = null;
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
