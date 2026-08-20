package com.immersivecinematics.immersive_cinematics.editor.preset;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * 预设定义（0.3.5 第5轮 5D）。
 * <p>
 * 参数 schema + 生成函数；生成结果是与手写脚本等价的 JSON。
 */
public class Preset {

    @FunctionalInterface
    public interface Generator {
        JsonObject generate(JsonObject params);
    }

    private final String id;
    private final String name;
    private final String description;
    private final List<PresetParam> params;
    private final Generator generator;

    public Preset(String id, String name, String description, List<PresetParam> params, Generator generator) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.params = params;
        this.generator = generator;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<PresetParam> getParams() {
        return params;
    }

    public JsonObject generate(JsonObject params) {
        return generator.generate(params);
    }
}
