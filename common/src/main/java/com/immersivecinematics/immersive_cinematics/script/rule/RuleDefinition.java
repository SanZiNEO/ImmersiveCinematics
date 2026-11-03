package com.immersivecinematics.immersive_cinematics.script.rule;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 规则定义（脚本级 {@code rules[]} 的一条）：条件树 + 动作 + 触发策略的不可变数据载体。
 *
 * <p>条件树与动作保留原始 JSON（{@code condition} / {@code actions} 是深拷贝的只读约定对象）：
 * 本层只解析结构与策略字段，不做条件树解析、不改写内容——求值归判定源，执行归动作集。</p>
 *
 * <p>策略字段与 v1 {@code clip.rules} 同名同缺省：{@code once} = 只触发一次（缺省 true）、
 * {@code cooldown} = 触发后静默秒数（缺省 0）、{@code mode} = {@code enter} 瞬时边沿 / {@code dwell}
 * 驻留成立（缺省 enter）、{@code dwell} = 驻留阈值秒数（缺省 0）、{@code timeout} = 等待上限
 * （缺省 -1 = 无限）、{@code on_timeout} = 超时动作（缺省空）。</p>
 *
 * <p>定义本身不含片段引用——同一 {@link RuleDefinition} 可被多条 {@link RuleBinding} 复用（多入口
 * 各自独立运行态）。线程契约：仅客户端主线程；解析期一次建成、播放期只读。</p>
 */
public final class RuleDefinition {

    /** {@code once} 缺省：只触发一次。 */
    public static final boolean DEFAULT_ONCE = true;

    /** {@code cooldown} 缺省：不静默。 */
    public static final double DEFAULT_COOLDOWN = 0.0;

    /** {@code mode: enter} = 条件成立瞬间触发（缺省）。 */
    public static final String MODE_ENTER = "enter";

    /** {@code mode: dwell} = 条件驻留成立才触发。 */
    public static final String MODE_DWELL = "dwell";

    /** {@code mode} 合法取值（顺序 = 缺省优先）。 */
    public static final List<String> MODES = List.of(MODE_ENTER, MODE_DWELL);

    /** {@code dwell} 缺省：即刻触发。 */
    public static final double DEFAULT_DWELL = 0.0;

    /** {@code timeout} 缺省：无限等待。 */
    public static final double TIMEOUT_NONE = -1.0;

    private final String id;
    private final JsonObject condition;
    private final List<JsonObject> actions;
    private final boolean once;
    private final double cooldown;
    private final String mode;
    private final double dwell;
    private final double timeout;
    private final List<JsonObject> onTimeout;

    /**
     * @param id        规则 id（脚本内唯一）
     * @param condition 条件树原文（不得为 {@code null}）
     * @param actions   动作原文（至少一个）
     * @param once      只触发一次
     * @param cooldown  触发后静默秒数（{@code >= 0}）
     * @param mode      {@link #MODE_ENTER} / {@link #MODE_DWELL}
     * @param dwell     驻留阈值秒数（{@code >= 0}）
     * @param timeout   等待上限（{@code > 0}，或 {@link #TIMEOUT_NONE}）
     * @param onTimeout 超时动作（可为空表）
     */
    public RuleDefinition(String id, JsonObject condition, List<JsonObject> actions,
                          boolean once, double cooldown, String mode, double dwell,
                          double timeout, List<JsonObject> onTimeout) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("规则 id 不能为空");
        }
        if (condition == null) {
            throw new IllegalArgumentException("规则 " + id + " 缺少条件树");
        }
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("规则 " + id + " 至少需要一个动作");
        }
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("规则 " + id + " 的 mode 非法: " + mode);
        }
        if (!Double.isFinite(cooldown) || cooldown < 0.0) {
            throw new IllegalArgumentException("规则 " + id + " 的 cooldown 必须 >= 0: " + cooldown);
        }
        if (!Double.isFinite(dwell) || dwell < 0.0) {
            throw new IllegalArgumentException("规则 " + id + " 的 dwell 必须 >= 0: " + dwell);
        }
        if (timeout != TIMEOUT_NONE && (!Double.isFinite(timeout) || !(timeout > 0.0))) {
            throw new IllegalArgumentException("规则 " + id + " 的 timeout 必须 > 0 或 -1: " + timeout);
        }
        this.id = id;
        this.condition = condition;
        this.actions = List.copyOf(actions);
        this.once = once;
        this.cooldown = cooldown;
        this.mode = mode;
        this.dwell = dwell;
        this.timeout = timeout;
        this.onTimeout = onTimeout == null ? List.of() : List.copyOf(onTimeout);
    }

    /** 规则 id（脚本内唯一）。 */
    public String id() {
        return id;
    }

    /** 条件树原文（只读约定；求值归判定源）。 */
    public JsonObject condition() {
        return condition;
    }

    /** 触发动作原文（只读，至少一个）。 */
    public List<JsonObject> actions() {
        return actions;
    }

    /** 是否只触发一次（缺省 true）。 */
    public boolean once() {
        return once;
    }

    /** 触发后静默时长（秒，{@code >= 0}；0 = 不静默）。 */
    public double cooldown() {
        return cooldown;
    }

    /** 触发方式（{@link #MODE_ENTER} / {@link #MODE_DWELL}）。 */
    public String mode() {
        return mode;
    }

    /** 驻留判定阈值（秒，{@code >= 0}；仅 {@link #MODE_DWELL} 下参与判定）。 */
    public double dwell() {
        return dwell;
    }

    /** 等待上限（秒，{@code > 0}；{@link #TIMEOUT_NONE} = 无限等待）。 */
    public double timeout() {
        return timeout;
    }

    /** 超时动作原文（可为空表）。 */
    public List<JsonObject> onTimeout() {
        return onTimeout;
    }

    @Override
    public String toString() {
        return String.format("RuleDefinition{id=%s, actions=%d, once=%s, mode=%s}",
                id, actions.size(), once, mode);
    }
}
