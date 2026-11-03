package com.immersivecinematics.immersive_cinematics.script.event;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.List;

/**
 * 服务端 EVENT 时间线的 seek 数学：把每条事件运行时按「全局秒」重定位到对应片段局部时间。
 *
 * <p>只做一件事——逐片段算出局部时间并调 {@link EventClipRuntime#reset(double)}：
 * 游标移到目标时间之后的下一条、清空已触发标记。因此</p>
 * <ul>
 *   <li>前跳：目标之前的记录不再补触发（游标已越过）；</li>
 *   <li>后跳回退：目标之后的记录可再次触发（已触发标记被清空）；</li>
 *   <li>目标落在片段之前 = 回到片段开头；落在有限片段之后 = 停在片段末尾（不会越界推进）。</li>
 * </ul>
 *
 * <p>无其它副作用：不读写暂停态、时间线基准、扫描进度等 {@code ScriptPlayback} 字段，
 * 也不改 {@link EventClipRuntime#advance(double)} 的单调语义。暂停与 seek 正交。</p>
 */
public final class EventSeekPlan {

    private EventSeekPlan() {}

    /**
     * 把每条事件运行时重置到 {@code targetSeconds}（全局秒）对应的片段局部时间；越界钳制到
     * {@code [0, duration]}（无限片段不钳上界）。
     *
     * @param eventClips    事件片段表（{@code ScriptPlayback.eventClips}）
     * @param runtimes      与 {@code eventClips} 一一对应的事件运行时（{@code ScriptPlayback.eventRuntimes}）
     * @param targetSeconds 目标全局秒；非有限或负数按 {@code 0} 处理（回到脚本起点）
     */
    public static void resetAll(List<Clip> eventClips, List<EventClipRuntime> runtimes, double targetSeconds) {
        if (eventClips == null || runtimes == null) return;
        double target = Double.isFinite(targetSeconds) && targetSeconds > 0.0 ? targetSeconds : 0.0;
        int count = Math.min(eventClips.size(), runtimes.size());
        for (int i = 0; i < count; i++) {
            Clip clip = eventClips.get(i);
            EventClipRuntime runtime = runtimes.get(i);
            if (clip == null || runtime == null) continue;
            runtime.reset(localTime(clip, target));
        }
    }

    /**
     * 全局秒 → 片段局部秒：{@code target - clip.startTime}，钳制到 {@code [0, duration]}；
     * 无限片段（{@code duration < 0}）不钳上界。
     */
    public static double localTime(Clip clip, double targetSeconds) {
        double local = targetSeconds - clip.getStartTime();
        if (local < 0.0) return 0.0;
        if (!clip.isInfinite() && local > clip.getDuration()) return clip.getDuration();
        return local;
    }
}
