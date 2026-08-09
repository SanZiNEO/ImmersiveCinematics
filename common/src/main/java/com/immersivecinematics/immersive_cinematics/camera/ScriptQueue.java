package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * 客户端播放队列（C1）：当前脚本不可打断时，新脚本一律入队（容量 8，满则拒绝），
 * 当前脚本结束后按优先级（高→低，同优先级先入先出）自动接播。
 * 优先级仅用于队列内排序——不可打断的脚本永远不会被打断（优先级不能大于打断）。
 */
public class ScriptQueue {

    public static final int CAPACITY = 8;

    private record QueuedScript(CinematicScript script, long sequence) {}

    private final PriorityQueue<QueuedScript> queue = new PriorityQueue<>(
            Comparator.comparingInt((QueuedScript q) -> q.script().getMeta() != null ? q.script().getMeta().getPriority() : 0)
                    .reversed()
                    .thenComparingLong(QueuedScript::sequence));
    private long seq = 0;

    /** 入队成功返回 true；队列已满返回 false */
    public boolean offer(CinematicScript s) {
        if (queue.size() >= CAPACITY) return false;
        queue.add(new QueuedScript(s, seq++));
        return true;
    }

    public CinematicScript poll() {
        QueuedScript q = queue.poll();
        return q == null ? null : q.script();
    }

    public void clear() {
        queue.clear();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
