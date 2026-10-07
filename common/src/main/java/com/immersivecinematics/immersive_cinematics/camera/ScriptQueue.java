package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * 客户端播放队列（C1）：当前脚本不可打断时，新脚本一律入队（容量 8，满则拒绝），
 * 当前脚本结束后按优先级（高→低，同优先级先入先出）自动接播。
 * 优先级仅用于队列内排序——不可打断的脚本永远不会被打断（优先级不能大于打断）。
 *
 * <p>每个排队项携带发起该播放请求时的播放实例 id（§3.7），供接播时随 {@code C2SPlaybackStarted} 上报。
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
