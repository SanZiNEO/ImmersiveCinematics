package com.immersivecinematics.immersive_cinematics.script.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 片段模板展开用的 JSON 构造小工具（避免每个模板重复拼同样的对象）。
 *
 * <p>只做"字段名正确"这一件事：关键帧字段名与 {@code docs/SCRIPT_FORMAT.md} / {@code ScriptValidator}
 * 的口径一致（CAMERA 关键帧的 {@code position} / {@code position_mode} / {@code yaw} / {@code pitch} /
 * {@code roll} / {@code fov} / {@code zoom}）。</p>
 */
public final class TemplateJson {

    private TemplateJson() {}

    /** 相对位置对象 {@code {dx,dy,dz}}（相对基准点：触发点 / follow 实体）。 */
    public static JsonObject position(float dx, float dy, float dz) {
        JsonObject p = new JsonObject();
        p.addProperty("dx", clean(dx));
        p.addProperty("dy", clean(dy));
        p.addProperty("dz", clean(dz));
        return p;
    }

    /**
     * 归一数值：四舍五入到 1e-6（几何计算里的 {@code -0.0} 与 {@code 4.9e-16} 这类噪声不进产物）。
     */
    public static float clean(float v) {
        float f = (float) (Math.round((double) v * 1_000_000.0) / 1_000_000.0);
        return f == 0f ? 0f : f;   // -0.0f == 0f → 归一为 +0.0
    }

    /**
     * CAMERA 关键帧。
     *
     * <p>六个可控字段全部显式写出（含默认值）：{@code ScriptValidator} 对缺省的 CAMERA 关键帧字段
     * 会逐条提示"将使用默认值"，生成产物要做到零提示——显式写出即与手写脚本的完整写法一致。</p>
     */
    public static JsonObject cameraKeyframe(float time, JsonObject position,
                                            float yaw, float pitch, float roll, float fov, float zoom) {
        JsonObject kf = new JsonObject();
        kf.addProperty("time", time);
        kf.add("position", position);
        kf.addProperty("yaw", yaw);
        kf.addProperty("pitch", pitch);
        kf.addProperty("roll", roll);
        kf.addProperty("fov", fov);
        kf.addProperty("zoom", zoom);
        return kf;
    }

    /** 覆盖层关键帧（只写用到的统一参数：不透明度）。 */
    public static JsonObject opacityKeyframe(float time, float opacity) {
        JsonObject kf = new JsonObject();
        kf.addProperty("time", time);
        kf.addProperty("opacity", opacity);
        return kf;
    }

    /** 贝塞尔路径曲线：两个**相对**控制点（相对段起点关键帧位置）。 */
    public static JsonObject bezierCurve(JsonObject controlPoint1, JsonObject controlPoint2) {
        JsonObject curve = new JsonObject();
        curve.addProperty("type", "bezier");
        JsonArray cps = new JsonArray();
        cps.add(controlPoint1);
        cps.add(controlPoint2);
        curve.add("control_points", cps);
        return curve;
    }

    /** 关键帧数组构造入口。 */
    public static JsonArray keyframes(JsonObject... frames) {
        JsonArray arr = new JsonArray();
        for (JsonObject f : frames) arr.add(f);
        return arr;
    }
}
