package com.immersivecinematics.immersive_cinematics.api;

import com.immersivecinematics.immersive_cinematics.trigger.server.fact.Fact;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactLedger;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactResult;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactType;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.PlayerFacts;

import java.util.List;
import java.util.UUID;

/**
 * 服务端事实只读门面（对外稳定面）：第三方查询「这个玩家触发过什么 / 播放结果如何 / 某时间窗内发生过什么」。
 *
 * <p><b>数据源</b>：全部转发到既有的服务端事实账本 {@link FactLedger#INSTANCE}（按玩家分片的
 * {@link PlayerFacts}）。本类<b>不重建第二套记录</b>、不写事实、不缓存、不持有状态——每次调用都是一次
 * 对账本的直接查询，看到的就是账本当下的内容。
 *
 * <p><b>线程契约</b>：仅服务端主线程。账本本身是主线程独占的（{@link PlayerFacts} 无同步），
 * 其它线程调用方须自行 marshal 到服务端主线程。
 *
 * <p><b>只读</b>：本类只暴露查询。事实的写入面（追加 / 跳过票 / 落盘）
 * 属于内部实现，<b>不对外</b>；{@code api} 包不提供任何写入、清理或快照 API。
 *
 * <p><b>查询口径</b>：
 * <ul>
 *   <li>「是否 / 次数 / 最近一次」走 {@link PlayerFacts} 的常驻索引，是<b>累计口径</b>：
 *       保留集淘汰旧事实后结论仍然成立。</li>
 *   <li>时间窗查询与 {@link #lastPlaybackWithResult} 扫描保留集，<b>受保留深度约束</b>：
 *       比保留集最旧事实更早的记录查不到（上限按 {@link FactType#maxRetained()} 声明）。</li>
 * </ul>
 *
 * <p><b>空玩家安全</b>：{@code player == null} 或该玩家没有账本条目时，返回
 * {@code false} / {@code 0} / {@code null} / {@link List#of()}，<b>不抛异常</b>。
 *
 * <p><b>稳定性</b>：本包（{@code api}）内的类型与方法<b>只增不删</b>，语义不变；破坏性变更只允许发生在
 * {@code spi} 包与内部实现。
 */
public final class ServerFactsApi {

    private ServerFactsApi() {
    }

    // ===== 播放事实 =====

    /** 该玩家是否播放过该脚本（累计口径）；空玩家 / 未知玩家为 {@code false}。 */
    public static boolean hasPlayed(UUID player, String scriptId) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts != null && facts.exists(FactType.PLAYBACK_STARTED, scriptId, "");
    }

    /** 该玩家播放该脚本的累计次数（累计口径）；空玩家 / 未知玩家为 {@code 0}。 */
    public static int countPlaybacks(UUID player, String scriptId) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts == null ? 0 : facts.count(FactType.PLAYBACK_STARTED, scriptId, "");
    }

    /** 该玩家最近一次开始播放该脚本的事实（累计口径）；从未播放 / 空玩家为 {@code null}。 */
    public static Fact lastPlayback(UUID player, String scriptId) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts == null ? null : facts.last(FactType.PLAYBACK_STARTED, scriptId, "");
    }

    /**
     * 该玩家最近一次以 {@code result} 结束播放该脚本的事实（{@link FactResult#FINISHED} 自然结束、
     * {@link FactResult#SKIPPED} 跳过、{@link FactResult#INTERRUPTED} 打断…）。
     *
     * <p>结束事实的 {@code object} 恒为空串；本方法扫描保留集，受保留深度约束。
     * {@code result == null} 由 {@link PlayerFacts} 视为 {@link FactResult#NONE}。
     */
    public static Fact lastPlaybackWithResult(UUID player, String scriptId, FactResult result) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts == null ? null : facts.last(FactType.PLAYBACK_FINISHED, scriptId, "", result);
    }

    // ===== 触发器事实 =====

    /** 该玩家是否触发过该脚本的该触发器（累计口径）；空玩家 / 未知玩家为 {@code false}。 */
    public static boolean hasTriggered(UUID player, String scriptId, String triggerId) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts != null && facts.exists(FactType.TRIGGER_FIRED, scriptId, triggerId);
    }

    /** 该玩家触发该脚本该触发器的累计次数（累计口径）；空玩家 / 未知玩家为 {@code 0}。 */
    public static int countTriggerHits(UUID player, String scriptId, String triggerId) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts == null ? 0 : facts.count(FactType.TRIGGER_FIRED, scriptId, triggerId);
    }

    /** 该玩家最近一次触发该脚本该触发器的事实（累计口径）；从未触发 / 空玩家为 {@code null}。 */
    public static Fact lastTriggerHit(UUID player, String scriptId, String triggerId) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts == null ? null : facts.last(FactType.TRIGGER_FIRED, scriptId, triggerId);
    }

    // ===== 时间窗（服务端 tick，闭区间语义以 PlayerFacts 为准） =====

    /**
     * 闭区间 {@code [fromTick, toTick]} 内该玩家是否开始播放过该脚本；受保留深度约束。
     * {@code fromTick > toTick} 或空玩家为 {@code false}。
     */
    public static boolean playedWithin(UUID player, String scriptId, int fromTick, int toTick) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts != null && facts.anyWithin(FactType.PLAYBACK_STARTED, scriptId, "", fromTick, toTick);
    }

    /**
     * 闭区间 {@code [fromTick, toTick]} 内该玩家最近一次开始播放该脚本的事实；受保留深度约束。
     * 无命中 / 空玩家为 {@code null}。
     */
    public static Fact lastPlaybackWithin(UUID player, String scriptId, int fromTick, int toTick) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts == null
                ? null
                : facts.lastWithin(FactType.PLAYBACK_STARTED, scriptId, fromTick, toTick, fact -> true);
    }

    /**
     * 闭区间 {@code [fromTick, toTick]} 内该玩家是否触发过该脚本的该触发器；受保留深度约束。
     * {@code fromTick > toTick} 或空玩家为 {@code false}。
     */
    public static boolean triggeredWithin(UUID player, String scriptId, String triggerId, int fromTick, int toTick) {
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts != null
                && facts.anyWithin(FactType.TRIGGER_FIRED, scriptId, triggerId, fromTick, toTick);
    }

    // ===== 保留集（调试 / 展示用；数量受保留深度约束） =====

    /**
     * 该类型当前保留集的只读快照（append 顺序，最旧在前）。
     *
     * <p>返回的是 {@link PlayerFacts#retained(FactType)} 给出的不可变副本（{@link List#copyOf}），
     * 调用方无法通过它修改账本。条数受 {@link FactType#maxRetained()} 约束，不是累计总数。
     * {@code type == null} 或空玩家为 {@link List#of()}。
     */
    public static List<Fact> retainedFacts(UUID player, FactType type) {
        if (type == null) {
            return List.of();
        }
        PlayerFacts facts = FactLedger.INSTANCE.ofOrNull(player);
        return facts == null ? List.of() : facts.retained(type);
    }
}
