package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Camera 轨道播放器 — 从 ScriptPlayer.processCameraTrack() 抽取
 * <p>
 * 职责：
 * <ul>
 *   <li>定位当前活跃的 CameraClip</li>
 *   <li>调用 KeyframeInterpolator 计算相机状态</li>
 *   <li>将结果写入注入的 CameraManager</li>
 * </ul>
 */
public class CameraTrackPlayer implements TrackPlayer {

    private final List<CameraClip> clips;
    private final Vec3 originPos;
    private final CameraManager cameraManager;
    private final InterpolationType scriptInterpolation;
    private final CurveCompositionMode curveCompositionMode;

    public CameraTrackPlayer(TimelineTrack track, Vec3 originPos, CameraManager cameraManager,
                             InterpolationType scriptInterpolation, CurveCompositionMode curveCompositionMode) {
        this.clips = track.getCameraClips();
        this.originPos = originPos;
        this.cameraManager = cameraManager;
        this.scriptInterpolation = scriptInterpolation;
        this.curveCompositionMode = curveCompositionMode;
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        if (clips.isEmpty()) return;

        CameraClip activeClip = findActiveClip(globalTime);
        if (activeClip == null) return;

        float clipLocalTime = globalTime - activeClip.getStartTime();

        // 计算插值（根据 curveCompositionMode 选择覆盖/组合模式）
        KeyframeInterpolator.InterpolationResult result =
                KeyframeInterpolator.computeInterpolation(clipLocalTime, activeClip,
                        scriptInterpolation, curveCompositionMode);

        if (result == null) return;

        // 插值所有属性（KeyframeInterpolator 已保证输出合法，无需额外 sanitize）
        Vec3 pos = KeyframeInterpolator.interpolatePosition(result.from, result.to, result.adjustedT, activeClip);
        float yaw = KeyframeInterpolator.interpolateYaw(result.from, result.to, result.adjustedT);
        float pitch = KeyframeInterpolator.interpolatePitch(result.from, result.to, result.adjustedT);
        float roll = KeyframeInterpolator.interpolateRoll(result.from, result.to, result.adjustedT);
        float fov = KeyframeInterpolator.interpolateFov(result.from, result.to, result.adjustedT);
        float zoom = KeyframeInterpolator.interpolateZoom(result.from, result.to, result.adjustedT);
        float dof = KeyframeInterpolator.interpolateDof(result.from, result.to, result.adjustedT);

        // 相对模式：加上玩家基准位置
        if (activeClip.isPositionModeRelative()) {
            pos = originPos.add(pos);
        }

        // 写入 CameraManager active 缓冲区
        cameraManager.getPath().setPositionDirect(pos);
        cameraManager.getProperties().setYawDirect(yaw);
        cameraManager.getProperties().setPitchDirect(pitch);
        cameraManager.getProperties().setRollDirect(roll);
        cameraManager.getProperties().setFovDirect(fov);
        cameraManager.getProperties().setZoomDirect(zoom);
        cameraManager.getProperties().setDofDirect(dof);
    }

    @Override
    public void onStop() {
        // Camera 轨道无需特殊清理
    }

    /**
     * 找到当前全局时间所在的 camera clip
     * <p>
     * 如果时间在所有 clip 之前，返回第一个 clip；
     * 如果时间在所有 clip 之后，返回最后一个 clip；
     * 如果在两个 clip 之间的间隙，返回前一个 clip（保持最后一帧）。
     * 无限时长 clip（duration=-1）一旦进入就永远活跃。
     */
    private CameraClip findActiveClip(float globalTime) {
        CameraClip result = null;

        for (int i = 0; i < clips.size(); i++) {
            CameraClip clip = clips.get(i);
            float clipEnd = clip.getStartTime() + clip.getDuration();

            // 无限时长 clip：一旦进入就永远活跃
            if (clip.isInfinite()) {
                if (globalTime >= clip.getStartTime()) {
                    result = clip;  // 记录但继续查找，可能有更具体的有限时长 clip
                }
                continue;
            }

            if (globalTime >= clip.getStartTime() && globalTime < clipEnd) {
                return clip;  // 有限时长 clip 精确匹配，优先返回
            }
        }

        // 没有精确匹配的有限时长 clip，返回最后匹配的无限时长 clip
        if (result != null) return result;

        if (clips.isEmpty()) return null;

        // 在所有 clip 之前：返回第一个 clip
        if (globalTime < clips.get(0).getStartTime()) {
            return clips.get(0);
        }

        // 在所有 clip 之后：返回最后一个 clip
        return clips.get(clips.size() - 1);
    }
}
