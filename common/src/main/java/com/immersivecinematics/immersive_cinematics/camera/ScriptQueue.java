package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * 客户端播放队列：当前脚本不可打断时新脚本入队（容量 8，满则拒绝），阻塞它的实例结束后按优先级
 * （高→低，同优先级先入先出）接播。优先级只决定队列内次序——不可打断的脚本永远不会被打断。
 *
 * <p>队列键 = 脚本 id：同脚本第二次请求替换自己的排队位（{@link #offerOrReplace}，不占新格）；
 * {@link #cancel} 按脚本 id 撤销排队项。队列跨实例存活——只有全停（{@code forceDeactivate} /
 * {@code emergencyStop}）才 {@link #clear}。
 *
 * <p>每个排队项携带发起该播放请求时的播放实例 id（§3.7），供接播时随 {@code C2SPlaybackStarted} 上报。
 *
 * <p>线程：仅客户端主线程。
 */
public class ScriptQueue {

    public static final int CAPACITY = 8;

    /** 一个排队中的播放请求：脚本 + 该请求的播放实例 id（本地来源为空串）。 */
    public record Entry(CinematicScript script, String instanceId) {}

    private record QueuedScript(CinematicScript script, String instanceId, long sequence) {}

    private final PriorityQueue<QueuedScript> queue = new PriorityQueue<>(
            Comparator.comparingInt((QueuedScript q) -> q.script().getMeta() != null ? q.script().getMeta().getPriority() : 0)
                    .reversed()
                    .thenComparingLong(QueuedScript::sequence));
    private long seq = 0;

    /** 入队成功返回 true；队列已满返回 false */
    public boolean offer(CinematicScript s, String instanceId) {
        if (queue.size() >= CAPACITY) return false;
        queue.add(new QueuedScript(s, instanceId == null ? "" : instanceId, seq++));
        return true;
    }

    /**
     * 入队或替换排队位：同脚本 id 已在排队 → 用本次请求替换该位（instanceId 更新、sequence 保留原位次，
     * 即不插到队尾）；未排队 → 等价 {@link #offer}。
     *
     * @return {@code true} = 已入队（含替换）；{@code false} = 队列已满且该脚本未排队
     */
    public boolean offerOrReplace(CinematicScript s, String instanceId) {
        String id = s.getId();
        for (QueuedScript queued : queue) {
            if (Objects.equals(id, queued.script().getId())) {
                queue.remove(queued);
                queue.add(new QueuedScript(s, instanceId == null ? "" : instanceId, queued.sequence()));
                return true;
            }
        }
        return offer(s, instanceId);
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

    public Entry poll() {
        QueuedScript q = queue.poll();
        return q == null ? null : new Entry(q.script(), q.instanceId());
    }

    public void clear() {
        queue.clear();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
