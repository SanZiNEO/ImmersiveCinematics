package com.immersivecinematics.immersive_cinematics.script;

/**
 * 电影脚本 —— 顶层静态数据容器：{@link ScriptMeta}（元数据 + 运行时行为）+ {@link Timeline}。
 * 层级：CinematicScript → ScriptMeta / Timeline → TimelineTrack[] → Clip[] → Keyframe[]。
 *
 * 只包含编辑时确定的静态数据，不包含运行时动态状态；运行时状态由 {@link ScriptPlayer} 管理。
 */
public class CinematicScript {

    private final ScriptMeta meta;
    private final Timeline timeline;

    /** 原始 JSON（用于服务端→客户端网络同步，非解析时为空） */
    private String rawJson;

    public CinematicScript(ScriptMeta meta, Timeline timeline) {
        this.meta = meta;
        this.timeline = timeline;
        this.rawJson = null;
    }

    public ScriptMeta getMeta() { return meta; }
    public Timeline getTimeline() { return timeline; }

    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }

    public String getId() { return meta.getId(); }

    public String getName() { return meta.getName(); }

    public int getVersion() { return meta.getVersion(); }

    public float getTotalDuration() { return timeline.getTotalDuration(); }

    public boolean isInfinite() { return timeline.isInfinite(); }

    @Override
    public String toString() {
        return String.format("CinematicScript{id=%s, name=%s, v%d, %s}",
                meta.getId(), meta.getName(), meta.getVersion(), timeline);
    }
}
