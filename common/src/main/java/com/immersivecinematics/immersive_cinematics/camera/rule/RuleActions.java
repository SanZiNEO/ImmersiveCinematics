package com.immersivecinematics.immersive_cinematics.camera.rule;

import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ClipRule;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * 规则动作执行入口（0.3.6 C6-2）：{@code rules[].do} / {@code on_timeout} 的元素逐条执行。
 *
 * 本任务只落最小可用两支：{@code goto_clip}（{@code at} = 目标已播放时间秒 → {@code ScriptPlayer.seekTo}）
 * 与 {@code play_sound}（{@code sound} 必填 + 可选 {@code volume} / {@code pitch}，本机播放）；其余 type 走
 * 「未实现动作」限频日志，由 C6-3 按同一签名补齐（camera_set / overlay_set / adjust_set / pause / resume /
 * exit / emit_command 等）。作用对象恒为触发它的那个实例（设计 §5 末条）。
 *
 * 失败回退（设计 §5.1）：参数非法 / 目标越界 / 执行异常一律不传播、不中断播放——限频日志 + 继续
 * （分支无栈，回退目标恒为主支）。分配契约：只在动作触发帧调用（非稳态路径），可读 JSON 字符串。
 * 线程契约：仅客户端主线程。
 */
public final class RuleActions {

    private RuleActions() {}

    /**
     * 执行一条动作（接口签名固定，C6-3 按此扩展）。
     *
     * @param instance 触发规则的播放实例（动作作用对象；探针无宿主时为 {@code null}）
     * @param clip     规则所在片段
     * @param rule     规则（动作来源，日志定位用）
     * @param action   动作原文（{@code do} / {@code on_timeout} 的一个元素）
     */
    public static void execute(PlaybackInstance instance, Clip clip, ClipRule rule, JsonObject action) {
        if (action == null || instance == null) return;
        String type = str(action, "type");
        if (type == null) return;
        try {
            switch (type) {
                case "goto_clip" -> gotoClip(instance, action);
                case "play_sound" -> playSound(instance, action);
                default -> ErrorLog.logRateLimited("Rules", "action:" + type, 5000L,
                        "规则动作未实现（C6-3 落地）: " + type);
            }
        } catch (Exception e) {
            ErrorLog.logRateLimited("Rules", "action:" + type, 5000L, "规则动作执行异常: " + type, e);
        }
    }

    /**
     * {@code goto_clip}：跳到目标已播放时间（秒）。{@code at} 缺失 / 非数字 / 越界（负数或超过总时长）→
     * 限频日志 + 不动（播放继续，设计 §5.1 失败即回主支）。轨道寻址（{@code track}）与分支状态归 C6-3。
     */
    private static void gotoClip(PlaybackInstance instance, JsonObject action) {
        if (!isNumber(action, "at")) {
            ErrorLog.logRateLimited("Rules", "goto_clip:at", 5000L,
                    "goto_clip 缺少数字参数 at（目标已播放时间，秒）——动作跳过，播放继续");
            return;
        }
        float at = action.get("at").getAsFloat();
        CinematicScript script = instance.script();
        float total = script != null ? script.getTotalDuration() : -1f;
        if (!Float.isFinite(at) || at < 0f || (total > 0f && at > total)) {
            ErrorLog.logRateLimited("Rules", "goto_clip:range", 5000L,
                    "goto_clip 目标时间越界: " + at + "s（总时长 " + total + "s）——动作跳过，播放继续");
            return;
        }
        instance.player().seekTo(at);
    }

    /** {@code play_sound}：按 id 播一条本机声音（位置 = 玩家，音量 / 音调可选，缺省 1）；未知 id → 限频日志。 */
    private static void playSound(PlaybackInstance instance, JsonObject action) {
        String id = str(action, "sound");
        if (id == null || id.isEmpty()) {
            ErrorLog.logRateLimited("Rules", "play_sound:id", 5000L,
                    "play_sound 缺少 sound（音效 id，如 minecraft:ambient.cave）——动作跳过，播放继续");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;   // 无客户端世界：静默跳过
        ResourceLocation key = ResourceLocation.tryParse(id);
        SoundEvent sound = key != null ? BuiltInRegistries.SOUND_EVENT.get(key) : null;
        if (sound == null) {
            ErrorLog.logRateLimited("Rules", "play_sound:unknown", 5000L,
                    "play_sound 未知音效 id: " + id + "——动作跳过，播放继续");
            return;
        }
        float volume = isNumber(action, "volume") ? action.get("volume").getAsFloat() : 1f;
        float pitch = isNumber(action, "pitch") ? action.get("pitch").getAsFloat() : 1f;
        mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                sound, SoundSource.AMBIENT, volume, pitch, false);
    }

    private static boolean isNumber(JsonObject c, String key) {
        return c.has(key) && c.get(key).isJsonPrimitive() && c.get(key).getAsJsonPrimitive().isNumber();
    }

    /** 字符串字段（非字符串 / 缺失 → null）。 */
    private static String str(JsonObject c, String key) {
        if (!c.has(key)) return null;
        JsonElement e = c.get(key);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
