package com.immersivecinematics.migrate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 旧 clip 级 {@code curve} → 脚本级 {@code paths[]}。
 *
 * <p>每个相邻全局帧对生成一条 path；两端帧的 {@code position} 必须可解析且没有
 * {@code follow=entity}，否则该段不生成（旧运行时该段忽略 curve）。</p>
 */
final class CurveMapper {

    private final Report report;

    CurveMapper(Report report) {
        this.report = report;
    }

    /**
     * @return 生成的 path 条数
     */
    int apply(JsonObject root, String clipId, List<JsonObject> kfs, JsonElement curve,
              String scriptPath) {
        if (curve == null || !curve.isJsonObject() || kfs.size() < 2) {
            return 0;
        }
        boolean anyPosition = false;
        for (JsonObject kf : kfs) {
            if (isPosition(kf.get("position"))) {
                anyPosition = true;
                break;
            }
        }
        if (!anyPosition) {
            report.manual(scriptPath + " " + clipId + ": 片段没有 position 通道，curve 不生成 paths");
            return 0;
        }

        JsonArray paths = paths(root);
        int count = 0;
        for (int i = 0; i < kfs.size() - 1; i++) {
            JsonObject from = kfs.get(i);
            JsonObject to = kfs.get(i + 1);
            if (!isPosition(from.get("position")) || !isPosition(to.get("position"))) {
                report.manual(scriptPath + " " + clipId + ": 段 " + i
                        + " 端点 position 不可解析，curve 段不生成");
                continue;
            }
            if ("entity".equals(ObjectMapper.str(from, "follow", "none"))
                    || "entity".equals(ObjectMapper.str(to, "follow", "none"))) {
                report.manual(scriptPath + " " + clipId + ": 段 " + i
                        + " 端点为 follow=entity，curve 段不生成（旧运行时忽略）");
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "p_" + clipId + "_" + i);
            entry.addProperty("clip", clipId);
            entry.addProperty("from", ObjectMapper.time(from));
            entry.addProperty("to", ObjectMapper.time(to));
            JsonArray axes = new JsonArray();
            axes.add("x");
            axes.add("y");
            axes.add("z");
            entry.add("axes", axes);
            entry.add("geometry", curve.deepCopy());
            JsonObject timing = new JsonObject();
            timing.addProperty("type", "linear");
            entry.add("timing", timing);
            paths.add(entry);
            count++;
        }
        return count;
    }

    private static JsonArray paths(JsonObject root) {
        JsonElement existing = root.get("paths");
        if (existing != null && existing.isJsonArray()) {
            return existing.getAsJsonArray();
        }
        JsonArray paths = new JsonArray();
        root.add("paths", paths);
        return paths;
    }

    private static boolean isPosition(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return false;
        }
        JsonObject p = e.getAsJsonObject();
        return p.has("x") || p.has("y") || p.has("z")
                || p.has("dx") || p.has("dy") || p.has("dz")
                || p.has("fwd") || p.has("up") || p.has("right");
    }
}
