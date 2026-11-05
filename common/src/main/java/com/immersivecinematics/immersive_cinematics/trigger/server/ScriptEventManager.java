package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import com.immersivecinematics.immersive_cinematics.script.event.EventClipRuntime;
import com.immersivecinematics.immersive_cinematics.script.event.EventRecord;
import com.immersivecinematics.immersive_cinematics.script.event.EventSeekPlan;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CSkipVoteUpdatePacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.FactLedger;
import com.immersivecinematics.immersive_cinematics.util.RuleCommandSource;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ScriptEventManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ScriptEventManager INSTANCE = new ScriptEventManager();

    /**
     * 播放账本：scriptId → 实例 id → 该实例的播放状态。
     * 观看者 / 跳过投票 / 事件时间线全部按实例维护：同一脚本的不同播放请求各记一条、互不干扰；
     * 空实例 id 是“无实例来源”的退化条目，该脚本的全部观看者共用一条。
     */
    private final Map<String, Map<String, ScriptPlayback>> scriptPlaybacks = new HashMap<>();

    /**
     * 播放授权：scriptId → instanceId → 目标玩家集合。由服务端发送播放包的路径创建
     * （{@code CinematicCommand#playScript} / {@code StartPlaybackAction}），客户端回执不能自证。
     */
    private final Map<String, Map<String, PlaybackGrant>> playbackGrants = new HashMap<>();

    /**
     * 规则命令动作表缓存：scriptId → (编译时的脚本对象, 表)。脚本对象身份变化（重新加载 / 替换）
     * 即重建；{@link #revokeGrants} 同时让旧表失效。
     */
    private final Map<String, CachedCommandTable> commandTables = new HashMap<>();

    /** 最近一次服务端 tick（授权创建时间戳用；{@link #onServerTick} 刷新）。 */
    private int serverTick;

    private ScriptEventManager() {}

    // ===== 播放授权（服务端签发，客户端回执只能消费） =====

    /**
     * 为一次播放请求签发观看者授权：{@code targets} 是本次实际收到播放包的玩家。
     * 同一 {@code (scriptId, instanceId)} 重复签发 = 覆盖目标集合（ACK 重发不重复建）。
     */
    public void createGrant(String scriptId, String instanceId, Collection<UUID> targets) {
        String sid = scriptId == null ? "" : scriptId;
        String iid = instanceId == null ? "" : instanceId;
        if (sid.isEmpty() || targets == null || targets.isEmpty()) return;
        Set<UUID> ids = new HashSet<>();
        for (UUID target : targets) {
            if (target != null) ids.add(target);
        }
        if (ids.isEmpty()) return;
        playbackGrants.computeIfAbsent(sid, k -> new HashMap<>())
                .put(iid, new PlaybackGrant(sid, iid, ids, serverTick));
        LOGGER.debug("播放授权已签发: script={} instance={} targets={}", sid, iid, ids.size());
    }

    /** 该玩家是否在 {@code (scriptId, instanceId)} 的播放授权内（授权不存在 → {@code false}）。 */
    public boolean isGranted(String scriptId, String instanceId, UUID player) {
        if (player == null) return false;
        PlaybackGrant grant = grant(scriptId, instanceId);
        return grant != null && grant.targets().contains(player);
    }

    /** 撤销某脚本的全部播放授权与动作表（脚本替换 / 全量重载）。 */
    public void revokeGrants(String scriptId) {
        if (scriptId == null) return;
        playbackGrants.remove(scriptId);
        commandTables.remove(scriptId);
    }

    /** 撤销全部播放授权与动作表（服务端停止 / 全部脚本重载）。 */
    public void revokeAllGrants() {
        playbackGrants.clear();
        commandTables.clear();
    }

    /**
     * 按即将下发的脚本版本编译动作表：{@code /icinematics play} 直接用文件解析，
     * 与服务端脚本表可能不是同一对象，因此以“下发的那一份”为准。
     */
    public void primeCommandTable(CinematicScript script) {
        if (script == null) return;
        String scriptId = script.getId();
        if (scriptId == null || scriptId.isEmpty()) return;
        commandTables.put(scriptId, new CachedCommandTable(script, RuleCommandTable.compile(script)));
    }

    private PlaybackGrant grant(String scriptId, String instanceId) {
        if (scriptId == null) return null;
        Map<String, PlaybackGrant> byInstance = playbackGrants.get(scriptId);
        return byInstance == null ? null : byInstance.get(instanceId == null ? "" : instanceId);
    }

    /** 撤销单个实例的授权（实例结束 / 全部观看者离开）。 */
    private void revokeGrant(String scriptId, String instanceId) {
        Map<String, PlaybackGrant> byInstance = playbackGrants.get(scriptId);
        if (byInstance == null) return;
        byInstance.remove(instanceId == null ? "" : instanceId);
        if (byInstance.isEmpty()) playbackGrants.remove(scriptId);
    }

    /**
     * 取 {@code scriptId} 的动作表：缓存与当前脚本对象不一致（脚本被替换）→ 重建。
     * 无缓存且服务端脚本表里也没有该脚本 → {@code null}（请求按未知引用拒绝）。
     */
    private RuleCommandTable commandTable(String scriptId) {
        if (scriptId == null || scriptId.isEmpty()) return null;
        CinematicScript current = ScriptManager.INSTANCE.getScript(scriptId);
        CachedCommandTable cached = commandTables.get(scriptId);
        if (cached != null && (current == null || cached.script() == current)) {
            return cached.table();
        }
        if (current == null) return null;
        RuleCommandTable table = RuleCommandTable.compile(current);
        commandTables.put(scriptId, new CachedCommandTable(current, table));
        return table;
    }

    private record CachedCommandTable(CinematicScript script, RuleCommandTable table) {}

    // ===== 观看者账本 =====

    public void addViewer(ServerPlayer player, String scriptId, String instanceId) {
        String iid = instanceId == null ? "" : instanceId;
        // 观看者身份只能来自服务端签发的播放授权：客户端播放回执不能把自己登记进任意脚本
        if (!isGranted(scriptId, iid, player.getUUID())) {
            LOGGER.debug("观看者被拒（无播放授权）: player={} script={} instance={}",
                    player.getName().getString(), scriptId, iid);
            return;
        }
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        ScriptPlayback pb = byInstance != null ? byInstance.get(iid) : null;
        if (pb == null) {
            CinematicScript script = ScriptManager.INSTANCE.getScript(scriptId);
            if (script == null) return;

            List<Clip> clips = extractEventClips(script);
            Integer scriptRatio = script.getMeta().getSkipVoteRatio();
            int voteRatio = scriptRatio != null ? scriptRatio : Config.skipVoteRatio;
            pb = new ScriptPlayback(scriptId, iid, clips, player.server.getTickCount(), voteRatio, player.server);
            scriptPlaybacks.computeIfAbsent(scriptId, k -> new HashMap<>()).put(iid, pb);
        }
        // 相对玩家激活位置的 CAMERA 关键帧以本位置为基准（与客户端脚本起点的 originPos 同口径）
        pb.viewerOrigins.putIfAbsent(player.getUUID(), new ViewerOrigin(player.position(), player.getYRot()));
        pb.viewers.add(player.getUUID());
    }

    public void startPlayback(ServerPlayer player, String scriptId, String instanceId) {
        addViewer(player, scriptId, instanceId);
    }

    /**
     * 账本精确解析：按 {@code (scriptId, instanceId)} 直取实例。
     * 结束 / 暂停信号随包携带实例 id（{@code C2SScriptFinishedPacket} / {@code C2SScriptPausePacket}），
     * 与开始信号同一 id；查不到即返回 {@code null}——按“观看者成员”回退猜测会误配到别的实例。
     */
    private ScriptPlayback playback(String scriptId, String instanceId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        return byInstance == null ? null : byInstance.get(instanceId == null ? "" : instanceId);
    }

    /** 摘除单个实例；脚本下不再有实例时移除脚本条目。授权与实例投票随实例结束一并清理。 */
    private void removePlayback(ScriptPlayback pb) {
        revokeGrant(pb.scriptId, pb.instanceId);
        FactLedger.INSTANCE.clearInstance(pb.scriptId, pb.instanceId);
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(pb.scriptId);
        if (byInstance == null) return;
        byInstance.remove(pb.instanceId);
        if (byInstance.isEmpty()) scriptPlaybacks.remove(pb.scriptId);
    }

    public void onPlayerFinished(ServerPlayer player, String scriptId, String instanceId, CompletionReason reason) {
        ScriptPlayback pb = playback(scriptId, instanceId);
        if (pb == null) return;

        UUID uuid = player.getUUID();
        pb.viewers.remove(uuid);

        if (reason == CompletionReason.SKIPPED) {
            // 兼容其它结束路径（客户端仍可能带 SKIPPED 回执）：票入事实账本并标记为投票者，重复幂等。
            // 这里只补记账、不做达标判定——强停统一由 onSkipVote 负责，避免两条路径各判一次。
            FactLedger.INSTANCE.recordVote(uuid, pb.scriptId, pb.instanceId, player.server.getTickCount());
            pb.skipVoters.add(uuid);
        }

        LOGGER.debug("Player {} finished script '{}' (instance '{}', viewers left: {})",
                player.getName().getString(), scriptId, pb.instanceId, pb.viewers.size());

        if (pb.viewers.isEmpty()) {
            removePlayback(pb);
            LOGGER.info("Script '{}' fully complete — all viewers finished", scriptId);
            return;
        }

        int votes = FactLedger.INSTANCE.voteCount(pb.scriptId, pb.instanceId);
        // 刚离开的这位已不在 viewers 里，但仍属于这一批：分母补 1（仅日志口径，判定在 onSkipVote）
        int needed = ScriptEventManager.neededVotes(pb.viewers.size() + 1, pb.skipVoteRatio);
        boolean broadcast = broadcastSkipVote(pb, votes);
        LOGGER.debug("Skip vote tally for script '{}' instance '{}': {} / {} (needed {}), broadcast={}",
                scriptId, pb.instanceId, votes, pb.viewers.size() + 1, needed, broadcast);
    }

    /**
     * 客户端跳过投票：只计票，投票者本人继续播放；票数达到比例后由这里统一停掉这一批全部在场者。
     *
     * <p>投票者仍留在 {@code viewers} 内（新语义：投票不退出播放），因此分母就是 {@code viewers.size()}。
     * 非本实例观众的投票直接丢弃：批次外的玩家不能决定别人的播放。
     */
    public void onSkipVote(ServerPlayer player, String scriptId, String instanceId) {
        ScriptPlayback pb = playback(scriptId, instanceId);
        if (pb == null) return;

        UUID uuid = player.getUUID();
        if (!pb.viewers.contains(uuid)) return;
        // 已投过票：重复投票（丢包重发 / 连点）直接吞掉，票数与 youVoted 都不会变，不必再广播一轮
        if (pb.skipVoters.contains(uuid)) return;

        // 账本按 (玩家, 脚本, 实例) 幂等：重复投票不改变票数
        FactLedger.INSTANCE.recordVote(uuid, pb.scriptId, pb.instanceId, player.server.getTickCount());
        pb.skipVoters.add(uuid);

        int votes = FactLedger.INSTANCE.voteCount(pb.scriptId, pb.instanceId);
        int needed = pb.neededVotes();

        if (votes >= needed) {
            // 达标即终局：先停这一批所有在场者，再清账本；发一包票数只会被紧随其后的停止覆盖。
            LOGGER.info("Script '{}' force-stopped by skip vote ({} / {} needed)", scriptId, votes, needed);
            for (UUID vid : pb.viewers) {
                ServerPlayer p = pb.server.getPlayerList().getPlayer(vid);
                if (p != null) S2CStopScriptPacket.send(p, scriptId, pb.instanceId, "");
            }
            removePlayback(pb);
            return;
        }

        boolean broadcast = broadcastSkipVote(pb, votes);
        LOGGER.debug("Skip vote tally for script '{}' instance '{}': {} / {} (needed {}), broadcast={}",
                scriptId, pb.instanceId, votes, pb.viewers.size(), needed, broadcast);
    }

    /**
     * 跳过投票所需票数——强停判定与票数广播共用的唯一阈值实现。
     *
     * <p>分母是这一批的在场人数：新语义下投票不退出播放，投票者仍留在 {@code viewers} 里，
     * 因此分母不需要任何补正，也不会随投票缩水。
     *
     * <p>{@code max(1, …)} 对齐原版 {@code SleepStatus.sleepersNeeded}：比例再低也得真有人投过票。
     * 没有这个下限，空场或极低比例下 {@code ceil} 会算出 0，就成了「零票即通过」。
     */
    static int neededVotes(int totalViewers, int ratio) {
        return Math.max(1, Mth.ceil(totalViewers * ratio / 100f));
    }

    /**
     * 把当前票数推给仍在场的每个观众。
     *
     * <p>不再按 {@code (votes,total)} 节流：包里带 {@code youVoted}，投票者与未投票者收到的内容不同，
     * 靠「数值没变」跳过发包会让部分观众的 {@code youVoted} 永远停在旧值（已投票者仍在响应长按）。
     * 投票是低频事件（每人每实例最多一票），为此维护一张按观众的上次状态表不划算。
     *
     * @return 是否至少发给了一个在线观众（调用方用于日志）
     */
    private boolean broadcastSkipVote(ScriptPlayback pb, int votes) {
        if (pb.viewers.isEmpty()) return false;
        int total = pb.viewers.size();
        boolean sent = false;
        for (UUID vid : pb.viewers) {
            ServerPlayer vp = pb.server.getPlayerList().getPlayer(vid);
            if (vp != null) {
                S2CSkipVoteUpdatePacket.send(vp, pb.scriptId, pb.instanceId, votes, total,
                        pb.skipVoters.contains(vid));
                sent = true;
            }
        }
        return sent;
    }

    public void onScriptFinished(ServerPlayer player, String scriptId, String instanceId, CompletionReason reason) {
        onPlayerFinished(player, scriptId, instanceId, reason);
    }

    public boolean isScriptActive(String scriptId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        return byInstance != null && !byInstance.isEmpty();
    }

    /**
     * 该玩家是否正在播放指定脚本。
     * 只查 {@code scriptId} 名下的实例，任一实例把该玩家记为观看者即为真：表达的是“同脚本同玩家单实例”的占用，
     * 不因“有别的脚本在播”而返回真——{@code TriggerEngine.shouldSkip} 据此实现跨脚本不阻塞。
     */
    public boolean isPlayerPlayingScript(UUID playerUuid, String scriptId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        if (byInstance == null) return false;
        for (ScriptPlayback pb : byInstance.values()) {
            if (pb.viewers.contains(playerUuid)) return true;
        }
        return false;
    }

    public boolean isFullyComplete(String scriptId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        if (byInstance == null) return true;
        for (ScriptPlayback pb : byInstance.values()) {
            if (!pb.viewers.isEmpty()) return false;
        }
        return true;
    }

    public int getRemainingViewers(String scriptId) {
        Map<String, ScriptPlayback> byInstance = scriptPlaybacks.get(scriptId);
        if (byInstance == null) return 0;
        int total = 0;
        for (ScriptPlayback pb : byInstance.values()) {
            total += pb.viewers.size();
        }
        return total;
    }

    public void onServerTick(MinecraftServer server) {
        // ACK 超时重发检查：必须先于下面的空检查执行，否则无播放账本时永不重发
        com.immersivecinematics.immersive_cinematics.trigger.network.AckTracker.tick();
        serverTick = server.getTickCount();
        if (scriptPlaybacks.isEmpty()) return;
        int currentTick = serverTick;

        for (Map<String, ScriptPlayback> byInstance : scriptPlaybacks.values()) {
            byInstance.entrySet().removeIf(entry -> {
                ScriptPlayback pb = entry.getValue();
                if (pb.viewers.isEmpty()) {
                    // 全部观看者已离开：实例结束，授权与实例投票一并撤销（后续规则命令按无授权拒绝）
                    revokeGrant(pb.scriptId, pb.instanceId);
                    FactLedger.INSTANCE.clearInstance(pb.scriptId, pb.instanceId);
                    return true;
                }

                PlaybackGrant grant = grant(pb.scriptId, pb.instanceId);
                pb.viewers.removeIf(uuid -> {
                    if (server.getPlayerList().getPlayer(uuid) != null) return false;
                    // 玩家退出：观看者 / 授权目标 / 限频状态一并清理，避免重连后读到上一轮授权
                    if (grant != null) grant.targets().remove(uuid);
                    RuleCommandRateLimiter.INSTANCE.forgetPlayer(uuid);
                    return true;
                });
                pb.viewerOrigins.keySet().retainAll(pb.viewers);

                // 暂停态：不处理 keyframe（恢复帧按上报内容位置重定基准）
                if (pb.paused) {
                    return false;
                }

                // 时间线读数 = 上报的内容位置 + 基准之后经过的服务端 tick
                // （未收到上报 = 基准不动，回落 tick 推算——不卡事件）
                float elapsed = pb.timelineAnchorSeconds + (currentTick - pb.timelineAnchorTick) / 20f;

                // 事件边沿：只推进 (上次扫描时间, 本次 elapsed] 区间；首次从 -inf 开始
                float previous = pb.scannedThroughSeconds;
                if (!(elapsed > previous)) {
                    return false;
                }

                for (int clipIndex = 0; clipIndex < pb.eventClips.size(); clipIndex++) {
                    Clip clip = pb.eventClips.get(clipIndex);
                    float clipStart = clip.getStartTime();
                    float clipDuration = clip.getDuration();
                    float clipEnd = clipDuration < 0 ? Float.POSITIVE_INFINITY : clipStart + clipDuration;

                    if (elapsed < clipStart || previous >= clipEnd) {
                        continue;
                    }

                    double to = Math.min(elapsed, clipEnd);
                    if (!(to >= clipStart)) {
                        continue;
                    }

                    EventClipRuntime runtime = pb.eventRuntimes.get(clipIndex);
                    int start = runtime.cursor();
                    int end = runtime.advance(to - clipStart);
                    for (int i = start; i < end; i++) {
                        fireEvent(pb, server, runtime.record(i), currentTick);
                    }
                }
                pb.scannedThroughSeconds = elapsed;

                return false;
            });
        }
        scriptPlaybacks.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * 触发一条事件记录。EVENT 只接受 {@code event_type=command}；空命令是占位（已标记触发，不执行）。
     * 其他事件类型跳过，并按实例限频告警（每 200 tick 最多一条）。
     */
    private void fireEvent(ScriptPlayback pb, MinecraftServer server, EventRecord record, int currentTick) {
        if (!"command".equals(record.eventType())) {
            if (currentTick - pb.unsupportedEventTypeWarnTick >= 200) {
                pb.unsupportedEventTypeWarnTick = currentTick;
                LOGGER.warn("Script '{}' EVENT 记录 event_type='{}' 不受支持（只接受 command），已跳过",
                        pb.scriptId, record.eventType());
            }
            return;
        }
        String command = record.command();
        if (command == null || command.isEmpty()) {
            return;
        }
        for (UUID uuid : pb.viewers) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) executeCommand(p, command);
        }
    }

    /**
     * 处理客户端发来的暂停 / 恢复信号（载荷带内容相对位置）。
     * 状态转换那一帧按上报位置重定 EVENT 时间线基准（{@link ScriptPlayback#timelineAnchorSeconds}）：
     * 暂停期间不处理关键帧，恢复后从上报位置继续推进（退掉墙钟暂停 tick 账本）。
     *
     * @param positionClipStart 片段起始秒（{@link Float#NaN} = 无片段覆盖：位置即 {@code positionOffset} 绝对秒）
     * @param positionOffset    片段内偏移秒（{@link Float#NaN} = 未上报 → 保持既有基准不动，回落 tick 推算）
     */
    public void handlePause(ServerPlayer player, String scriptId, String instanceId, boolean paused,
                            float positionClipStart, float positionOffset) {
        ScriptPlayback pb = playback(scriptId, instanceId);
        if (pb == null) return;

        int tick = player.server.getTickCount();
        if (paused && !pb.paused) {
            // 进入暂停
            pb.paused = true;
            reanchor(pb, positionClipStart, positionOffset, tick);
            LOGGER.debug("Script '{}' paused at tick {} (position {}) by player {}",
                    scriptId, tick, pb.timelineAnchorSeconds, player.getName().getString());
        } else if (!paused && pb.paused) {
            // 恢复：从上报的内容位置续
            pb.paused = false;
            reanchor(pb, positionClipStart, positionOffset, tick);
            LOGGER.debug("Script '{}' resumed at tick {} (position {})",
                    scriptId, tick, pb.timelineAnchorSeconds);
        }
    }

    /** 按上报的内容位置重定时间线基准；未上报（NaN）时保持基准不动（回落 tick 推算，不卡事件）。 */
    private static void reanchor(ScriptPlayback pb, float positionClipStart, float positionOffset, int tick) {
        if (Float.isNaN(positionOffset)) return;
        float position = Float.isNaN(positionClipStart) ? positionOffset : positionClipStart + positionOffset;
        if (!Float.isFinite(position) || position < 0f) return;
        pb.timelineAnchorSeconds = position;
        pb.timelineAnchorTick = tick;
        // 不重置事件游标：重锚沿用既有暂停/恢复行为（向前跨越的事件按边沿区间补扫，向后回退不重复触发）。
        // 显式 seek（游标重置）走 handleSeek → EventSeekPlan#resetAll，与暂停握手分离。
    }

    /**
     * 客户端 seek（{@code C2SScriptSeekPacket}）：把本实例的 EVENT 时间线重定位到 {@code targetSeconds}。
     *
     * <p>三件事：基准与扫描进度对齐目标（{@code timelineAnchorSeconds} / {@code timelineAnchorTick} /
     * {@code scannedThroughSeconds}），再按 {@link EventSeekPlan#resetAll} 重置每条事件运行时
     * （前跳不补触发、后跳回退可重新触发、目标之前的记录不重复触发）。
     * <b>不改暂停态</b>：seek 与暂停正交，暂停中的 seek 只重定位基准与游标，恢复仍按暂停握手续。</p>
     *
     * <p>信任边界：只有该实例的观看者可以 seek；无账本 / 非观看者 / 非法目标一律丢弃（限频 debug 日志）。</p>
     */
    public void handleSeek(ServerPlayer player, String scriptId, String instanceId, double targetSeconds) {
        if (player == null) return;
        ScriptPlayback pb = playback(scriptId, instanceId);
        if (pb == null) {
            LOGGER.debug("Seek ignored: no playback for script '{}' instance '{}'", scriptId, instanceId);
            return;
        }
        int tick = player.server.getTickCount();
        if (!pb.viewers.contains(player.getUUID())) {
            if (tick - pb.unauthorizedSeekWarnTick >= 200) {
                pb.unauthorizedSeekWarnTick = tick;
                LOGGER.debug("Seek rejected (not a viewer of script '{}' instance '{}') by player {}",
                        scriptId, pb.instanceId, player.getName().getString());
            }
            return;
        }
        if (!Double.isFinite(targetSeconds) || targetSeconds < 0.0) {
            LOGGER.debug("Seek ignored: invalid target {} for script '{}' instance '{}'",
                    targetSeconds, scriptId, pb.instanceId);
            return;
        }

        pb.timelineAnchorSeconds = (float) targetSeconds;
        pb.timelineAnchorTick = tick;
        pb.scannedThroughSeconds = (float) targetSeconds;
        EventSeekPlan.resetAll(pb.eventClips, pb.eventRuntimes, targetSeconds);
        LOGGER.debug("Script '{}' seeked to {}s at tick {} by player {}",
                scriptId, targetSeconds, tick, player.getName().getString());
    }

    /**
     * 规则动作 {@code emit_command} 的服务端入口：客户端只提交动作引用，命令原文由服务端按脚本解析。
     *
     * <p>校验顺序（任一失败即拒绝，不执行部分命令、不中断播放）：
     * <ol>
     *   <li>发送者在 {@code (scriptId, instanceId)} 的播放授权内（服务端发播放包时签发）；</li>
     *   <li>该实例存在且发送者在观看者账本内（回执只能确认“已开始”，不能自证观看者）；</li>
     *   <li>动作引用在服务端动作表里命中，且解析出的命令非空；</li>
     *   <li>该 {@code (玩家, 实例)} 未超过频率上限（{@link RuleCommandRateLimiter}）。</li>
     * </ol>
     *
     * <p>执行口径：命令按脚本定义拆 {@code &&}，每段用
     * {@link RuleCommandSource}（权限 = {@code Config.ruleCommandPermissionLevel}，缺省 2）执行；
     * 单段异常只记录、不中断。EVENT 轨仍走 {@link #executeCommand}（{@code SuccessOnlySource}，权限 4），
     * 两者不共用命令源。审计见 {@link RuleCommandAudit}（不记命令原文）。
     */
    public void handleRuleCommand(ServerPlayer player, String scriptId, String instanceId,
                                  String bindingId, int clipIndex, int ruleIndex, int actionIndex) {
        String sid = scriptId == null ? "" : scriptId;
        String iid = instanceId == null ? "" : instanceId;
        if (player == null) {
            RuleCommandAudit.reject(null, sid, iid, bindingId, clipIndex, ruleIndex, actionIndex, "无发送玩家");
            return;
        }
        UUID uuid = player.getUUID();
        if (!isGranted(sid, iid, uuid)) {
            RuleCommandAudit.reject(player, sid, iid, bindingId, clipIndex, ruleIndex, actionIndex, "无播放授权");
            return;
        }
        ScriptPlayback pb = playback(sid, iid);
        if (pb == null || !pb.viewers.contains(uuid)) {
            RuleCommandAudit.reject(player, sid, iid, bindingId, clipIndex, ruleIndex, actionIndex,
                    "不在播放账本");
            return;
        }
        RuleCommandTable table = commandTable(sid);
        RuleCommandTable.CommandEntry entry = table == null
                ? null : table.resolve(bindingId, clipIndex, ruleIndex, actionIndex);
        if (entry == null || entry.command().isBlank()) {
            RuleCommandAudit.reject(player, sid, iid, bindingId, clipIndex, ruleIndex, actionIndex,
                    "未知动作引用");
            return;
        }
        if (!RuleCommandRateLimiter.INSTANCE.tryAcquire(uuid, iid)) {
            RuleCommandAudit.reject(player, sid, iid, bindingId, clipIndex, ruleIndex, actionIndex,
                    "超过频率上限");
            return;
        }
        int[] stats = executeRuleCommand(player, entry.command());
        RuleCommandAudit.executed(player, sid, iid, entry.bindingId(), entry.clipIndex(),
                entry.ruleIndex(), entry.actionIndex(), stats[0], stats[1]);
    }

    /**
     * 按脚本定义执行规则命令：{@code &&} 拆分，每段独立执行（单段失败不影响后续段）。
     *
     * @return {@code [尝试段数, 抛异常段数]}
     */
    private int[] executeRuleCommand(ServerPlayer player, String command) {
        String[] parts = command.split("\\s*&&\\s*");
        // 成功结果（/locate 坐标等）发给玩家，失败反馈静默（原因由 RuleCommandAudit 记录）
        CommandSourceStack source = new RuleCommandSource(player, Config.ruleCommandPermissionLevel);
        int segments = 0;
        int failed = 0;
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            segments++;
            try {
                player.server.getCommands().performPrefixedCommand(source, part.trim());
            } catch (Exception e) {
                failed++;
                // 不记录命令原文（审计口径）：只留异常类型与玩家
                LOGGER.error("规则命令片段执行失败（玩家 {}）：{}",
                        player.getName().getString(), e.getClass().getName());
            }
        }
        return new int[]{segments, failed};
    }

    /**
     * 规则命令权限门：发送者是否是该脚本当前播放实例的观看者（{@code ScriptPlayback.viewers} 含其 UUID）。
     * 探针可直调（不需要 {@code ServerPlayer} 实例）。
     */
    public boolean isRuleCommandAllowed(UUID playerId, String scriptId, String instanceId) {
        if (playerId == null || scriptId == null) return false;
        ScriptPlayback pb = playback(scriptId, instanceId);
        return pb != null && pb.viewers.contains(playerId);
    }

    private void executeCommand(ServerPlayer player, String command) {
        String[] parts = command.split("\\s*&&\\s*");
        // 成功结果（/locate 坐标等）发给玩家，失败反馈吞掉（不打扰玩家，错误原因进 ErrorLog 排查）
        CommandSourceStack source = new com.immersivecinematics.immersive_cinematics.util.SuccessOnlySource(player);
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            try {
                player.server.getCommands().performPrefixedCommand(source, part.trim());
            } catch (Exception e) {
                LOGGER.error("Failed to execute event command for player {}: /{}", player.getName().getString(), part.trim(), e);
            }
        }
    }

    /** 取脚本的全部 EVENT 轨片段（不再只取第一条），与客户端 {@code PlayerMoveController} 同口径。 */
    private List<Clip> extractEventClips(CinematicScript script) {
        List<Clip> clips = new ArrayList<>();
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            if (TrackTypeId.EVENT.equals(track.getType())) {
                clips.addAll(track.getClips());
            }
        }
        return clips;
    }

    /** 观看者激活脚本时的位置与朝向（相对玩家激活位置的 CAMERA 关键帧按此换算世界坐标）。 */
    private record ViewerOrigin(Vec3 position, float yaw) {}

    public static class ScriptPlayback {
        final String scriptId;
        /** 播放实例 id；空串 = 无实例 id 的来源（该脚本的全部观看者共用一条退化账本） */
        final String instanceId;
        final Set<UUID> viewers;
        /**
         * 本实例已投出跳过票的玩家。
         *
         * <p>与 {@code viewers} 分开：新语义下投票不退出播放，投票者仍在 {@code viewers} 里，
         * 这张集合只用来判断「谁已经投过」（客户端据此停掉长按提示），不参与人数统计。
         */
        final Set<UUID> skipVoters;
        /** 本场播放实际生效的跳过投票比例（脚本覆盖 ?? 全局配置），创建时解析一次 */
        final int skipVoteRatio;
        /** 本实例的事件身份令牌：同脚本的不同播放实例互不相等（按对象身份去重）。 */
        final Object eventToken = new Object();
        /**
         * 每片段一份事件运行时（记录 + 游标 + 已触发集合）。
         *
         * <p>保留在实例内、不进事实层：这些是「本实例已执行到哪里」的推进游标（去重 / 边沿推进用的当前态），
         * 每 tick 被读写，不是可查询的历史信号；且与事实层不存在同一信号双写——EVENT 关键帧执行不记事实，
         * 记事实的 TRIGGER_FIRED 是另一条链路（触发器命中，不是关键帧游标）。
         */
        final List<EventClipRuntime> eventRuntimes;
        final int startTick;
        final List<Clip> eventClips;
        /** 上次扫描到的内容时间（全局秒）；{@code -inf} = 首帧从片段起点开始。 */
        float scannedThroughSeconds = Float.NEGATIVE_INFINITY;
        /** 不支持的 {@code event_type} 最近一次告警的 tick（限频）。 */
        int unsupportedEventTypeWarnTick = -1000;
        /** 非观看者 seek 最近一次告警的 tick（限频）。 */
        int unauthorizedSeekWarnTick = -1000;
        /** 观看者激活脚本时的位置 / 朝向（相对玩家激活位置的 CAMERA 关键帧基准） */
        final Map<UUID, ViewerOrigin> viewerOrigins = new HashMap<>();
        /** 播放所在服务端：构造时由 {@code addViewer} 传入（广播票数 / 强停时取玩家表用）。 */
        final MinecraftServer server;

        /**
         * 本实例还差多少票。分母是 {@code viewers.size()}：投票不退出播放，投票者仍在其中。
         */
        int neededVotes() {
            return ScriptEventManager.neededVotes(viewers.size(), skipVoteRatio);
        }

        // ── 暂停状态与时间线基准 ──
        /** 本实例暂停中（暂停期间不处理 EVENT 关键帧）。 */
        boolean paused = false;
        /** EVENT 时间线基准：最近一次上报的内容位置（秒；初始 = 脚本起点 0）。 */
        float timelineAnchorSeconds = 0f;
        /** 基准建立时的服务端 tick（读数 = 基准 + 其后经过的 tick）。 */
        int timelineAnchorTick;

        ScriptPlayback(String scriptId, String instanceId, List<Clip> eventClips,
                       int startTick, int skipVoteRatio, MinecraftServer server) {
            this.scriptId = scriptId;
            this.instanceId = instanceId;
            this.viewers = new HashSet<>();
            this.skipVoters = new HashSet<>();
            this.skipVoteRatio = skipVoteRatio;
            this.eventClips = eventClips;
            this.eventRuntimes = new ArrayList<>(eventClips.size());
            for (Clip clip : eventClips) {
                this.eventRuntimes.add(EventClipRuntime.compile(clip, eventToken));
            }
            this.startTick = startTick;
            this.timelineAnchorTick = startTick;
            this.server = server;
        }
    }
}
