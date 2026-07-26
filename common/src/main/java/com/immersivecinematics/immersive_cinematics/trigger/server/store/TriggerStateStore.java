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
import java.util.Set;
import java.util.UUID;

public class TriggerStateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TriggerStateStore INSTANCE = new TriggerStateStore();

    private static final LevelResource STORE_PATH = new LevelResource("immersive_cinematics/trigger_state");
    private static final int VERSION = 1;

    private final Map<UUID, PlayerTriggerState> playerStates = new HashMap<>();
    private Path storeRoot;

    private TriggerStateStore() {}

    public void initialize(MinecraftServer server) {
        this.storeRoot = server.getWorldPath(STORE_PATH);
        try {
            Files.createDirectories(storeRoot);
        } catch (IOException e) {
            LOGGER.error("Failed to create trigger state directory", e);
        }
    }

    // ===== Query =====

    public boolean isTriggered(UUID player, String scriptId, String triggerId) {
        PlayerTriggerState state = playerStates.get(player);
        return state != null && state.isTriggered(scriptId, triggerId);
    }

    public boolean isScriptCompleted(UUID player, String scriptId) {
        PlayerTriggerState state = playerStates.get(player);
        return state != null && state.isScriptCompleted(scriptId);
    }

    public Set<String> getTriggeredIds(UUID player, String scriptId) {
        PlayerTriggerState state = playerStates.get(player);
        return state != null ? state.getTriggeredIds(scriptId) : null;
    }

    public PlayerTriggerState getOrCreate(UUID player) {
        return playerStates.computeIfAbsent(player, k -> new PlayerTriggerState());
    }

    // ===== Mutation =====

    public boolean markTriggered(UUID player, String scriptId, String triggerId) {
        PlayerTriggerState state = getOrCreate(player);
        return state.markTriggered(scriptId, triggerId);
    }

    public boolean markScriptCompleted(UUID player, String scriptId) {
        PlayerTriggerState state = getOrCreate(player);
        return state.markScriptCompleted(scriptId);
    }

    public void resetScript(UUID player, String scriptId) {
        PlayerTriggerState state = playerStates.get(player);
        if (state != null) state.resetScript(scriptId);
    }

    public void resetAll(UUID player) {
        PlayerTriggerState state = playerStates.get(player);
        if (state != null) state.resetAll();
    }

    // ===== Persistence (SNBT text format) =====

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

    public void unloadForPlayer(UUID player) {
        saveIfChanged(player);
        playerStates.remove(player);
    }

    public void saveIfChanged(UUID player) {
        PlayerTriggerState state = playerStates.get(player);
        if (state == null || !state.isDirty() || storeRoot == null) return;
        savePlayer(player, state);
        state.markClean();
    }

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
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
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
        if (tag.contains("completed_scripts")) {
            ListTag completedScripts = tag.getList("completed_scripts", 8);
            for (int i = 0; i < completedScripts.size(); i++) {
                state.getCompletedScripts().add(completedScripts.getString(i));
            }
        }
        return state;
    }
}
