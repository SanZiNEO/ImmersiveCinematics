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

    /** 缓存上一帧匹配的 clip 索引，用于优化顺序播放时的搜索 */
    private int lastClipIndex = 0;

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
        lastClipIndex = 0;
    }

    /**
     * 找到当前全局时间所在的 camera clip
     * <p>
     * 如果时间在所有 clip 之前，返回第一个 clip；
     * 如果时间在所有 clip 之后，返回最后一个 clip；
     * 如果在两个 clip 之间的间隙，返回前一个 clip（保持最后一帧）。
     * 无限时长 clip（duration&lt;0）一旦进入就永远活跃。
     * <p>
     * 优化：利用 {@link #lastClipIndex} 缓存上一帧匹配位置，
     * 顺序播放时从缓存索引开始搜索，避免每帧从头遍历。
     */
    private CameraClip findActiveClip(float globalTime) {
        if (clips.isEmpty()) return null;

        CameraClip result = null;
        int resultIndex = -1;
        int startIdx = Math.max(0, Math.min(lastClipIndex, clips.size() - 1));

        // 第一轮：从缓存索引向后搜索
        for (int i = startIdx; i < clips.size(); i++) {
            CameraClip clip = clips.get(i);
            float clipEnd = clip.getStartTime() + clip.getDuration();

            if (clip.isInfinite()) {
                if (globalTime >= clip.getStartTime()) {
                    result = clip;
                    resultIndex = i;
                }
                continue;
            }

            if (globalTime >= clip.getStartTime() && globalTime < clipEnd) {
                lastClipIndex = i;
                return clip;
            }
        }

        // 第二轮：从 0 到缓存索引搜索（处理时间回退或间隙回绕）
        for (int i = 0; i < startIdx; i++) {
            CameraClip clip = clips.get(i);
            float clipEnd = clip.getStartTime() + clip.getDuration();

            if (clip.isInfinite()) {
                if (globalTime >= clip.getStartTime()) {
                    result = clip;
                    resultIndex = i;
                }
                continue;
            }

            if (globalTime >= clip.getStartTime() && globalTime < clipEnd) {
                lastClipIndex = i;
                return clip;
            }
        }

        // 没有精确匹配的有限时长 clip，返回最后匹配的无限时长 clip
        if (result != null) {
            lastClipIndex = resultIndex;
            return result;
        }

        // 在所有 clip 之前：返回第一个 clip
        if (globalTime < clips.get(0).getStartTime()) {
            lastClipIndex = 0;
            return clips.get(0);
        }

        // 在所有 clip 之后：返回最后一个 clip
        lastClipIndex = clips.size() - 1;
        return clips.get(clips.size() - 1);
    }
}
