package com.immersivecinematics.immersive_cinematics.api;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本元信息只读快照（对外稳定面）：{@link PlaybackApi#scripts()} 的返回元素。
 *
 * <p>不可变：{@code trackTypes} 在构造时拷贝并冻结，调用方修改源列表不影响本对象。
 *
 * @param id         脚本 id
 * @param name       脚本名（作者可读名）
 * @param duration   有限内容末端（秒，由片段窗口派生）；{@code infinite = true} 时无意义（{@code -1}）
 * @param infinite   内容是否无限（任一无限片段或宏观循环无限）
 * @param trackTypes 时间轴里出现过的轨道类型（去重、按首次出现顺序）；无轨道时为空列表
 */
public record ScriptInfo(String id, String name, double duration, boolean infinite,
                         List<TrackTypeId> trackTypes) {

    public ScriptInfo {
        trackTypes = trackTypes == null ? List.of() : List.copyOf(trackTypes);
    }

    /**
     * 由脚本实例提取元信息（内部使用）：轨道类型取时间轴各轨道的 {@link TimelineTrack#getType()}，
     * 去重并保持首次出现顺序。
     *
     * @param script 源脚本，不得为 {@code null}
     */
    public static ScriptInfo of(CinematicScript script) {
        if (script == null) {
            throw new NullPointerException("script");
        }
        List<TimelineTrack> tracks = script.getTimeline().getTracks();
        List<TrackTypeId> types = new ArrayList<>(tracks.size());
        for (int i = 0; i < tracks.size(); i++) {
            TrackTypeId type = tracks.get(i).getType();
            if (type != null && !types.contains(type)) {
                types.add(type);
            }
        }
        return new ScriptInfo(script.getId(), script.getName(),
                script.hasFiniteContentEnd() ? script.contentEnd() : -1.0,
                script.isInfiniteContent(), types);
    }
}
