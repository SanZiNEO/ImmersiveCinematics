package com.immersivecinematics.immersive_cinematics.script.track;

import com.immersivecinematics.immersive_cinematics.script.AdjustTrackPlayer;
import com.immersivecinematics.immersive_cinematics.script.AudioTrackPlayer;
import com.immersivecinematics.immersive_cinematics.script.CameraTrackPlayer;
import com.immersivecinematics.immersive_cinematics.script.LetterboxTrackPlayer;
import com.immersivecinematics.immersive_cinematics.script.ModEventTrackPlayer;
import com.immersivecinematics.immersive_cinematics.script.OverlayTrackPlayer;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置轨道的驱动工厂：id → 具体 {@link TrackDriver} 实现。
 *
 * <p>不含 {@link TrackTypeId#EVENT}（事件轨在服务端由 {@code ScriptEventManager} 驱动，客户端不建驱动），
 * 因此 {@link #factoryFor} 对它是 {@code null}——{@link TrackRegistry#createDriver} 据此返回 {@code null}。</p>
 */
public final class BuiltinTrackDrivers {

    private static final Map<TrackTypeId, TrackRegistry.TrackDriverFactory> FACTORIES = new LinkedHashMap<>();

    static {
        FACTORIES.put(TrackTypeId.CAMERA,
                (player, originPos, cameraManager, overlayManager, trackIndex) ->
                        new CameraTrackPlayer(player, originPos, cameraManager, trackIndex));
        FACTORIES.put(TrackTypeId.LETTERBOX,
                (player, originPos, cameraManager, overlayManager, trackIndex) ->
                        new LetterboxTrackPlayer(player, overlayManager, trackIndex));
        FACTORIES.put(TrackTypeId.AUDIO,
                (player, originPos, cameraManager, overlayManager, trackIndex) ->
                        new AudioTrackPlayer(player, originPos, trackIndex));
        FACTORIES.put(TrackTypeId.MOD_EVENT,
                (player, originPos, cameraManager, overlayManager, trackIndex) ->
                        new ModEventTrackPlayer(player, trackIndex));
        FACTORIES.put(TrackTypeId.OVERLAY,
                (player, originPos, cameraManager, overlayManager, trackIndex) ->
                        new OverlayTrackPlayer(player, overlayManager, trackIndex));
        FACTORIES.put(TrackTypeId.ADJUST,
                (player, originPos, cameraManager, overlayManager, trackIndex) ->
                        new AdjustTrackPlayer(player, trackIndex));
    }

    private BuiltinTrackDrivers() {}

    /**
     * 取内置轨道的驱动工厂。
     *
     * @param id 轨道类型 id
     * @return 工厂；{@code EVENT} 或非内置 id 返回 {@code null}
     */
    public static TrackRegistry.TrackDriverFactory factoryFor(TrackTypeId id) {
        return id == null ? null : FACTORIES.get(id);
    }
}
