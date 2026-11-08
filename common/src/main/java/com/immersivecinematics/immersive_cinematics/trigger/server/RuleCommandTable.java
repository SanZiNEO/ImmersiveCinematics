package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.CinematicScript;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleBinding;
import com.immersivecinematics.immersive_cinematics.script.rule.RuleCollection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端规则命令动作表：把脚本里每个 {@code emit_command} 动作编译成可解析的表项。
 *
 * <p>客户端只提交动作引用（{@code bindingId} + {@code actionIndex}），命令原文只存在于服务端这张表里；
 * 请求命中表项才执行，未知引用（脚本外动作 / 非命令动作 / 越界索引）一律解析不到。</p>
 *
 * <p>键空间只有一种：脚本级规则（{@code rules} + {@code rule_bindings}），键 = {@code (bindingId, actionIndex)}，
 * 命令取 {@code binding.definition().actions()[actionIndex].command}。</p>
 *
 * <p>只存服务端内存；不写脚本、不发给客户端。脚本替换时由
 * {@link ScriptEventManager#primeCommandTable} 重建，旧引用随之失效。</p>
 *
 * <p>线程契约：编译只在服务端主线程的播放请求路径发生；解析只读，可重复。</p>
 */
public final class RuleCommandTable {

    /** 动作类型名（与客户端 {@code RuleActions} 的分派名同一字符串）。 */
    private static final String TYPE_EMIT_COMMAND = "emit_command";

    /**
     * 一条可执行的规则命令动作。
     *
     * @param scriptId    脚本 id
     * @param bindingId   绑定 id
     * @param actionIndex 动作列表下标
     * @param command     命令原文（只存在于服务端）
     * @param sourcePath  动作在脚本中的位置（审计 / 诊断用，不含命令原文）
     */
    public record CommandEntry(String scriptId, String bindingId, int actionIndex,
                               String command, String sourcePath) {}

    private static final RuleCommandTable EMPTY = new RuleCommandTable(Map.of());

    private final Map<Key, CommandEntry> entries;

    private RuleCommandTable(Map<Key, CommandEntry> entries) {
        this.entries = entries;
    }

    /**
     * 编译脚本的全部 {@code emit_command} 动作（脚本为 {@code null} / 无时间线 → 空表）。
     *
     * <p>空命令 / 非字符串命令 / 非 {@code emit_command} 动作不建条目：请求方拿不到可执行命令，
     * 与非命令动作同样按“未知引用”拒绝。</p>
     */
    public static RuleCommandTable compile(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return EMPTY;
        String scriptId = script.getId() == null ? "" : script.getId();
        Map<Key, CommandEntry> entries = new HashMap<>();

        // 脚本级规则的绑定动作（键 = (bindingId, actionIndex)）
        RuleCollection collection = script.getRuleCollection();
        if (collection != null) {
            for (RuleBinding binding : collection.bindings()) {
                if (binding == null || binding.definition() == null) continue;
                List<JsonObject> actions = binding.definition().actions();
                for (int ai = 0; ai < actions.size(); ai++) {
                    JsonObject action = actions.get(ai);
                    if (!isEmitCommand(action)) continue;
                    String command = commandOf(action);
                    if (command == null) continue;
                    String path = "rules[" + binding.definition().id() + "].actions[" + ai + "]";
                    entries.put(new Key(binding.id(), ai),
                            new CommandEntry(scriptId, binding.id(), ai, command, path));
                }
            }
        }

        return entries.isEmpty() ? EMPTY : new RuleCommandTable(Map.copyOf(entries));
    }

    /**
     * 按动作引用查表。
     *
     * @param bindingId   绑定 id
     * @param actionIndex 动作下标
     * @return 命中项；未知引用一律 {@code null}
     */
    public CommandEntry resolve(String bindingId, int actionIndex) {
        return entries.get(new Key(bindingId == null ? "" : bindingId, actionIndex));
    }

    /** 表项数（诊断 / 探针）。 */
    public int size() {
        return entries.size();
    }

    private static boolean isEmitCommand(JsonObject action) {
        return action != null && TYPE_EMIT_COMMAND.equals(stringField(action, "type"));
    }

    /** 非空白命令原文；缺失 / 非字符串 / 空白 → {@code null}。 */
    private static String commandOf(JsonObject action) {
        String command = stringField(action, "command");
        return command == null || command.isBlank() ? null : command;
    }

    private static String stringField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private record Key(String bindingId, int actionIndex) {}
}
