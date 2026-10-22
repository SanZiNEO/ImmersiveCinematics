package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * 客户端播放队列：当前脚本不可打断时新脚本入队（不设上限），阻塞它的实例结束后按优先级
 * （高→低，同优先级先入先出）接播。优先级只决定队列内次序——不可打断的脚本永远不会被打断。
 *
 * 队列键 = 脚本 id：同脚本第二次请求替换自己的排队位（{@link #offerOrReplace}，不占新格）；
 * {@link #cancel} 按脚本 id 撤销排队项。队列跨实例存活——只有全停（{@code forceDeactivate} /
 * {@code emergencyStop}）才 {@link #clear}。
 *
 * 出队侧：{@link #pollFirstEligible(Predicate)} 按 priority/FIFO 选最靠前的可播项（被同脚本在播
 * 实例阻塞的项跳过但保留原位）；不做「取出再放回」的临时重排。每个排队项携带发起该播放请求时的
 * 播放实例 id，供接播时随 {@code C2SPlaybackStarted} 上报。
 *
 * 线程：仅客户端主线程。
 */
public class ScriptQueue {

    /** 队列顺序：priority 降序，同优先级 sequence 升序（FIFO）。 */
    private static final Comparator<QueuedScript> ORDER =
            Comparator.comparingInt((QueuedScript q) -> q.script().getMeta() != null ? q.script().getMeta().getPriority() : 0)
                    .reversed()
                    .thenComparingLong(QueuedScript::sequence);

    /** 一个排队中的播放请求：脚本 + 该请求的播放实例 id（本地来源为空串）。 */
    public record Entry(CinematicScript script, String instanceId) {}

    private record QueuedScript(CinematicScript script, String instanceId, long sequence) {}

    private final PriorityQueue<QueuedScript> queue = new PriorityQueue<>(ORDER);
    private long seq = 0;

    /** 入队（队列不设上限）。 */
    public void offer(CinematicScript s, String instanceId) {
        queue.add(new QueuedScript(s, instanceId == null ? "" : instanceId, seq++));
    }

    /**
     * 入队或替换排队位：同脚本 id 已在排队 → 用本次请求替换该位（instanceId 更新、sequence 保留原位次，
     * 即不插到队尾）；未排队 → 等价 {@link #offer}。
     */
    public void offerOrReplace(CinematicScript s, String instanceId) {
        String id = s.getId();
        for (QueuedScript queued : queue) {
            if (Objects.equals(id, queued.script().getId())) {
                queue.remove(queued);
                queue.add(new QueuedScript(s, instanceId == null ? "" : instanceId, queued.sequence()));
                return;
            }
        }
        offer(s, instanceId);
    }

    /** 按脚本 id 取消排队项；{@code true} = 确有该项被移除（未排队 = {@code false}）。 */
    public boolean cancel(String scriptId) {
        return queue.removeIf(q -> Objects.equals(scriptId, q.script().getId()));
    }

    /** 队头（不出队）；空队列返回 {@code null}。 */
    public Entry peek() {
        QueuedScript head = queue.peek();
        return head == null ? null : new Entry(head.script(), head.instanceId());
    }

    /**
     * 按队列顺序取首个可播项并移除；无可播项返回 {@code null}。
     *
     * 线扫全部排队项，仅移除选中的那一项——不把不可播项临时取出再放回，其他项的相对次序不变。
     *
     * @param eligible 可播判定（入参 = 排队脚本；{@code true} = 当前可启动）
     */
    public Entry pollFirstEligible(Predicate<CinematicScript> eligible) {
        QueuedScript best = null;
        for (QueuedScript candidate : queue) {
            if (!eligible.test(candidate.script())) continue;
            if (best == null || ORDER.compare(candidate, best) < 0) best = candidate;
        }
        if (best == null) return null;
        queue.remove(best);
        return new Entry(best.script(), best.instanceId());
    }

    public void clear() {
        queue.clear();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
