package com.immersivecinematics.immersive_cinematics.script.template;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TrackType;

import java.util.ArrayList;
import java.util.List;

/**
 * 片段模板：黑场 / 白场（OVERLAY 轨的纯色遮罩片段）。
 *
 * <p>底层是纯色覆盖层（{@code layer_type=fade} + {@code color}）+ 不透明度关键帧，
 * 黑场与白场之间没有实现差异（同一个工具换颜色）——见 {@code plans/0.3.6/scene-transition.md} §3.3。</p>
 *
 * <p>三种用法由两个参数表达，都是同一条 opacity 曲线的特例：</p>
 * <ul>
 *   <li>纯色场（默认）：{@code fade_in=0, fade_out=0} → 整段不透明（"黑场 1s"一键插入）；</li>
 *   <li>压场（fade to black）：{@code fade_in=N} → 开头 N 秒由透明渐入全遮；</li>
 *   <li>亮起（fade from black）：{@code fade_out=N} → 结尾 N 秒由全遮渐出透明。</li>
 * </ul>
 */
public final class FadeClipTemplate implements ClipTemplate {

    /** 黑场 / 白场的十六进制色值（{@code FadeLayer.setColor} 的 #RRGGBB 口径）。 */
    private static final String BLACK = "#000000";
    private static final String WHITE = "#FFFFFF";

    @Override
    public String id() {
        return "fade";
    }

    @Override
    public String name() {
        return "黑场 / 白场";
    }

    @Override
    public String description() {
        return "OVERLAY 轨纯色遮罩片段：color=black/white；fade_in 渐入（压场）、fade_out 渐出（亮起），"
                + "两者为 0 即纯色场";
    }

    @Override
    public TrackType trackType() {
        return TrackType.OVERLAY;
    }

    @Override
    public List<TemplateParam> params() {
        return List.of(
                TemplateParam.enumParam("color", "颜色", "black", "core", "black", "white"),
                TemplateParam.floatParam("duration", "时长（秒）", 1f, "core"),
                TemplateParam.floatParam("fade_in", "渐入（秒，0=直接全遮）", 0f, "fade"),
                TemplateParam.floatParam("fade_out", "渐出（秒，0=遮到片段结束）", 0f, "fade"));
    }

    @Override
    public JsonObject expand(TemplateArgs args) {
        float duration = Math.max(args.getFloat("duration"), 0.1f);
        float fadeIn = clamp(args.getFloat("fade_in"), 0f, duration);
        float fadeOut = clamp(args.getFloat("fade_out"), 0f, duration - fadeIn);

        JsonObject clip = new JsonObject();
        clip.addProperty("start_time", args.startTime());
        clip.addProperty("duration", duration);
        clip.addProperty("layer_type", "fade");
        clip.addProperty("color", "white".equals(args.getString("color")) ? WHITE : BLACK);
        clip.addProperty("z_index", 10);

        // opacity：0=透明、1=全遮。按时间递增收集；同一时刻（fade_in 与 fade_out 在短片段上相遇）只留一个点。
        List<float[]> points = new ArrayList<>();
        addPoint(points, 0f, fadeIn > 0f ? 0f : 1f);
        if (fadeIn > 0f) addPoint(points, fadeIn, 1f);
        if (fadeOut > 0f) {
            addPoint(points, duration - fadeOut, 1f);
            addPoint(points, duration, 0f);
        } else {
            addPoint(points, duration, 1f);
        }

        JsonObject[] frames = new JsonObject[points.size()];
        for (int i = 0; i < points.size(); i++) {
            frames[i] = TemplateJson.opacityKeyframe(points.get(i)[0], points.get(i)[1]);
        }
        clip.add("keyframes", TemplateJson.keyframes(frames));
        return clip;
    }

    private static void addPoint(List<float[]> points, float time, float opacity) {
        for (float[] existing : points) {
            if (existing[0] == time) return;   // 同一时刻已有取值：保留先出现的那个
        }
        points.add(new float[]{time, opacity});
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
