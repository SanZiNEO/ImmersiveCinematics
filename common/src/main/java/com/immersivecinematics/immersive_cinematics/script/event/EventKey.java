package com.immersivecinematics.immersive_cinematics.script.event;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.Objects;

/**
 * 事件身份：播放实例 + 片段 + 对象路径 + 事件索引。
 *
 * <p>四个分量都是「身份」而非「内容」：{@link Clip} 未重写 {@code equals}，record 默认比较即对象身份；
 * {@code instanceToken} 是每个播放实例新建的 {@link Object}，同样按身份比较；{@code objectPath}
 * 是对象路径（{@code event} / {@code mod_event}），{@code eventIndex} 是该对象内关键帧的稳定下标。
 * 因此同一脚本的两个播放实例、两个内容相同的片段都不会互相去重。</p>
 *
 * <p>服务端 / 客户端各自持有自己的 token 与已触发集合：身份用于本端去重，不做跨进程比较。</p>
 *
 * @param instanceToken 播放实例令牌（按对象身份比较）
 * @param clip          片段（按对象身份比较）
 * @param objectPath    对象路径（{@code event} 或 {@code mod_event}）
 * @param eventIndex    对象内事件下标（加载时确定）
 */
public record EventKey(Object instanceToken, Clip clip, String objectPath, int eventIndex) {

    public EventKey {
        Objects.requireNonNull(instanceToken, "instanceToken");
        Objects.requireNonNull(clip, "clip");
        Objects.requireNonNull(objectPath, "objectPath");
        if (eventIndex < 0) {
            throw new IllegalArgumentException("eventIndex 不能为负数: " + eventIndex);
        }
    }
}
