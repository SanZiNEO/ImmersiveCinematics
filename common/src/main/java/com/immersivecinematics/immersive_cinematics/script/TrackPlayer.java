package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.world.phys.Vec3;

/**
 * 轨道播放器接口 — 解耦 ScriptPlayer 与具体轨道类型的处理逻辑
 * <p>
 * 每种轨道类型（CAMERA/LETTERBOX/AUDIO/MOD_EVENT）有对应的 TrackPlayer 实现，
 * ScriptPlayer 只负责调度（遍历 trackPlayers，调用 onRenderFrame），
 * 不再直接访问 CameraManager/OverlayManager 的写入方法。
 * <p>
 * 新增轨道类型时只需：
 * <ol>
 *   <li>创建新的 TrackPlayer 实现类</li>
 *   <li>在 {@link #create} 工厂方法中注册</li>
 * </ol>
 * ScriptPlayer 零改动。
 */
public interface TrackPlayer {

    /**
     * 判断该轨道在指定全局时间是否活跃
     *
     * @param globalTime 全局时间（秒）
     * @return 是否活跃
     */
    boolean isActiveAt(float globalTime);

    /**
     * 每渲染帧驱动 — 计算当前帧状态并写入目标系统
     *
     * @param globalTime 全局时间（秒）
     */
    void onRenderFrame(float globalTime);

    /**
     * 停止时清理资源
     */
    void onStop();

    /**
     * 组 A：脚本数据被替换（编辑器编辑 → replaceScript）时调用。
     * 实现应复位依赖 clip 对象身份的状态（lastClipIndex 等）；
     * 音频轨道在此重映射实例（sound+startTime+duration 匹配复用，避免重解码）。
     */
    default void onScriptReplaced() {
        // 默认无状态
    }

    /**
     * 工厂方法 — 根据轨道类型创建对应的 TrackPlayer
     * <p>
     * 组 A：TrackPlayer 不再持有轨道数据快照，数据源改为运行时从 {@link ScriptPlayer}
     * 动态获取（{@link ScriptPlayer#clipsForTrack(TrackType)}）——编辑器编辑走 replaceScript
     * 增量替换，TrackPlayer 零重建、常驻活跃。
     *
     * @param type            轨道类型
     * @param scriptPlayer    所属播放器（动态数据源）
     * @param originPos       相对模式基准位置
     * @param cameraManager   相机管理器（Camera 轨道需要）
     * @param overlayManager  覆盖层管理器（Letterbox/Overlay 轨道需要）
     * @param trackIndex      轨道索引（数据源定位；支持同类型多条轨道，如多 OVERLAY 轨道）
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
            default -> throw new IllegalArgumentException("未知轨道类型: " + type);
        };
    }
}
