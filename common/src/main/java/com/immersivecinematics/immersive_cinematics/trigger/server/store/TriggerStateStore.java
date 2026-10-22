package com.immersivecinematics.immersive_cinematics.trigger.server.store;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 触发器状态存储：每玩家一个 SNBT 文件（世界目录 {@code immersive_cinematics/trigger_state/<uuid>.snbt}）。
 * 读：{@link #loadForPlayer} 在玩家进入时载入内存，查询接口只读内存、不触盘；
 * 写：标记方法只置 dirty，{@link #saveIfChanged} / {@link #saveAll} 才落盘（临时文件 + 原子移动，防半写）。
 */
public class TriggerStateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TriggerStateStore INSTANCE = new TriggerStateStore();

    private static final String STORE_PATH = "immersive_cinematics/trigger_state";
    private static final int VERSION = 1;

    private final Map<UUID, PlayerTriggerState> playerStates = new HashMap<>();
    private Path storeRoot;

    private TriggerStateStore() {}

    /** 绑定世界目录并建目录；未初始化时所有落盘调用都静默跳过（纯内存模式）。 */
    public void initialize(MinecraftServer server) {
        this.storeRoot = server.getWorldPath(LevelResource.ROOT).resolve(STORE_PATH);
        try {
            Files.createDirectories(storeRoot);
        } catch (IOException e) {
            LOGGER.error("Failed to create trigger state directory", e);
        }
    }

    // ===== Query =====

    /** 该 (玩家, 脚本, 触发器) 是否已触发过；无内存状态视为未触发。 */
    public boolean isTriggered(UUID player, String scriptId, String triggerId) {
        PlayerTriggerState state = playerStates.get(player);
        return state != null && state.isTriggered(scriptId, triggerId);
    }

    /** 该玩家是否已结束过指定脚本（script_completed 的判定源）。 */
    public boolean isScriptCompleted(UUID player, String scriptId) {
        PlayerTriggerState state = playerStates.get(player);
        return state != null && state.isScriptCompleted(scriptId);
    }

    /** 该玩家是否已经开始播放指定脚本 */
    public boolean isScriptStarted(UUID player, String scriptId) {
        PlayerTriggerState state = playerStates.get(player);
        return state != null && state.isScriptStarted(scriptId);
    }

    /** 该玩家是否“播放过”指定脚本：开始播放 &amp;&amp; 结束播放（任何退出原因都算） */
    public boolean hasPlayed(UUID player, String scriptId) {
        PlayerTriggerState state = playerStates.get(player);
        return state != null && state.hasPlayed(scriptId);
    }

    /** 取玩家状态，不存在则建空状态（不置 dirty，未变更不会落盘）。 */
    public PlayerTriggerState getOrCreate(UUID player) {
        return playerStates.computeIfAbsent(player, k -> new PlayerTriggerState());
    }

    // ===== Mutation =====

    /** 首次标记返回 true 并置 dirty；已触发过返回 false。 */
    public boolean markTriggered(UUID player, String scriptId, String triggerId) {
        PlayerTriggerState state = getOrCreate(player);
        return state.markTriggered(scriptId, triggerId);
    }

    /** 首次标记返回 true 并置 dirty；已记录返回 false。 */
    public boolean markScriptStarted(UUID player, String scriptId) {
        PlayerTriggerState state = getOrCreate(player);
        return state.markScriptStarted(scriptId);
    }

    /** 首次标记返回 true 并置 dirty；已记录返回 false。 */
    public boolean markScriptCompleted(UUID player, String scriptId) {
        PlayerTriggerState state = getOrCreate(player);
        return state.markScriptCompleted(scriptId);
    }

    // ===== Persistence (SNBT text format) =====

    /** 从磁盘载入；文件缺失或解析失败都回落到空状态（坏档不阻塞进服）。 */
    public void loadForPlayer(UUID player) {
        if (storeRoot == null) return;
        Path file = storeRoot.resolve(player.toString() + ".snbt");
        if (!Files.isRegularFile(file)) {
            playerStates.put(player, new PlayerTriggerState());
            return;
        }
        try {
            String snbt = Files.readString(file);
            CompoundTag tag = TagParser.parseTag(snbt);
            PlayerTriggerState state = deserialize(tag);
            playerStates.put(player, state);
        } catch (Exception e) {
            LOGGER.error("Failed to load trigger state for player {}", player, e);
            playerStates.put(player, new PlayerTriggerState());
        }
    }

    /** 先落盘（若 dirty）再移除内存状态；断线清理用。 */
    public void unloadForPlayer(UUID player) {
        saveIfChanged(player);
        playerStates.remove(player);
    }

    /** 仅 dirty 时落盘并清脏位；未初始化时直接返回。 */
    public void saveIfChanged(UUID player) {
        PlayerTriggerState state = playerStates.get(player);
        if (state == null || !state.isDirty() || storeRoot == null) return;
        savePlayer(player, state);
        state.markClean();
    }

    /** 落盘所有 dirty 玩家（关卡保存 / 服务端停止）。 */
    public void saveAll() {
        if (storeRoot == null) return;
        for (Map.Entry<UUID, PlayerTriggerState> entry : playerStates.entrySet()) {
            if (entry.getValue().isDirty()) {
                savePlayer(entry.getKey(), entry.getValue());
                entry.getValue().markClean();
            }
        }
    }

    private void savePlayer(UUID player, PlayerTriggerState state) {
        Path file = storeRoot.resolve(player.toString() + ".snbt");
        Path tmp = storeRoot.resolve(player.toString() + ".snbt.tmp");
        try {
            CompoundTag tag = serialize(state);
            String snbt = new StringTagVisitor().visit(tag);
            Files.writeString(tmp, snbt);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to save trigger state for player {}", player, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败可忽略（主保存错误已在上方 LOGGER.error 记录）；防清理异常掩盖主错误
            }
        }
    }

    private CompoundTag serialize(PlayerTriggerState state) {
        CompoundTag root = new CompoundTag();
        root.putInt("version", VERSION);

        CompoundTag triggeredScripts = new CompoundTag();
        for (var entry : state.getTriggeredScripts().object2ObjectEntrySet()) {
            ListTag triggerList = new ListTag();
            for (String triggerId : entry.getValue()) {
                triggerList.add(StringTag.valueOf(triggerId));
            }
            triggeredScripts.put(entry.getKey(), triggerList);
        }
        root.put("triggered_scripts", triggeredScripts);

        ListTag startedScripts = new ListTag();
        for (String scriptId : state.getStartedScripts()) {
            startedScripts.add(StringTag.valueOf(scriptId));
        }
        root.put("started_scripts", startedScripts);

        ListTag completedScripts = new ListTag();
        for (String scriptId : state.getCompletedScripts()) {
            completedScripts.add(StringTag.valueOf(scriptId));
        }
        root.put("completed_scripts", completedScripts);

        return root;
    }

    private PlayerTriggerState deserialize(CompoundTag tag) {
        PlayerTriggerState state = new PlayerTriggerState();
        if (tag.contains("triggered_scripts")) {
            CompoundTag triggeredScripts = tag.getCompound("triggered_scripts");
            for (String scriptId : triggeredScripts.getAllKeys()) {
                ListTag triggerList = triggeredScripts.getList(scriptId, 8);
                ObjectOpenHashSet<String> triggers = new ObjectOpenHashSet<>();
                for (int i = 0; i < triggerList.size(); i++) {
                    triggers.add(triggerList.getString(i));
                }
                state.getTriggeredScripts().put(scriptId, triggers);
            }
        }
        if (tag.contains("started_scripts")) {
            ListTag started = tag.getList("started_scripts", 8);
            for (int i = 0; i < started.size(); i++) {
                state.getStartedScripts().add(started.getString(i));
            }
        }
        if (tag.contains("completed_scripts")) {
            ListTag completedScripts = tag.getList("completed_scripts", 8);
            for (int i = 0; i < completedScripts.size(); i++) {
                state.getCompletedScripts().add(completedScripts.getString(i));
            }
        }
        return state;
    }
}
