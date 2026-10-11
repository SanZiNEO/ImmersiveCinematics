package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.api.BaseCameraProvider;
import com.immersivecinematics.immersive_cinematics.api.CameraState;

/**
 * 飞行 Base 层（档位 {@link BaseCameraProvider#PRIORITY_FLIGHT}）。
 *
 * 活跃条件：飞行 / 取景直控态（{@code FlightModeManager} 进入即置位，退出 / 停播清除）——
 * 该态下轨道写入被跳过，相机由飞行控制器逐帧直写。
 * 状态：全局写入缓冲的当前值（同 {@link ScriptDirectorBase}）。
 */
final class FlightBase implements BaseCameraProvider {

    private final PreviewChannel previewChannel;
    private final CameraStateHolder stateHolder;

    FlightBase(PreviewChannel previewChannel, CameraStateHolder stateHolder) {
        this.previewChannel = previewChannel;
        this.stateHolder = stateHolder;
    }

    @Override
    public boolean isBaseActive() {
        return previewChannel.isPreviewDirectControl();
    }

    @Override
    public int basePriority() {
        return PRIORITY_FLIGHT;
    }

    @Override
    public CameraState provideBaseState() {
        return stateHolder.current();
    }
}
