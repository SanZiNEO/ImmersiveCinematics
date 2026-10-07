package com.immersivecinematics.immersive_cinematics.script.template;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TrackType;

import java.util.List;

/**
 * 片段模板：推近 / 拉远（直线变距）。
 *
 * <p>沿基准朝向的前后轴直线移动，两个关键帧定速度（运行时匀速线性）。给两个不同的 fov 就是
 * <b>希区柯克变焦</b>（dolly zoom）：位置推进的同时视角反向变化，主体大小不变、背景透视被压缩——
 * 不单列模板，因为它与推近是同一条路径，只差光学参数。</p>
 */
public final class DollyClipTemplate implements ClipTemplate {

    @Override
    public String id() {
        return "dolly";
    }

    @Override
    public String name() {
        return "推近 / 拉远";
    }

    @Override
    public String description() {
        return "直线变距：direction=in 推近 / out 拉远；fov_start≠fov_end 即希区柯克变焦（推轨 + 反向变焦）";
    }

    @Override
    public TrackType trackType() {
        return TrackType.CAMERA;
    }

    @Override
    public List<TemplateParam> params() {
        return List.of(
                TemplateParam.enumParam("direction", "方向", "in", "core", "in", "out"),
                TemplateParam.floatParam("distance_near", "近端距离（米）", 2f, "core"),
                TemplateParam.floatParam("distance_far", "远端距离（米）", 8f, "core"),
                TemplateParam.floatParam("duration", "时长（秒）", 4f, "core"),
                TemplateParam.floatParam("height", "高度 dy（米）", 2f, "position"),
                TemplateParam.floatParam("offset_x", "横向偏移 dx（米）", 0f, "position"),
                TemplateParam.floatParam("yaw", "yaw（度）", 0f, "look"),
                TemplateParam.floatParam("pitch", "pitch（度）", 0f, "look"),
                TemplateParam.floatParam("fov_start", "起始 fov（度）", 70f, "look"),
                TemplateParam.floatParam("fov_end", "结束 fov（度）", 70f, "look"));
    }

    @Override
    public JsonObject expand(TemplateArgs args) {
        float duration = Math.max(args.getFloat("duration"), 0.1f);
        float near = Math.max(args.getFloat("distance_near"), 0f);
        float far = Math.max(args.getFloat("distance_far"), 0f);
        boolean dollyIn = !"out".equals(args.getString("direction"));
        float startDistance = dollyIn ? far : near;
        float endDistance = dollyIn ? near : far;

        float dx = args.getFloat("offset_x");
        float dy = args.getFloat("height");
        float yaw = args.getFloat("yaw");
        float pitch = args.getFloat("pitch");
        float fovStart = args.getFloat("fov_start");
        float fovEnd = args.getFloat("fov_end");

        JsonObject clip = new JsonObject();
        clip.addProperty("start_time", args.startTime());
        clip.addProperty("duration", duration);
        clip.addProperty("transition", "cut");
        clip.add("keyframes", TemplateJson.keyframes(
                TemplateJson.cameraKeyframe(0f, TemplateJson.position(dx, dy, -startDistance),
                        yaw, pitch, 0f, fovStart, 1f),
                TemplateJson.cameraKeyframe(duration, TemplateJson.position(dx, dy, -endDistance),
                        yaw, pitch, 0f, fovEnd, 1f)));
        return clip;
    }
}
