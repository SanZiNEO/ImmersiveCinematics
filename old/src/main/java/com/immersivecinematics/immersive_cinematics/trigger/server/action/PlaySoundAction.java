package com.immersivecinematics.immersive_cinematics.trigger.server.action;

import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.registries.ForgeRegistries;

public class PlaySoundAction implements TriggerAction {

    private final ResourceLocation soundId;
    private final float volume;
    private final float pitch;

    public PlaySoundAction(ResourceLocation soundId, float volume, float pitch) {
        this.soundId = soundId;
        this.volume = volume;
        this.pitch = pitch;
    }

    public static PlaySoundAction fromJson(JsonObject obj) {
        ResourceLocation id = ResourceLocation.parse(obj.get("sound").getAsString());
        float vol = obj.has("volume") ? obj.get("volume").getAsFloat() : 1.0f;
        float pit = obj.has("pitch") ? obj.get("pitch").getAsFloat() : 1.0f;
        return new PlaySoundAction(id, vol, pit);
    }

    @Override
    public void execute(ServerPlayer player) {
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(soundId);
        if (sound == null) return;
        player.connection.send(new ClientboundSoundPacket(
                Holder.direct(sound), SoundSource.MASTER,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.level().getRandom().nextLong()
        ));
    }
}
