package com.immersivecinematics.immersive_cinematics.webui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本架构图数据扫描（WebUI 架构图视图的数据侧，见 plans/0.3.6/editor-script-graph.md §2）。
 *
 * <p>复用 {@link ScriptFileService} 的递归列举与路径越界防护扫描 scripts 目录，产出三类数据：</p>
 * <ul>
 *   <li><b>节点</b> = 脚本：相对路径 / 所在文件夹 / meta 摘要 / 时长 / 轨道构成 / 触发器清单 / requires；</li>
 *   <li><b>边</b> = 触发器 {@code requires} 前置依赖：被依赖脚本 → 依赖方脚本（有向）；</li>
 *   <li><b>分区</b> = 文件夹：脚本所在子文件夹（{@code ""} = scripts 根）。</li>
 * </ul>
 *
 * <p>节点 id 用相对路径（唯一）；边的两端按 {@code meta.id}（脚本 id）解析——{@code requires}
 * 引用的是脚本 id 而不是路径。指向不存在脚本的 requires 保留在节点上（{@code resolved=false}）
 * 并进入 warnings，由前端按悬空边提示（与 {@code ScriptManager.loadFromDir} 的加载期校验同口径）。</p>
 *
 * <p>容错扫描：不依赖 {@code ScriptParser}（解析失败即停，是运行时口径）。写坏的脚本也要出现在图上，
 * 只是标记 {@code valid=false} + {@code error}；时长/轨道/触发器能读多少读多少。</p>
 */
public final class ScriptGraphService {

    /**
     * 内置脚本型前置条件：脚本 id 在 {@code data.script}（见 {@code TriggerRequirement}）。
     * 其他（自定义）类型没有脚本 id，不成边——与 {@code ScriptManager.scriptIdOfRequirement} 同口径。
     */
    private static final Set<String> SCRIPT_REQUIREMENT_TYPES =
            Set.of("script_played", "script_started", "script_completed");

    /** 旧语法（{@code requires: ["script_a"]}）等价的前置条件类型，见 {@code ScriptParser.parseTriggerRequires} */
    private static final String LEGACY_REQUIREMENT_TYPE = "script_played";

    /** warnings 上限：异常包（上百条坏引用）不至于把消息撑爆 */
    private static final int MAX_WARNINGS = 200;

    private ScriptGraphService() {
    }

    /** 扫描全部脚本并组装图数据：{@code {nodes, edges, folders, warnings}}。 */
    public static JsonObject buildGraph() {
        List<String> paths = ScriptFileService.listScripts();
        List<Node> nodes = new ArrayList<>();
        Map<String, Node> byScriptId = new LinkedHashMap<>();
        List<JsonObject> warnings = new ArrayList<>();

        // ── 第一遍：读文件 + 建 脚本 id → 节点 映射（边要按 id 解析，得先知道全量 id）──
        for (String path : paths) {
            Node node = new Node(path, folderOf(path));
            String json = null;
            try {
                json = ScriptFileService.loadScript(path);
            } catch (IOException e) {
                node.error = "读取失败: " + e.getMessage();
            }
            if (json != null) {
                try {
                    node.root = JsonParser.parseString(json).getAsJsonObject();
                } catch (Exception e) {
                    node.error = "JSON 解析失败: " + e.getMessage();
                }
            }
            if (node.root != null) readNode(node);
            if (node.error != null) {
                addWarning(warnings, "invalid", path, node.error);
            }
            nodes.add(node);
            if (node.scriptId != null) {
                Node prev = byScriptId.putIfAbsent(node.scriptId, node);
                if (prev != null) {
                    addWarning(warnings, "duplicate_id", path,
                            "脚本 id '" + node.scriptId + "' 与 " + prev.path + " 重复：requires 只解析到先扫到的那个");
                }
            }
        }

        // ── 第二遍：解析 requires → 有向边（被依赖 → 依赖方）+ 悬空/自引用提示 ──
        List<JsonObject> edges = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        for (Node node : nodes) {
            for (JsonObject trigger : node.triggers) {
                String triggerType = trigger.get("type").getAsString();
                for (JsonElement re : trigger.getAsJsonArray("requires")) {
                    JsonObject req = re.getAsJsonObject();
                    String targetId = req.get("script").getAsString();
                    if (targetId.isEmpty()) continue;   // 自定义前置条件没有脚本 id，不成边
                    String reqType = req.get("type").getAsString();
                    if (targetId.equals(node.scriptId)) {
                        req.addProperty("resolved", false);
                        addWarning(warnings, "self_reference", node.path,
                                "触发器 " + triggerType + " 的前置条件自引用脚本 '" + targetId + "'");
                        continue;
                    }
                    Node target = byScriptId.get(targetId);
                    if (target == null) {
                        req.addProperty("resolved", false);
                        addWarning(warnings, "dangling", node.path,
                                "触发器 " + triggerType + " 的前置条件指向不存在的脚本 '" + targetId + "'");
                        continue;
                    }
                    req.addProperty("resolved", true);
                    String key = target.path + ">" + node.path + "|" + triggerType + "|" + reqType;
                    if (!edgeKeys.add(key)) continue;   // 同一对脚本的重复前置只画一条
                    JsonObject edge = new JsonObject();
                    edge.addProperty("from", target.path);
                    edge.addProperty("to", node.path);
                    edge.addProperty("fromScript", targetId);
                    edge.addProperty("toScript", node.scriptId);
                    edge.addProperty("trigger", triggerType);
                    edge.addProperty("requirement", reqType);
                    edges.add(edge);
                }
            }
        }

        // ── 分区：按文件夹汇总（"" = scripts 根）──
        Map<String, Integer> folderCounts = new LinkedHashMap<>();
        for (Node node : nodes) {
            folderCounts.merge(node.folder, 1, Integer::sum);
        }
        List<String> folderPaths = new ArrayList<>(folderCounts.keySet());
        folderPaths.sort((a, b) -> a.isEmpty() ? (b.isEmpty() ? 0 : -1) : b.isEmpty() ? 1 : a.compareTo(b));

        JsonObject out = new JsonObject();
        JsonArray nodeArr = new JsonArray();
        for (Node node : nodes) nodeArr.add(node.toJson());
        out.add("nodes", nodeArr);
        JsonArray edgeArr = new JsonArray();
        for (JsonObject edge : edges) edgeArr.add(edge);
        out.add("edges", edgeArr);
        JsonArray folderArr = new JsonArray();
        for (String folder : folderPaths) {
            JsonObject f = new JsonObject();
            f.addProperty("path", folder);
            f.addProperty("count", folderCounts.get(folder));
            folderArr.add(f);
        }
        out.add("folders", folderArr);
        JsonArray warnArr = new JsonArray();
        for (JsonObject w : warnings) warnArr.add(w);
        out.add("warnings", warnArr);
        return out;
    }

    // ========== 节点读取（容错，全部字段可缺）==========

    private static void readNode(Node node) {
        JsonObject root = node.root;
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("meta");
            node.scriptId = str(meta, "id");
            if (node.scriptId == null || node.scriptId.isEmpty()) {
                node.scriptId = null;
                node.error = "meta.id 缺失";
            }
            node.name = orEmpty(str(meta, "name"));
            node.author = orEmpty(str(meta, "author"));
            // 文本资源：description 顺手支持 @lang:<key>（§4 范围表）；节点 tooltip / 属性面板可见
            node.description = com.immersivecinematics.immersive_cinematics.util.LangResources
                    .resolve(orEmpty(str(meta, "description")));
            node.dimension = orEmpty(str(meta, "dimension"));
            node.priority = intOf(meta, "priority");
            readTriggers(node, meta);
        } else if (node.error == null) {
            node.error = "meta 缺失或不是对象";
        }

        if (!root.has("timeline") || !root.get("timeline").isJsonObject()) {
            if (node.error == null) node.error = "timeline 缺失或不是对象";
            return;
        }
        JsonObject timeline = root.getAsJsonObject("timeline");
        // 时长：timeline.total_duration（负数 = 无限时长；运行时口径见 Timeline.getTotalDuration）
        node.duration = floatOf(timeline, "total_duration");
        node.infinite = node.duration < 0f;
        if (timeline.has("tracks") && timeline.get("tracks").isJsonArray()) {
            for (JsonElement te : timeline.getAsJsonArray("tracks")) {
                if (!te.isJsonObject()) continue;
                JsonObject track = te.getAsJsonObject();
                JsonObject t = new JsonObject();
                t.addProperty("type", orEmpty(str(track, "type")));
                t.addProperty("clips", track.has("clips") && track.get("clips").isJsonArray()
                        ? track.getAsJsonArray("clips").size() : 0);
                node.tracks.add(t);
            }
        }
    }

    /** 触发器清单 + 每个触发器的 requires（元素 = 字符串脚本 id，或对象 {type, script}）。 */
    private static void readTriggers(Node node, JsonObject meta) {
        if (!meta.has("triggers") || !meta.get("triggers").isJsonArray()) return;
        for (JsonElement te : meta.getAsJsonArray("triggers")) {
            if (!te.isJsonObject()) continue;
            JsonObject trigger = te.getAsJsonObject();
            JsonObject out = new JsonObject();
            out.addProperty("type", orEmpty(str(trigger, "type")));
            out.addProperty("repeatable", boolOf(trigger, "repeatable"));
            JsonArray reqs = new JsonArray();
            if (trigger.has("requires") && trigger.get("requires").isJsonArray()) {
                for (JsonElement re : trigger.getAsJsonArray("requires")) {
                    JsonObject req = readRequirement(re);
                    if (req == null) continue;
                    reqs.add(req);
                    String script = req.get("script").getAsString();
                    if (!script.isEmpty() && !node.requires.contains(script)) {
                        node.requires.add(script);
                    }
                }
            }
            out.add("requires", reqs);
            node.triggers.add(out);
        }
    }

    /**
     * 单个 requires 元素 → {@code {type, script}}（解析结果 resolved 由第二遍填）。
     * 字符串元素按旧语法等价 {@code {"type":"script_played","script":"..."}}；
     * 对象元素只有内置 script_* 类型带脚本 id，自定义类型 script 留空。
     */
    private static JsonObject readRequirement(JsonElement re) {
        JsonObject out = new JsonObject();
        if (re.isJsonPrimitive() && re.getAsJsonPrimitive().isString()) {
            String id = re.getAsString();
            if (id.isEmpty()) return null;
            out.addProperty("type", LEGACY_REQUIREMENT_TYPE);
            out.addProperty("script", id);
            return out;
        }
        if (!re.isJsonObject()) return null;
        JsonObject req = re.getAsJsonObject();
        String type = orEmpty(str(req, "type"));
        out.addProperty("type", type);
        out.addProperty("script", SCRIPT_REQUIREMENT_TYPES.contains(type) ? orEmpty(str(req, "script")) : "");
        return out;
    }

    // ========== 小工具 ==========

    /** {@code "chapter/boss.json"} → {@code "chapter"}；根目录脚本 → {@code ""} */
    private static String folderOf(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "" : relativePath.substring(0, slash);
    }

    private static void addWarning(List<JsonObject> warnings, String kind, String path, String message) {
        if (warnings.size() >= MAX_WARNINGS) return;
        JsonObject w = new JsonObject();
        w.addProperty("kind", kind);
        w.addProperty("path", path);
        w.addProperty("message", message);
        warnings.add(w);
    }

    private static String str(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean boolOf(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static int intOf(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        try {
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static float floatOf(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        try {
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : 0f;
        } catch (Exception ignored) {
            return 0f;
        }
    }

    /** 扫描中间态：一个脚本的原始 JSON + 抽出的展示字段。 */
    private static final class Node {
        /** 节点 id = scripts 下的相对路径（唯一） */
        final String path;
        final String folder;
        final List<JsonObject> tracks = new ArrayList<>();
        final List<JsonObject> triggers = new ArrayList<>();
        final List<String> requires = new ArrayList<>();
        JsonObject root;
        String scriptId;
        String name = "";
        String author = "";
        String description = "";
        String dimension = "";
        int priority;
        float duration;
        boolean infinite;
        String error;

        Node(String path, String folder) {
            this.path = path;
            this.folder = folder;
        }

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("id", path);
            out.addProperty("path", path);
            out.addProperty("folder", folder);
            out.addProperty("scriptId", scriptId);
            out.addProperty("name", name.isEmpty() ? path : name);
            out.addProperty("author", author);
            out.addProperty("description", description);
            out.addProperty("dimension", dimension);
            out.addProperty("priority", priority);
            out.addProperty("duration", duration);
            out.addProperty("infinite", infinite);
            out.addProperty("valid", error == null);
            if (error != null) out.addProperty("error", error);
            JsonArray trackArr = new JsonArray();
            for (JsonObject t : tracks) trackArr.add(t);
            out.add("tracks", trackArr);
            JsonArray triggerArr = new JsonArray();
            for (JsonObject t : triggers) triggerArr.add(t);
            out.add("triggers", triggerArr);
            JsonArray reqArr = new JsonArray();
            for (String r : requires) reqArr.add(r);
            out.add("requires", reqArr);
            return out;
        }
    }
}
