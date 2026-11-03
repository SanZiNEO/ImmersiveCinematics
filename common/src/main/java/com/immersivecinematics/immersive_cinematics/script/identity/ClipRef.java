package com.immersivecinematics.immersive_cinematics.script.identity;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 一条「片段使用」引用：被引用的片段 + 引用它的 id 字符串 + 入口局部时间。
 *
 * <p>{@code id} = 引用点写下的片段 id（未命名的轨道放置为 {@code null}，此时只能按
 * {@link #clip()} 的对象身份识别）；{@code entryLocalTime} = 从该片段执行内部的哪一秒进入，
 * 缺省 {@code 0}（从片段起点进入）。</p>
 */
public record ClipRef(Clip clip, String id, double entryLocalTime) {

    public ClipRef {
        if (clip == null) {
            throw new NullPointerException("clip 不能为 null");
        }
        if (!Double.isFinite(entryLocalTime) || entryLocalTime < 0) {
            throw new IllegalArgumentException("entryLocalTime 必须是非负有限秒数，实际: " + entryLocalTime);
        }
    }

    /** 从片段起点进入的引用。 */
    public static ClipRef of(Clip clip) {
        return of(clip, 0.0);
    }

    /** 指定入口局部时间的引用。 */
    public static ClipRef of(Clip clip, double entryLocalTime) {
        return new ClipRef(clip, clip == null ? null : clip.scriptClipId(), entryLocalTime);
    }
}
