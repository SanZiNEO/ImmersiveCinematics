package com.immersivecinematics.immersive_cinematics.script.template;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TrackType;

import java.util.List;

/**
 * 片段模板：固定机位 + 呼吸（手持感）。
 *
 * <p>两个等值关键帧把位置 / 朝向 / 光学钉死，晃动完全由 clip 级的程序化呼吸扰动
 * （{@code cam_breath_*}，逐帧噪声，不是关键帧数据）产生——"固定视角"与"呼吸"是两个正交的层。</p>
 */
public final class StaticBreathClipTemplate implements ClipTemplate {

    @Override
    public String id() {
        return "static_breath";
    }

    @Override
    public String name() {
        return "固定机位 + 呼吸";
    }

    @Override
    public String description() {
        return "镜头固定在给定位置/朝向，叠加程序化呼吸扰动（cam_breath_*）："
                + "type=sine 规律呼吸 / perlin 平滑手持 / trauma 受击衰减";
    }

    @Override
    public TrackType trackType() {
        return TrackType.CAMERA;
    }

    @Override
    public List<TemplateParam> params() {
        return List.of(
                TemplateParam.floatParam("duration", "时长（秒）", 6f, "core"),
                TemplateParam.floatParam("dx", "位置 dx（相对基准点）", 0f, "position"),
                TemplateParam.floatParam("dy", "位置 dy（高度）", 2f, "position"),
                TemplateParam.floatParam("dz", "位置 dz（前后，负=前方）", 0f, "position"),
                TemplateParam.floatParam("yaw", "yaw（度）", 0f, "look"),
                TemplateParam.floatParam("pitch", "pitch（度）", 0f, "look"),
                TemplateParam.floatParam("fov", "fov（度）", 70f, "look"),
                TemplateParam.enumParam("breath_type", "呼吸类型", "perlin", "breath",
                        "perlin", "perlin_axis", "sine", "trauma"),
                TemplateParam.floatParam("breath_intensity", "呼吸强度", 0.05f, "breath"),
                TemplateParam.floatParam("breath_speed", "呼吸速度", 1f, "breath"),
                TemplateParam.intParam("breath_seed", "呼吸种子", 0, "breath"));
    }

    @Override
    public JsonObject expand(TemplateArgs args) {
        float duration = Math.max(args.getFloat("duration"), 0.1f);
        JsonObject position = TemplateJson.position(
                args.getFloat("dx"), args.getFloat("dy"), args.getFloat("dz"));

        JsonObject clip = new JsonObject();
        clip.addProperty("start_time", args.startTime());
        clip.addProperty("duration", duration);
        clip.addProperty("transition", "cut");
        clip.addProperty("cam_breath_enabled", true);
        clip.addProperty("cam_breath_type", args.getString("breath_type"));
        clip.addProperty("cam_breath_intensity", Math.max(args.getFloat("breath_intensity"), 0f));
        clip.addProperty("cam_breath_speed", Math.max(args.getFloat("breath_speed"), 0f));
        clip.addProperty("cam_breath_seed", args.getInt("breath_seed"));

        float yaw = args.getFloat("yaw");
        float pitch = args.getFloat("pitch");
        float fov = args.getFloat("fov");
        clip.add("keyframes", TemplateJson.keyframes(
                TemplateJson.cameraKeyframe(0f, position.deepCopy(), yaw, pitch, 0f, fov, 1f),
                TemplateJson.cameraKeyframe(duration, position, yaw, pitch, 0f, fov, 1f)));
        return clip;
    }
}
