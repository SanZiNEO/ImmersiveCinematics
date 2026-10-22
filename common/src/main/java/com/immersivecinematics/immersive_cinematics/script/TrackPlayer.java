package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 轨道播放器接口 — 每种轨道类型一个实现，{@link ScriptPlayer} 只负责按帧调度。
 *
 * 生命周期：{@link #isActiveAt} 判定本帧是否活跃；{@link #onRenderFrame} 每渲染帧计算状态并写入目标系统；
 * {@link #onStop} 释放本轨资源（只清本实例，不影响同帧其它实例）。派生状态在 {@link #onSeek} /
 * {@link #onLoopRestart} / {@link #onScriptReplaced} 归位。
 * 线程契约：仅客户端主线程，逐渲染帧调用。
 * 数据源：实现不持有轨道快照，按轨道索引从 {@link ScriptPlayer#clipsForTrack(int)} 动态取。
 * 新增轨道类型 = 新建实现 + 在 {@link #create} 注册：解耦 {@link ScriptPlayer} 与具体轨道类型，
 * 它不直接触碰各轨道系统的写入 API，故新增类型时 ScriptPlayer 零改动。
 */
public interface TrackPlayer {

    /** 该轨道在指定全局时间（秒）是否活跃。 */
    boolean isActiveAt(float globalTime);

    /** 每渲染帧驱动：计算当前帧状态并写入目标系统（globalTime 单位：秒）。 */
    void onRenderFrame(float globalTime);

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

    /**
     * 工厂方法 — 按轨道类型创建对应的 TrackPlayer。
     *
     * 实现不持有轨道数据快照：数据源在运行时从 {@link ScriptPlayer#clipsForTrack(int)} 取，
     * 编辑器编辑走 replaceScript 增量替换，TrackPlayer 零重建。
     *
     * @param type           轨道类型
     * @param scriptPlayer   所属播放器（动态数据源）
     * @param originPos      相对模式基准位置
     * @param cameraManager  相机管理器（CAMERA 轨道需要）
     * @param overlayManager 覆盖层管理器（LETTERBOX / OVERLAY 轨道需要）
     * @param trackIndex     轨道索引（支持同类型多条轨道，如多 OVERLAY 轨道）
     * @return 对应的 TrackPlayer 实例
     */
    static TrackPlayer create(TrackType type, ScriptPlayer scriptPlayer, Vec3 originPos,
                              com.immersivecinematics.immersive_cinematics.camera.CameraManager cameraManager,
                              com.immersivecinematics.immersive_cinematics.overlay.OverlayManager overlayManager,
                              int trackIndex) {
        return switch (type) {
            case CAMERA -> new CameraTrackPlayer(scriptPlayer, type, originPos, cameraManager, trackIndex);
            case LETTERBOX -> new LetterboxTrackPlayer(scriptPlayer, type, overlayManager, trackIndex);
            case AUDIO -> new AudioTrackPlayer(scriptPlayer, type, originPos, trackIndex);
            case MOD_EVENT -> new ModEventTrackPlayer(scriptPlayer, type, trackIndex);
            case OVERLAY -> new OverlayTrackPlayer(scriptPlayer, type, overlayManager, trackIndex);
            case ADJUST -> new AdjustTrackPlayer(scriptPlayer, trackIndex);
            default -> throw new IllegalArgumentException("未知轨道类型: " + type);
        };
    }
}
