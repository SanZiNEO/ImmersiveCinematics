package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

public class CameraTrackPlayer implements TrackPlayer {

    private final List<Clip> clips;
    private final Vec3 originPos;
    private final CameraManager cameraManager;

    /** 独立 Bezier 路径策略实例，脚本结束时随 TrackPlayer 一起 GC，LUT 缓存自动释放 */
    private final PathStrategy bezierStrategy = new BezierPathStrategy();

    private int lastClipIndex = 0;

    public CameraTrackPlayer(TimelineTrack track, Vec3 originPos, CameraManager cameraManager) {
        this.clips = track.getClips();
        this.originPos = originPos;
        this.cameraManager = cameraManager;
    }


    @Override
    public boolean isActiveAt(float globalTime) {
        Clip c = findActiveClip(globalTime);
        if (c != null) return true;
        for (int i = 0; i < clips.size() - 1; i++) {
            Clip prev = clips.get(i);
            if (prev.isMorph() && prev.getTransitionDuration() > 0f && !prev.isInfinite()) {
                float prevEnd = prev.getStartTime() + prev.getDuration();
                float morphEnd = prevEnd + prev.getTransitionDuration();
                if (globalTime >= prevEnd && globalTime < morphEnd) return true;
            }
        }
        return false;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        if (clips.isEmpty()) return;

        // Morph: 在 [A_end, A_end+A.transition_duration) 内取 A 末帧→B 首帧 lerp
        for (int i = 0; i < clips.size() - 1; i++) {
            Clip prev = clips.get(i);
            Clip next = clips.get(i + 1);
            if (prev.isMorph() && prev.getTransitionDuration() > 0f && !prev.isInfinite()) {
                float prevEnd = prev.getStartTime() + prev.getDuration();
                float morphEnd = prevEnd + prev.getTransitionDuration();
                if (globalTime >= prevEnd && globalTime < morphEnd) {
                    float weight = (globalTime - prevEnd) / prev.getTransitionDuration();
                    renderMorph(prev, next, weight, globalTime);
                    return;
                }
            }
        }

        Clip primaryClip = findActiveClip(globalTime);
        if (primaryClip == null) return;

        float clipLocalTime = globalTime - primaryClip.getStartTime();
        renderSingle(globalTime, primaryClip, clipLocalTime);
    }

    private void renderSingle(float globalTime, Clip clip, float clipLocalTime) {
        KeyframeInterpolator.InterpolationResult result =
                KeyframeInterpolator.computeInterpolation(clipLocalTime, clip);
        if (result == null) return;

        float s = result.adjustedT;
        writeAttributes(result.from, result.to, s, clip, globalTime);
    }

    private void renderMorph(Clip prevClip, Clip nextClip, float weight, float globalTime) {
        KeyframeInterpolator.InterpolationResult prevResult =
                KeyframeInterpolator.computeInterpolation(prevClip.getDuration(), prevClip);
        KeyframeInterpolator.InterpolationResult nextResult =
                KeyframeInterpolator.computeInterpolation(0f, nextClip);

        if (prevResult == null && nextResult == null) return;

        float prevS = prevResult != null ? prevResult.adjustedT : 0f;
        float nextS = nextResult != null ? nextResult.adjustedT : 0f;

        Keyframe prevFrom = prevResult != null ? prevResult.from : null;
        Keyframe prevTo = prevResult != null ? prevResult.to : null;
        Keyframe nextFrom = nextResult != null ? nextResult.from : null;
        Keyframe nextTo = nextResult != null ? nextResult.to : null;

        float invWeight = 1f - weight;

        Vec3 prevPos = prevFrom != null
                ? KeyframeInterpolator.interpolatePosition(prevFrom, prevTo, prevS, prevClip, bezierStrategy)
                : Vec3.ZERO;
        if (prevClip.isPositionModeRelative()) {
            prevPos = originPos.add(prevPos);
        }

        Vec3 nextPos = nextFrom != null
                ? KeyframeInterpolator.interpolatePosition(nextFrom, nextTo, nextS, nextClip, bezierStrategy)
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

        // ====== Tracking override ======
        String lookAt = prevClip.getString("cam_tracking_look_at", "none");
        String follow = prevClip.getString("cam_tracking_follow", "none");

        if ("coordinate".equals(lookAt) || "entity".equals(lookAt)) {
            Vec3 targetPos;
            if ("coordinate".equals(lookAt)) {
                float tx = prevClip.getFloat("cam_tracking_look_target_x", 0);
                float ty = prevClip.getFloat("cam_tracking_look_target_y", 64);
                float tz = prevClip.getFloat("cam_tracking_look_target_z", 0);
                targetPos = new Vec3(tx, ty, tz);
            } else {
                String selector = prevClip.getString("cam_tracking_target_selector", "@p");
                targetPos = resolveEntityTarget(selector);
            }
            if (targetPos != null) {
                double dx = targetPos.x - pos.x;
                double dy = targetPos.y - pos.y;
                double dz = targetPos.z - pos.z;
                float newYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                float newPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx + dz*dz)));
                yaw = newYaw;
                pitch = newPitch;
            }
        }

        if ("entity".equals(follow)) {
            Vec3 followTarget = resolveEntityTarget(prevClip.getString("cam_tracking_target_selector", "@p"));
            if (followTarget != null) {
                float ox = prevClip.getFloat("cam_tracking_follow_offset_x", 0);
                float oy = prevClip.getFloat("cam_tracking_follow_offset_y", 2);
                float oz = prevClip.getFloat("cam_tracking_follow_offset_z", 0);
                pos = followTarget.add(ox, oy, oz);
            }
        }
        // ====== End tracking ======

        // ====== Breath disturbance ======
        if (prevClip.getBool("cam_breath_enabled", false)) {
            float intensity = prevClip.getFloat("cam_breath_intensity", 0.05f);
            int seed = prevClip.getInt("cam_breath_seed", 0);
            long timeSeed = (long)(globalTime * 100) + seed;
            Random rng = new Random(timeSeed);
            float yawJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            float pitchJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            float rollJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            yaw += yawJitter;
            pitch += pitchJitter;
            roll += rollJitter;
        }
        // ====== End breath ======

        cameraManager.getPath().setPositionDirect(pos);
        cameraManager.getProperties().setAllDirect(yaw, pitch, roll, fov, zoom);
    }

    private void writeAttributes(Keyframe from, Keyframe to, float s, Clip clip, float globalTime) {
        Vec3 pos = KeyframeInterpolator.interpolatePosition(from, to, s, clip, bezierStrategy);
        float yaw = KeyframeInterpolator.interpolateYaw(from, to, s);
        float pitch = KeyframeInterpolator.interpolatePitch(from, to, s);
        float roll = KeyframeInterpolator.interpolateRoll(from, to, s);
        float fov = KeyframeInterpolator.interpolateFov(from, to, s);
        float zoom = KeyframeInterpolator.interpolateZoom(from, to, s);

        // ====== Tracking override ======
        String lookAt = clip.getString("cam_tracking_look_at", "none");
        String follow = clip.getString("cam_tracking_follow", "none");

        if ("coordinate".equals(lookAt) || "entity".equals(lookAt)) {
            Vec3 targetPos;
            if ("coordinate".equals(lookAt)) {
                float tx = clip.getFloat("cam_tracking_look_target_x", 0);
                float ty = clip.getFloat("cam_tracking_look_target_y", 64);
                float tz = clip.getFloat("cam_tracking_look_target_z", 0);
                targetPos = new Vec3(tx, ty, tz);
            } else {
                String selector = clip.getString("cam_tracking_target_selector", "@p");
                targetPos = resolveEntityTarget(selector);
            }
            if (targetPos != null) {
                double dx = targetPos.x - pos.x;
                double dy = targetPos.y - pos.y;
                double dz = targetPos.z - pos.z;
                float newYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                float newPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx + dz*dz)));
                yaw = newYaw;
                pitch = newPitch;
            }
        }

        if ("entity".equals(follow)) {
            Vec3 followTarget = resolveEntityTarget(clip.getString("cam_tracking_target_selector", "@p"));
            if (followTarget != null) {
                float ox = clip.getFloat("cam_tracking_follow_offset_x", 0);
                float oy = clip.getFloat("cam_tracking_follow_offset_y", 2);
                float oz = clip.getFloat("cam_tracking_follow_offset_z", 0);
                pos = followTarget.add(ox, oy, oz);
            }
        }
        // ====== End tracking ======

        if (clip.isPositionModeRelative()) {
            pos = originPos.add(pos);
        }

        // ====== Breath disturbance ======
        if (clip.getBool("cam_breath_enabled", false)) {
            float intensity = clip.getFloat("cam_breath_intensity", 0.05f);
            int seed = clip.getInt("cam_breath_seed", 0);
            long timeSeed = (long)(globalTime * 100) + seed;
            Random rng = new Random(timeSeed);
            float yawJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            float pitchJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            float rollJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;
            yaw += yawJitter;
            pitch += pitchJitter;
            roll += rollJitter;
        }
        // ====== End breath ======

        cameraManager.getPath().setPositionDirect(pos);
        cameraManager.getProperties().setAllDirect(yaw, pitch, roll, fov, zoom);
    }

    @Override
    public void onStop() {
        lastClipIndex = 0;
        // bezierStrategy 随 TrackPlayer 实例一起被 GC，其 LUT 缓存自动释放
    }

    private Clip findActiveClip(float globalTime) {
        if (clips.isEmpty()) return null;

        Clip result = null;
        int resultIndex = -1;
        int startIdx = Math.max(0, Math.min(lastClipIndex, clips.size() - 1));

        for (int i = startIdx; i < clips.size(); i++) {
            Clip clip = clips.get(i);
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
            Clip clip = clips.get(i);
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
        return null;
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

    private Vec3 resolveEntityTarget(String selector) {
        if ("@p".equals(selector) || "@s".equals(selector)) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) return mc.player.position();
        }
        return null; // Phase 1: only @p/@s supported
    }
}
