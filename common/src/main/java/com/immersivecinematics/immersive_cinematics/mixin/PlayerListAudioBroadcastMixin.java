package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.trigger.server.AudioListenerServerState;
import com.immersivecinematics.immersive_cinematics.util.AudioConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 相机听者模式下的服务端声音广播补丁。
 * <p>
 * 原版 {@code PlayerList.broadcast} 只按玩家实体坐标筛选声音包，相机听者离玩家很远时
 * 生物音/攻击音不会下发。这里对声音类包增加“相机听者位置”这个第二判定点：
 * <ul>
 *   <li>玩家自身仍在原版半径内 → 照常发送；</li>
 *   <li>相机听者在放宽后的半径内 → 补发；</li>
 *   <li>两者都不在 → 不发。</li>
 * </ul>
 * 只拦截声音相关包，其他广播原样走原版逻辑。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListAudioBroadcastMixin {

    @Shadow
    @Final
    private List<ServerPlayer> players;

    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void immersivecinematics_broadcastWithAudioListener(
            @Nullable Player except,
            double x, double y, double z,
            double range,
            ResourceKey<Level> dimension,
            Packet<?> packet,
            CallbackInfo ci) {
        if (!isAudioBroadcast(packet)) {
            return;
        }

        double vanillaRangeSqr = range * range;
        double cameraRange = Math.max(range, AudioConstants.CAMERA_ATTENUATION_DISTANCE);
        double cameraRangeSqr = cameraRange * cameraRange;

        for (ServerPlayer serverPlayer : this.players) {
            if (serverPlayer == except) {
                continue;
            }
            if (serverPlayer.level().dimension() != dimension) {
                continue;
            }

            boolean shouldSend = isWithin(serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                    x, y, z, vanillaRangeSqr);

            if (!shouldSend) {
                Vec3 listener = AudioListenerServerState.get(serverPlayer.getUUID());
                if (listener != null) {
                    shouldSend = isWithin(listener.x, listener.y, listener.z, x, y, z, cameraRangeSqr);
                }
            }

            if (shouldSend) {
                serverPlayer.connection.send(packet);
            }
        }

        ci.cancel();
    }

    private static boolean isAudioBroadcast(Packet<?> packet) {
        return packet instanceof ClientboundSoundPacket
                || packet instanceof ClientboundSoundEntityPacket
                || packet instanceof ClientboundLevelEventPacket;
    }

    private static boolean isWithin(double px, double py, double pz,
                                   double sx, double sy, double sz,
                                   double rangeSqr) {
        double dx = sx - px;
        double dy = sy - py;
        double dz = sz - pz;
        return dx * dx + dy * dy + dz * dz < rangeSqr;
    }
}
