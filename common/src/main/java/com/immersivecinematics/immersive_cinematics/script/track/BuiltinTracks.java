package com.immersivecinematics.immersive_cinematics.script.track;

import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.List;

/**
 * 内置轨道的固定顺序：注册顺序 = 编辑器 / 导出顺序 = 新建脚本骨架里的轨道顺序。
 *
 * <p>只承载 id 顺序；字段表在 {@code TrackSchemas}，驱动工厂在 {@link BuiltinTrackDrivers}——
 * 这里<b>不</b>建第二份 schema 表。</p>
 */
public final class BuiltinTracks {

    private static final List<TrackTypeId> IDS = List.of(
            TrackTypeId.CAMERA,
            TrackTypeId.LETTERBOX,
            TrackTypeId.AUDIO,
            TrackTypeId.EVENT,
            TrackTypeId.MOD_EVENT,
            TrackTypeId.OVERLAY,
            TrackTypeId.ADJUST);

    private BuiltinTracks() {}

    /**
     * 内置 7 个轨道 id（不可变，固定顺序）。
     *
     * @return id 列表
     */
    public static List<TrackTypeId> ids() {
        return IDS;
    }
}
