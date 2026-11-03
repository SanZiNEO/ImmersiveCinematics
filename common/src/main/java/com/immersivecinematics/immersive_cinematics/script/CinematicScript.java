package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.script.identity.UsageIndex;
import com.immersivecinematics.immersive_cinematics.script.path.PathCollection;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleCollection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电影脚本 —— 顶层静态数据容器：{@link ScriptMeta}（元数据 + 运行时行为）+ {@link Timeline}
 * + 脚本级独立片段表（{@code clips[]}）。
 * 层级：CinematicScript → ScriptMeta / Timeline → TimelineTrack[] → Clip[] → Keyframe[]。
 *
 * <p>独立片段没有默认放置：只存在于 {@link #getStandaloneClips()}，由规则 / 路径 / 分支 / 出口按 id 引用。
 * 内联片段与独立片段共用同一个 id 命名空间，{@link #findClip(String)} 对两者统一解析。</p>
 *
 * 只包含编辑时确定的静态数据，不包含运行时动态状态；运行时状态由 {@link ScriptPlayer} 管理。
 */
public class CinematicScript {

    private final ScriptMeta meta;
    private final Timeline timeline;

    /** 脚本级独立片段表（{@code clips[]}；缺省空列表）。构造后不再修改。 */
    private final List<Clip> standaloneClips;

    /**
     * 使用边构建器（解析期由 {@link ScriptParser} 装入）；{@link #getUsageIndex()} 首次访问时冻结。
     * 解析期装配顺序：先建脚本 → 登记全部轨道放置 → 装入 builder → 首次查询时冻结。
     */
    private UsageIndex.Builder usageBuilder;

    /** 冻结后的使用边索引（惰性，首次 {@link #getUsageIndex()} 建成；此后只读）。 */
    private UsageIndex usageIndex;

    /** id → 片段索引（惰性，覆盖 timeline 全部内联片段 + 独立片段；重复 id 保留第一个） */
    private Map<String, Clip> clipsById;

    /** 原始 JSON（用于服务端→客户端网络同步，非解析时为空） */
    private String rawJson;

    /** 脚本级规则集合（{@code rules} + {@code rule_bindings}；缺省空集合）。解析期装入一次，播放期只读。 */
    private RuleCollection ruleCollection = RuleCollection.EMPTY;

    /** 脚本级路径集合（{@code paths[]}；缺省空集合）。解析期装入一次，播放期只读。 */
    private PathCollection pathCollection = PathCollection.EMPTY;

    public CinematicScript(ScriptMeta meta, Timeline timeline) {
        this(meta, timeline, List.of());
    }

    /**
     * @param standaloneClips 脚本级独立片段表（{@code null} 视为空）
     */
    public CinematicScript(ScriptMeta meta, Timeline timeline, List<Clip> standaloneClips) {
        this.meta = meta;
        this.timeline = timeline;
        this.standaloneClips = standaloneClips == null || standaloneClips.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(standaloneClips));
        this.rawJson = null;
    }

    public ScriptMeta getMeta() { return meta; }
    public Timeline getTimeline() { return timeline; }

    /** 脚本级独立片段表（不可变；缺省空列表）。 */
    public List<Clip> getStandaloneClips() { return standaloneClips; }

    /** 脚本级规则集合（{@code rules} + {@code rule_bindings}；永不为 {@code null}，缺省空集合）。 */
    public RuleCollection getRuleCollection() { return ruleCollection; }

    /** 装入规则集合（解析期一次；{@code null} 视为空集合）。 */
    public void setRuleCollection(RuleCollection ruleCollection) {
        this.ruleCollection = ruleCollection == null ? RuleCollection.EMPTY : ruleCollection;
    }

    /** 脚本级路径集合（{@code paths[]}；永不为 {@code null}，缺省空集合）。 */
    public PathCollection getPathCollection() { return pathCollection; }

    /** 装入路径集合（解析期一次；{@code null} 视为空集合）。 */
    public void setPathCollection(PathCollection pathCollection) {
        this.pathCollection = pathCollection == null ? PathCollection.EMPTY : pathCollection;
    }

    /**
     * 按脚本内 id 查片段：先查惰性索引（{@link #getStandaloneClips()} + timeline 全部片段），
     * 索引未建时构建一次。未命名片段不进索引；同一 id 重复出现时返回第一个
     * （重复本身由 {@code ScriptValidator} 报错）。
     *
     * @param id 片段 id（{@code null} → {@code null}）
     * @return 命中的片段，未知返回 {@code null}
     */
    public Clip findClip(String id) {
        if (id == null) return null;
        return clipIndex().get(id);
    }

    /** 使用边构建器（解析期装配用；未装配时 {@code null}）。 */
    public UsageIndex.Builder usageBuilder() { return usageBuilder; }

    /** 装入使用边构建器；同时丢弃已冻结的旧索引（解析期只发生一次）。 */
    public void setUsageBuilder(UsageIndex.Builder usageBuilder) {
        this.usageBuilder = usageBuilder;
        this.usageIndex = null;
    }

    /**
     * 使用边索引（惰性冻结）：首次访问时用 {@link #usageBuilder()} 对当前 id 表冻结；
     * 无 builder → 空索引。冻结后重复访问返回同一实例。
     */
    public UsageIndex getUsageIndex() {
        UsageIndex index = usageIndex;
        if (index == null) {
            UsageIndex.Builder builder = usageBuilder;
            index = builder != null ? builder.freeze(clipIndex()) : UsageIndex.empty();
            usageIndex = index;
        }
        return index;
    }

    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }

    public String getId() { return meta.getId(); }

    public String getName() { return meta.getName(); }

    public int getVersion() { return meta.getVersion(); }

    public float getTotalDuration() { return timeline.getTotalDuration(); }

    public boolean isInfinite() { return timeline.isInfinite(); }

    // ========== 内部：id 索引 ==========

    private Map<String, Clip> clipIndex() {
        Map<String, Clip> index = clipsById;
        if (index == null) {
            index = buildClipIndex();
            clipsById = index;
        }
        return index;
    }

    private Map<String, Clip> buildClipIndex() {
        Map<String, Clip> index = new LinkedHashMap<>();
        List<TimelineTrack> tracks = timeline != null ? timeline.getTracks() : null;
        if (tracks != null) {
            for (TimelineTrack track : tracks) {
                if (track == null || track.getClips() == null) continue;
                for (Clip clip : track.getClips()) {
                    register(index, clip);
                }
            }
        }
        for (Clip clip : standaloneClips) {
            register(index, clip);
        }
        return index;
    }

    /** 未命名片段不进索引；重复 id 保留第一次出现（重复由校验器报错）。 */
    private static void register(Map<String, Clip> index, Clip clip) {
        if (clip == null) return;
        String id = clip.scriptClipId();
        if (id != null) {
            index.putIfAbsent(id, clip);
        }
    }

    @Override
    public String toString() {
        return String.format("CinematicScript{id=%s, name=%s, v%d, %s, standalone=%d}",
                meta.getId(), meta.getName(), meta.getVersion(), timeline, standaloneClips.size());
    }
}
