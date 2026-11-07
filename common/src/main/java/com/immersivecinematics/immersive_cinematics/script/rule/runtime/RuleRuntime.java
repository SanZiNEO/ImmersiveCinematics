package com.immersivecinematics.immersive_cinematics.script.rule.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.client.rule.ClientConditions;
import com.immersivecinematics.immersive_cinematics.client.rule.FactSource;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExecution;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExecutionLedger;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleBinding;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleCollection;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleControl;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleDefinition;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleRelease;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleReleaseKind;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleSchedule;
import com.immersivecinematics.immersive_cinematics.util.Clock;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;

import java.util.Arrays;
import java.util.List;

/**
 * 脚本级规则运行态引擎：每播放实例一份，按「片段执行进入 → 建激活 → 时间门 → 条件树 → 成功事实
 * → 提交动作」驱动（{@code ROUND-I-4-MAP} §0.2-4 / §2.3）。
 *
 * <p>与旧 {@code clip.rules} 引擎（{@code camera.rule.RuleEngine}）并存、互不影响：旧引擎继续按
 * 片段活跃窗判定片段内的 {@code rules}，本引擎只看脚本级 {@code rules} + {@code rule_bindings}。
 * 同一脚本同时出现两者由解析期拒绝（§0.2-1），因此正常播放时只有一侧有活。</p>
 *
 * <p>帧内语义（严格按任务契约）：
 * <ol>
 *   <li>片段事实采集：执行进入即记录（时间门之前），条件树可引用任意片段 id 的
 *       {@code clip_fact}；门后判定只读事实，不重采集。</li>
 *   <li>激活建立：绑定目标片段执行身份变化 = 上一执行结束（不补事实）+ 本执行进入；
 *       一帧跨多次执行时账本只保留最终执行身份，中间执行不建激活、不产生事实。</li>
 *   <li>时间门：{@code localTime >= gate.at} 且片段已开始；门不满足 → 释放该绑定的持有并跳过。</li>
 *   <li>判定：{@code ClientConditions.evaluate(condition, ctx, localTime, facts)}；{@code enter}
 *       上升沿 / {@code dwell} 驻留 {@code dtPlayback} 累计；{@code once} / {@code cooldown} 门。</li>
 *   <li>触发：记录成功事实、释放持有、按声明提交动作：无 {@code schedule} → 立即提交并记录
 *       {@code action_completed}；有 {@code schedule} → 入队等待释放条件（<b>不</b>记录动作完成，
 *       成功 ≠ 动作完成）。</li>
 *   <li>待执行队列：每帧在绑定循环<b>之前</b>出队已释放的动作（本帧释放对同帧判定可见）。五种释放
 *       条件见 {@link #releaseVerdict}；出队的动作按承诺顺序提交。队列不冻结内容，只作为
 *       {@code ScriptPlayer} 的自然完成依赖（{@link #hasPendingActions()}）。</li>
 *   <li>超时：门满足后未成功，真实时钟（{@link #setClockSource}）到点 → {@code on_timeout} 一次，
 *       标记 {@code timedOut} / {@code finished}。</li>
 *   <li>持有：{@code control=hold} 且门后未成立 → {@link SuspensionLedger}（来源
 *       {@link SuspensionLedger#RULE_HOLD_PREFIX} + {@code binding.id()}）；成功 / 超时 / 门未满足 /
 *       执行切换 → 释放。{@code pause} 动作另记 {@link SuspensionLedger#ACTION_PAUSE}，
 *       {@code resume} 只释放该来源。帧末按「0→1 / 1→0」边沿调 {@code beginSuspend} /
 *       {@code endSuspend}，且只解除本引擎造成的挂起。</li>
 *   <li>释放可达性诊断（限频、每个「绑定 × 执行」最多一次）：冻结期间判定持有等待尚未开始的
 *       其它片段（{@code clip_fact}）或未来局部时间（{@code time.at} / {@code time.from}）→
 *       {@link Diagnostics}；{@code pause} 无任何释放者 → 告警并自动释放该来源。</li>
 * </ol>
 *
 * <p>分配契约：绑定数组、激活数组、持有来源字符串在 {@link #sync} 时预分配；{@link #evaluate}
 * 不新建集合、不装箱（条件树求值见 {@link ClientConditions}，事实查表见 {@link RuleFactStore}）。
 * 线程契约：仅客户端主线程。</p>
 */
public final class RuleRuntime {

    /** 规则动作提交出口：默认 {@link RuleActionRunner#run}；探针可注入计数实现。 */
    @FunctionalInterface
    public interface ActionSink {
        void run(PlaybackInstance instance, Clip clip, RuleBinding binding,
                 List<JsonObject> actions, boolean timedOut);
    }

    /** 诊断出口（不可达持有 / 无释放者的 pause）：默认限频日志；探针可注入计数实现。 */
    @FunctionalInterface
    public interface Diagnostics {
        void warn(String key, String message);
    }

    /** 诊断限频窗口（毫秒）：同一 key 每 5 秒最多一条。 */
    private static final long DIAGNOSTIC_INTERVAL_MS = 5000L;

    /** 条件树扫描最大递归层数（防手写脚本深嵌套）。 */
    private static final int MAX_CONDITION_DEPTH = 16;

    /** 动作作用对象（本实例）；未接线（探针）时为 {@code null}。 */
    private PlaybackInstance owner;

    /** 判定源上下文（生产 = 客户端实现；探针可注入固定值实现）。 */
    private ClientConditions.Context context = new ClientConditions.LocalContext();

    /** 真实时钟（超时用）：挂起期间照走。 */
    private Clock clock = () -> System.nanoTime() / 1e9;

    /** 默认动作族（实例方法：{@code pause} / {@code resume} 进本运行态的持有账本）。 */
    private final RuleActionRunner actionRunner = new RuleActionRunner(this);

    /** 动作提交出口（默认 = 真实动作族）。 */
    private ActionSink actionSink = actionRunner::run;

    /** 诊断出口（默认 = 限频日志）。 */
    private Diagnostics diagnostics = (key, message) ->
            ErrorLog.logRateLimited("Rules", key, DIAGNOSTIC_INTERVAL_MS, message);

    /** 已发出的诊断条数（探针用）。 */
    private int diagnosticCount;

    // ===== 同步数据（sync 时建成，帧内只读） =====

    /** 当前脚本（{@code rules} 事实查询 / 片段 id 解析用）。 */
    private CinematicScript script;

    /** 当前规则集合（缺省空集合）。 */
    private RuleCollection collection = RuleCollection.EMPTY;

    private RuleBinding[] bindings = new RuleBinding[0];
    private RuleActivation[] activations = new RuleActivation[0];

    /** 各绑定的持有来源字符串（预分配，帧内不拼串）。 */
    private String[] holdSources = new String[0];

    /** 各绑定已诊断过的执行身份（0 = 尚未诊断；sync 时预分配）。 */
    private long[] diagnosedExecutions = new long[0];

    /** 待执行动作队列（预分配；帧内只读 + 出队，扩容只发生在入队帧）。 */
    private PendingAction[] pending = new PendingAction[0];

    /** 队列中有效条目数（{@code pending[0, pendingCount)} 为承诺顺序）。 */
    private int pendingCount;

    /** 规则集合里是否存在 {@code pause} 的释放者（{@code resume} 动作 / 带 {@code schedule} 的绑定）。 */
    private boolean pauseReleasable;

    /** 最近一次不可达扫描命中的片段 id（诊断文案用；{@code null} = 未命中）。 */
    private String unreachableClipId;

    /** 最近一次不可达扫描命中的局部时间阈值（诊断文案用；{@code NaN} = 未命中）。 */
    private double unreachableTime = Double.NaN;

    private int bindingCount;

    // ===== 运行态 =====

    private final RuleFactStore facts = new RuleFactStore();
    private final SuspensionLedger holds = new SuspensionLedger();

    /** 事实查询适配器（复用实例，帧内不新建）。 */
    private final FactSource factSource = new RuntimeFactSource();

    /** 本引擎是否造成了当前挂起（只解除自己造成的挂起，不碰旧 until / pause）。 */
    private boolean frozenByRules;

    /** 上一帧播放时钟（{@code dwell} 用；首帧 / 回退按 0）。 */
    private double lastElapsed = Double.NaN;

    /** 激活建立次数（诊断 / 探针：验证「跳过执行不建激活」）。 */
    private long activationCreations;

    /** 接线动作作用对象（{@code PlaybackInstance} 构造时调用一次）。 */
    public void attachOwner(PlaybackInstance owner) {
        this.owner = owner;
    }

    /** 注入判定源上下文（探针用；生产不调，默认 = 客户端实现）。 */
    public void setContext(ClientConditions.Context context) {
        if (context != null) this.context = context;
    }

    /** 注入真实时钟（超时用；探针用，生产不调 = 系统单调时钟）。 */
    public void setClockSource(Clock clock) {
        if (clock != null) this.clock = clock;
    }

    /** 注入动作提交出口（探针用；生产不调 = {@link RuleActionRunner}）。 */
    public void setActionSink(ActionSink sink) {
        if (sink != null) this.actionSink = sink;
    }

    /** 注入诊断出口（探针用；生产不调 = 限频日志）。 */
    public void setDiagnostics(Diagnostics diagnostics) {
        if (diagnostics != null) this.diagnostics = diagnostics;
    }

    // ===== 动作持有的入口（RuleActionRunner 调用） =====

    /**
     * {@code pause} 动作：给本实例加一个 {@link SuspensionLedger#ACTION_PAUSE} 持有。
     * 帧末的 {@link #updateSuspension} 把账本状态转成 {@code ScriptPlayer} 挂起。
     */
    public void onActionPause() {
        holds.acquire(SuspensionLedger.ACTION_PAUSE);
    }

    /**
     * {@code resume} 动作：释放全部 {@code pause} 持有（判定持有 / 其它来源不受影响，
     * 因此最后一个来源仍在时内容位置不恢复推进）。
     */
    public void onActionResume() {
        holds.releaseAll(SuspensionLedger.ACTION_PAUSE);
    }

    // ===== 生命周期 =====

    /**
     * 按脚本重建运行态（{@code start} / {@code replaceScript} 时调用）：释放全部持有、
     * 清空事实、按绑定表预分配激活。
     *
     * @param script 新脚本（{@code null} = 只清运行态）
     * @param player 所属播放器（释放本引擎挂起用；可为 {@code null}）
     */
    public void sync(CinematicScript script, ScriptPlayer player) {
        releaseAllHolds(player);
        clearPending();
        this.script = script;
        this.collection = script != null ? script.getRuleCollection() : RuleCollection.EMPTY;
        this.facts.reset();
        this.lastElapsed = Double.NaN;

        List<RuleBinding> list = collection.bindings();
        int count = list.size();
        bindings = new RuleBinding[count];
        activations = new RuleActivation[count];
        holdSources = new String[count];
        diagnosedExecutions = new long[count];
        pending = new PendingAction[Math.max(4, count * 2)];
        for (int i = 0; i < count; i++) {
            RuleBinding binding = list.get(i);
            RuleActivation activation = new RuleActivation();
            activation.bind(binding);
            activation.clearExecution();
            bindings[i] = binding;
            activations[i] = activation;
            holdSources[i] = SuspensionLedger.RULE_HOLD_PREFIX + binding.id();
        }
        bindingCount = count;
        pauseReleasable = computePauseReleasable();
    }

    /** 清空运行态并释放本引擎全部持有（{@code stop} 时调用：不留冻结的实例）。 */
    public void reset(ScriptPlayer player) {
        releaseAllHolds(player);
        clearPending();
        pending = new PendingAction[0];
        facts.reset();
        script = null;
        collection = RuleCollection.EMPTY;
        bindings = new RuleBinding[0];
        activations = new RuleActivation[0];
        holdSources = new String[0];
        diagnosedExecutions = new long[0];
        pauseReleasable = false;
        unreachableClipId = null;
        unreachableTime = Double.NaN;
        bindingCount = 0;
        lastElapsed = Double.NaN;
    }

    // ===== 帧驱动 =====

    /**
     * 每帧判定一次（调用点 = {@code FrameDriver} 里旧 {@code RuleEngine.evaluate} 之后）。
     *
     * @param player 本实例播放器（时间轴读数 / 执行账本 / 挂起开关都作用于它）
     */
    public void evaluate(ScriptPlayer player) {
        if (player == null || bindingCount == 0) return;
        ClipExecutionLedger ledger = player.executions();
        if (ledger == null) return;

        double head = player.timelineTime();
        double nowReal = clock.seconds();

        // 门前采集 + 待执行出队：都不依赖判定源上下文（上下文不可用时延迟 / 事实仍要释放）
        recordClipFacts(ledger, head);
        pollPending(ledger, head, nowReal);

        context.beginFrame();
        if (!context.available()) return;

        double elapsed = player.getElapsedSeconds();
        double dt = Double.isNaN(lastElapsed) ? 0.0 : elapsed - lastElapsed;
        if (!(dt >= 0.0)) dt = 0.0;          // 首帧 / 播放时钟回退：驻留不倒退
        lastElapsed = elapsed;

        for (int i = 0; i < bindingCount; i++) {
            stepBinding(ledger, head, elapsed, dt, nowReal, i);
        }

        releasePauseWithoutReleaser(ledger);
        updateSuspension(player);
        diagnoseUnreachableHolds(ledger, head, player);
    }

    // ===== 单绑定判定 =====

    private void stepBinding(ClipExecutionLedger ledger, double head,
                             double elapsed, double dt, double nowReal, int index) {
        RuleBinding binding = bindings[index];
        RuleActivation activation = activations[index];
        ClipExecution execution = ledger.executionOf(binding.target());
        if (execution == null) return;

        // 片段尚未开始：不建激活、不采集事实、不持有（门不得因 localTime=0 误满足）
        if (execution.windowStart() > head) {
            if (activation.executionId() != 0L) {
                releaseHold(index);
                activation.clearExecution();
            }
            return;
        }

        // 激活建立：执行身份变化 = 上一执行结束（不补事实）+ 本执行进入
        if (activation.executionId() != execution.executionId()) {
            releaseHold(index);
            activation.beginExecution(execution.executionId(), execution.executionIndex());
            activationCreations++;
            // 事实作用域 = 最近一次激活：新激活尚未成功、也尚未提交动作
            facts.clearActivationFacts(binding);
            if (!activation.started()) {
                facts.recordClipStarted(binding.target(), execution.executionId());
                activation.setStarted(true);
            }
        }

        // 片段完成事实（只记最终执行；跳过执行从不被观察到）
        if (execution.done()) {
            facts.recordClipCompleted(binding.target(), execution.executionId());
        }

        // 时间门：外层门 AND 内层条件树；门不满足 → 释放该绑定的持有并跳过
        double localTime = execution.localTime();
        if (localTime < binding.gateTime() || localTime < 0.0) {
            releaseHold(index);
            return;
        }
        if (!activation.armed()) {
            activation.arm(nowReal);         // 首次满足：超时从这里起算
        }

        RuleDefinition definition = binding.definition();
        boolean truth = ClientConditions.evaluate(definition.condition(), context, localTime, factSource);
        boolean trigger;
        if (RuleDefinition.MODE_DWELL.equals(definition.mode())) {
            if (truth) {
                activation.addDwell(dt);
                trigger = activation.dwellAccum() >= definition.dwell();
            } else {
                activation.resetDwell();     // 中途断掉清零
                trigger = false;
            }
        } else {
            trigger = truth && !activation.edgeTrue();
            activation.setEdgeTrue(truth);
        }

        // 触发：once 已成功 / 冷却中 / 已终态 → 不再触发
        if (trigger && !activation.finished()
                && !(definition.once() && activation.succeeded())
                && elapsed >= activation.cooldownUntil()) {
            activation.setSucceeded(true);
            activation.setCooldownUntil(elapsed + definition.cooldown());
            facts.recordRuleSucceeded(binding, execution.executionId());
            releaseHold(index);
            commit(binding, definition.actions(), execution, ledger, nowReal, false);
            return;
        }

        // 超时：门后仍未成功，真实时钟到点 → on_timeout 一次（判定成功与动作提交是两件事）
        if (!activation.succeeded() && !activation.finished()
                && definition.timeout() >= 0.0
                && nowReal - activation.gateReachedAtReal() >= definition.timeout()) {
            activation.setTimedOut(true);
            activation.setFinished(true);
            releaseHold(index);
            commit(binding, definition.onTimeout(), execution, ledger, nowReal, true);
            return;
        }

        // 持有：门后未成立（且未成功 / 未超时 / 未终态）→ 按来源持有；否则释放
        if (binding.control() == RuleControl.HOLD) {
            if (!activation.succeeded() && !activation.timedOut() && !activation.finished()) {
                holds.acquire(holdSources[index]);
                activation.setHoldAcquired(true);
            } else {
                releaseHold(index);
            }
        }
    }

    // ===== 待执行动作（成功 ≠ 动作完成） =====

    /** 出队判据结果：继续等待。 */
    private static final int WAIT = 0;

    /** 出队判据结果：释放（提交）。 */
    private static final int RELEASED = 1;

    /** 出队判据结果：不可满足（限频告警后丢弃，不阻塞自然完成）。 */
    private static final int DROPPED = -1;

    /**
     * 判定成功 / 超时后的动作提交：无 {@code schedule} → 立即提交并记录 {@code action_completed}；
     * 有 {@code schedule} → 入队等待释放条件，<b>不</b>记录动作完成事实。
     */
    private void commit(RuleBinding binding, List<JsonObject> actions, ClipExecution execution,
                        ClipExecutionLedger ledger, double nowReal, boolean timedOut) {
        RuleSchedule schedule = binding.schedule();
        if (schedule == null || schedule.isImmediate()) {
            actionSink.run(owner, binding.target(), binding, actions, timedOut);
            facts.recordActionCompleted(binding, execution.executionId());
            return;
        }
        RuleRelease release = schedule.release();
        if (release == null) {                   // 解析期不可能出现；防御：按立即提交处理
            actionSink.run(owner, binding.target(), binding, actions, timedOut);
            facts.recordActionCompleted(binding, execution.executionId());
            return;
        }
        if (pendingCount >= pending.length) {
            pending = Arrays.copyOf(pending, Math.max(4, pending.length * 2));
        }
        pending[pendingCount++] = new PendingAction(binding, actions, release, ledger.lastHead(), nowReal,
                facts.sequence(), releaseExecutionId(ledger, release), timedOut);
    }

    /**
     * 释放条件目标片段的当前执行身份（{@code -1} = 该形态没有目标片段，如 {@code delay} / {@code fact}）。
     * 入队时快照，用于区分「本执行已到达」与「下一次执行开始」。
     */
    private static long releaseExecutionId(ClipExecutionLedger ledger, RuleRelease release) {
        Clip clip = release.clip();
        if (clip == null) return -1L;
        ClipExecution execution = ledger.executionOf(clip);
        return execution != null ? execution.executionId() : -1L;
    }

    /**
     * 出队已释放的待执行动作（在绑定循环之前：本帧释放的动作对同帧判定可见）。
     *
     * <p>不满足的按承诺顺序留在队里；满足的出队提交；不可满足的丢弃。帧内不新建集合。</p>
     */
    private void pollPending(ClipExecutionLedger ledger, double head, double nowReal) {
        if (pendingCount == 0) return;
        int kept = 0;
        for (int i = 0; i < pendingCount; i++) {
            PendingAction action = pending[i];
            int verdict = releaseVerdict(ledger, head, nowReal, action);
            if (verdict == RELEASED) {
                submit(action, ledger);
            } else if (verdict == WAIT) {
                pending[kept++] = action;
            }   // DROPPED：告警已在判据里发出
        }
        for (int i = kept; i < pendingCount; i++) {
            pending[i] = null;
        }
        pendingCount = kept;
    }

    /**
     * 单条待执行动作的释放判据（{@code ROUND-I-4-MAP} §1.4）：
     * <ol>
     *   <li>{@code clip_start}：目标片段在承诺<b>之后</b>记录了开始事实（序号判据）；兼容判据 =
     *       执行身份变化且目标已进入活跃窗（覆盖账本重建分配新身份的路径）。</li>
     *   <li>{@code clip_complete}：目标片段已完成，且完成事实序号晚于承诺。</li>
     *   <li>{@code local_time}：目标片段活跃、局部时间 {@code >= at}，且已离开承诺帧（播放头前进或
     *       执行身份变化）。</li>
     *   <li>{@code delay}：真实时钟经过 {@code seconds} 秒（挂起不冻结）。</li>
     *   <li>{@code fact}：另一绑定的 {@code succeeded} / {@code action_completed} 事实序号晚于承诺。</li>
     * </ol>
     */
    private int releaseVerdict(ClipExecutionLedger ledger, double head, double nowReal,
                               PendingAction action) {
        RuleRelease release = action.release;
        if (release == null) return DROPPED;               // 防御：入队路径已排除
        RuleReleaseKind kind = release.kind();
        if (kind == null) return DROPPED;
        switch (kind) {
            case CLIP_START: {
                Clip clip = release.clip();
                ClipExecution execution = ledger.executionOf(clip);
                if (execution == null) return drop(action, "目标片段不在本实例账本中");
                if (facts.clipStartSeq(clip) > action.requestedSeq) return RELEASED;
                boolean nextExecution = execution.executionId() != action.requestedExecutionId
                        && head >= execution.windowStart();
                return nextExecution ? RELEASED : WAIT;
            }
            case CLIP_COMPLETE: {
                Clip clip = release.clip();
                if (ledger.executionOf(clip) == null) return drop(action, "目标片段不在本实例账本中");
                return facts.clipCompleted(clip) && facts.clipCompletionSeq(clip) > action.requestedSeq
                        ? RELEASED : WAIT;
            }
            case LOCAL_TIME: {
                Clip clip = release.clip();
                ClipExecution execution = ledger.executionOf(clip);
                if (execution == null) return drop(action, "目标片段不在本实例账本中");
                if (!ledger.isActive(clip, head)) return WAIT;
                if (ledger.localTime(clip, head) < release.at()) return WAIT;
                boolean advanced = execution.executionId() != action.requestedExecutionId
                        || head > action.requestedHead;
                return advanced ? RELEASED : WAIT;
            }
            case DELAY:
                return nowReal - action.requestedReal >= release.at() ? RELEASED : WAIT;
            case FACT: {
                RuleBinding source = release.source();
                if (source == null) return drop(action, "事实释放条件缺少来源绑定");
                long seq;
                if ("succeeded".equals(release.fact())) {
                    seq = facts.ruleSuccessSeq(source);
                } else if ("action_completed".equals(release.fact())) {
                    seq = facts.ruleActionSeq(source);
                } else {
                    return drop(action, "未知事实: " + release.fact());
                }
                return seq > action.requestedSeq ? RELEASED : WAIT;
            }
            default:
                return drop(action, "未知释放条件: " + kind);
        }
    }

    /** 提交一条待执行动作并记录 {@code action_completed}（成功 ≠ 动作完成）。 */
    private void submit(PendingAction action, ClipExecutionLedger ledger) {
        ClipExecution execution = ledger.executionOf(action.binding.target());
        long executionId = execution != null ? execution.executionId() : action.requestedExecutionId;
        actionSink.run(owner, action.binding.target(), action.binding, action.actions, action.timedOut);
        facts.recordActionCompleted(action.binding, executionId);
        logReleased(action);
    }

    /** 提交成功的限频日志（正常路径，不计入 {@link #diagnosticCount()}）。 */
    private void logReleased(PendingAction action) {
        RuleBinding binding = action.binding;
        ErrorLog.logRateLimited("Rules", "action:released:" + binding.id(), DIAGNOSTIC_INTERVAL_MS,
                "规则 " + binding.definition().id() + "（绑定 " + binding.id()
                        + "）的待执行动作已提交：" + action.release.kind().scriptName());
    }

    /** 丢弃不可满足的待执行动作：限频告警（不阻塞自然完成）。 */
    private int drop(PendingAction action, String reason) {
        warn("action:drop:" + action.binding.id(), "规则 " + action.binding.definition().id()
                + "（绑定 " + action.binding.id() + "）的待执行动作已丢弃：" + reason);
        return DROPPED;
    }

    /** 清空待执行队列（{@code sync} / {@code reset}：旧承诺随旧脚本 / 旧实例失效）。 */
    private void clearPending() {
        for (int i = 0; i < pendingCount; i++) {
            pending[i] = null;
        }
        pendingCount = 0;
    }

    /** 是否有已承诺、尚未提交的动作（{@code ScriptPlayer} 的自然完成依赖）。 */
    public boolean hasPendingActions() {
        return pendingCount > 0;
    }

    /** 待执行动作条数（诊断 / 探针）。 */
    public int pendingCount() {
        return pendingCount;
    }

    // ===== 片段事实采集 =====

    /**
     * 按片段采集开始 / 完成事实（条件树可引用任意片段 id，不限于绑定目标）。
     * 只观察账本当前执行：一帧跨多次执行时中间执行从不被观察到（§0.2-5）。
     */
    private void recordClipFacts(ClipExecutionLedger ledger, double head) {
        for (int i = 0, size = ledger.size(); i < size; i++) {
            ClipExecution execution = ledger.at(i);
            if (execution == null || execution.windowStart() > head) continue;
            long executionId = execution.executionId();
            if (executionId <= 0L) continue;
            facts.recordClipStarted(execution.clip(), executionId);
            if (execution.done()) {
                facts.recordClipCompleted(execution.clip(), executionId);
            }
        }
    }

    // ===== 持有 / 挂起边沿 =====

    /** 释放第 {@code index} 条绑定经账本的持有（未持有时无操作）。 */
    private void releaseHold(int index) {
        if (!activations[index].holdAcquired()) return;
        holds.release(holdSources[index]);
        activations[index].setHoldAcquired(false);
    }

    /** 释放本引擎全部持有，并在「1→0」边沿解除本引擎造成的挂起。 */
    private void releaseAllHolds(ScriptPlayer player) {
        holds.clear();
        for (int i = 0; i < bindingCount; i++) {
            activations[i].setHoldAcquired(false);
        }
        if (frozenByRules) {
            frozenByRules = false;
            if (player != null && player.isSuspended()) player.endSuspend();
        }
    }

    /** 帧末：持有非空且实例未挂起 → 由本引擎挂起；持有清空且本引擎曾挂起 → 解除。 */
    private void updateSuspension(ScriptPlayer player) {
        if (holds.held()) {
            if (!player.isSuspended()) {
                player.beginSuspend();
                frozenByRules = true;
            }
        } else if (frozenByRules) {
            frozenByRules = false;
            if (player.isSuspended()) player.endSuspend();
        }
    }

    // ===== 释放可达性诊断 =====

    /**
     * {@code pause} 在本执行内已无仍可触发的 {@code resume} → 告警并自动释放该来源。
     *
     * <p>判据不是「规则集合里写没写 {@code resume}」：写了但绑定已 {@code finished}
     * （含超时终结）或 {@code once} 已成功，就再也触发不了。与旧 {@code clip.rules}
     * 的同片段口径一致：防卡死。</p>
     */
    private void releasePauseWithoutReleaser(ClipExecutionLedger ledger) {
        if (!holds.has(SuspensionLedger.ACTION_PAUSE)) return;
        if (hasLivePauseReleaser(ledger)) return;
        holds.releaseAll(SuspensionLedger.ACTION_PAUSE);
        warn("pause:no-releaser",
                "规则动作 pause 已无释放者（本执行内没有仍可触发的 resume）——自动释放");
    }

    /**
     * 本执行内是否仍有可触发的 {@code resume}（{@code pause} 的活释放者）。
     *
     * <ul>
     *   <li>静态层面就无释放者（{@link #pauseReleasable} 为 false）→ 没有；</li>
     *   <li>任一绑定带非立即 {@code schedule} → 有（调度释放仍可能）；</li>
     *   <li>动作 / {@code on_timeout} 含 {@code resume} 的绑定：执行未知、激活尚未对应该执行、
     *       或尚未 {@code finished}（且非 {@code once} 已成功）→ 有。</li>
     * </ul>
     */
    private boolean hasLivePauseReleaser(ClipExecutionLedger ledger) {
        if (!pauseReleasable) return false;
        for (int i = 0; i < bindingCount; i++) {
            RuleBinding binding = bindings[i];
            if (!binding.schedule().isImmediate()) return true;
            RuleDefinition definition = binding.definition();
            if (!hasActionType(definition.actions(), "resume")
                    && !hasActionType(definition.onTimeout(), "resume")) {
                continue;
            }
            ClipExecution execution = ledger.executionOf(binding.target());
            if (execution == null) return true;   // 未知 → 保守按仍可触发
            RuleActivation activation = activations[i];
            if (activation.executionId() != execution.executionId()) return true;   // 本执行尚未建立激活
            if (!activation.finished() && !(definition.once() && activation.succeeded())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 冻结期间的判定持有是否等待了不会发生的进度：每个「绑定 × 执行」最多报告一次。
     *
     * <p>只报告、不自动解除：不可达持有是作者声明问题，静默解除会掩盖模型错误。</p>
     */
    private void diagnoseUnreachableHolds(ClipExecutionLedger ledger, double head, ScriptPlayer player) {
        if (!player.isSuspended()) return;
        for (int i = 0; i < bindingCount; i++) {
            if (!holds.has(holdSources[i])) continue;
            long executionId = activations[i].executionId();
            if (executionId == 0L || diagnosedExecutions[i] == executionId) continue;
            RuleBinding binding = bindings[i];
            ClipExecution execution = ledger.executionOf(binding.target());
            if (execution == null) continue;
            unreachableClipId = null;
            unreachableTime = Double.NaN;
            if (!scanUnreachable(binding.definition().condition(), binding.target(), ledger,
                    head, execution.localTime(), 1)) {
                continue;
            }
            diagnosedExecutions[i] = executionId;
            warn("hold:unreachable:" + binding.id(), unreachableMessage(binding));
        }
    }

    /** 命中原因 → 诊断文案（{@code clip_fact} 优先，其次 {@code time}）。 */
    private String unreachableMessage(RuleBinding binding) {
        String ruleId = binding.definition().id();
        if (unreachableClipId != null) {
            return "规则 " + ruleId + " 的判定持有等待片段 " + unreachableClipId
                    + " 开始/完成，但本实例内容位置已冻结——该片段不会开始；"
                    + "请释放持有（例如先不声明 control=hold）或改用可达的释放条件";
        }
        return "规则 " + ruleId + " 的判定持有等待局部时间 " + unreachableTime
                + " 秒，但本实例内容位置已冻结——time 条件不会被冻结的内容推进满足；"
                + "请释放持有（例如先不声明 control=hold）或改用可达的释放条件";
    }

    /**
     * 只读扫描条件树，找不可达依赖：{@code clip_fact} 引用目标片段以外、当前播放头尚未到达
     * 其窗口起点的片段；{@code time} 的 {@code at} / {@code from} 大于当前执行局部时间。
     *
     * <p>结构兼容两种形态：顶层组合器 {@code {type, list:[{type, conditions}]}} 与嵌套组合器
     * {@code {type:"all_of", conditions:{list:[...]}}}。不建集合、不修改 JSON。</p>
     *
     * @return {@code true} = 命中（命中信息写入 {@link #unreachableClipId} / {@link #unreachableTime}）
     */
    private boolean scanUnreachable(JsonObject node, Clip target, ClipExecutionLedger ledger,
                                    double head, double localTime, int depth) {
        if (node == null || depth > MAX_CONDITION_DEPTH) return false;
        String type = stringOf(node, "type");
        if (type == null) return false;
        switch (type) {
            case "clip_fact":
                return scanUnreachableClipFact(dataOf(node), target, ledger, head);
            case "time":
                return scanUnreachableTime(dataOf(node), localTime);
            case "all_of", "any": {
                JsonObject data = dataOf(node);
                if (scanUnreachableList(node.get("list"), target, ledger, head, localTime, depth)) {
                    return true;
                }
                return data != node
                        && scanUnreachableList(data.get("list"), target, ledger, head, localTime, depth);
            }
            default:
                return false;
        }
    }

    /** 组合器子节点数组（{@code list}）：逐元素递归。 */
    private boolean scanUnreachableList(JsonElement list, Clip target, ClipExecutionLedger ledger,
                                        double head, double localTime, int depth) {
        if (list == null || !list.isJsonArray()) return false;
        JsonArray array = list.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) continue;
            if (scanUnreachable(element.getAsJsonObject(), target, ledger, head, localTime, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** {@code clip_fact}：目标片段以外、且窗口起点仍在播放头之后的片段 → 冻结下不会开始。 */
    private boolean scanUnreachableClipFact(JsonObject data, Clip target, ClipExecutionLedger ledger,
                                            double head) {
        String clipId = stringOf(data, "clip");
        if (clipId == null || clipId.isEmpty()) return false;
        if (target != null && clipId.equals(target.scriptClipId())) return false;
        Clip clip = script != null ? script.findClip(clipId) : null;
        if (clip == null) return false;
        ClipExecution execution = ledger.executionOf(clip);
        if (execution == null || execution.windowStart() <= head) return false;
        unreachableClipId = clipId;
        return true;
    }

    /** {@code time}：{@code at} / {@code from} 超出当前执行局部时间 → 冻结下不会被满足。 */
    private boolean scanUnreachableTime(JsonObject data, double localTime) {
        double at = numberOrNaN(data, "at");
        double from = numberOrNaN(data, "from");
        double threshold = Double.isNaN(at) ? from
                : (Double.isNaN(from) ? at : Math.max(at, from));
        if (!Double.isFinite(threshold) || threshold <= localTime) return false;
        unreachableTime = threshold;
        return true;
    }

    /** 发出诊断（计数供探针断言；限频由默认实现负责）。 */
    private void warn(String key, String message) {
        diagnosticCount++;
        diagnostics.warn(key, message);
    }

    /**
     * 规则集合里是否存在 {@code pause} 的释放者：任一绑定带非立即 {@code schedule}，或任一规则的
     * 动作 / 超时动作含 {@code resume}。{@code sync} 时算一次。
     */
    private boolean computePauseReleasable() {
        List<RuleBinding> list = collection.bindings();
        for (int i = 0; i < list.size(); i++) {
            RuleBinding binding = list.get(i);
            if (!binding.schedule().isImmediate()) return true;
            RuleDefinition definition = binding.definition();
            if (hasActionType(definition.actions(), "resume")
                    || hasActionType(definition.onTimeout(), "resume")) {
                return true;
            }
        }
        return false;
    }

    /** 动作列表是否含指定 {@code type}（非对象 / 非字符串元素一律忽略）。 */
    private static boolean hasActionType(List<JsonObject> actions, String type) {
        for (int i = 0; i < actions.size(); i++) {
            JsonObject action = actions.get(i);
            if (action != null && type.equals(stringOf(action, "type"))) return true;
        }
        return false;
    }

    /** 条件节点的数据体：{@code conditions} 对象（嵌套形态）或节点自身（顶层形态）。 */
    private static JsonObject dataOf(JsonObject node) {
        if (node != null && node.has("conditions") && node.get("conditions").isJsonObject()) {
            return node.getAsJsonObject("conditions");
        }
        return node;
    }

    /** 字符串字段（缺失 / 非字符串 → {@code null}）。 */
    private static String stringOf(JsonObject object, String key) {
        if (object == null || !object.has(key)) return null;
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    /** 数值字段（缺失 / 非数字 → {@code NaN}）。 */
    private static double numberOrNaN(JsonObject data, String key) {
        if (data == null || !data.has(key)) return Double.NaN;
        JsonElement element = data.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return Double.NaN;
        return element.getAsDouble();
    }

    // ===== 查询 =====

    /** 事实存储（诊断 / 探针）。 */
    public RuleFactStore facts() {
        return facts;
    }

    /** 持有账本（诊断 / 探针）。 */
    public SuspensionLedger holds() {
        return holds;
    }

    /** 是否没有可判定的绑定（空集合 = 每帧直接返回）。 */
    public boolean isEmpty() {
        return bindingCount == 0;
    }

    /** 已发出的诊断条数（探针 / 诊断用；不受默认限频实现影响）。 */
    public int diagnosticCount() {
        return diagnosticCount;
    }

    /**
     * 激活建立次数（诊断 / 探针：验证「一帧跨多次执行只建最终执行的激活」）。
     * 包内可见，不是脚本数据 API。
     */
    long activationCreations() {
        return activationCreations;
    }

    /** 按绑定 id 取当前激活（诊断 / 探针；未知 → {@code null}）。包内可见。 */
    RuleActivation activationOf(String bindingId) {
        if (bindingId == null) return null;
        for (int i = 0; i < bindingCount; i++) {
            if (bindingId.equals(bindings[i].id())) return activations[i];
        }
        return null;
    }

    // ===== 事实适配 =====

    /** 把 {@link RuleFactStore} 包装成条件树的 {@link FactSource}（帧内只读、零分配）。 */
    private final class RuntimeFactSource implements FactSource {

        @Override
        public boolean clipFact(String clipId, boolean started) {
            Clip clip = script != null ? script.findClip(clipId) : null;
            if (clip == null) return false;
            return started ? facts.clipStarted(clip) : facts.clipCompleted(clip);
        }

        @Override
        public boolean ruleFact(String bindingId, boolean actionCompleted) {
            RuleBinding binding = collection.binding(bindingId);
            if (binding == null) return false;
            return actionCompleted ? facts.actionCompleted(binding) : facts.ruleSucceeded(binding);
        }
    }
}
