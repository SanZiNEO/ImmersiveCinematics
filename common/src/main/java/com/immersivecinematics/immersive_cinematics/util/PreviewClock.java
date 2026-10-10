package com.immersivecinematics.immersive_cinematics.util;

/**
 * 预览播放头时钟：编辑器预览实例的独立时钟，秒数 double 累加，与游戏共享虚拟时钟互不影响。
 *
 * seek 直接写读数（拖播放头 / 停止归零）；推进 = 按真实时间累加；冻结 = 暂停或退出预览通道时
 * 停住并清基准（下次推进不补算冻结期间的真实时间）。
 */
public final class PreviewClock implements Clock {

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
            freeze();
            return;
        }
        if (lastNanos != 0) {
            seconds += (double) (nowNanos - lastNanos) / 1_000_000_000.0;
        }
        lastNanos = nowNanos;
    }

    /** 冻结并清基准：读数不变，下次推进从当前时刻起算。 */
    public void freeze() {
        lastNanos = 0;
    }

    /** 定位播放头（秒）：直接写读数。 */
    public void seek(double seconds) {
        this.seconds = seconds;
    }

    @Override
    public double seconds() {
        return seconds;
    }
}
