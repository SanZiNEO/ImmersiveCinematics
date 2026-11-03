package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

/**
 * 一条服务端事实：谁（玩家分片）、何时（服务端 tick）、关于哪个对象、发生了什么、结果如何。
 *
 * <p>只追加、不修改。字符串维度用空串表示「无」，{@link FactResult#NONE} 表示无结果参数。
 * {@code object} 的含义随类型约定（影子脚本 / 触发器身份 / 目标 id / 物品 id…）。
 */
public record Fact(FactType type, String script, String object, String detail,
                   String instance, int tick, FactResult result, FactScene scene) {

    public Fact {
        script = script == null ? "" : script;
        object = object == null ? "" : object;
        detail = detail == null ? "" : detail;
        instance = instance == null ? "" : instance;
        result = result == null ? FactResult.NONE : result;
    }

    // ===== Factories =====

    /** 事件类事实：无脚本、无详情、无实例。 */
    public static Fact event(FactType type, String object, int tick) {
        return new Fact(type, "", object, "", "", tick, FactResult.NONE, null);
    }

    /** 事件类事实，带详情（如物品 id / 目标类型）。 */
    public static Fact event(FactType type, String object, String detail, int tick) {
        return new Fact(type, "", object, detail, "", tick, FactResult.NONE, null);
    }

    /** 事件类事实，带场景快照。 */
    public static Fact scene(FactType type, String object, FactScene scene, int tick) {
        return new Fact(type, "", object, "", "", tick, FactResult.NONE, scene);
    }

    public static Fact playbackStarted(String script, String instance, int tick) {
        return new Fact(FactType.PLAYBACK_STARTED, script, "", "", instance, tick, FactResult.NONE, null);
    }

    public static Fact playbackFinished(String script, String instance, FactResult result, int tick) {
        return new Fact(FactType.PLAYBACK_FINISHED, script, "", "", instance, tick, result, null);
    }

    public static Fact triggerFired(String script, String triggerId, int tick) {
        return new Fact(FactType.TRIGGER_FIRED, script, triggerId, "", "", tick, FactResult.NONE, null);
    }

    /** 区域进入 / 离开事实；{@code type} 应为 {@link FactType#REGION_ENTERED} 或 {@link FactType#REGION_LEFT}。 */
    public static Fact region(FactType type, String script, String triggerId, int tick) {
        return new Fact(type, script, triggerId, "", "", tick, FactResult.NONE, null);
    }

    public static Fact vote(String script, String instance, int tick) {
        return new Fact(FactType.SKIP_VOTE, script, "", "", instance, tick, FactResult.NONE, null);
    }
}
