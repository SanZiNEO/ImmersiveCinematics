package com.immersivecinematics.immersive_cinematics.client.lane;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * lane 剔除槽的池 + 登记表 + 换入换出帧栈（单客户端单例：{@code LevelRenderer} 每客户端只有一个）。
 *
 * 池：槽按需创建（{@link #acquire}）、lane 停播归还（{@link #release}，清空后复用），内存 ∝ 并发 lane 峰值。
 * 登记表：{@link #mirrorCompiled} 向当前被 lane 持有的全部槽投递「区块已编译」事件——原版那份队列每帧只被
 * 首个 pass 排空，其余 lane 槽会漏掉局部更新，候选集停在旧口径、新编译区块在 lane 画面里缺块。
 *
 * 帧栈：{@link #beginPush} / {@link #endPop} 配对登记「本层换入了哪个槽、旧值存进哪个保存位」，
 * 深度通常 ≤ 1（lane pass 顺序执行）；{@link #depth} = 0 时字段就是原版自己的值（无 lane 时零差异）。
 *
 * 线程：池 / 登记表 / 帧栈由渲染线程读写；{@link #mirrorCompiled} 可能来自区块编译完成回调（线程不定），
 * 只做 volatile 快照遍历 + 队列入队，漏投一次不致命（该区块在槽的下一次全量 BFS 里补上）。
 * 分配：换入 / 换出零分配为预热后口径（保存位复用）；首次用到某层时惰性新建保存位、栈扩容时重建数组；
 * {@link #acquire} / {@link #release} 各重建一次
 * {@link #liveSnapshot}（只在 lane 起停时发生，不随帧数增长）。
 */
public final class LaneCullSlots {

    /** 停播归还的槽（已清空，可复用）。 */
    private static final ArrayDeque<LaneCullSlot> POOL = new ArrayDeque<>();

    /** 当前被 lane 持有的槽（渲染线程维护）。 */
    private static final List<LaneCullSlot> LIVE = new ArrayList<>();

    /** {@link #LIVE} 的只读快照（供 {@link #mirrorCompiled} 在其它线程遍历）。 */
    private static volatile LaneCullSlot[] liveSnapshot = new LaneCullSlot[0];

    /** 帧栈：每层「旧值保存位」与「换入的 lane 槽」。 */
    private static LaneCullSlot[] savedStack = new LaneCullSlot[2];
    private static LaneCullSlot[] installedStack = new LaneCullSlot[2];
    private static int depth;

    private LaneCullSlots() {
    }

    // ===== 槽生命周期 =====

    /** 取一个空槽（池复用 / 新建）；调用方（{@code Lane}）负责跨帧持有，停播时 {@link #release}。 */
    public static LaneCullSlot acquire() {
        LaneCullSlot slot = POOL.poll();
        if (slot == null) {
            slot = new LaneCullSlot();
        }
        LIVE.add(slot);
        liveSnapshot = LIVE.toArray(new LaneCullSlot[0]);
        return slot;
    }

    /** 归还槽（清空后入池）；必须在该槽未被换入时调用。 */
    public static void release(LaneCullSlot slot) {
        slot.clear();
        if (LIVE.remove(slot)) {
            liveSnapshot = LIVE.toArray(new LaneCullSlot[0]);
        }
        POOL.add(slot);
    }

    // ===== 帧栈（换入 / 换出）=====

    /** 栈深度（0 = 字段持有原版值）。 */
    public static int depth() {
        return depth;
    }

    /** 栈顶保存位（depth = 0 时 {@code null}）：换入前写入当前字段值，换出时读回。 */
    public static LaneCullSlot saved() {
        return depth == 0 ? null : savedStack[depth - 1];
    }

    /** 栈顶换入的 lane 槽（depth = 0 时 {@code null}）。 */
    public static LaneCullSlot installed() {
        return depth == 0 ? null : installedStack[depth - 1];
    }

    /**
     * 登记一层换入并返回该层的保存位（调用方随即把当前字段值写进去）。
     *
     * @param laneSlot 本层要换入的 lane 槽
     * @return 本层的保存位（复用；首次用到该层时惰性新建）
     */
    public static LaneCullSlot beginPush(LaneCullSlot laneSlot) {
        if (depth == savedStack.length) {
            int grown = savedStack.length * 2;
            LaneCullSlot[] saved = new LaneCullSlot[grown];
            LaneCullSlot[] installed = new LaneCullSlot[grown];
            System.arraycopy(savedStack, 0, saved, 0, savedStack.length);
            System.arraycopy(installedStack, 0, installed, 0, installedStack.length);
            savedStack = saved;
            installedStack = installed;
        }
        if (savedStack[depth] == null) {
            savedStack[depth] = new LaneCullSlot();
        }
        installedStack[depth] = laneSlot;
        depth++;
        return savedStack[depth - 1];
    }

    /** 弹出一层；未配对（depth = 0）时返回 {@code false}。 */
    public static boolean endPop() {
        if (depth == 0) {
            return false;
        }
        depth--;
        installedStack[depth] = null;
        return true;
    }

    // ===== 区块编译完成的逐 lane 投递 =====

    /**
     * 把一个「刚编译完的区块」投给每个 lane 槽的局部更新队列。
     *
     * 只投给候选集已就位的槽：跳过当前换入的槽（原版路径已写它）、未播种的槽、正等全量重建的槽
     * （{@code fullUpdate} = 局部事件会被下一次全量 BFS 覆盖，且避免局部更新去动播种来的别家候选集）。
     * 漏投 = 该区块等下个全量 BFS；重复投 = 多跑一次同源扩张；两者都不影响正确性。
     */
    public static void mirrorCompiled(ChunkRenderDispatcher.RenderChunk renderChunk) {
        LaneCullSlot installed = installed();
        LaneCullSlot[] slots = liveSnapshot;
        for (LaneCullSlot slot : slots) {
            if (slot != installed && slot.storage.get() != null && !slot.fullUpdate) {
                slot.compiled.add(renderChunk);
            }
        }
        LaneCullSlot vanilla = saved();
        if (vanilla != null && vanilla.storage != null && !vanilla.fullUpdate) {
            vanilla.compiled.add(renderChunk);
        }
    }

    // ===== 视距变化 / 换世界 =====

    /**
     * {@code LevelRenderer.allChanged} 后作废全部槽的候选集与可见集合：旧 {@code ViewArea} 的区块已被释放，
     * 槽内引用必须在下一次读取前被新一次全量 BFS 换掉（{@code fullUpdate = true} 保证顺序）。
     * 换入中的槽与各层保存位一并处理——{@code allChanged} 可能发生在 lane pass 内。
     */
    public static void onAllChanged() {
        for (LaneCullSlot slot : liveSnapshot) {
            slot.invalidate();
        }
        for (int i = 0; i < depth; i++) {
            installedStack[i].invalidate();
            savedStack[i].invalidate();
        }
    }

    /**
     * {@code LevelRenderer.needsUpdate()}（窗口尺寸变化 / 选项变化 / F3+A 等「立即全量重建」入口）：
     * 活槽与栈内各层一并标全量重建，可见集合不动（重建后的候选集由各自的门闩重新过滤）。
     */
    public static void onNeedsUpdate() {
        for (LaneCullSlot slot : liveSnapshot) {
            slot.fullUpdate = true;
        }
        for (int i = 0; i < depth; i++) {
            installedStack[i].fullUpdate = true;
            savedStack[i].fullUpdate = true;
        }
    }
}
