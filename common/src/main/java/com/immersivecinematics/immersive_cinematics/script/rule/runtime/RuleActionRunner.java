package com.immersivecinematics.immersive_cinematics.script.rule.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.camera.rule.RuleActions;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.execution.ClipExitPolicy;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleBinding;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;

import java.util.List;

/**
 * 脚本级规则的动作执行分派：把 {@code rules[].actions} / {@code on_timeout} 的每个元素交给
 * 对应动作实现。
 *
 * <p>分工：
 * <ul>
 *   <li>共享动作（{@code camera_set} / {@code overlay_set} / {@code adjust_set} / {@code play_sound}
 *       / {@code exit}）→ {@link RuleActions#executeShared}。</li>
 *   <li>{@code pause} / {@code resume} → 本实例的 {@link RuleRuntime#onActionPause()} /
 *       {@link RuleRuntime#onActionResume()}：进 {@link SuspensionLedger} 多来源账本
 *       （{@link SuspensionLedger#ACTION_PAUSE}）。</li>
 *   <li>{@code emit_command} → {@link RuleActions#emitCommandForBinding}（只发动作引用，命令原文由服务端
 *       按脚本解析；授权、权限上限与限频都在服务端）。</li>
 *   <li>{@code goto_clip}（{@code clip} + 可选 {@code entry_time}）= 分支启动：经
 *       {@link PlaybackInstance#branch()} 调 {@code ClipBranchController.launch}，入口覆盖取绑定
 *       {@code exit}（无则片段默认）。只接受 {@code clip} + 可选 {@code entry_time} 形态；
 *       {@code track}/{@code at} 在解析期已被拒绝，本执行器不做兼容。</li>
 * </ul>
 *
 * <p>失败不中断播放：参数非法 / 未知类型 / 执行异常一律限频日志后继续（与动作族同一约定）。
 * 分配契约：只在判定成功 / 超时帧调用（非稳态路径）。线程契约：仅客户端主线程。</p>
 */
public final class RuleActionRunner {

    /** 限频日志窗口（毫秒）：同一失败点每 5 秒最多一条。 */
    private static final long LOG_INTERVAL_MS = 5000L;

    /** 所属运行态（{@code pause} / {@code resume} 的持有账本）。 */
    private final RuleRuntime runtime;

    /**
     * @param runtime 所属运行态（不得为 {@code null}）
     */
    public RuleActionRunner(RuleRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("RuleActionRunner 需要所属 RuleRuntime");
        }
        this.runtime = runtime;
    }

    /**
     * 执行一批动作（按声明顺序）。
     *
     * <p>{@code pause} / {@code resume} 只动持有账本，不依赖宿主实例；其余动作需要
     * {@code instance}（{@code null} = 跳过该动作）。</p>
     *
     * @param instance 触发规则的播放实例（共享动作的作用对象；探针无宿主时可为 {@code null}）
     * @param clip     规则绑定目标片段（共享动作的作用域参数）
     * @param binding  触发动作的绑定（日志定位 / I4-5 的调度身份）
     * @param actions  动作原文列表（可为 {@code null} / 空 = 无操作）
     * @param timedOut {@code true} = 来自 {@code on_timeout}，{@code false} = 正常触发
     */
    public void run(PlaybackInstance instance, Clip clip, RuleBinding binding,
                    List<JsonObject> actions, boolean timedOut) {
        if (actions == null || actions.isEmpty()) return;
        String bindingId = binding != null ? binding.id() : "<none>";
        for (int i = 0; i < actions.size(); i++) {
            JsonObject action = actions.get(i);
            if (action == null) continue;
            String type = typeOf(action);
            if (type == null) continue;
            switch (type) {
                case "pause" -> runtime.onActionPause();
                case "resume" -> runtime.onActionResume();
                case "camera_set", "overlay_set", "adjust_set", "play_sound", "exit" -> {
                    if (instance != null) RuleActions.executeShared(instance, clip, action);
                }
                case "emit_command" -> {
                    if (instance != null) RuleActions.emitCommandForBinding(instance, binding, i, action);
                }
                case "goto_clip" -> launchBranch(instance, binding, action, bindingId, i);
                default -> ErrorLog.logRateLimited("Rules",
                        "script-rule:action:" + type, LOG_INTERVAL_MS,
                        "未知脚本级规则动作: " + type + "（绑定 " + bindingId
                                + (timedOut ? "，on_timeout" : "") + "）——动作跳过，播放继续");
            }
        }
    }

    /** 动作 type（非字符串 / 缺失 → {@code null}）。 */
    private static String typeOf(JsonObject action) {
        if (!action.has("type")) return null;
        JsonElement element = action.get("type");
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    /**
     * {@code goto_clip} 新形态 = 分支启动：目标片段按脚本 id 解析（内联 / 独立片段同一命名空间），
     * 入口局部时间可省（缺省 0），入口出口覆盖取绑定 {@code exit}。
     *
     * <p>失败不中断播放：无宿主 / 缺 {@code clip} / 未知片段 / 入口时间非法 / launch 被拒绝
     * （已在分支中、目标不在执行账本）一律限频日志后跳过。</p>
     */
    private void launchBranch(PlaybackInstance instance, RuleBinding binding, JsonObject action,
                              String bindingId, int actionIndex) {
        if (instance == null) return;
        String key = "script-rule:goto_clip:" + bindingId;

        JsonElement clipElement = action.get("clip");
        if (clipElement == null || !clipElement.isJsonPrimitive()
                || !clipElement.getAsJsonPrimitive().isString()
                || clipElement.getAsString().isEmpty()) {
            ErrorLog.logRateLimited("Rules", key, LOG_INTERVAL_MS,
                    "goto_clip 缺少 clip（目标片段 id；绑定 " + bindingId + "，动作 " + actionIndex
                            + "）——动作跳过，播放继续");
            return;
        }
        String clipId = clipElement.getAsString();
        CinematicScript script = instance.script();
        Clip target = script != null ? script.findClip(clipId) : null;
        if (target == null) {
            ErrorLog.logRateLimited("Rules", key, LOG_INTERVAL_MS,
                    "goto_clip 未知片段引用: " + clipId + "（绑定 " + bindingId
                            + "）——动作跳过，播放继续");
            return;
        }

        double entryTime = 0.0;
        if (action.has("entry_time")) {
            JsonElement entry = action.get("entry_time");
            if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()) {
                ErrorLog.logRateLimited("Rules", key, LOG_INTERVAL_MS,
                        "goto_clip 的 entry_time 需要数字（入口局部秒；绑定 " + bindingId
                                + "）——动作跳过，播放继续");
                return;
            }
            entryTime = entry.getAsDouble();
            if (!Double.isFinite(entryTime) || entryTime < 0.0) {
                ErrorLog.logRateLimited("Rules", key, LOG_INTERVAL_MS,
                        "goto_clip 的 entry_time 需要 >= 0 的有限数字，实际: " + entry
                                + "（绑定 " + bindingId + "）——动作跳过，播放继续");
                return;
            }
        }

        ClipExitPolicy entryOverride = binding != null ? binding.exitOverride() : null;
        if (!instance.branch().launch(target, entryTime, entryOverride)) {
            ErrorLog.logRateLimited("Rules", key, LOG_INTERVAL_MS,
                    "goto_clip 分支启动被拒绝: " + clipId + "（绑定 " + bindingId
                            + "；已在分支中或目标不在执行账本）——动作跳过，播放继续");
        }
    }
}
