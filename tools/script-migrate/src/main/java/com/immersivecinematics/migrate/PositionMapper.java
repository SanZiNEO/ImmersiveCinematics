package com.immersivecinematics.migrate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 旧 position 模型 → 新 position 对象。
 *
 * <p>旧语义：{@code position} 键集决定形态（x/y/z 绝对；dx/dy/dz 相对世界轴；
 * fwd/up/right 相对基准系），{@code follow=entity} 优先；{@code facing_origin} 决定原点。
 * 新语义：{@code position_mode} + {@code position_space} + {@code position_origin}。</p>
 */
final class PositionMapper {

    private final Schema schema;
    private final Report report;

    PositionMapper(Schema schema, Report report) {
        this.schema = schema;
        this.report = report;
    }

    private static final class Frame {
        String mode = "relative";
        String space = "world";
        String origin = "";
        double originX;
        double originY;
        double originZ;
        boolean hasOriginXYZ;
        boolean freeze;
        double x;
        double y;
        double z;
        String facingSource = "auto";
        String facingTarget = "";
    }

    void apply(TrackTypeId type, List<JsonObject> kfs, JsonObject objects,
               String scriptPath, String clipId) {
        if (!TrackTypeId.CAMERA.equals(type) || kfs.isEmpty()) {
            return;
        }
        boolean anyPosition = false;
        for (JsonObject kf : kfs) {
            if (kf.get("position") != null && kf.get("position").isJsonObject()) {
                anyPosition = true;
                break;
            }
        }
        if (!anyPosition) {
            return;
        }

        List<Frame> frames = new ArrayList<>(kfs.size());
        Frame previous = null;
        Set<String> modeConflicts = new LinkedHashSet<>();
        int filled = 0;
        for (JsonObject kf : kfs) {
            Frame f = frame(kf, previous, scriptPath, clipId, modeConflicts);
            if (f == null) {
                // position 缺失：沿用前一帧形态，三轴 0
                f = previous != null ? copy(previous) : new Frame();
                f.x = 0;
                f.y = 0;
                f.z = 0;
                filled++;
                report.sparse(scriptPath + " " + clipId + ": position 在 t="
                        + ObjectMapper.time(kf) + " 缺失，按 0 补齐");
            }
            frames.add(f);
            previous = f;
        }

        write(type, "position.x", kfs, frames, f -> new JsonPrimitive((float) f.x), objects);
        write(type, "position.y", kfs, frames, f -> new JsonPrimitive((float) f.y), objects);
        write(type, "position.z", kfs, frames, f -> new JsonPrimitive((float) f.z), objects);
        write(type, "position_mode", kfs, frames, f -> new JsonPrimitive(f.mode), objects);
        write(type, "position_space", kfs, frames, f -> new JsonPrimitive(f.space), objects);

        boolean anyOrigin = false;
        boolean allEntity = true;
        boolean anyFreeze = false;
        boolean anyLine = false;
        boolean allBase = true;
        for (Frame f : frames) {
            if (!f.origin.isEmpty()) {
                anyOrigin = true;
            }
            if (!isEntitySelector(f.origin)) {
                allEntity = false;
            }
            if (f.freeze) {
                anyFreeze = true;
            }
            if ("line".equals(f.facingSource)) {
                anyLine = true;
            }
            if (!"base".equals(f.space)) {
                allBase = false;
            }
        }
        if (anyOrigin) {
            write(type, "position_origin", kfs, frames, f -> new JsonPrimitive(f.origin), objects);
        }
        if (frames.stream().anyMatch(f -> f.hasOriginXYZ)) {
            write(type, "position_origin_x", kfs, frames,
                    f -> new JsonPrimitive((float) f.originX), objects);
            write(type, "position_origin_y", kfs, frames,
                    f -> new JsonPrimitive((float) f.originY), objects);
            write(type, "position_origin_z", kfs, frames,
                    f -> new JsonPrimitive((float) f.originZ), objects);
        }
        if (anyFreeze && allEntity) {
            write(type, "position_origin_freeze", kfs, frames,
                    f -> new JsonPrimitive(f.freeze), objects);
        } else if (anyFreeze) {
            report.manual(scriptPath + " " + clipId
                    + ": position_origin_freeze 只在部分帧成立（非全部实体选择器），未写入");
        }
        if (anyLine && allBase) {
            write(type, "facing_source", kfs, frames,
                    f -> new JsonPrimitive(f.facingSource), objects);
            write(type, "facing_target", kfs, frames,
                    f -> new JsonPrimitive(f.facingTarget), objects);
        } else if (anyLine) {
            report.manual(scriptPath + " " + clipId
                    + ": facing_source/facing_target 只在部分帧成立（非全部 base），未写入");
        }

        for (String conflict : modeConflicts) {
            report.manual(scriptPath + " " + clipId + ": 旧 position_mode 与键集推导不一致（"
                    + conflict + "），按键集口径迁移");
        }
        if (filled > 0) {
            report.sparse(scriptPath + " " + clipId + ": 位置缺失帧 " + filled + " 个");
        }
    }

    private Frame frame(JsonObject kf, Frame previous, String scriptPath, String clipId,
                        Set<String> modeConflicts) {
        JsonElement pe = kf.get("position");
        if (pe == null || !pe.isJsonObject()) {
            return null;
        }
        JsonObject pos = pe.getAsJsonObject();
        Frame f = new Frame();

        String follow = ObjectMapper.str(kf, "follow", "none");
        String followSelector = ObjectMapper.str(kf, "follow_selector", "@p");
        String originRaw = ObjectMapper.str(kf, "facing_origin", "");
        String originTime = ObjectMapper.str(kf, "facing_origin_time", "live");
        String facingTarget = ObjectMapper.str(kf, "facing_target", "");

        boolean hasX = pos.has("x") || pos.has("y") || pos.has("z");
        boolean hasD = pos.has("dx") || pos.has("dy") || pos.has("dz");
        boolean hasBase = pos.has("fwd") || pos.has("up") || pos.has("right");

        if ("entity".equals(follow)) {
            f.mode = "relative";
            f.space = "world";
            f.origin = followSelector.isBlank() ? "@p" : followSelector;
            f.freeze = false;
            f.x = axis(pos, "x", "dx", "fwd");
            f.y = axis(pos, "y", "dy", "up");
            f.z = axis(pos, "z", "dz", "right");
            report.manual(scriptPath + " " + clipId
                    + ": follow=entity → 动态原点 '" + f.origin + "'，人工复核");
        } else if (hasX) {
            f.mode = "absolute";
            f.space = "world";
            f.x = axis(pos, "x", "dx", "fwd");
            f.y = axis(pos, "y", "dy", "up");
            f.z = axis(pos, "z", "dz", "right");
            if (!originRaw.isEmpty()) {
                report.manual(scriptPath + " " + clipId
                        + ": absolute 位置下旧 facing_origin='" + originRaw + "' 被丢弃");
            }
            if (!facingTarget.isEmpty()) {
                report.manual(scriptPath + " " + clipId
                        + ": world 空间下旧 facing_target='" + facingTarget + "' 被丢弃");
            }
        } else if (hasD) {
            f.mode = "relative";
            f.space = "world";
            f.x = axis(pos, "x", "dx", "fwd");
            f.y = axis(pos, "y", "dy", "up");
            f.z = axis(pos, "z", "dz", "right");
            applyOrigin(f, originRaw, originTime, kf, scriptPath, clipId);
            if (!facingTarget.isEmpty()) {
                report.manual(scriptPath + " " + clipId
                        + ": world 空间下旧 facing_target='" + facingTarget + "' 被丢弃");
            }
        } else if (hasBase) {
            f.mode = "relative";
            f.space = "base";
            f.x = axis(pos, "x", "dx", "fwd");
            f.y = axis(pos, "y", "dy", "up");
            f.z = axis(pos, "z", "dz", "right");
            applyOrigin(f, originRaw, originTime, kf, scriptPath, clipId);
            if (!facingTarget.isEmpty()) {
                f.facingSource = "line";
                f.facingTarget = facingTarget;
            }
        } else {
            f.mode = "absolute";
            f.space = "world";
        }

        String legacyMode = ObjectMapper.str(kf, "position_mode", null);
        if (legacyMode != null && !legacyMode.equals(f.mode)) {
            modeConflicts.add("legacy=" + legacyMode + ", derived=" + f.mode);
        }
        return f;
    }

    private void applyOrigin(Frame f, String originRaw, String originTime, JsonObject kf,
                             String scriptPath, String clipId) {
        if (originRaw == null || originRaw.isBlank()) {
            return;
        }
        if ("coordinate".equals(originRaw)) {
            f.origin = "coordinate";
            f.hasOriginXYZ = true;
            f.originX = number(kf, "facing_origin_x", 0);
            f.originY = number(kf, "facing_origin_y", 0);
            f.originZ = number(kf, "facing_origin_z", 0);
        } else {
            f.origin = originRaw;
        }
        if ("snapshot".equals(originTime)) {
            if (isEntitySelector(originRaw)) {
                f.freeze = true;
            } else {
                report.manual(scriptPath + " " + clipId + ": facing_origin_time=snapshot 但原点不是实体选择器（"
                        + originRaw + "），不写 position_origin_freeze");
            }
        }
    }

    private void write(TrackTypeId type, String path, List<JsonObject> kfs, List<Frame> frames,
                       java.util.function.Function<Frame, JsonElement> getter, JsonObject objects) {
        List<JsonElement> values = new ArrayList<>(frames.size());
        for (Frame f : frames) {
            values.add(getter.apply(f));
        }
        ObjectMapper.putSeries(schema, report, type, path, kfs, values, objects);
    }

    private static Frame copy(Frame src) {
        Frame f = new Frame();
        f.mode = src.mode;
        f.space = src.space;
        f.origin = src.origin;
        f.originX = src.originX;
        f.originY = src.originY;
        f.originZ = src.originZ;
        f.hasOriginXYZ = src.hasOriginXYZ;
        f.freeze = src.freeze;
        f.facingSource = src.facingSource;
        f.facingTarget = src.facingTarget;
        return f;
    }

    private static boolean isEntitySelector(String origin) {
        return origin != null && origin.startsWith("@");
    }

    private static double axis(JsonObject pos, String key, String delta, String base) {
        JsonElement e = pos.get(key);
        if (e == null || e.isJsonNull()) {
            e = pos.get(delta);
        }
        if (e == null || e.isJsonNull()) {
            e = pos.get(base);
        }
        if (e == null || e.isJsonNull()) {
            return 0;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static double number(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }
}
