package com.immersivecinematics.immersive_cinematics.script;

import com.google.gson.JsonObject;

/**
 * 触发器前置条件（可扩展）。
 * <p>
 * 每个条件由 {@code type} + 参数 {@code data} 组成。
 * 内置类型由 {@code PrerequisiteRegistry} 注册，其他模组也可注册自己的类型。
 * 旧脚本的 {@code requires: ["script_a"]} 会在解析时转换为
 * {@code type="script_played", data={"script":"script_a"}}。
 */
public final class TriggerRequirement {

    private final String type;
    private final JsonObject data;

    public TriggerRequirement(String type, JsonObject data) {
        this.type = type;
        this.data = data != null ? data : new JsonObject();
    }

    public String getType() {
        return type;
    }

    public JsonObject getData() {
        return data;
    }

    /** 内置便捷构造：要求脚本“开始播放且结束播放（任何退出原因）” */
    public static TriggerRequirement scriptPlayed(String scriptId) {
        JsonObject data = new JsonObject();
        data.addProperty("script", scriptId);
        return new TriggerRequirement("script_played", data);
    }

    /** 内置便捷构造：只要求脚本开始播放 */
    public static TriggerRequirement scriptStarted(String scriptId) {
        JsonObject data = new JsonObject();
        data.addProperty("script", scriptId);
        return new TriggerRequirement("script_started", data);
    }

    /** 内置便捷构造：只要求脚本结束播放 */
    public static TriggerRequirement scriptCompleted(String scriptId) {
        JsonObject data = new JsonObject();
        data.addProperty("script", scriptId);
        return new TriggerRequirement("script_completed", data);
    }

    @Override
    public String toString() {
        return "TriggerRequirement{type=" + type + ", data=" + data + "}";
    }
}
