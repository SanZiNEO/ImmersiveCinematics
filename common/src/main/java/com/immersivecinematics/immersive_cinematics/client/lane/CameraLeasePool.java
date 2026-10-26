package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 相机租约池：每个 CAMERA 片段一次租约，槽位 + 代际构成视角状态的唯一身份。
 *
 * <p>登记：{@link IdentityHashMap} 按播放实例身份登记其租约列表；空闲槽表按最小槽位优先分配，
 * {@code generations} 按需扩容。获取时同一 {@code owner + clip}（对象身份）且维度相同直接复用；
 * 维度不同先归还旧租约再新建。归还时删除视角快照 / 颜色历史、归还剔除槽、槽位回空闲表，
 * 代际在下一次分配时自增，旧句柄因此不会命中新租约。</p>
 *
 * <p>对账：{@link #reconcile} 按对象身份释放不在本帧活跃片段列表里的租约。列表规模 = 活跃片段数，
 * 稳态帧内不新建租约，也不新建池级集合。</p>
 *
 * <p>线程契约：只在客户端主 / 渲染线程调用，无同步。{@link #release(CameraLease)} 必须在 lane pass
 * 之外调用（{@code LaneCullSlots.depth() == 0}）：归还的剔除槽会立即回到池里复用，lane pass 内归还
 * 会破坏当前换入的剔除状态。</p>
 */
public final class CameraLeasePool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 播放实例 → 该实例当前持有的租约（按获取顺序；身份比较）。 */
    private static final Map<PlaybackInstance, List<CameraLease>> LEASES = new IdentityHashMap<>();
    /** 空闲槽位（最小优先）。 */
    private static final TreeSet<Integer> FREE_SLOTS = new TreeSet<>();
    /** 每个槽位的当前代际（按需扩容）。 */
    private static int[] generations = new int[0];
    /** 下一个从未分配过的槽位编号；{@link #generations} 的长度只是容量，不能当已分配数量用。 */
    private static int nextSlotId;
    /** 当前活跃租约数（O(1) 读数）。 */
    private static int activeLeaseCount;

    private CameraLeasePool() {
    }

    /**
     * 获取（或复用）一条片段租约。同一 {@code owner + clip} 且维度相同 → 复用；维度不同 → 先归还再新建。
     * 新租约取最小空闲槽位（无空闲则新分配），代际自增，并借一个干净剔除槽。
     *
     * @param owner     播放实例
     * @param clip      CAMERA 片段（对象身份）
     * @param dimension 渲染维度；{@code null} 归一化为空串（玩家当前维度）
     * @return 该片段的租约；同一片段重复调用返回同一句柄
     */
    public static CameraLease acquire(PlaybackInstance owner, Clip clip, String dimension) {
        if (owner == null || clip == null) {
            throw new IllegalArgumentException("camera lease requires owner and clip");
        }
        String dim = dimension != null ? dimension : "";
        List<CameraLease> owned = LEASES.get(owner);
        if (owned != null) {
            for (int i = 0; i < owned.size(); i++) {
                CameraLease lease = owned.get(i);
                if (lease.clip() == clip) {
                    if (lease.dimension().equals(dim)) {
                        return lease;
                    }
                    release(lease);
                    break;
                }
            }
        }
        owned = LEASES.computeIfAbsent(owner, ignored -> new ArrayList<>(2));
        int slotId = allocateSlot();
        int generation = ++generations[slotId];
        String viewKey = "lease" + slotId + "g" + generation + (dim.isEmpty() ? "" : "@" + dim);
        CameraLease lease = new CameraLease(owner, clip, dim, slotId, generation, viewKey,
                LaneCullSlots.acquire());
        owned.add(lease);
        activeLeaseCount++;
        if (Config.debugLogging) {
            LOGGER.info("[camera-lease] acquire script={} clipStart={} slot={} gen={} active={} view={}",
                    owner.scriptId(), clip.getStartTime(), slotId, generation, activeLeaseCount, viewKey);
        }
        return lease;
    }

    /**
     * 按对象身份释放本帧不再活跃的租约。
     *
     * @param owner       播放实例
     * @param activeClips 本帧活跃片段（身份比较；{@code null} = 空）
     */
    public static void reconcile(PlaybackInstance owner, List<Clip> activeClips) {
        if (owner == null) {
            return;
        }
        List<CameraLease> owned = LEASES.get(owner);
        if (owned == null) {
            return;
        }
        for (int i = owned.size() - 1; i >= 0; i--) {
            CameraLease lease = owned.get(i);
            if (!containsIdentity(activeClips, lease.clip())) {
                release(lease);
            }
        }
    }

    /**
     * 归还一条租约（幂等）：只释放仍登记在本实例列表里的租约；释放视角快照 / 颜色历史、
     * 归还剔除槽、槽位回空闲表。必须在 lane pass 之外调用。
     */
    public static void release(CameraLease lease) {
        if (lease == null) {
            return;
        }
        List<CameraLease> owned = LEASES.get(lease.owner());
        if (owned == null) {
            return;
        }
        int index = indexOfIdentity(owned, lease);
        if (index < 0) {
            return;
        }
        owned.remove(index);
        if (owned.isEmpty()) {
            LEASES.remove(lease.owner());
        }
        // 视角快照释放失败也必须归还剔除槽 / 空闲槽 / 计数，否则一条异常会留下永久泄漏
        try {
            ViewStateCapture.releaseView(lease.viewKey());
        } finally {
            LaneCullSlots.release(lease.cull());
            FREE_SLOTS.add(lease.slotId());
            activeLeaseCount--;
        }
        if (Config.debugLogging) {
            LOGGER.info("[camera-lease] release script={} clipStart={} slot={} gen={} active={} view={}",
                    lease.owner().scriptId(), lease.clip().getStartTime(), lease.slotId(), lease.generation(),
                    activeLeaseCount, lease.viewKey());
        }
    }

    /** 归还某个播放实例的全部租约（实例停止 / 替换脚本 / 异常兜底）。 */
    public static void releaseOwner(PlaybackInstance owner) {
        if (owner == null) {
            return;
        }
        List<CameraLease> owned = LEASES.get(owner);
        if (owned == null || owned.isEmpty()) {
            return;
        }
        for (int i = owned.size() - 1; i >= 0; i--) {
            release(owned.get(i));
        }
        if (Config.debugLogging) {
            LOGGER.info("[camera-lease] releaseOwner script={} active={}", owner.scriptId(), activeLeaseCount);
        }
    }

    /** 归还全部租约（世界卸载 / 相机管理器停用）。 */
    public static void releaseAll() {
        if (!LEASES.isEmpty()) {
            List<PlaybackInstance> owners = new ArrayList<>(LEASES.keySet());
            for (int i = 0; i < owners.size(); i++) {
                releaseOwner(owners.get(i));
            }
        }
        // 防漏检查：正常收尾后活跃租约数必须归零（仅调试日志，不影响清理）
        if (Config.debugLogging && activeLeaseCount() != 0) {
            LOGGER.warn("[camera-lease] releaseAll 后仍有 {} 条租约", activeLeaseCount());
        }
    }

    /** 当前活跃租约数（诊断 / 验收用）。 */
    public static int activeLeaseCount() {
        return activeLeaseCount;
    }

    /** 取最小空闲槽位；没有空闲则按需扩容 generations 并返回新槽位。 */
    private static int allocateSlot() {
        Integer free = FREE_SLOTS.pollFirst();
        if (free != null) {
            return free;
        }
        int slotId = nextSlotId++;
        if (slotId >= generations.length) {
            int grown = generations.length == 0 ? 4 : generations.length * 2;
            while (grown <= slotId) {
                grown *= 2;
            }
            int[] next = new int[grown];
            System.arraycopy(generations, 0, next, 0, generations.length);
            generations = next;
        }
        return slotId;
    }

    private static int indexOfIdentity(List<CameraLease> leases, CameraLease lease) {
        for (int i = 0; i < leases.size(); i++) {
            if (leases.get(i) == lease) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsIdentity(List<Clip> clips, Clip clip) {
        if (clips == null) {
            return false;
        }
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i) == clip) {
                return true;
            }
        }
        return false;
    }
}
