package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class CameraTrackPlayer implements TrackPlayer {

    private final List<CameraClip> clips;
    private final Vec3 originPos;
    private final CameraManager cameraManager;

    private int lastClipIndex = 0;

    public CameraTrackPlayer(TimelineTrack track, Vec3 originPos, CameraManager cameraManager) {
        this.clips = track.getCameraClips();
        this.originPos = originPos;
        this.cameraManager = cameraManager;
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        if (clips.isEmpty()) return;

        // Morph: 在 [A_end, A_end+transition_duration) 内取 A 末帧→B 首帧 lerp
        for (int i = 0; i < clips.size() - 1; i++) {
            CameraClip prev = clips.get(i);
            CameraClip next = clips.get(i + 1);
            if (next.isMorph() && next.getTransitionDuration() > 0f && !prev.isInfinite()) {
                float prevEnd = prev.getStartTime() + prev.getDuration();
                float morphEnd = prevEnd + next.getTransitionDuration();
                if (globalTime >= prevEnd && globalTime < morphEnd) {
                    float weight = (globalTime - prevEnd) / next.getTransitionDuration();
                    renderMorph(prev, next, weight);
                    return;
                }
            }
        }

        CameraClip primaryClip = findActiveClip(globalTime);
        if (primaryClip == null) return;

        float clipLocalTime = globalTime - primaryClip.getStartTime();
        renderSingle(globalTime, primaryClip, clipLocalTime);
    }

    private void renderSingle(float globalTime, CameraClip clip, float clipLocalTime) {
        KeyframeInterpolator.InterpolationResult result =
                KeyframeInterpolator.computeInterpolation(clipLocalTime, clip);
        if (result == null) return;

        float s = result.adjustedT;
        writeAttributes(result.from, result.to, s, clip);
    }

    private void renderMorph(CameraClip prevClip, CameraClip nextClip, float weight) {
        KeyframeInterpolator.InterpolationResult prevResult =
                KeyframeInterpolator.computeInterpolation(prevClip.getDuration(), prevClip);
        KeyframeInterpolator.InterpolationResult nextResult =
                KeyframeInterpolator.computeInterpolation(0f, nextClip);

        if (prevResult == null && nextResult == null) return;

        float prevS = prevResult != null ? prevResult.adjustedT : 0f;
        float nextS = nextResult != null ? nextResult.adjustedT : 0f;

        CameraKeyframe prevFrom = prevResult != null ? prevResult.from : null;
        CameraKeyframe prevTo = prevResult != null ? prevResult.to : null;
        CameraKeyframe nextFrom = nextResult != null ? nextResult.from : null;
        CameraKeyframe nextTo = nextResult != null ? nextResult.to : null;

        float invWeight = 1f - weight;

        Vec3 prevPos = prevFrom != null
                ? KeyframeInterpolator.interpolatePosition(prevFrom, prevTo, prevS, prevClip)
                : Vec3.ZERO;
        if (prevClip.isPositionModeRelative()) {
            prevPos = originPos.add(prevPos);
        }

        Vec3 nextPos = nextFrom != null
                ? KeyframeInterpolator.interpolatePosition(nextFrom, nextTo, nextS, nextClip)
                : Vec3.ZERO;
        if (nextClip.isPositionModeRelative()) {
            nextPos = originPos.add(nextPos);
        }

        Vec3 pos = new Vec3(
                prevPos.x * invWeight + nextPos.x * weight,
                prevPos.y * invWeight + nextPos.y * weight,
                prevPos.z * invWeight + nextPos.z * weight
        );

        float yaw = blendAngle(
                prevFrom != null ? KeyframeInterpolator.interpolateYaw(prevFrom, prevTo, prevS) : 0f,
                nextFrom != null ? KeyframeInterpolator.interpolateYaw(nextFrom, nextTo, nextS) : 0f,
                weight);
        float pitch = blendFloat(
                prevFrom != null ? KeyframeInterpolator.interpolatePitch(prevFrom, prevTo, prevS) : 0f,
                nextFrom != null ? KeyframeInterpolator.interpolatePitch(nextFrom, nextTo, nextS) : 0f,
                weight);
        float roll = blendAngle(
                prevFrom != null ? KeyframeInterpolator.interpolateRoll(prevFrom, prevTo, prevS) : 0f,
                nextFrom != null ? KeyframeInterpolator.interpolateRoll(nextFrom, nextTo, nextS) : 0f,
                weight);
        float fov = blendFloat(
                prevFrom != null ? KeyframeInterpolator.interpolateFov(prevFrom, prevTo, prevS) : 70f,
                nextFrom != null ? KeyframeInterpolator.interpolateFov(nextFrom, nextTo, nextS) : 70f,
                weight);
        float zoom = blendFloat(
                prevFrom != null ? KeyframeInterpolator.interpolateZoom(prevFrom, prevTo, prevS) : 1f,
                nextFrom != null ? KeyframeInterpolator.interpolateZoom(nextFrom, nextTo, nextS) : 1f,
                weight);
        float dof = blendFloat(
                prevFrom != null ? KeyframeInterpolator.interpolateDof(prevFrom, prevTo, prevS) : 0f,
                nextFrom != null ? KeyframeInterpolator.interpolateDof(nextFrom, nextTo, nextS) : 0f,
                weight);

        cameraManager.getPath().setPositionDirect(pos);
        cameraManager.getProperties().setAllDirect(yaw, pitch, roll, fov, zoom, dof);
    }

    private void writeAttributes(CameraKeyframe from, CameraKeyframe to, float s, CameraClip clip) {
        Vec3 pos = KeyframeInterpolator.interpolatePosition(from, to, s, clip);
        float yaw = KeyframeInterpolator.interpolateYaw(from, to, s);
        float pitch = KeyframeInterpolator.interpolatePitch(from, to, s);
        float roll = KeyframeInterpolator.interpolateRoll(from, to, s);
        float fov = KeyframeInterpolator.interpolateFov(from, to, s);
        float zoom = KeyframeInterpolator.interpolateZoom(from, to, s);
        float dof = KeyframeInterpolator.interpolateDof(from, to, s);

        if (clip.isPositionModeRelative()) {
            pos = originPos.add(pos);
        }

        cameraManager.getPath().setPositionDirect(pos);
        cameraManager.getProperties().setAllDirect(yaw, pitch, roll, fov, zoom, dof);
    }

    @Override
    public void onStop() {
        lastClipIndex = 0;
    }

    private CameraClip findActiveClip(float globalTime) {
        if (clips.isEmpty()) return null;

        CameraClip result = null;
        int resultIndex = -1;
        int startIdx = Math.max(0, Math.min(lastClipIndex, clips.size() - 1));

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

        if (result != null) {
            lastClipIndex = resultIndex;
            return result;
        }

        if (globalTime < clips.get(0).getStartTime()) {
            lastClipIndex = 0;
            return clips.get(0);
        }

        lastClipIndex = clips.size() - 1;
        return clips.get(clips.size() - 1);
    }

    private static float blendFloat(float a, float b, float weight) {
        return a * (1f - weight) + b * weight;
    }

    private static float blendAngle(float a, float b, float weight) {
        float diff = ((b - a) % 360f + 540f) % 360f - 180f;
        return a + diff * weight;
    }

    private static Vec3 blendVec3(Vec3 a, Vec3 b, float weight) {
        float inv = 1f - weight;
        return new Vec3(
                a.x * inv + b.x * weight,
                a.y * inv + b.y * weight,
                a.z * inv + b.z * weight
        );
    }
}
