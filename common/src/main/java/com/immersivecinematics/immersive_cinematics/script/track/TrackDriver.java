package com.immersivecinematics.immersive_cinematics.script.track;

import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;

/**
 * 轨道驱动接口 — 每种轨道类型一个实现，{@link ScriptPlayer} 只负责按帧调度。
 *
 * 生命周期：{@link #isActiveAt} 判定本帧是否活跃；{@link #onRenderFrame} 每渲染帧计算状态并写入目标系统；
 * {@link #onStop} 释放本轨资源（只清本实例，不影响同帧其它实例）。派生状态在 {@link #onSeek} /
 * {@link #onLoopRestart} / {@link #onScriptReplaced} 归位。
 * 线程契约：仅客户端主线程，逐渲染帧调用。
 * 数据源：实现不持有轨道快照，按轨道索引从 {@link ScriptPlayer#clipsForTrack(int)} 动态取。
 * 创建入口：{@link TrackRegistry#createDriver} 按轨道 id 分派——内置轨走 {@link BuiltinTrackDrivers}，
 * 第三方轨包一层 {@link ExternalTrackDriver}，故新增类型时 ScriptPlayer 零改动。
 */
public interface TrackDriver {

    /** 该轨道在指定全局时间（秒，double 精度）是否活跃。 */
    boolean isActiveAt(double globalTime);

    /** 每渲染帧驱动：计算当前帧状态并写入目标系统（globalTime 单位：秒，double 精度）。 */
    void onRenderFrame(double globalTime);

    /** 停止时释放本轨资源。 */
    void onStop();

    /**
     * 编辑器 seek（{@code editor.seek} / {@code setTime}）时调用：把按时间推进的派生状态归位
     * （相机运动模型实例等），使之后从 seek 点重新推进；时钟基准由 {@link ScriptPlayer#alignTime} 单独调整。
     */
    default void onSeek() {
        // 默认无状态
    }

    /**
     * 宏观循环折返（圈边界，圈内局部时间由大到小）时调用：把按时间推进的派生状态归位，
     * 使新一圈从圈内局部时间重新推进（圈内局部时间对轨道不可见，故由播放器在折返点通知）。
     */
    default void onLoopRestart() {
        // 默认无状态
    }

    /**
     * 脚本数据被替换（编辑器编辑 → replaceScript）时调用。
     * 实现应复位依赖 clip 对象身份的状态（lastClipIndex 等）；
     * 音频轨道在此重映射实例（sound + startTime + duration 匹配复用，避免重解码）。
     */
    default void onScriptReplaced() {
        // 默认无状态
    }
}
