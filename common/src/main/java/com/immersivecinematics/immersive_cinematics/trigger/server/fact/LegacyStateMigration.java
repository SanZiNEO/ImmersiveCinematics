package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.List;
import java.util.Objects;

/**
 * v1 触发状态（三份集合）到事实的一次性转换。
 *
 * <p>只做转换、不做兼容读取：转换结果交给 {@link PlayerFacts}，由 {@link FactLedger} 在下一次
 * 保存时写成 v2。旧文件结构：
 * <ul>
 *   <li>{@code started_scripts}: 脚本 id 列表 → {@link FactType#PLAYBACK_STARTED}（tick 0，实例空串）</li>
 *   <li>{@code completed_scripts}: 脚本 id 列表 → {@link FactType#PLAYBACK_FINISHED}（tick 0，结果 NONE，实例空串）</li>
 *   <li>{@code triggered_scripts}: 脚本 id → 旧触发器键列表 → {@link FactType#TRIGGER_FIRED}</li>
 * </ul>
 *
 * <p>旧触发器键是 {@code type + "_" + scriptId} 拼出来的，不区分同脚本同类型的多条声明。
 * 只有当 {@link Lookup} 报告的该脚本声明中<b>恰好一个</b>的 {@link LegacyTrigger#legacyKey()}
 * 等于旧键时才映射到它的 {@link LegacyTrigger#identity()}；0 个或多个都丢弃（歧义无法恢复）。
 */
public final class LegacyStateMigration {

    private LegacyStateMigration() {}

    /** 一条声明在旧格式里的键与新身份。 */
    public record LegacyTrigger(String legacyKey, String identity) {

        public LegacyTrigger {
            legacyKey = legacyKey == null ? "" : legacyKey;
            identity = identity == null ? "" : identity;
        }
    }

    /** 按脚本 id 列出该脚本的全部触发器声明；未知脚本返回空列表。 */
    @FunctionalInterface
    public interface Lookup {
        List<LegacyTrigger> triggers(String scriptId);
    }

    /**
     * 把 v1 根标签里的三份集合转换成事实追加到 {@code target}。
     *
     * @param v1     v1 SNBT 根（{@code version:1}）
     * @param target 目标玩家存储；调用方负责在其后置脏（{@link PlayerFacts#markDirty()}）
     * @param lookup 脚本声明查询；{@code null} 视为所有旧触发器键都无匹配
     */
    public static void migrate(CompoundTag v1, PlayerFacts target, Lookup lookup) {
        Objects.requireNonNull(v1, "v1");
        Objects.requireNonNull(target, "target");

        ListTag started = v1.getList("started_scripts", 8);
        for (int i = 0; i < started.size(); i++) {
            String script = started.getString(i);
            if (!script.isEmpty()) {
                target.append(Fact.playbackStarted(script, "", 0));
            }
        }

        ListTag completed = v1.getList("completed_scripts", 8);
        for (int i = 0; i < completed.size(); i++) {
            String script = completed.getString(i);
            if (!script.isEmpty()) {
                target.append(Fact.playbackFinished(script, "", FactResult.NONE, 0));
            }
        }

        CompoundTag triggered = v1.getCompound("triggered_scripts");
        for (String script : triggered.getAllKeys()) {
            if (script.isEmpty()) {
                continue;
            }
            ListTag legacyKeys = triggered.getList(script, 8);
            List<LegacyTrigger> declared = lookup == null ? List.of() : lookup.triggers(script);
            if (declared == null) {
                declared = List.of();
            }
            for (int i = 0; i < legacyKeys.size(); i++) {
                String identity = resolveIdentity(legacyKeys.getString(i), declared);
                if (identity != null) {
                    target.append(Fact.triggerFired(script, identity, 0));
                }
            }
        }
    }

    /** 恰好一个声明的 legacyKey 匹配时返回其 identity；否则 null。 */
    private static String resolveIdentity(String legacyKey, List<LegacyTrigger> declared) {
        String identity = null;
        int matches = 0;
        for (LegacyTrigger trigger : declared) {
            if (trigger.legacyKey().equals(legacyKey)) {
                matches++;
                if (matches > 1) {
                    return null;
                }
                identity = trigger.identity();
            }
        }
        return matches == 1 ? identity : null;
    }
}
