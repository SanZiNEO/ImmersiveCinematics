package com.immersivecinematics.migrate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * 旧 {@code clip.rules[]} → 脚本级 {@code rules[]} + {@code rule_bindings[]}。
 *
 * <p>旧条目形态：{@code {if|until, do, ...}}（历史）或 {@code {condition, actions, ...}}。
 * 新定义 = condition + actions + policy；绑定 gate 固定 {@code {at: 0}}（旧规则随片段活跃窗生效）。</p>
 *
 * <p>旧 {@code goto_clip} 动作（{@code track + at}）在迁移期静态解析成
 * {@code clip + entry_time}；解析不到的动作为了不产生非法新格式，直接从数组里删除并记人工复核。</p>
 */
final class RuleMapper {

    private static final List<String> POLICY_KEYS =
            List.of("once", "cooldown", "mode", "dwell", "timeout", "on_timeout");

    private final Report report;

    RuleMapper(Report report) {
        this.report = report;
    }

    /**
     * @return {@code {定义数, 绑定数}}
     */
    int[] apply(JsonObject root, String clipId, JsonArray rules, String scriptPath,
                Set<String> usedIds) {
        if (rules == null || rules.isEmpty()) {
            return new int[]{0, 0};
        }
        JsonArray defs = array(root, "rules");
        JsonArray bindings = array(root, "rule_bindings");
        int n = 0;
        for (JsonElement element : rules) {
            if (!element.isJsonObject()) {
                report.error(scriptPath + " " + clipId + ": rules[] 条目不是对象，跳过");
                continue;
            }
            JsonObject rule = element.getAsJsonObject();
            JsonElement condition = first(rule, "condition", "if", "until");
            JsonElement actions = first(rule, "actions", "do");
            if (condition == null || actions == null) {
                report.error(scriptPath + " " + clipId
                        + ": rules[] 条目缺少 condition/if/until 或 actions/do，跳过");
                continue;
            }
            n++;
            String ruleId = "r_" + clipId + "_" + n;
            JsonObject def = new JsonObject();
            def.addProperty("id", ruleId);
            def.add("condition", condition.deepCopy());

            JsonElement convertedActions = convertGoto(actions.deepCopy(), root, scriptPath,
                    clipId, usedIds);
            def.add("actions", convertedActions == null ? new JsonArray() : convertedActions);

            JsonObject policy = new JsonObject();
            for (String key : POLICY_KEYS) {
                if (!rule.has(key)) {
                    continue;
                }
                if ("on_timeout".equals(key)) {
                    JsonElement converted = convertGoto(rule.get(key).deepCopy(), root, scriptPath,
                            clipId, usedIds);
                    if (converted != null) {
                        policy.add(key, converted);
                    }
                } else {
                    policy.add(key, rule.get(key).deepCopy());
                }
            }
            if (policy.size() > 0) {
                def.add("policy", policy);
            }
            defs.add(def);

            boolean until = rule.has("until") || "until".equals(ObjectMapper.str(rule, "kind", null));
            JsonObject binding = new JsonObject();
            binding.addProperty("id", "b_" + clipId + "_" + n);
            binding.addProperty("rule", ruleId);
            binding.addProperty("clip", clipId);
            JsonObject gate = new JsonObject();
            gate.addProperty("at", 0);
            binding.add("gate", gate);
            binding.addProperty("control", until ? "hold" : "observe");
            bindings.add(binding);

            report.semantic(scriptPath + " " + clipId + ": 规则 " + ruleId
                    + " 旧「随片段活跃窗生效」→ 新绑定 gate={at:0}");
            if (until) {
                report.manual(scriptPath + " " + clipId + ": 规则 " + ruleId
                        + " until → control=hold（语义近似），需人工复核");
            }
        }
        return new int[]{n, n};
    }

    // ------------------------------------------------------------------
    // goto_clip 静态解析
    // ------------------------------------------------------------------

    /**
     * 递归转换动作树里的旧 {@code goto_clip}（track + at）。
     *
     * @return 转换后的节点；{@code null} = 该动作被删除
     */
    private JsonElement convertGoto(JsonElement element, JsonObject root, String scriptPath,
                                    String ownerClipId, Set<String> usedIds) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray source = element.getAsJsonArray();
            JsonArray result = new JsonArray();
            for (JsonElement item : source) {
                JsonElement converted = convertGoto(item, root, scriptPath, ownerClipId, usedIds);
                if (converted != null) {
                    result.add(converted);
                }
            }
            return result;
        }
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject action = element.getAsJsonObject();
        if ("goto_clip".equals(ObjectMapper.str(action, "type", ""))
                && action.has("track") && action.has("at") && !action.has("clip")) {
            String trackType = ObjectMapper.str(action, "track", "");
            double at = number(action, "at", 0);
            Target target = findTarget(root, trackType, at);
            if (target == null) {
                report.manual(scriptPath + " " + ownerClipId + ": goto_clip(track=" + trackType
                        + ", at=" + at + ") 无法静态解析目标片段，已丢弃");
                return null;
            }
            String targetId = ObjectMapper.str(target.clip, "id", null);
            if (targetId == null || targetId.isBlank()) {
                targetId = assignId(target.clip, target.trackIndex, target.clipIndex, usedIds);
            }
            action.remove("track");
            action.remove("at");
            action.addProperty("clip", targetId);
            action.addProperty("entry_time", at - number(target.clip, "start_time", 0));
        }
        for (String key : List.of("actions", "on_timeout", "do")) {
            JsonElement nested = action.get(key);
            if (nested != null && (nested.isJsonArray() || nested.isJsonObject())) {
                JsonElement converted = convertGoto(nested, root, scriptPath, ownerClipId, usedIds);
                if (converted == null) {
                    action.remove(key);
                } else {
                    action.add(key, converted);
                }
            }
        }
        return action;
    }

    private static final class Target {
        final JsonObject clip;
        final int trackIndex;
        final int clipIndex;

        Target(JsonObject clip, int trackIndex, int clipIndex) {
            this.clip = clip;
            this.trackIndex = trackIndex;
            this.clipIndex = clipIndex;
        }
    }

    /** 第一条 type 等于 {@code trackType} 的轨道；在该轨 clips 里找覆盖 {@code at} 的片段。 */
    private static Target findTarget(JsonObject root, String trackType, double at) {
        JsonElement timelineEl = root.get("timeline");
        if (timelineEl == null || !timelineEl.isJsonObject()) {
            return null;
        }
        JsonElement tracksEl = timelineEl.getAsJsonObject().get("tracks");
        if (tracksEl == null || !tracksEl.isJsonArray()) {
            return null;
        }
        JsonArray tracks = tracksEl.getAsJsonArray();
        for (int ti = 0; ti < tracks.size(); ti++) {
            JsonElement trackEl = tracks.get(ti);
            if (!trackEl.isJsonObject()) {
                continue;
            }
            JsonObject track = trackEl.getAsJsonObject();
            if (!trackType.equals(ObjectMapper.str(track, "type", ""))) {
                continue;
            }
            JsonElement clipsEl = track.get("clips");
            if (clipsEl == null || !clipsEl.isJsonArray()) {
                return null;
            }
            JsonArray clips = clipsEl.getAsJsonArray();
            for (int ci = 0; ci < clips.size(); ci++) {
                JsonElement clipEl = clips.get(ci);
                if (!clipEl.isJsonObject()) {
                    continue;
                }
                JsonObject clip = clipEl.getAsJsonObject();
                double start = number(clip, "start_time", 0);
                double duration = number(clip, "duration", 0);
                boolean hit = duration < 0 ? at >= start : (at >= start && at <= start + duration);
                if (hit) {
                    return new Target(clip, ti, ci);
                }
            }
            return null;
        }
        return null;
    }

    private static String assignId(JsonObject clip, int trackIndex, int clipIndex,
                                   Set<String> used) {
        String base = "c" + (trackIndex + 1) + "_" + (clipIndex + 1);
        String candidate = base;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + n++;
        }
        clip.addProperty("id", candidate);
        return candidate;
    }

    private static double number(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static JsonArray array(JsonObject root, String key) {
        JsonElement existing = root.get(key);
        if (existing != null && existing.isJsonArray()) {
            return existing.getAsJsonArray();
        }
        JsonArray arr = new JsonArray();
        root.add(key, arr);
        return arr;
    }

    private static JsonElement first(JsonObject o, String... keys) {
        for (String key : keys) {
            JsonElement e = o.get(key);
            if (e != null && !e.isJsonNull()) {
                return e;
            }
        }
        return null;
    }
}
