package com.immersivecinematics.immersive_cinematics.util;

/**
 * 游戏共享虚拟时钟：按真实时间累加的秒数，游戏暂停时冻结、末实例退出时归零。
 *
 * 冻结 = 本帧不累加并清掉基准：恢复后的第一帧只重新起算基准，不补算冻结期间的真实时间，
 * 因此暂停 / 恢复不产生时间跳变。累加源为 {@code System.nanoTime()}（单调，不受系统时钟调整影响）。
 */
public final class GameClock implements Clock {

    private double seconds = 0;

    /** 上一推进帧的真实纳秒；0 = 无基准（本帧只起算基准，不累加） */
    private long lastNanos = 0;

    /**
     * 帧内推进一次。
     *
     * @param frozen {@code true} = 冻结（不累加并清基准）
     * @param nowNanos 本帧真实纳秒
     */
    public void advance(boolean frozen, long nowNanos) {
        if (frozen) {
            lastNanos = 0;
            return;
        }
        if (lastNanos != 0) {
            seconds += (double) (nowNanos - lastNanos) / 1_000_000_000.0;
        }
        lastNanos = nowNanos;
    }

    /** 归零：读数回 0 并清基准。 */
    public void reset() {
        seconds = 0;
        lastNanos = 0;
    }

    @Override
    public double seconds() {
        return seconds;
    }
}
