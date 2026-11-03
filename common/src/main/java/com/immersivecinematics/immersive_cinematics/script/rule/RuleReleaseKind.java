package com.immersivecinematics.immersive_cinematics.script.rule;

import java.util.List;

/**
 * 判定成功后动作的释放条件（{@code schedule.on}）：缺省（无 schedule）= 立即提交。
 */
public enum RuleReleaseKind {

    /** 目标片段下一次执行开始时提交。 */
    CLIP_START("clip_start"),

    /** 目标片段执行完成时提交。 */
    CLIP_COMPLETE("clip_complete"),

    /** 目标片段执行到达局部时间时提交。 */
    LOCAL_TIME("local_time"),

    /** 判定成功后经过真实秒数提交（挂起不冻结）。 */
    DELAY("delay"),

    /** 另一绑定的最近一次激活事实成立时提交。 */
    FACT("fact");

    /** 脚本内写法（{@code schedule.on} 的值）。 */
    private final String scriptName;

    RuleReleaseKind(String scriptName) {
        this.scriptName = scriptName;
    }

    /** 脚本内写法。 */
    public String scriptName() {
        return scriptName;
    }

    /** 全部合法写法（编辑器 schema / 报错文案用；顺序 = 文档顺序）。 */
    public static final List<String> SCRIPT_NAMES = List.of(
            "clip_start", "clip_complete", "local_time", "delay", "fact");

    /** 按脚本写法解析；未知返回 {@code null}。 */
    public static RuleReleaseKind fromScriptName(String name) {
        if (name == null) return null;
        for (RuleReleaseKind kind : values()) {
            if (kind.scriptName.equals(name)) return kind;
        }
        return null;
    }
}
