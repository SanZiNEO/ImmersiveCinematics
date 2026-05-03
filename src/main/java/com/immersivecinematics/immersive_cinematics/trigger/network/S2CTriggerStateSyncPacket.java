package com.immersivecinematics.immersive_cinematics.trigger.network;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;

public class S2CTriggerStateSyncPacket {

    private final Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> triggeredScripts;
    private final ObjectOpenHashSet<String> completedScripts;

    public S2CTriggerStateSyncPacket(
            Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> triggeredScripts,
            ObjectOpenHashSet<String> completedScripts) {
        this.triggeredScripts = triggeredScripts;
        this.completedScripts = completedScripts;
    }

    public S2CTriggerStateSyncPacket(FriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            triggeredScripts = new Object2ObjectOpenHashMap<>();
            completedScripts = new ObjectOpenHashSet<>();
            return;
        }
        triggeredScripts = new Object2ObjectOpenHashMap<>();
        if (tag.contains("triggered_scripts")) {
            CompoundTag ts = tag.getCompound("triggered_scripts");
            for (String scriptId : ts.getAllKeys()) {
                ListTag list = ts.getList(scriptId, 8);
                ObjectOpenHashSet<String> triggers = new ObjectOpenHashSet<>();
                for (int i = 0; i < list.size(); i++) {
                    triggers.add(list.getString(i));
                }
                triggeredScripts.put(scriptId, triggers);
            }
        }
        completedScripts = new ObjectOpenHashSet<>();
        if (tag.contains("completed_scripts")) {
            ListTag list = tag.getList("completed_scripts", 8);
            for (int i = 0; i < list.size(); i++) {
                completedScripts.add(list.getString(i));
            }
        }
    }

    public void write(FriendlyByteBuf buf) {
        CompoundTag tag = new CompoundTag();
        CompoundTag ts = new CompoundTag();
        for (var entry : triggeredScripts.object2ObjectEntrySet()) {
            ListTag list = new ListTag();
            for (String id : entry.getValue()) {
                list.add(StringTag.valueOf(id));
            }
            ts.put(entry.getKey(), list);
        }
        tag.put("triggered_scripts", ts);
        ListTag cs = new ListTag();
        for (String id : completedScripts) {
            cs.add(StringTag.valueOf(id));
        }
        tag.put("completed_scripts", cs);
        buf.writeNbt(tag);
    }

    public Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> getTriggeredScripts() { return triggeredScripts; }
    public ObjectOpenHashSet<String> getCompletedScripts() { return completedScripts; }

    public static void send(ServerPlayer player,
                            Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> triggered,
                            ObjectOpenHashSet<String> completed) {
        NetworkHandler.sendToPlayer(new S2CTriggerStateSyncPacket(triggered, completed), player);
    }
}
