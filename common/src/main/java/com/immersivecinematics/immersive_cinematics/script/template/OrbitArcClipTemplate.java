package com.immersivecinematics.immersive_cinematics.script.template;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TrackType;

import java.util.List;

/**
 * 片段模板：环绕弧线（绕基准点做圆弧环绕，一段 ≤120°）。
 *
 * <p>算法沿用 0.3.5 留档（{@code plans/complete/0.3.5/presets.md}）：一段圆弧 = 一条三次贝塞尔，
 * 控制点到端点的距离 = {@code R × 4/3 × tan(θ/4)}（θ=90° → 0.5523R；θ=120° → 0.7698R）。
 * 单段控制在 120° 以内是拼圆的既有预算——整圈 = 三段 120° 拼圆，属**轨道级**模板（templates.md 步骤 3），
 * 本片段模板只负责其中一段。</p>
 *
 * <p>位置与两个控制点都写成**相对**形式：位置相对基准点（触发点，或 {@code center_mode=entity} 时的目标实体），
 * 控制点相对段起点——"以玩家触发点为圆心绕圆"这类运行时才知道坐标的场景因此可直接生成。</p>
 */
public final class OrbitArcClipTemplate implements ClipTemplate {

    /** 单段贝塞尔可接受的圆弧角度上限（度）：超过即交给轨道级"三段拼圆"。 */
    private static final float MAX_SWEEP = 120f;

    @Override
    public String id() {
        return "orbit_arc";
    }

    @Override
    public String name() {
        return "环绕弧线（≤120°）";
    }

    @Override
    public String description() {
        return "绕基准点/目标实体做圆弧环绕（一段三次贝塞尔，|sweep| ≤ 120°）："
                + "整圈 = 三段 120° 拼圆，属轨道级模板";
    }

    @Override
    public TrackType trackType() {
        return TrackType.CAMERA;
    }

    @Override
    public List<TemplateParam> params() {
        return List.of(
                TemplateParam.enumParam("center_mode", "圆心", "trigger", "core", "trigger", "entity"),
                TemplateParam.stringParam("target_selector", "目标选择器（圆心实体 / 看向目标）", "@p", "core"),
                TemplateParam.floatParam("radius", "半径（米）", 8f, "geometry"),
                TemplateParam.floatParam("height", "高度 dy（相对圆心，米）", 2f, "geometry"),
                TemplateParam.floatParam("start_angle", "起始方位角（度，0=+x）", 0f, "geometry"),
                TemplateParam.floatParam("sweep", "扫过角度（度，正负=方向，≤120）", 90f, "geometry"),
                TemplateParam.floatParam("duration", "时长（秒）", 6f, "core"),
                TemplateParam.floatParam("fov", "fov（度）", 70f, "look"));
    }

    @Override
    public JsonObject expand(TemplateArgs args) {
        float sweepDeg = args.getFloat("sweep");
        if (sweepDeg == 0f) {
            throw new IllegalArgumentException("sweep 不能为 0（环绕弧线需要一段非零圆弧）");
        }
        if (Math.abs(sweepDeg) > MAX_SWEEP) {
            throw new IllegalArgumentException("sweep=" + sweepDeg + " 超出单段上限 ±" + MAX_SWEEP
                    + "°（整圈请用轨道级模板：三段 120° 拼圆）");
        }
        float duration = Math.max(args.getFloat("duration"), 0.1f);
        float radius = Math.max(args.getFloat("radius"), 0.1f);
        float height = args.getFloat("height");
        float fov = args.getFloat("fov");

        double a0 = Math.toRadians(args.getFloat("start_angle"));
        double theta = Math.toRadians(sweepDeg);
        double a1 = a0 + theta;
        double dir = Math.signum(theta);

        // 圆弧端点（相对圆心，y = height）
        double p0x = radius * Math.cos(a0), p0z = radius * Math.sin(a0);
        double p3x = radius * Math.cos(a1), p3z = radius * Math.sin(a1);
        // 贝塞尔控制点距离：k = R × 4/3 × tan(θ/4)
        double k = radius * 4.0 / 3.0 * Math.tan(Math.abs(theta) / 4.0);
        // 端点处切线（单位向量，沿角度增大的方向）
        double t0x = -Math.sin(a0) * dir, t0z = Math.cos(a0) * dir;
        double t1x = -Math.sin(a1) * dir, t1z = Math.cos(a1) * dir;

        JsonObject cp1 = TemplateJson.position((float) (k * t0x), 0f, (float) (k * t0z));
        JsonObject cp2 = TemplateJson.position(
                (float) ((p3x - p0x) - k * t1x), 0f, (float) ((p3z - p0z) - k * t1z));

        boolean followEntity = "entity".equals(args.getString("center_mode"));
        String selector = args.getString("target_selector");

        JsonObject clip = new JsonObject();
        clip.addProperty("start_time", args.startTime());
        clip.addProperty("duration", duration);
        clip.addProperty("transition", "cut");
        clip.add("curve", TemplateJson.bezierCurve(cp1, cp2));

        JsonObject start = TemplateJson.cameraKeyframe(0f,
                TemplateJson.position((float) p0x, height, (float) p0z), 0f, 0f, 0f, fov, 1f);
        JsonObject end = TemplateJson.cameraKeyframe(duration,
                TemplateJson.position((float) p3x, height, (float) p3z), 0f, 0f, 0f, fov, 1f);
        for (JsonObject kf : new JsonObject[]{start, end}) {
            if (followEntity) {
                kf.addProperty("follow", "entity");
                kf.addProperty("follow_selector", selector);
            }
            kf.addProperty("look_at", "entity");
            kf.addProperty("look_at_selector", selector);
        }
        clip.add("keyframes", TemplateJson.keyframes(start, end));
        return clip;
    }
}
