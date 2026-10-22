package com.immersivecinematics.immersive_cinematics.trigger.shared;

import com.google.gson.JsonObject;
import net.minecraft.util.Mth;

/**
 * 判定源共享几何核：触发器侧（服务端 {@code Evaluators}）与规则侧（客户端 {@code ClientConditions}）
 * 共用的纯函数——只吃 JSON 条件 + 数值输入，不碰玩家 / 世界对象。
 *
 * 本类由 {@code Evaluators} 逐位等值平移而来（服务端改委托，行为不变）。
 *
 * 入口：{@link #matchesId}（id 通配）、{@link #inBox} / {@link #inRadius}（区域）、
 * {@link #expandConditions}（区域外扩）、{@link #facingInRange}（朝向区间，含跨 ±180° 环绕）。
 * 字段名与取值语义以 {@code TriggerSchemas} 为单源。
 *
 * 分配契约：除 {@link #expandConditions} 外只读输入、不新建集合、不装箱（帧内路径安全）；
 * {@link #expandConditions} 按契约产出新对象，只在注册 / 校验期调用。
 * 线程契约：无状态静态函数，任意线程可调。
 */
public final class GeometryConditions {

    private GeometryConditions() {}

    /**
     * id 通配匹配：{@code *} = 全匹配；{@code ns:*} = 命名空间前缀；无 {@code :} = 子串包含；
     * 其余 = 全等。用于 dimension / biome / 物品等 id 条件。
     */
    public static boolean matchesId(String actual, String pattern) {
        if (pattern.equals("*") || pattern.equals(actual)) return true;
        if (pattern.endsWith(":*")) {
            return actual.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        if (!pattern.contains(":")) {
            return actual.contains(pattern);
        }
        return false;
    }

    /**
     * 点是否落在轴对齐方体内（含边界）：corner1 / corner2 各轴分量先 min/max 归一，故角落点顺序无关。
     * 任一角落缺 x/y/z 分量 → false（条件不满足）。
     */
    public static boolean inBox(double x, double y, double z, JsonObject c1, JsonObject c2) {
        if (c1 == null || c2 == null
                || !c1.has("x") || !c1.has("y") || !c1.has("z")
                || !c2.has("x") || !c2.has("y") || !c2.has("z")) {
            return false;
        }
        double minX = Math.min(c1.get("x").getAsDouble(), c2.get("x").getAsDouble());
        double maxX = Math.max(c1.get("x").getAsDouble(), c2.get("x").getAsDouble());
        double minY = Math.min(c1.get("y").getAsDouble(), c2.get("y").getAsDouble());
        double maxY = Math.max(c1.get("y").getAsDouble(), c2.get("y").getAsDouble());
        double minZ = Math.min(c1.get("z").getAsDouble(), c2.get("z").getAsDouble());
        double maxZ = Math.max(c1.get("z").getAsDouble(), c2.get("z").getAsDouble());
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** 点到 {@code pos} 的距离是否 ≤ {@code radius}（球，含边界）；{@code pos} 缺 x/y/z 分量 → false。 */
    public static boolean inRadius(double x, double y, double z, JsonObject pos, double radius) {
        if (pos == null || !pos.has("x") || !pos.has("y") || !pos.has("z")) {
            return false;
        }
        double px = pos.get("x").getAsDouble();
        double py = pos.get("y").getAsDouble();
        double pz = pos.get("z").getAsDouble();
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /**
     * 区域条件外扩 {@code buffer} 格（触发器 {@code exit_buffer} 用）：球（position+radius）扩半径，
     * 方体（corner1+corner2）沿三轴向外扩；两者都没写 / 结构不全 → {@code null}（调用方视为无外扩）。
     *
     * @return 外扩后的条件副本（新对象；原条件不改）
     */
    public static JsonObject expandConditions(JsonObject c, float buffer) {
        if (c == null || buffer <= 0f) return null;
        if (!c.has("corner1") || !c.has("corner2")) {
            if (c.has("position") && c.get("position").isJsonObject()
                    && c.has("radius") && c.get("radius").isJsonPrimitive()
                    && c.get("radius").getAsJsonPrimitive().isNumber()) {
                JsonObject expanded = c.deepCopy();
                expanded.addProperty("radius", c.get("radius").getAsFloat() + buffer);
                return expanded;
            }
            return null;
        }
        if (!c.get("corner1").isJsonObject() || !c.get("corner2").isJsonObject()) {
            return null;
        }
        JsonObject result = c.deepCopy();
        JsonObject c1 = result.getAsJsonObject("corner1");
        JsonObject c2 = result.getAsJsonObject("corner2");
        expandAxis(c1, c2, "x", buffer);
        expandAxis(c1, c2, "y", buffer);
        expandAxis(c1, c2, "z", buffer);
        return result;
    }

    /**
     * 沿单个轴把方体向外扩 buffer：先对 corner1/corner2 在该轴的分量做 min/max 归一化，
     * 保证无论角落点写入顺序如何，该轴都是向外扩大（与 {@link #inBox} 的顺序无关语义一致）。
     */
    private static void expandAxis(JsonObject c1, JsonObject c2, String axis, float buffer) {
        boolean has1 = c1.has(axis);
        boolean has2 = c2.has(axis);
        if (has1 && has2) {
            double a = c1.get(axis).getAsDouble();
            double b = c2.get(axis).getAsDouble();
            c1.addProperty(axis, Math.min(a, b) - buffer);
            c2.addProperty(axis, Math.max(a, b) + buffer);
        } else if (has1) {
            c1.addProperty(axis, c1.get(axis).getAsDouble() - buffer);
        } else if (has2) {
            c2.addProperty(axis, c2.get(axis).getAsDouble() + buffer);
        }
    }

    /**
     * 朝向是否落在 (yaw1, pitch1) → (yaw2, pitch2) 区间内：pitch 取 min/max（顺序无关，正 = 向下看）；
     * yaw 从 yaw1 顺时针扫到 yaw2（跨 ±180° 自然环绕），起止相同 = 整圈（不限制）。
     *
     * @param yaw   实际 yaw（度，任意范围；内部 wrap 到 ±180°）
     * @param pitch 实际 pitch（度）
     */
    public static boolean facingInRange(float yaw, float pitch,
                                        float yaw1, float pitch1, float yaw2, float pitch2) {
        if (pitch < Math.min(pitch1, pitch2) || pitch > Math.max(pitch1, pitch2)) return false;

        float wrappedYaw = Mth.wrapDegrees(yaw);
        float w1 = Mth.wrapDegrees(yaw1);
        float w2 = Mth.wrapDegrees(yaw2);
        float span = Mth.wrapDegrees(w2 - w1);
        if (span == 0f) return true;                 // 起止相同：整圈，不限制朝向
        float delta = Mth.wrapDegrees(wrappedYaw - w1);   // 从起点顺时针走过的角度
        if (span > 0f) return delta >= 0f && delta <= span;
        // span < 0：顺时针弧 > 180°（= span + 360），delta 落在补弧 (span, 0) 之外即为真
        return delta >= 0f || delta <= span;
    }
}
