package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.camera.cover.BaseCameraProvider;

/**
 * 编辑器直控 Base 层（档位 {@link BaseCameraProvider#PRIORITY_EDITOR_DIRECT}）。
 *
 * 活跃条件：本帧发生过五参数直写——活跃窗口实际只覆盖「直写穿透刷新」那一瞬
 * （{@link CameraManager#setCameraDirect(float, float, float, float, float)} 内的那次求值）；
 * 帧末求值因帧首已清零而恒不活跃，本层与脚本导演层同源同值，故本版本无可观察差异。
 * 「直控压制脚本导演层」的语义要等各层各持自有状态快照后才生效（B4-P5 / 定稿 §12）。
 * 状态：全局写入缓冲的当前值（同 {@link ScriptDirectorBase}）。
 */
final class DirectControlBase implements BaseCameraProvider {

    private final CameraStateHolder stateHolder;

    /** 本帧是否发生过五参数直写；帧首由 {@link CameraManager#onRenderFrame()} 清零。 */
    private boolean writtenThisFrame = false;

    DirectControlBase(CameraStateHolder stateHolder) {
        this.stateHolder = stateHolder;
    }

    /** 直写入口上报（{@link CameraManager#setCameraDirect(float, float, float, float, float)}）。 */
    void markWritten() {
        writtenThisFrame = true;
    }

    /** 帧首清零：活跃窗口收口到下一帧开头（本版本帧末求值恒不活跃）。 */
    void clearFrame() {
        writtenThisFrame = false;
    }

    @Override
    public boolean isBaseActive() {
        return writtenThisFrame;
    }

    @Override
    public int basePriority() {
        return PRIORITY_EDITOR_DIRECT;
    }

    @Override
    public CameraState provideBaseState() {
        return stateHolder.current();
    }
}
