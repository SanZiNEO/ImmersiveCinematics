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
     * 工厂方法 — 根据轨道类型创建对应的 TrackPlayer
     * <p>
     * 速度驱动模型下，插值控制已下放到片段级（CameraClip.speed/interpolation），
     * 脚本级不再传递插值参数。
     *
     * @param track               时间轴轨道
     * @param originPos           相对模式基准位置
     * @param cameraManager       相机管理器（Camera 轨道需要）
     * @param overlayManager      覆盖层管理器（Letterbox 轨道需要）
     * @return 对应的 TrackPlayer 实例
     */
    static TrackPlayer create(TimelineTrack track, Vec3 originPos,
                              com.immersivecinematics.immersive_cinematics.camera.CameraManager cameraManager,
                              com.immersivecinematics.immersive_cinematics.overlay.OverlayManager overlayManager) {
        return switch (track.getType()) {
            case CAMERA -> new CameraTrackPlayer(track, originPos, cameraManager);
            case LETTERBOX -> new LetterboxTrackPlayer(track, overlayManager);
            case AUDIO -> new AudioTrackPlayer(track);
            case MOD_EVENT -> new ModEventTrackPlayer(track);
            default -> throw new IllegalArgumentException("未知轨道类型: " + track.getType());
        };
    }
}
