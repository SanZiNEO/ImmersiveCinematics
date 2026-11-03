package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.ScriptManager;
import com.immersivecinematics.immersive_cinematics.script.TriggerDefinition;
import com.immersivecinematics.immersive_cinematics.trigger.server.fact.LegacyStateMigration.LegacyTrigger;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端事实账本：按玩家分片的内存表 + SNBT v2 持久化 + 跳过票实例索引。
 *
 * <p>线程契约：仅服务端主线程。时间不取全局 tick：{@link #record} / {@link #recordVote} 的调用方
 * 显式传入服务端 tick。只依赖 {@link MinecraftServer} 与 {@link UUID}，不依赖 {@code ServerPlayer}。
 *
 * <p>持久化：每个玩家一份 {@code immersive_cinematics/trigger_state/<uuid>.snbt}（与 v1 同路径），
 * 根结构 {@code {version:2, facts:[...]}}，只写 {@link FactType.Durability#PERSISTENT} 事实；
 * 写入走临时文件 + {@code ATOMIC_MOVE}，失败只记日志、不抛出。
 *
 * <p>v1 一次性转换：{@link #loadForPlayer(UUID)} 读到 {@code version:1} 时调用
 * {@link LegacyStateMigration}，触发器身份按脚本声明生成
 * {@code legacyKey = type + "_" + scriptId}、{@code identity = TriggerDefinition#getIdentity()}
 * （显式 {@code id} 优先，缺省为 {@code type + "#" + 声明下标}）。
 */
public final class FactLedger {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final FactLedger INSTANCE = new FactLedger();
    public static final int VERSION = 2;

    private static final String STORE_PATH = "immersive_cinematics/trigger_state";

    private final Map<UUID, PlayerFacts> playerFacts = new HashMap<>();
    /** script → instance → 投票者；与事实里的 SKIP_VOTE 同步维护，用于幂等与计数。 */
    private final Map<String, Map<String, Set<UUID>>> votes = new HashMap<>();
    private Path storeRoot;

    /**
     * 不绑定目录的空账本。生产代码用 {@link #INSTANCE}；探针 / 单测可自行 {@code new} 后
     * {@link #initialize(Path)} 到临时目录，避免替换静态终态字段。
     */
    public FactLedger() {
    }

    // ===== Lifecycle =====

    /** 绑定世界目录（{@code <world>/immersive_cinematics/trigger_state}）并清空内存表。 */
    public void initialize(MinecraftServer server) {
        initialize(server.getWorldPath(LevelResource.ROOT).resolve(STORE_PATH));
    }

    /** 目录重载：探针 / 单测入口，与 {@link #initialize(MinecraftServer)} 同一套目录语义。 */
    public void initialize(Path storeRoot) {
        this.storeRoot = storeRoot;
        try {
            Files.createDirectories(storeRoot);
        } catch (IOException e) {
            LOGGER.error("Failed to create fact store directory", e);
        }
        playerFacts.clear();
        votes.clear();
    }

    // ===== Player access =====

    /** 取玩家存储；不存在则建空存储（不置脏，未变更不会落盘）。 */
    public PlayerFacts of(UUID player) {
        return playerFacts.computeIfAbsent(player, ignored -> new PlayerFacts());
    }

    /** 取玩家存储；不存在返回 {@code null}（查询不产生副作用时用）。 */
    public PlayerFacts ofOrNull(UUID player) {
        return playerFacts.get(player);
    }

    /**
     * 从磁盘载入玩家事实：文件缺失 → 空；{@code version:2} → 解码持久事实；
     * {@code version:1} → 一次性转换并置脏；其他版本 → WARN 后按空处理。坏档不阻塞进服。
     */
    public void loadForPlayer(UUID player) {
        if (storeRoot == null) {
            return;
        }
        Path file = file(player);
        if (!Files.isRegularFile(file)) {
            playerFacts.put(player, new PlayerFacts());
            return;
        }
        try {
            CompoundTag root = TagParser.parseTag(Files.readString(file));
            int version = root.getInt("version");
            PlayerFacts facts = new PlayerFacts();
            if (version == VERSION) {
                decodeV2(root, facts);
                facts.markClean();
            } else if (version == 1) {
                LegacyStateMigration.migrate(root, facts, FactLedger::legacyTriggers);
                facts.markDirty();
            } else {
                LOGGER.warn("Unknown trigger state version {} for player {}; treating as empty", version, player);
            }
            playerFacts.put(player, facts);
        } catch (Exception e) {
            LOGGER.error("Failed to load facts for player {}", player, e);
            playerFacts.put(player, new PlayerFacts());
        }
    }

    /** 先落盘（若 dirty）再移除内存状态；断线清理用。 */
    public void unloadForPlayer(UUID player) {
        saveIfChanged(player);
        playerFacts.remove(player);
    }

    // ===== Persistence =====

    /** 仅 dirty 时落盘并清脏位；未初始化时直接返回。 */
    public void saveIfChanged(UUID player) {
        PlayerFacts facts = playerFacts.get(player);
        if (facts == null || !facts.isDirty() || storeRoot == null) {
            return;
        }
        savePlayer(player, facts);
        facts.markClean();
    }

    /** 落盘所有 dirty 玩家（关卡保存 / 服务端停止）。 */
    public void saveAll() {
        if (storeRoot == null) {
            return;
        }
        for (Map.Entry<UUID, PlayerFacts> entry : playerFacts.entrySet()) {
            if (entry.getValue().isDirty()) {
                savePlayer(entry.getKey(), entry.getValue());
                entry.getValue().markClean();
            }
        }
    }

    private void savePlayer(UUID player, PlayerFacts facts) {
        Path tmp = storeRoot.resolve(player + ".snbt.tmp");
        try {
            CompoundTag root = new CompoundTag();
            root.putInt("version", VERSION);
            ListTag list = new ListTag();
            for (Fact fact : facts.persistentFacts()) {
                list.add(FactCodec.encode(fact));
            }
            root.put("facts", list);
            Files.writeString(tmp, new StringTagVisitor().visit(root));
            Files.move(tmp, file(player), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to save facts for player {}", player, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败可忽略（主保存错误已记录）；不掩盖主错误
            }
        }
    }

    private static void decodeV2(CompoundTag root, PlayerFacts facts) {
        ListTag list = root.getList("facts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Fact fact = FactCodec.decode(list.getCompound(i));
            if (fact != null) {
                facts.append(fact);
            }
        }
    }

    private Path file(UUID player) {
        return storeRoot.resolve(player + ".snbt");
    }

    // ===== Recording =====

    public void record(UUID player, Fact fact) {
        of(player).append(fact);
    }

    /** 记录一次跳过票；同一 (玩家, 脚本, 实例) 重复投票直接返回（幂等）。 */
    public void recordVote(UUID voter, String script, String instance, int tick) {
        String scriptId = normalize(script);
        String instanceId = normalize(instance);
        if (hasVoted(voter, scriptId, instanceId)) {
            return;
        }
        votes.computeIfAbsent(scriptId, ignored -> new HashMap<>())
                .computeIfAbsent(instanceId, ignored -> new HashSet<>())
                .add(voter);
        of(voter).append(Fact.vote(scriptId, instanceId, tick));
    }

    public boolean hasVoted(UUID voter, String script, String instance) {
        Map<String, Set<UUID>> byInstance = votes.get(normalize(script));
        if (byInstance == null) {
            return false;
        }
        Set<UUID> voters = byInstance.get(normalize(instance));
        return voters != null && voters.contains(voter);
    }

    public int voteCount(String script, String instance) {
        Map<String, Set<UUID>> byInstance = votes.get(normalize(script));
        if (byInstance == null) {
            return 0;
        }
        Set<UUID> voters = byInstance.get(normalize(instance));
        return voters == null ? 0 : voters.size();
    }

    /** 实例结束：清票事实与实例索引。 */
    public void clearInstance(String script, String instance) {
        String scriptId = normalize(script);
        String instanceId = normalize(instance);
        Map<String, Set<UUID>> byInstance = votes.get(scriptId);
        if (byInstance != null) {
            byInstance.remove(instanceId);
            if (byInstance.isEmpty()) {
                votes.remove(scriptId);
            }
        }
        for (PlayerFacts facts : playerFacts.values()) {
            facts.removeInstance(scriptId, instanceId);
        }
    }

    /** 服务端停止 / 换档：清空全部玩家事实与投票索引（不落盘）。 */
    public void clearAll() {
        playerFacts.clear();
        votes.clear();
    }

    // ===== v1 lookup =====

    private static List<LegacyTrigger> legacyTriggers(String scriptId) {
        CinematicScript script = ScriptManager.INSTANCE.getScript(scriptId);
        if (script == null) {
            return List.of();
        }
        List<TriggerDefinition> definitions = script.getMeta().getTriggers();
        List<LegacyTrigger> result = new ArrayList<>(definitions.size());
        for (TriggerDefinition definition : definitions) {
            result.add(new LegacyTrigger(definition.getType() + "_" + scriptId, definition.getIdentity()));
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
