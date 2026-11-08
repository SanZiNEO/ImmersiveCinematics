package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.spi.TriggerType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 触发器定义：类型 + 条件表 + 重复 / 延迟 / 进入 / 退出缓冲 / 前置条件 / 轮询间隔（静态数据，运行时求值在 trigger 包）。
 */
public class TriggerDefinition {

    /** 触发器声明 id 的合法形式（1~64 位字母 / 数字 / 下划线）。 */
    public static final String ID_REGEX = "[A-Za-z0-9_]{1,64}";

    private final String type;
    /** 声明处显式写出的 id；null = 未指定，身份由 {@link #getIdentity()} 按声明下标合成 */
    private final String id;
    /** 声明在 {@code meta.triggers[]} 中的下标；-1 = 未知（短路构造） */
    private final int declarationIndex;
    private final Map<String, Object> conditions;
    private final boolean repeatable;
    private final float delay;
    private final boolean onEnter;
    private final float exitBuffer;
    /** 前置条件：AND 语义，全部满足才允许触发；空 = 无前置 */
    private final List<TriggerRequirement> requires;
    /** 触发器级轮询间隔（tick，{@link TriggerType#POLL_INTERVAL_MIN}~{@link TriggerType#POLL_INTERVAL_MAX}）；
     *  null = 未指定，运行时回落到脚本级 meta.trigger_poll_interval → 类型级默认；仅轮询类类型有效 */
    private final Integer pollInterval;
    /** 连续语义开关（扫掠 / 停留 / 事件子条件窗口）；{@link ContinuitySpec#DEFAULT} = 关闭，走旧点判定路径 */
    private final ContinuitySpec continuity;

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable) {
        this(type, conditions, repeatable, 0f, false, 0f, Collections.emptyList(), null, null, -1);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay) {
        this(type, conditions, repeatable, delay, false, 0f, Collections.emptyList(), null, null, -1);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter) {
        this(type, conditions, repeatable, delay, onEnter, 0f, Collections.emptyList(), null, null, -1);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter, float exitBuffer) {
        this(type, conditions, repeatable, delay, onEnter, exitBuffer, Collections.emptyList(), null, null, -1);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter, float exitBuffer, List<TriggerRequirement> requires) {
        this(type, conditions, repeatable, delay, onEnter, exitBuffer, requires, null, null, -1);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter,
                             float exitBuffer, List<TriggerRequirement> requires, Integer pollInterval) {
        this(type, conditions, repeatable, delay, onEnter, exitBuffer, requires, pollInterval, null, -1);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter,
                             float exitBuffer, List<TriggerRequirement> requires, Integer pollInterval,
                             String id, int declarationIndex) {
        this(type, conditions, repeatable, delay, onEnter, exitBuffer, requires, pollInterval, id, declarationIndex,
                ContinuitySpec.DEFAULT);
    }

    public TriggerDefinition(String type, Map<String, Object> conditions, boolean repeatable, float delay, boolean onEnter,
                             float exitBuffer, List<TriggerRequirement> requires, Integer pollInterval,
                             String id, int declarationIndex, ContinuitySpec continuity) {
        this.type = type;
        this.conditions = conditions != null ? conditions : Collections.emptyMap();
        this.repeatable = repeatable;
        this.delay = delay;
        this.onEnter = onEnter;
        this.exitBuffer = exitBuffer;
        this.requires = requires != null ? requires : Collections.emptyList();
        this.pollInterval = pollInterval;
        this.id = id;
        this.declarationIndex = declarationIndex;
        this.continuity = continuity != null ? continuity : ContinuitySpec.DEFAULT;
    }

    public String getType() { return type; }
    /** 声明处显式写出的 id；null = 未指定 */
    public String getId() { return id; }
    /** 脚本内唯一身份：显式 id 优先，否则 {@code type#声明下标}。 */
    public String getIdentity() { return id != null ? id : type + "#" + declarationIndex; }
    public Map<String, Object> getConditions() { return conditions; }
    public boolean isRepeatable() { return repeatable; }
    public float getDelay() { return delay; }
    public boolean isOnEnter() { return onEnter; }
    public float getExitBuffer() { return exitBuffer; }
    public List<TriggerRequirement> getRequires() { return requires; }
    /** 触发器级轮询间隔（tick）；null = 未指定（回落到脚本级 → 类型级默认） */
    public Integer getPollInterval() { return pollInterval; }
    /** 连续语义开关；{@link ContinuitySpec#DEFAULT} = 未声明（旧点判定路径） */
    public ContinuitySpec getContinuity() { return continuity; }

    @Override
    public String toString() {
        return String.format("TriggerDefinition{identity=%s, type=%s, repeatable=%s, delay=%.1f, requires=%s, conditions=%s}",
                getIdentity(), type, repeatable, delay, requires, conditions);
    }
}
