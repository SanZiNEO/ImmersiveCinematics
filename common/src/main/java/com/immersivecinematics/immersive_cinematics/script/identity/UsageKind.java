package com.immersivecinematics.immersive_cinematics.script.identity;

/**
 * 片段使用边的种类：同一片段可能被多个宿主以不同方式引用，反向索引按此分类。
 */
public enum UsageKind {

    /** 轨道内联放置：{@code timeline.tracks[i].clips[j]}。 */
    TRACK_PLACEMENT,

    /** 规则绑定目标：{@code rule_bindings[].clip}。 */
    RULE_BINDING,

    /** 路径条目端点：{@code paths[].clip}。 */
    PATH_ENTRY,

    /** 分支目标：新形态 {@code goto_clip} 的 {@code clip}。 */
    BRANCH_TARGET,

    /** 出口目标：{@code exit.target}。 */
    EXIT_TARGET
}
