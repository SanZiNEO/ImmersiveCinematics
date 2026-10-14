package com.immersivecinematics.immersive_cinematics.camera.rule;

import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.camera.transition.TransitionSpec;
import com.immersivecinematics.immersive_cinematics.client.rule.ClientConditions;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ClipRule;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.util.Clock;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 判定规则引擎（0.3.6 C6-2）：每帧逐规则判定 clip 级 {@code rules}（判定端 = 客户端逐实例）。
 *
 * 槽位在 {@link #sync} 时按脚本一次性建成（片段活跃窗 + 规则 + 基本类型状态数组，帧内零分配）；
 * 活跃窗口径与 {@code ClipModifierGate} 一致：{@code [startTime, windowEnd)}，morph 前一段窗末延长半个过渡窗。
 * 帧内：{@code if} 规则按 enter 边沿 / dwell 驻留 + once / cooldown 门触发；{@code until} 规则条件假则挂起本实例
 * 播放时钟、真或 timeout（真实时钟）先到则解除并触发（动作执行归 {@link RuleActions}）。
 *
 * 时钟口径：cooldown 与 dwell（if）走播放时钟（挂起 / 游戏暂停一起冻结）；timeout 与 dwell（until）走
 * {@link #setClockSource 注入的真实时钟}（挂起期间照走）。折返（timelineTime 回退 = 宏观循环圈边界或向后 seek）
 * 重置 once / 边沿 / 驻留 / until 等待态（cooldown 按播放时钟跨圈保持）。
 *
 * 分配契约：{@link #evaluate} 不新建集合、不装箱（条件求值走 {@link ClientConditions}，其访问器同样零分配）。
 * 线程契约：仅客户端主线程。
 */
public final class RuleEngine {

    /** 动作作用对象（本实例）；未接线（探针）时为 {@code null}，动作执行按无宿主跳过。 */
    private PlaybackInstance owner;

    /** 判定源上下文（生产 = 读本地玩家 / 相机的客户端实现；探针可注入固定值实现）。 */
    private ClientConditions.Context context = new ClientConditions.LocalContext();

    /** 真实时钟（timeout / until 驻留用）：挂起期间照走。 */
    private Clock clock = () -> System.nanoTime() / 1e9;

    // ===== 槽位（sync 时建成，帧内只读） =====

    private Clip[] slotClips = new Clip[0];
    private ClipRule[] slotRules = new ClipRule[0];
    private float[] slotWindowStart = new float[0];
    private float[] slotWindowEnd = new float[0];
    private int slotCount = 0;

    // ===== 规则状态（每槽一份；基本类型数组，帧内不新建） =====

    /** once 已触发（if；折返重置）。 */
    private boolean[] onceFired = new boolean[0];

    /** until 已解除（条件成立 / 超时；折返重置）。 */
    private boolean[] untilDone = new boolean[0];

    /** until 等待已开始（首次进入活跃窗；折返重置）。 */
    private boolean[] untilArmed = new boolean[0];

    /** 上帧条件真值（enter 边沿；折返重置）。 */
    private boolean[] edgeTrue = new boolean[0];

    /** 驻留累计（秒；if 走播放时钟、until 走真实时钟）。 */
    private float[] dwellAccum = new float[0];

    /** 冷却截止（播放时钟秒；0 = 无冷却）。 */
    private float[] cooldownUntil = new float[0];

    /** until 等待起始（真实时钟秒）。 */
    private double[] untilWaitStart = new double[0];

    // ===== 帧内临时（复用缓冲，帧内不新建） =====

    private int[] resolvedSlots = new int[0];
    private boolean[] resolvedTimedOut = new boolean[0];
    private int resolvedCount = 0;
    private boolean wantSuspend = false;
    private boolean suspendRequested = false;
    private float lastTimeline = Float.NaN;
    private float lastElapsed = Float.NaN;
    private double lastClock = Double.NaN;

    /** 接线动作作用对象（{@code PlaybackInstance} 构造时调用一次）。 */
    public void attachOwner(PlaybackInstance owner) {
        this.owner = owner;
    }

    /** 注入判定源上下文（探针用；生产不调，默认 = 客户端实现）。 */
    public void setContext(ClientConditions.Context context) {
        if (context != null) this.context = context;
    }

    /** 注入真实时钟（探针用；生产不调，默认 = 系统单调时钟）。 */
    public void setClockSource(Clock clock) {
        if (clock != null) this.clock = clock;
    }

    /**
     * 按脚本重建规则槽位（{@code start} / {@code replaceScript} 时调用）：旧等待一并作废（脚本已换）。
     *
     * @param script 新脚本（{@code null} = 只清槽）
     * @param player 所属播放器（释放引擎挂起用；可为 {@code null}）
     */
    public void sync(CinematicScript script, ScriptPlayer player) {
        releaseSuspend(player);
        clearSlots();
        if (script == null || script.getTimeline() == null) return;

        int count = 0;
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            for (Clip clip : track.getClips()) {
                count += clip.getRules().size();
            }
        }
        if (count == 0) return;

        allocateSlots(count);
        int slot = 0;
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            List<Clip> clips = track.getClips();
            for (int ci = 0; ci < clips.size(); ci++) {
                Clip clip = clips.get(ci);
                List<ClipRule> rules = clip.getRules();
                if (rules.isEmpty()) continue;
                Clip next = ci + 1 < clips.size() ? clips.get(ci + 1) : null;
                float start = clip.getStartTime();
                float end = windowEnd(clip, next);
                for (int ri = 0; ri < rules.size(); ri++) {
                    slotClips[slot] = clip;
                    slotRules[slot] = rules.get(ri);
                    slotWindowStart[slot] = start;
                    slotWindowEnd[slot] = end;
                    slot++;
                }
            }
        }
        slotCount = count;
        lastTimeline = Float.NaN;
        lastElapsed = Float.NaN;
        lastClock = Double.NaN;
    }

    /** 清空规则槽位并释放引擎持有的挂起（{@code stop} 时调用：不留冻结的实例）。 */
    public void reset(ScriptPlayer player) {
        releaseSuspend(player);
        clearSlots();
    }

    /**
     * 每帧判定一次（调用点 = 帧循环里本实例轨道分发之后）。
     *
     * @param player 本实例播放器（时间轴读数 / 挂起开关 / seek 都作用于它）
     */
    public void evaluate(ScriptPlayer player) {
        if (slotCount == 0 || player == null) return;
        context.beginFrame();
        if (!context.available()) return;

        float timeline = player.timelineTime();
        float elapsed = player.getElapsedSeconds();
        float dt = Float.isNaN(lastElapsed) ? 0f : elapsed - lastElapsed;
        if (!(dt >= 0f)) dt = 0f;                       // 首帧 / 时间轴回退：驻留不倒退
        lastElapsed = elapsed;
        if (!Float.isNaN(lastTimeline) && timeline < lastTimeline) resetForLap();
        lastTimeline = timeline;

        double now = clock.seconds();
        float dtReal = Double.isNaN(lastClock) ? 0f : (float) (now - lastClock);
        if (!(dtReal >= 0f)) dtReal = 0f;
        lastClock = now;

        resolvedCount = 0;
        wantSuspend = false;
        for (int i = 0; i < slotCount; i++) {
            if (timeline < slotWindowStart[i] || timeline >= slotWindowEnd[i]) continue;
            ClipRule rule = slotRules[i];
            float localTime = timeline - slotClips[i].getStartTime();
            if (ClipRule.KIND_UNTIL.equals(rule.getKind())) {
                stepUntil(i, rule, localTime, now, dtReal);
            } else {
                stepIf(i, rule, localTime, elapsed, dt);
            }
        }

        if (resolvedCount > 0) releaseSuspend(player);
        for (int r = 0; r < resolvedCount; r++) {
            int slot = resolvedSlots[r];
            ClipRule rule = slotRules[slot];
            List<JsonObject> actions = resolvedTimedOut[r] ? rule.getOnTimeout() : rule.getActions();
            for (int a = 0; a < actions.size(); a++) {
                RuleActions.execute(owner, slotClips[slot], rule, actions.get(a));
            }
        }
        if (wantSuspend) {
            player.beginSuspend();
            suspendRequested = true;
        } else if (suspendRequested) {
            releaseSuspend(player);
        }
    }

    /** 持续规则（{@code if}）：enter 边沿 / dwell 驻留 + once / cooldown 门 → 触发。 */
    private void stepIf(int slot, ClipRule rule, float localTime, float elapsed, float dt) {
        if (onceFired[slot]) return;
        boolean truth = ClientConditions.evaluate(rule.getCondition(), context, localTime);
        boolean trigger;
        if (ClipRule.MODE_DWELL.equals(rule.getMode())) {
            dwellAccum[slot] = truth ? dwellAccum[slot] + dt : 0f;
            trigger = truth && dwellAccum[slot] >= rule.getDwell();
        } else {
            trigger = truth && !edgeTrue[slot];
            edgeTrue[slot] = truth;
        }
        if (!trigger || elapsed < cooldownUntil[slot]) return;
        // 冷却以触发瞬间的播放时钟起算（动作若 seek，窗口按 seek 后的播放时钟继续计）
        cooldownUntil[slot] = elapsed + rule.getCooldown();
        if (rule.isOnce()) onceFired[slot] = true;
        resolve(slot, false);
    }

    /** 阻塞规则（{@code until}）：条件假 → 请求挂起；真 / timeout（真实时钟）先到 → 解除并触发。 */
    private void stepUntil(int slot, ClipRule rule, float localTime, double now, float dtReal) {
        if (untilDone[slot]) return;
        if (!untilArmed[slot]) {
            untilArmed[slot] = true;
            untilWaitStart[slot] = now;
        }
        boolean truth = ClientConditions.evaluate(rule.getCondition(), context, localTime);
        if (ClipRule.MODE_DWELL.equals(rule.getMode())) {
            dwellAccum[slot] = truth ? dwellAccum[slot] + dtReal : 0f;
            truth = truth && dwellAccum[slot] >= rule.getDwell();
        }
        if (truth) {
            resolve(slot, false);
        } else if (rule.getTimeout() != ClipRule.TIMEOUT_NONE
                && now - untilWaitStart[slot] >= rule.getTimeout()) {
            resolve(slot, true);
        } else {
            wantSuspend = true;
        }
    }

    /** 记一次触发（动作延迟到挂起释放之后按声明序执行：设计 §3-3「恢复时钟并执行 do」）。 */
    private void resolve(int slot, boolean timedOut) {
        if (ClipRule.KIND_UNTIL.equals(slotRules[slot].getKind())) untilDone[slot] = true;
        if (resolvedCount >= resolvedSlots.length) return;
        resolvedSlots[resolvedCount] = slot;
        resolvedTimedOut[resolvedCount] = timedOut;
        resolvedCount++;
    }

    /** 折返（时间轴读数回退）重置：once / 边沿 / 驻留 / until 等待态归零；cooldown 按播放时钟保持。 */
    private void resetForLap() {
        for (int i = 0; i < slotCount; i++) {
            onceFired[i] = false;
            untilDone[i] = false;
            untilArmed[i] = false;
            edgeTrue[i] = false;
            dwellAccum[i] = 0f;
            untilWaitStart[i] = 0.0;
        }
    }

    /** 释放引擎持有的挂起（幂等；未持有 / 未挂起时无操作）。 */
    private void releaseSuspend(ScriptPlayer player) {
        if (!suspendRequested) return;
        suspendRequested = false;
        if (player != null) player.endSuspend();
    }

    private void clearSlots() {
        slotCount = 0;
        slotClips = new Clip[0];
        slotRules = new ClipRule[0];
        slotWindowStart = new float[0];
        slotWindowEnd = new float[0];
        onceFired = new boolean[0];
        untilDone = new boolean[0];
        untilArmed = new boolean[0];
        edgeTrue = new boolean[0];
        dwellAccum = new float[0];
        cooldownUntil = new float[0];
        untilWaitStart = new double[0];
        resolvedSlots = new int[0];
        resolvedTimedOut = new boolean[0];
        resolvedCount = 0;
        wantSuspend = false;
        lastTimeline = Float.NaN;
        lastElapsed = Float.NaN;
        lastClock = Double.NaN;
    }

    private void allocateSlots(int count) {
        slotClips = new Clip[count];
        slotRules = new ClipRule[count];
        slotWindowStart = new float[count];
        slotWindowEnd = new float[count];
        onceFired = new boolean[count];
        untilDone = new boolean[count];
        untilArmed = new boolean[count];
        edgeTrue = new boolean[count];
        dwellAccum = new float[count];
        cooldownUntil = new float[count];
        untilWaitStart = new double[count];
        resolvedSlots = new int[count];
        resolvedTimedOut = new boolean[count];
    }

    /** 片段活跃窗末端：与 {@code ClipModifierGate} 同口径（morph 前一段窗末延长半个过渡窗）。 */
    private static float windowEnd(Clip clip, Clip next) {
        float end = clip.getWindowEnd();
        if (next == null || clip.isEffectivelyInfinite() || !clip.isMorph()) return end;
        TransitionSpec spec = clip.getTransitionSpec();
        return spec == null ? end : spec.windowEnd(end);
    }
}
