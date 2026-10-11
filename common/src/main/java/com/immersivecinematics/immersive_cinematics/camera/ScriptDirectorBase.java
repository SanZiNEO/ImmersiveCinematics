package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.api.BaseCameraProvider;
import com.immersivecinematics.immersive_cinematics.api.CameraState;

/**
 * 脚本导演 Base 层（档位 {@link BaseCameraProvider#PRIORITY_SCRIPT_DIRECTOR}）。
 *
 * 活跃条件：有活跃播放实例（实例存活期间脚本导演持有相机）——片段间隙 / 本帧未写出相机状态的帧
 * 仍然活跃，状态保持写入缓冲的上一次写入值。
 * 状态：全局写入缓冲的当前值。脚本导演 / 编辑器直控 / 飞行三层写的是同一份写入缓冲，
 * 层与层的差别只在活跃条件与优先级；写入方见 {@link CameraManager#setScriptState}。
 * Modifier 抑制：跟当前值的来源片段（clip 级 {@code suppress_modifiers}），随写入缓冲同步。
 */
final class ScriptDirectorBase implements BaseCameraProvider {

    private final PlaybackRegistry registry;
    private final CameraStateHolder stateHolder;

    ScriptDirectorBase(PlaybackRegistry registry, CameraStateHolder stateHolder) {
        this.registry = registry;
        this.stateHolder = stateHolder;
    }

    @Override
    public boolean isBaseActive() {
        return registry.isActive();
    }

    @Override
    public int basePriority() {
        return PRIORITY_SCRIPT_DIRECTOR;
    }

    @Override
    public CameraState provideBaseState() {
        return stateHolder.current();
    }

    @Override
    public boolean suppressesModifiers() {
        return stateHolder.suppressesModifiers();
    }
}
