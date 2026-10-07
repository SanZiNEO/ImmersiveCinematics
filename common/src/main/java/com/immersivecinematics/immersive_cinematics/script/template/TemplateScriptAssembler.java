package com.immersivecinematics.immersive_cinematics.script.template;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.ScriptTemplate;

/**
 * 把片段模板的产物装进一份可播放的标准脚本（接入点用）。
 *
 * <p>骨架直接复用新建脚本的默认结构 {@link ScriptTemplate#build()}（meta 默认值 + 每种轨道一条空轨），
 * 把展开出的 clip 追加到 {@link ClipTemplate#trackType()} 对应的轨道上，并把
 * {@code timeline.total_duration} 收到 clip 末端。</p>
 *
 * <p>这**不是**脚本级模板（那要引用多条轨道、带触发器与默认转场）：这里只是"片段 → 能播的脚本"的
 * 最小装配，产物与手写脚本同构，走 {@code ScriptParser} / {@code ScriptValidator} 后即可编辑 / 播放。</p>
 */
public final class TemplateScriptAssembler {

    /** 生成脚本的默认作者标记。 */
    private static final String AUTHOR = "ImmersiveCinematics";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TemplateScriptAssembler() {}

    /** 装配脚本 JSON 文本。 */
    public static String assembleJson(ClipTemplate template, TemplateArgs args, String scriptId) {
        return GSON.toJson(assemble(template, args, scriptId));
    }

    /**
     * 装配脚本对象。
     *
     * @param template 片段模板
     * @param args     已归一的实参（{@link TemplateArgs#startTime()} 决定 clip 的 {@code start_time}）
     * @param scriptId 脚本 id / 文件名（写入 {@code meta.id} 与 {@code meta.name}）
     */
    public static JsonObject assemble(ClipTemplate template, TemplateArgs args, String scriptId) {
        JsonObject script = ScriptTemplate.build();
        JsonObject meta = script.getAsJsonObject("meta");
        meta.addProperty("id", scriptId);
        meta.addProperty("name", scriptId);
        meta.addProperty("author", AUTHOR);
        meta.addProperty("description", template.description() + "（模板 " + template.id() + " 生成）");

        JsonObject clip = template.expand(args);
        JsonObject timeline = script.getAsJsonObject("timeline");
        JsonObject track = findTrack(timeline.getAsJsonArray("tracks"), template.trackType().name());
        if (track == null) {
            throw new IllegalStateException("脚本骨架缺少轨道类型 " + template.trackType().name());
        }
        track.getAsJsonArray("clips").add(clip);

        float end = clip.get("start_time").getAsFloat() + Math.max(clip.get("duration").getAsFloat(), 0f);
        if (end > 0f) {
            timeline.addProperty("total_duration", end);
        }
        return script;
    }

    private static JsonObject findTrack(JsonArray tracks, String type) {
        for (JsonElement te : tracks) {
            JsonObject track = te.getAsJsonObject();
            if (type.equals(track.get("type").getAsString())) return track;
        }
        return null;
    }
}
