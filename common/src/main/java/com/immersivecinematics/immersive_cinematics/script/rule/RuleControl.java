package com.immersivecinematics.immersive_cinematics.script.rule;

import java.util.List;

/**
 * 规则绑定的执行控制：时间门到达后如何对待宿主片段实例。
 *
 * <ul>
 *   <li>{@link #OBSERVE}（缺省）：非阻塞——判定不成立也继续播放，成立则提交动作；</li>
 *   <li>{@link #HOLD}：到达时间门后判定未成立即持有实例（挂起），成立或被释放后继续。</li>
 * </ul>
 */
public enum RuleControl {

    /** 非阻塞观察（缺省）。 */
    OBSERVE("observe"),

    /** 到门后未成立即持有实例。 */
    HOLD("hold");

    /** 脚本内写法（JSON 字符串值）。 */
    private final String scriptName;

    RuleControl(String scriptName) {
        this.scriptName = scriptName;
    }

    /** 脚本内写法。 */
    public String scriptName() {
        return scriptName;
    }

    /** 全部合法写法（编辑器 schema / 报错文案用；顺序 = 缺省优先）。 */
    public static final List<String> SCRIPT_NAMES = List.of("observe", "hold");

    /** 按脚本写法解析；未知返回 {@code null}。 */
    public static RuleControl fromScriptName(String name) {
        if (name == null) return null;
        for (RuleControl control : values()) {
            if (control.scriptName.equals(name)) return control;
        }
        return null;
    }
}
