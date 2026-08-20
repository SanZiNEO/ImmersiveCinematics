package com.immersivecinematics.immersive_cinematics.editor.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorDefaults;
import java.util.List;

/**
 * 环绕轨道预设（0.3.5 第5轮 5D）。
 * <p>
 * 用三段三次贝塞尔（每段 120°）拼整圆，生成 3 个 CAMERA clip。
 * 控制点距离 = R * 4/3 * tan(θ/4)，θ=120° 时 ≈ 0.7698R。
 */
public final class OrbitCirclePreset {

    private static final float CTRL = 0.7698f;

    private OrbitCirclePreset() {}

    public static Preset create() {
        return new Preset(
                "orbit_circle",
                "环绕轨道",
                "围绕中心点做三段贝塞尔拼圆的环绕镜头",
                List.of(
                        new PresetParam("radius", "半径", "number", 8f, 2f, 200f),
                        new PresetParam("height", "高度", "number", 2f, -50f, 50f),
                        new PresetParam("duration", "时长", "number", 8f, 1f, 600f)
                ),
                OrbitCirclePreset::generate
        );
    }

    private static JsonObject generate(JsonObject params) {
        float radius = getFloat(params, "radius", 8f);
        float height = getFloat(params, "height", 2f);
        float duration = getFloat(params, "duration", 8f);
        float segDur = Math.max(0.1f, duration / 3f);

        JsonObject script = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("id", "orbit_circle");
        meta.addProperty("name", "环绕轨道");
        meta.addProperty("version", 3);
        EditorDefaults.fillMetaDefaults(meta);
        script.add("meta", meta);

        JsonObject timeline = new JsonObject();
        timeline.addProperty("total_duration", duration);
        JsonArray tracks = new JsonArray();
        JsonObject cameraTrack = new JsonObject();
        cameraTrack.addProperty("type", "CAMERA");
        cameraTrack.addProperty("id", "camera_1");
        JsonArray clips = new JsonArray();

        for (int i = 0; i < 3; i++) {
            float a0 = (float) Math.toRadians(i * 120);
            float a1 = (float) Math.toRadians((i + 1) * 120);
            float sx = radius * (float) Math.cos(a0);
            float sz = radius * (float) Math.sin(a0);
            float ex = radius * (float) Math.cos(a1);
            float ez = radius * (float) Math.sin(a1);

            float p1x = sx + CTRL * radius * (float) -Math.sin(a0);
            float p1z = sz + CTRL * radius * (float) Math.cos(a0);
            float p2x = ex - CTRL * radius * (float) -Math.sin(a1);
            float p2z = ez - CTRL * radius * (float) Math.cos(a1);

            JsonObject clip = new JsonObject();
            clip.addProperty("start_time", i * segDur);
            clip.addProperty("duration", segDur);
            clip.addProperty("transition", "cut");
            clip.addProperty("interpolation", "linear");
            clip.addProperty("loop", false);

            JsonObject curve = new JsonObject();
            curve.addProperty("type", "bezier");
            JsonArray cps = new JsonArray();
            cps.add(pos(p1x, height, p1z));
            cps.add(pos(p2x, height, p2z));
            curve.add("control_points", cps);
            clip.add("curve", curve);

            JsonArray kfs = new JsonArray();
            kfs.add(keyframe(0f, sx, height, sz));
            kfs.add(keyframe(segDur, ex, height, ez));
            clip.add("keyframes", kfs);

            clips.add(clip);
        }

        cameraTrack.add("clips", clips);
        tracks.add(cameraTrack);
        timeline.add("tracks", tracks);
        script.add("timeline", timeline);
        return script;
    }

    private static JsonObject keyframe(float time, float x, float y, float z) {
        JsonObject kf = new JsonObject();
        kf.addProperty("time", time);
        kf.add("position", pos(x, y, z));
        kf.addProperty("position_mode", "absolute");
        kf.addProperty("look_at", "coordinate");
        kf.addProperty("look_at_target_x", 0f);
        kf.addProperty("look_at_target_y", y);
        kf.addProperty("look_at_target_z", 0f);
        kf.addProperty("fov", 70f);
        kf.addProperty("zoom", 1f);
        return kf;
    }

    private static JsonObject pos(float x, float y, float z) {
        JsonObject p = new JsonObject();
        p.addProperty("x", x);
        p.addProperty("y", y);
        p.addProperty("z", z);
        return p;
    }

    private static float getFloat(JsonObject params, String key, float def) {
        return params.has(key) ? params.get(key).getAsFloat() : def;
    }
}
