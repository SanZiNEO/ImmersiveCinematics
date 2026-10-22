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
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 判定规则引擎：每帧逐规则判定 clip 级 {@code rules}（判定端 = 客户端逐实例）。
 *
 * 槽位在 {@link #sync} 时按脚本一次性建成（片段活跃窗 + 规则 + 基本类型状态数组，帧内零分配）；
 * 活跃窗口径与 {@code ClipModifierGate} 一致：{@code [startTime, windowEnd)}，morph 前一段窗末延长半个过渡窗。
 * 帧内：{@code if} 规则按 enter 边沿 / dwell 驻留 + once / cooldown 门触发；{@code until} 规则条件假则挂起本实例
 * （保持内容位置，见 {@code ScriptPlayer#beginSuspend}）、真或 timeout 先到则解除并触发（动作执行归 {@link RuleActions}）。
 *
 * 时钟口径：cooldown 与 dwell（if）走播放时钟（挂起 / 游戏暂停一起冻结）；timeout 与 dwell（until）走
 * {@link #setClockSource 注入的真实时钟}（挂起期间照走）。折返（timelineTime 回退 = 宏观循环圈边界或向后 seek）
 * 重置 once / 边沿 / 驻留 / until 等待态（cooldown 按播放时钟跨圈保持）。
 *
 * 动作侧：{@code camera_set} / {@code overlay_set} / {@code adjust_set} 的实例级工件在触发帧
 * {@link #attachArtifact 接入}、片段活跃窗末 / 引擎重建 / 停播时回收（同类再次触发 = 覆盖）；
 * {@code pause} / {@code resume} 动作走 {@link #suspendByAction} / {@link #resumeByAction}
 * （与 until 的挂起同一状态、分开记账：pause 的挂起由 {@code resume} / 触发片段里再无释放者时的
 * 挂起出口（见 {@link #evaluate}）/ 引擎重建 / 停播解除）。
 *
 * 分配契约：{@link #evaluate} 不新建集合、不装箱（条件求值走 {@link ClientConditions}，其访问器同样零分配）。
 * 线程契约：仅客户端主线程。
 */
public final class RuleEngine {

    /** 限频日志窗口（毫秒）：同一失败点每 5 秒最多一条。 */
    private static final long LOG_INTERVAL_MS = 5000L;

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

    // ===== 动作工件（camera_set / overlay_set / adjust_set；触发帧建出，窗末 / 重建 / 停播回收） =====

    /** 已接入的实例级工件（同类至多一件；槽位数组 + 窗末平行数组，帧内零分配）。 */
    private RuleArtifact[] artifactSlots = new RuleArtifact[0];

    /** 各工件的回收时刻（时间轴读数秒；到达即回收）。 */
    private float[] artifactWindowEnd = new float[0];

    private int artifactCount = 0;

    /** 本批动作的注册窗末（= 所属槽位的片段活跃窗末；{@link #attachArtifact} 记进工件）。 */
    private float actionWindowEnd = Float.MAX_VALUE;

    /** 动作 {@code pause} 持有的挂起（与 until 的挂起分开记账：until 按帧判定，pause 由 resume / 回收解除）。 */
    private boolean actionSuspendHeld = false;

    /** 触发 {@code pause} 动作的片段（挂起出口判定的作用域：只认触发它的片段里的释放者；{@code null} = 无）。 */
    private Clip actionSuspendClip = null;

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

    // ===== 动作侧入口（RuleActions 调用；动作只作用触发它的本实例） =====

    /**
     * 接入一件规则动作工件（{@code camera_set} / {@code overlay_set} / {@code adjust_set} 触发时）。
     * 窗末 = 本批动作所属片段的活跃窗末（与规则槽位同一口径），{@link #evaluate} 到期回收；
     * 同类工件先回收旧的（一实例同类至多一件 = 触发即覆盖）。
     */
    public void attachArtifact(RuleArtifact artifact) {
        if (artifact == null) return;
        for (int i = artifactCount - 1; i >= 0; i--) {
            if (artifactSlots[i].kind() == artifact.kind()) {
                artifactSlots[i].release();
                removeArtifact(i);
            }
        }
        if (artifactCount >= artifactSlots.length) growArtifacts();
        artifactSlots[artifactCount] = artifact;
        artifactWindowEnd[artifactCount] = actionWindowEnd;
        artifactCount++;
        artifact.attach();
    }

    /**
     * 规则动作 {@code pause}：保持本实例内容位置（引擎持有；{@code resume} 动作 / 片段里已无释放者
     * （见 {@link #evaluate}）/ 引擎重建 / 停播时解除——防卡死）。与 until 的挂起同一状态、分开记账。
     *
     * @param clip 触发本动作的片段（挂起出口判定只认它里面的释放者）
     */
    public void suspendByAction(Clip clip) {
        ScriptPlayer player = player();
        if (player == null) return;
        player.beginSuspend();
        actionSuspendHeld = true;
        actionSuspendClip = clip;
    }

    /** 规则动作 {@code resume}：解除挂起（幂等；until 等待与 pause 的挂起一并解除）。 */
    public void resumeByAction() {
        releaseSuspend(player());
    }

    /**
     * 按脚本重建规则槽位（{@code start} / {@code replaceScript} 时调用）：旧等待一并作废（脚本已换）。
     *
     * @param script 新脚本（{@code null} = 只清槽）
     * @param player 所属播放器（释放引擎挂起用；可为 {@code null}）
     */
    public void sync(CinematicScript script, ScriptPlayer player) {
        releaseSuspend(player);
        releaseArtifacts();
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

    /** 清空规则槽位并释放引擎持有的挂起与动作工件（{@code stop} 时调用：不留冻结的实例 / 不留残件）。 */
    public void reset(ScriptPlayer player) {
        releaseSuspend(player);
        releaseArtifacts();
        clearSlots();
    }

    /**
     * 每帧判定一次（调用点 = 帧循环里本实例轨道分发之后）。
     *
     * @param player 本实例播放器（时间轴读数 / 挂起开关 / seek 都作用于它）
     */
    public void evaluate(ScriptPlayer player) {
        if (player == null) return;
        float timeline = player.timelineTime();
        // 动作工件到期回收：片段活跃窗末已过（先于本帧链求值——本帧渲染不再看到过期工件）
        if (artifactCount > 0) expireArtifacts(timeline);
        if (slotCount == 0) return;
        context.beginFrame();
        if (!context.available()) return;

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

        if (resolvedCount > 0) releaseUntilSuspend(player);
        for (int r = 0; r < resolvedCount; r++) {
            int slot = resolvedSlots[r];
            ClipRule rule = slotRules[slot];
            List<JsonObject> actions = resolvedTimedOut[r] ? rule.getOnTimeout() : rule.getActions();
            // 本批动作的注册窗末（camera_set / overlay_set / adjust_set 的工件回收时刻）
            actionWindowEnd = slotWindowEnd[slot];
            for (int a = 0; a < actions.size(); a++) {
                RuleActions.execute(owner, slotClips[slot], rule, actions.get(a));
            }
        }
        if (wantSuspend) {
            player.beginSuspend();
            suspendRequested = true;
        } else if (suspendRequested) {
            releaseUntilSuspend(player);
        }
        // 挂起出口（防卡死）：pause 的挂起在触发它的片段里已无释放者 → 自动解除 + 限频告警
        if (actionSuspendHeld && !pauseReleasable(timeline, elapsed)) {
            ErrorLog.logRateLimited("Rules", "pause:no-releaser", LOG_INTERVAL_MS,
                    "规则动作 pause 已无释放者（片段 " + clipLabel(actionSuspendClip)
                            + " 里既无未完成的 until、也无仍可触发的 resume）——自动解除挂起："
                            + "暂停保持内容位置 ⇒ 后续片段不激活、时间条件不走，跨片段 / 时间门的 resume 不可达；"
                            + "请在触发 pause 的同一片段里声明 resume 或 until");
            releaseActionSuspend(player);
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

    /** 记一次触发（动作延迟到挂起释放之后按声明序执行）。 */
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

    /** 解除 until 的挂起请求（pause 动作的挂起仍持有时不解冻；幂等）。 */
    private void releaseUntilSuspend(ScriptPlayer player) {
        suspendRequested = false;
        if (!actionSuspendHeld && player != null) player.endSuspend();
    }

    /** 解除全部挂起（until + pause；{@code resume} 动作 / 引擎重建 / 停播；幂等）。 */
    private void releaseSuspend(ScriptPlayer player) {
        if (!suspendRequested && !actionSuspendHeld) return;
        suspendRequested = false;
        actionSuspendHeld = false;
        actionSuspendClip = null;
        if (player != null) player.endSuspend();
    }

    /** 只解除 pause 动作持有的挂起（until 的挂起请求不受影响；幂等）——挂起出口用。 */
    private void releaseActionSuspend(ScriptPlayer player) {
        if (!actionSuspendHeld) return;
        actionSuspendHeld = false;
        actionSuspendClip = null;
        if (!suspendRequested && player != null) player.endSuspend();
    }

    /**
     * {@code pause} 的挂起是否仍有释放者：触发它的片段里（且仍在活跃窗内——出窗的规则本帧不再求值）
     * 存在未完成的 {@code until}（其解除帧一并解冻），或存在仍可触发的 {@code resume} 动作
     * （{@code once} 已用完 / 冷却未过的不算——挂起中播放时钟不走，冷却永不失效）。挂起出口读它，见 {@link #evaluate}。
     */
    private boolean pauseReleasable(float timeline, float elapsed) {
        Clip clip = actionSuspendClip;
        if (clip == null) return true;
        for (int i = 0; i < slotCount; i++) {
            if (slotClips[i] != clip) continue;
            if (timeline < slotWindowStart[i] || timeline >= slotWindowEnd[i]) continue;
            ClipRule rule = slotRules[i];
            if (ClipRule.KIND_UNTIL.equals(rule.getKind())) {
                if (!untilDone[i]) return true;
            } else if ((!rule.isOnce() || !onceFired[i]) && elapsed >= cooldownUntil[i] && hasResume(rule)) {
                return true;
            }
        }
        return false;
    }

    /** 规则的动作声明（{@code do} / {@code on_timeout}）里是否含 {@code resume}。 */
    private static boolean hasResume(ClipRule rule) {
        return containsResume(rule.getActions()) || containsResume(rule.getOnTimeout());
    }

    /** 动作数组里是否含 {@code resume} 动作（只读 JSON type 字段，零分配）。 */
    private static boolean containsResume(List<JsonObject> actions) {
        for (int i = 0; i < actions.size(); i++) {
            JsonObject action = actions.get(i);
            if (action != null && "resume".equals(stringField(action, "type"))) return true;
        }
        return false;
    }

    /** JSON 字符串字段（缺失 / 非字符串 → {@code null}）。 */
    private static String stringField(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    /** 片段标签（日志文案用；{@code null} → {@code "<未知片段>"}）。 */
    private static String clipLabel(Clip clip) {
        return clip == null ? "<未知片段>" : clip.getTrackType() + "@" + clip.getStartTime() + "s";
    }

    /** 本实例的播放器（未接线时为 {@code null}）。 */
    private ScriptPlayer player() {
        return owner != null ? owner.player() : null;
    }

    // ===== 动作工件（帧内零分配：槽位数组 + 窗末平行数组） =====

    /** 片段窗末到期回收（{@code timeline} 已达回收时刻即 {@link RuleArtifact#release()}）。 */
    private void expireArtifacts(float timeline) {
        for (int i = artifactCount - 1; i >= 0; i--) {
            if (timeline >= artifactWindowEnd[i]) {
                artifactSlots[i].release();
                removeArtifact(i);
            }
        }
    }

    /** 回收全部工件（引擎重建 / 停播）。 */
    private void releaseArtifacts() {
        for (int i = 0; i < artifactCount; i++) {
            artifactSlots[i].release();
            artifactSlots[i] = null;
        }
        artifactCount = 0;
    }

    /** 摘除一个槽位（尾元素填坑，保序无关：同类覆盖与到期回收都不依赖顺序）。 */
    private void removeArtifact(int index) {
        int last = artifactCount - 1;
        artifactSlots[index] = artifactSlots[last];
        artifactWindowEnd[index] = artifactWindowEnd[last];
        artifactSlots[last] = null;
        artifactCount = last;
    }

    /** 扩容工件槽位（只在触发帧发生）。 */
    private void growArtifacts() {
        int size = Math.max(2, artifactSlots.length * 2);
        RuleArtifact[] slots = new RuleArtifact[size];
        float[] ends = new float[size];
        System.arraycopy(artifactSlots, 0, slots, 0, artifactCount);
        System.arraycopy(artifactWindowEnd, 0, ends, 0, artifactCount);
        artifactSlots = slots;
        artifactWindowEnd = ends;
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
        actionSuspendClip = null;
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
