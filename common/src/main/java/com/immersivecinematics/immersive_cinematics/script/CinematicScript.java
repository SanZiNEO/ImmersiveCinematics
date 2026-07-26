package com.immersivecinematics.immersive_cinematics.script;

/**
 * 电影脚本 — 顶层数据容器
 * <p>
 * 脚本层级结构：
 * <pre>
 * CinematicScript
 * ├── ScriptMeta (id/name/author/version + 14个运行时行为布尔)
 * └── Timeline
 *     └── TimelineTrack[] (并行轨道)
 *         └── Clip[] (片段，多态)
 *             └── CameraKeyframe[] (仅 CameraClip)
 * </pre>
 * <p>
 * 脚本只包含编辑时确定的静态数据，不包含运行时动态状态。
 * 运行时状态由 ScriptPlayer 管理。
 */
public class CinematicScript {

    /** 脚本属性 */
    private final ScriptMeta meta;

    /** 时间轴 */
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

    /** 便捷方法：获取脚本ID */
    public String getId() { return meta.getId(); }

    /** 便捷方法：获取脚本名称 */
    public String getName() { return meta.getName(); }

    /** 便捷方法：获取格式版本号 */
    public int getVersion() { return meta.getVersion(); }

    /** 便捷方法：获取总时长 */
    public float getTotalDuration() { return timeline.getTotalDuration(); }

    /** 便捷方法：是否为无限时长脚本 */
    public boolean isInfinite() { return timeline.isInfinite(); }

    @Override
    public String toString() {
        return String.format("CinematicScript{id=%s, name=%s, v%d, %s}",
                meta.getId(), meta.getName(), meta.getVersion(), timeline);
    }
}
