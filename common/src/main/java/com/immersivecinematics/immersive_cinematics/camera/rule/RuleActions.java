package com.immersivecinematics.immersive_cinematics.camera.rule;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraChannels;
import com.immersivecinematics.immersive_cinematics.camera.cover.ClipModifier;
import com.immersivecinematics.immersive_cinematics.camera.cover.ComposeMode;
import com.immersivecinematics.immersive_cinematics.camera.cover.RuleModifier;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.client.post.MasterColorAdjust;
import com.immersivecinematics.immersive_cinematics.overlay.CanvasTransform;
import com.immersivecinematics.immersive_cinematics.overlay.FadeLayer;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.ClipRule;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.TimelineTrack;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SRuleCommandPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkGuard;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.List;

/**
 * 规则动作执行入口（0.3.6 C6-2/C6-3）：{@code rules[].do} / {@code on_timeout} 的元素逐条执行。
 *
 * v1 动作集九种：{@code camera_set}（覆盖链 Modifier 叠加，工件随片段结束注销）/ {@code overlay_set}
 * （fade 临时层）/ {@code adjust_set}（master 规则调整层）/ {@code goto_clip}（{@code track} + {@code at}
 * 时间寻址）/ {@code pause} / {@code resume}（实例挂起：保持内容位置 / 从该位置续）/ {@code exit}（声明式退出，跳过 hold_at_end 拒绝）/
 * {@code play_sound}（本实例一次性发声）/ {@code emit_command}（C2S 交服务端，仅观看者可执行）。
 * {@code hold} 销项（用 {@code until} 无 timeout 表达，见 {@link #ACTION_HOLD}）。
 *
 * 作用对象恒为触发它的那个实例（设计 §5 末条）。失败回退（设计 §5.1）：参数非法 / 目标越界 / 执行异常
 * 一律不传播、不中断播放——限频日志 + 继续（分支无栈，回退目标恒为主支 = 「动作失败 = 什么都没发生」）。
 * 分配契约：只在动作触发帧调用（非稳态路径），可读 JSON 字符串 / 建工件。线程契约：仅客户端主线程。
 */
public final class RuleActions {

    /** v1 动作 type 白名单（与 {@link #execute} 的 switch 同名单源；校验器按它拦未知动作）。 */
    public static final List<String> ACTION_TYPES_V1 = List.of(
            "camera_set", "overlay_set", "adjust_set", "goto_clip",
            "pause", "resume", "exit", "play_sound", "emit_command");

    /** 销项动作：{@code hold} 语义 = {@code until} 无 timeout（无限等待），不单独实现。 */
    public static final String ACTION_HOLD = "hold";

    /** {@code adjust_set} v1 通道名（顺序 = 基础校色 15 通道；参数名与 ADJUST 轨关键帧字段同名）。 */
    public static final List<String> ADJUST_CHANNELS_V1 = List.of(
            "exposure", "contrast", "highlights", "shadows", "whites", "blacks",
            "hue", "saturation", "vibrance", "lightness",
            "temperature", "tint", "red", "green", "blue");

    /** {@code overlay_set} v1 层类型（fade 临时层；image / subtitle 留 v2）。 */
    public static final List<String> OVERLAY_LAYER_TYPES_V1 = List.of("fade");

    /** 限频日志窗口（毫秒）：同一失败点每 5 秒最多一条。 */
    private static final long LOG_INTERVAL_MS = 5000L;

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
                case "camera_set" -> cameraSet(instance, action);
                case "overlay_set" -> overlaySet(instance, action);
                case "adjust_set" -> adjustSet(instance, action);
                case "goto_clip" -> gotoClip(instance, action);
                case "pause" -> instance.ruleEngine().suspendByAction(clip);
                case "resume" -> instance.ruleEngine().resumeByAction();
                case "exit" -> CameraManager.INSTANCE.requestDeclaredExit(instance);
                case "play_sound" -> playSound(instance, action);
                case "emit_command" -> emitCommand(instance, action);
                case ACTION_HOLD -> ErrorLog.logRateLimited("Rules", "action:hold", LOG_INTERVAL_MS,
                        "hold 动作用 until 无 timeout 表达（无限等待），本版本不单独实现——见"
                                + " docs/SCRIPT_FORMAT.md §判定规则");
                default -> ErrorLog.logRateLimited("Rules", "action:" + type, LOG_INTERVAL_MS,
                        "未知规则动作: " + type + "（v1 动作: " + typesLabel() + "）");
            }
        } catch (Exception e) {
            ErrorLog.logRateLimited("Rules", "action:" + type, LOG_INTERVAL_MS, "规则动作执行异常: " + type, e);
        }
    }

    /** v1 动作清单（报错文案用）。 */
    public static String typesLabel() {
        return String.join(" / ", ACTION_TYPES_V1);
    }

    // ========== 参数调整族（camera_set / overlay_set / adjust_set） ==========

    /**
     * {@code camera_set}：建一条 {@link RuleModifier} 并接入覆盖链（持续叠加到片段结束 / 被同类覆盖）。
     * 参数结构 = {@code modifier} 字段同款（{@code channels} + {@code mode?} + {@code priority?} + 通道值）；
     * 非法参数 → 限频日志 + 不动（播放继续）。{@code blend} / {@code look_at} / {@code follow} 留 v2。
     */
    private static void cameraSet(PlaybackInstance instance, JsonObject action) {
        int mask = cameraChannels(action);
        if (mask == 0) return;
        String modeName = str(action, "mode");
        ComposeMode mode = null;
        if (modeName != null) {
            mode = switch (modeName) {
                case "add" -> ComposeMode.ADD;
                case "multiply" -> ComposeMode.MULTIPLY;
                case "replace" -> ComposeMode.REPLACE;
                default -> null;
            };
            if (mode == null) {
                ErrorLog.logRateLimited("Rules", "camera_set:mode", LOG_INTERVAL_MS,
                        "camera_set 未知合成模式: " + modeName + "（可选: add / multiply / replace）——动作跳过");
                return;
            }
        }
        int priority = isNumber(action, "priority") ? action.get("priority").getAsInt() : 0;
        double[] values = new double[ClipModifier.VALUE_COUNT];
        if (!readCameraValues(action, mask, values)) return;
        instance.ruleEngine().attachArtifact(new CameraArtifact(instance,
                new RuleModifier(priority, mask, mode, values)));
    }

    /** 解析 {@code channels}：非空、通道名子集、不重复；非法 → 限频日志 + 返回 0。 */
    private static int cameraChannels(JsonObject action) {
        JsonElement e = action.get("channels");
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty()) {
            ErrorLog.logRateLimited("Rules", "camera_set:channels", LOG_INTERVAL_MS,
                    "camera_set 缺少非空 channels 数组（通道名: " + CameraChannels.namesHint() + "）——动作跳过");
            return 0;
        }
        int mask = 0;
        for (JsonElement channel : e.getAsJsonArray()) {
            if (!channel.isJsonPrimitive() || !channel.getAsJsonPrimitive().isString()) {
                ErrorLog.logRateLimited("Rules", "camera_set:channels", LOG_INTERVAL_MS,
                        "camera_set 的 channels 元素需要字符串（通道名: " + CameraChannels.namesHint() + "）——动作跳过");
                return 0;
            }
            String name = channel.getAsString();
            int bit = CameraChannels.bitOf(name);
            if (bit == 0 || (mask & bit) != 0) {
                ErrorLog.logRateLimited("Rules", "camera_set:channels", LOG_INTERVAL_MS,
                        "camera_set 通道非法: " + name + "（未知或重复；可用: " + CameraChannels.namesHint() + "）——动作跳过");
                return 0;
            }
            mask |= bit;
        }
        return mask;
    }

    /** 读取掩码内通道值（{@code position} = {@code {x,y,z}}，其余 = 有限数字）；非法 → 限频日志 + false。 */
    private static boolean readCameraValues(JsonObject action, int mask, double[] values) {
        for (String name : CameraChannels.NAMES) {
            if ((mask & CameraChannels.bitOf(name)) == 0) continue;
            JsonElement e = action.get(name);
            if (CameraChannels.POSITION == CameraChannels.bitOf(name)) {
                if (e == null || !e.isJsonObject()) {
                    ErrorLog.logRateLimited("Rules", "camera_set:value", LOG_INTERVAL_MS,
                            "camera_set 的 position 需要对象 {x, y, z}（世界坐标，方块）——动作跳过");
                    return false;
                }
                JsonObject position = e.getAsJsonObject();
                double[] axes = {axis(position, "x"), axis(position, "y"), axis(position, "z")};
                if (Double.isNaN(axes[0]) || Double.isNaN(axes[1]) || Double.isNaN(axes[2])) {
                    ErrorLog.logRateLimited("Rules", "camera_set:value", LOG_INTERVAL_MS,
                            "camera_set 的 position 三轴都要是有限数字（世界坐标，方块）——动作跳过");
                    return false;
                }
                values[ClipModifier.SLOT_POSITION_X] = axes[0];
                values[ClipModifier.SLOT_POSITION_Y] = axes[1];
                values[ClipModifier.SLOT_POSITION_Z] = axes[2];
                continue;
            }
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()
                    || !Float.isFinite(e.getAsFloat())) {
                ErrorLog.logRateLimited("Rules", "camera_set:value", LOG_INTERVAL_MS,
                        "camera_set 的通道 " + name + " 需要有限数字（REPLACE = 绝对值、ADD = 增量、"
                                + "MULTIPLY = 倍率）——动作跳过");
                return false;
            }
            float scalar = e.getAsFloat();
            switch (name) {
                case "yaw" -> values[ClipModifier.SLOT_YAW] = scalar;
                case "pitch" -> values[ClipModifier.SLOT_PITCH] = scalar;
                case "roll" -> values[ClipModifier.SLOT_ROLL] = scalar;
                case "fov" -> values[ClipModifier.SLOT_FOV] = scalar;
                case "zoom" -> values[ClipModifier.SLOT_ZOOM] = scalar;
                default -> { }
            }
        }
        return true;
    }

    /** 位置分量（有限数字；缺失 / 非法 → {@code NaN}）。 */
    private static double axis(JsonObject position, String key) {
        JsonElement e = position.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return Double.NaN;
        double v = e.getAsDouble();
        return Double.isFinite(v) ? v : Double.NaN;
    }

    /**
     * {@code overlay_set}：建一层临时覆盖层（v1 = fade，owner = 本实例）并接入覆盖层管理器，
     * 持续到片段结束移除。{@code layer_type} 非 fade / 参数非法 → 限频日志 + 不动。
     */
    private static void overlaySet(PlaybackInstance instance, JsonObject action) {
        String layerType = str(action, "layer_type");
        if (!OVERLAY_LAYER_TYPES_V1.contains(layerType)) {
            ErrorLog.logRateLimited("Rules", "overlay_set:type", LOG_INTERVAL_MS,
                    "overlay_set 的 layer_type 非法: " + layerType + "（v1 支持: "
                            + String.join(" / ", OVERLAY_LAYER_TYPES_V1) + "；image / subtitle 留 v2）——动作跳过");
            return;
        }
        FadeLayer layer = new FadeLayer(instance);
        String color = str(action, "color");
        layer.setColor(color != null ? color : "#000000");
        layer.setOpacity(isNumber(action, "opacity") ? clamp01(action.get("opacity").getAsFloat()) : 1f);
        layer.setZIndex(isNumber(action, "z_index")
                ? action.get("z_index").getAsInt() : CanvasTransform.DEFAULT_Z_INDEX);
        instance.ruleEngine().attachArtifact(new OverlayArtifact(layer));
    }

    /**
     * {@code adjust_set}：建一份 master 规则调整层（v1 = {@link #ADJUST_CHANNELS_V1} 十五个基础校色标量，
     * 参数名与 ADJUST 轨关键帧字段同名）并发布到 {@link MasterColorAdjust}，持续到片段结束清除。
     * 非法通道名 / 非有限数值 → 限频日志 + 不动。
     */
    private static void adjustSet(PlaybackInstance instance, JsonObject action) {
        ColorAdjustParams params = ruleAdjustParams(action);
        if (params == null) return;
        instance.ruleEngine().attachArtifact(new AdjustArtifact(instance.player(), params));
    }

    /**
     * 从 {@code adjust_set} 动作建规则调整层参数（只认 {@link #ADJUST_CHANNELS_V1}；未写通道取缺省 0 =
     * 不改变画面）；含未知键 / 非有限数值 / 无任何通道 → 限频日志 + {@code null}（调用方跳过）。
     */
    public static ColorAdjustParams ruleAdjustParams(JsonObject action) {
        float[] channels = new float[ADJUST_CHANNELS_V1.size()];
        boolean any = false;
        for (String key : action.keySet()) {
            if ("type".equals(key)) continue;
            int index = ADJUST_CHANNELS_V1.indexOf(key);
            if (index < 0) {
                ErrorLog.logRateLimited("Rules", "adjust_set:key", LOG_INTERVAL_MS,
                        "adjust_set 未知通道: " + key + "（v1 支持: " + String.join(" / ", ADJUST_CHANNELS_V1)
                                + "）——动作跳过");
                return null;
            }
            JsonElement e = action.get(key);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Float.isFinite(e.getAsFloat())) {
                ErrorLog.logRateLimited("Rules", "adjust_set:value", LOG_INTERVAL_MS,
                        "adjust_set 的通道 " + key + " 需要有限数字——动作跳过");
                return null;
            }
            channels[index] = e.getAsFloat();
            any = true;
        }
        if (!any) {
            ErrorLog.logRateLimited("Rules", "adjust_set:empty", LOG_INTERVAL_MS,
                    "adjust_set 未声明任何通道（至少一个: " + String.join(" / ", ADJUST_CHANNELS_V1) + "）——动作跳过");
            return null;
        }
        // 未写通道取缺省（0 = 不改变画面；曲线 / LUT / 层级混合取恒等），规则层因此只盖过写了的通道
        return new ColorAdjustParams(
                channels[0], channels[1], channels[2], channels[3], channels[4], channels[5],
                channels[6], channels[7], channels[8], channels[9], channels[10], channels[11],
                channels[12], channels[13], channels[14],
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                null, null, null, null,
                0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                null, 0f, 1f,
                null, null, null, null, null, null,
                0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, ColorAdjustParams.BLEND_NORMAL, 1f);
    }

    // ========== 分支 / 播放控制族 ==========

    /**
     * {@code goto_clip}：{@code track}（轨道类型名）+ {@code at}（目标已播放时间秒）时间寻址——
     * 目标时间须落在该轨某个片段的闭区间窗 {@code [startTime, windowEnd]} 内（末端取闭：跳到最后一段末尾
     * = 收尾）；轨道缺失 / 无片段覆盖 / 时间越界 → 限频日志 + 不动（播放继续，设计 §5.1 失败即回主支）。
     * 不引入 clip id 字段（id 体系归编辑器阶段）。
     */
    private static void gotoClip(PlaybackInstance instance, JsonObject action) {
        String trackName = str(action, "track");
        if (trackName == null || trackName.isEmpty()) {
            ErrorLog.logRateLimited("Rules", "goto_clip:track", LOG_INTERVAL_MS,
                    "goto_clip 缺少 track（轨道类型名，如 CAMERA / OVERLAY）——动作跳过，播放继续");
            return;
        }
        if (!isNumber(action, "at")) {
            ErrorLog.logRateLimited("Rules", "goto_clip:at", LOG_INTERVAL_MS,
                    "goto_clip 缺少数字参数 at（目标已播放时间，秒）——动作跳过，播放继续");
            return;
        }
        float at = action.get("at").getAsFloat();
        CinematicScript script = instance.script();
        if (script == null || script.getTimeline() == null) return;
        float total = script.getTotalDuration();
        if (!Float.isFinite(at) || at < 0f || (total > 0f && at > total)) {
            ErrorLog.logRateLimited("Rules", "goto_clip:range", LOG_INTERVAL_MS,
                    "goto_clip 目标时间越界: " + at + "s（总时长 " + total + "s）——动作跳过，播放继续");
            return;
        }
        TrackType type = trackType(trackName);
        if (type == null) {
            ErrorLog.logRateLimited("Rules", "goto_clip:track", LOG_INTERVAL_MS,
                    "goto_clip 未知轨道类型: " + trackName + "——动作跳过，播放继续");
            return;
        }
        if (!hasClipAt(script, type, at)) {
            ErrorLog.logRateLimited("Rules", "goto_clip:target", LOG_INTERVAL_MS,
                    "goto_clip 目标时间 " + at + "s 在 " + type + " 轨道上没有片段覆盖——动作跳过，播放继续");
            return;
        }
        instance.player().seekTo(at);
    }

    /** 轨道类型名 → 枚举（大小写不敏感；未知 → {@code null}）。 */
    private static TrackType trackType(String name) {
        for (TrackType type : TrackType.values()) {
            if (type.name().equalsIgnoreCase(name)) return type;
        }
        return null;
    }

    /** 该轨是否有片段覆盖目标时间（闭区间 {@code [startTime, windowEnd]}；无限片段恒真）。 */
    private static boolean hasClipAt(CinematicScript script, TrackType type, float at) {
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            if (track.getType() != type) continue;
            List<Clip> clips = track.getClips();
            for (int i = 0; i < clips.size(); i++) {
                Clip clip = clips.get(i);
                if (at >= clip.getStartTime() && at <= clip.getWindowEnd()) return true;
            }
        }
        return false;
    }

    // ========== 媒体族 ==========

    /** {@code play_sound}：按 id 播一条本机声音（相对听者，音量 / 音调可选，缺省 1）；未知 id → 限频日志。 */
    private static void playSound(PlaybackInstance instance, JsonObject action) {
        String id = str(action, "sound");
        if (id == null || id.isEmpty()) {
            ErrorLog.logRateLimited("Rules", "play_sound:id", LOG_INTERVAL_MS,
                    "play_sound 缺少 sound（音效 id，如 minecraft:ambient.cave）——动作跳过，播放继续");
            return;
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        SoundEvent sound = key != null ? BuiltInRegistries.SOUND_EVENT.get(key) : null;
        if (sound == null) {
            ErrorLog.logRateLimited("Rules", "play_sound:unknown", LOG_INTERVAL_MS,
                    "play_sound 未知音效 id: " + id + "——动作跳过，播放继续");
            return;
        }
        float volume = isNumber(action, "volume") ? action.get("volume").getAsFloat() : 1f;
        float pitch = isNumber(action, "pitch") ? action.get("pitch").getAsFloat() : 1f;
        instance.player().playOneShot(sound, SoundSource.AMBIENT, volume, pitch);
    }

    /**
     * {@code emit_command}：命令交服务端执行（EVENT 轨同口径：{@code &&} 拆分 + 成功结果回执），
     * 服务端只在该实例的观看者名单含发送者时才执行（防命令注入）。无脚本 / 无命令 → 限频日志 + 不动。
     */
    private static void emitCommand(PlaybackInstance instance, JsonObject action) {
        String command = str(action, "command");
        if (command == null || command.isBlank()) {
            ErrorLog.logRateLimited("Rules", "emit_command:command", LOG_INTERVAL_MS,
                    "emit_command 缺少 command（命令原文，不含前导 /；多条用 && 分隔）——动作跳过，播放继续");
            return;
        }
        String scriptId = instance.scriptId();
        if (scriptId == null || scriptId.isEmpty() || "<none>".equals(scriptId)) {
            ErrorLog.logRateLimited("Rules", "emit_command:script", LOG_INTERVAL_MS,
                    "emit_command 无脚本上下文（脚本 id 缺失）——动作跳过");
            return;
        }
        String instanceId = instance.instanceId();
        NetworkGuard.sendToServer("C2SRuleCommand", () -> NetworkHandler.sendToServer(
                new C2SRuleCommandPacket(scriptId, instanceId, command)));
    }

    // ========== 工件实现（RuleArtifact） ==========

    /** {@code camera_set} 工件：覆盖链 Modifier 的注册 / 注销。 */
    private static final class CameraArtifact implements RuleArtifact {
        private final PlaybackInstance instance;
        private final RuleModifier modifier;

        CameraArtifact(PlaybackInstance instance, RuleModifier modifier) {
            this.instance = instance;
            this.modifier = modifier;
        }

        @Override
        public Kind kind() {
            return Kind.CAMERA;
        }

        @Override
        public void attach() {
            instance.attachRuleModifier(modifier);
        }

        @Override
        public void release() {
            instance.detachRuleModifier(modifier);
        }
    }

    /** {@code overlay_set} 工件：临时覆盖层的加入 / 移除。 */
    private static final class OverlayArtifact implements RuleArtifact {
        private final FadeLayer layer;

        OverlayArtifact(FadeLayer layer) {
            this.layer = layer;
        }

        @Override
        public Kind kind() {
            return Kind.OVERLAY;
        }

        @Override
        public void attach() {
            OverlayManager.INSTANCE.addLayer(layer);
        }

        @Override
        public void release() {
            OverlayManager.INSTANCE.removeLayer(layer);
        }
    }

    /** {@code adjust_set} 工件：master 规则调整层的发布 / 清除（键 = 本实例的播放器）。 */
    private static final class AdjustArtifact implements RuleArtifact {
        private final ScriptPlayer key;
        private final ColorAdjustParams params;

        AdjustArtifact(ScriptPlayer key, ColorAdjustParams params) {
            this.key = key;
            this.params = params;
        }

        @Override
        public Kind kind() {
            return Kind.ADJUST;
        }

        @Override
        public void attach() {
            MasterColorAdjust.INSTANCE.publishRuleLayer(key, params);
        }

        @Override
        public void release() {
            MasterColorAdjust.INSTANCE.clearRuleLayer(key);
        }
    }

    // ========== 小工具 ==========

    private static boolean isNumber(JsonObject c, String key) {
        return c.has(key) && c.get(key).isJsonPrimitive() && c.get(key).getAsJsonPrimitive().isNumber();
    }

    /** 字符串字段（非字符串 / 缺失 → null）。 */
    private static String str(JsonObject c, String key) {
        if (!c.has(key)) return null;
        JsonElement e = c.get(key);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
