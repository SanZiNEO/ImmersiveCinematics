package com.immersivecinematics.immersive_cinematics.client.rule;

import com.immersivecinematics.immersive_cinematics.api.CameraState;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.trigger.server.evaluator.Evaluators;
import com.immersivecinematics.immersive_cinematics.trigger.shared.GeometryConditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/**
 * 客户端判定源（0.3.6 C6-2）：{@code clip.rules} 条件在本端的求值薄镜像——读本地玩家 / 相机状态，
 * 纯几何与 id 匹配调用 {@link GeometryConditions}（与触发器侧同一核，语义同源）。
 *
 * v1 判定源 = {@link #V1_TYPES} 八项：{@code time}（片段本地时钟 = timelineTime − clip.startTime）、
 * {@code location} / {@code facing} / {@code biome} / {@code dimension}（镜像触发器同名词汇）、
 * {@code camera_pos} / {@code camera_facing} / {@code camera_fov}（相机状态，新增）。组合器 {@code all_of}
 * / {@code any} 用触发器同一份 {@code list} 语义，嵌套上限 {@link #MAX_COMBINATION_DEPTH}（与触发器侧同值）。
 * 事件类 / tracker 类 / 前置条件类判定源 v1 不支持（validator 报 issue，运行期按不满足处理）。
 *
 * 分配契约：{@link #evaluate} 只读输入、不新建集合、不装箱（帧内路径安全）；维度 / 群系 id 字符串在
 * {@link LocalContext} 内按变化记忆，稳态零分配。线程契约：仅客户端主线程。
 */
public final class ClientConditions {

    /** v1 判定源白名单（validator 与求值侧同一单源；组合器另计）。 */
    public static final List<String> V1_TYPES = List.of(
            "time", "location", "facing", "biome", "dimension", "camera_pos", "camera_facing", "camera_fov");

    /** 组合器嵌套上限（含顶层为第 1 层）：与触发器侧同一常量（{@link Evaluators} 编译期内联）。 */
    public static final int MAX_COMBINATION_DEPTH = Evaluators.MAX_COMBINATION_DEPTH;

    /** 组合器子条件的空数据对象（只读共享：本类只调 has/get，不写）。 */
    private static final JsonObject NO_DATA = new JsonObject();

    private ClientConditions() {}

    /**
     * 求值一条条件：未知 / v1 不支持的 type、结构不全一律 false（不抛——非法 JSON 不该炸帧）。
     *
     * @param condition     条件原文（{@code ClipRule} 存的那份）
     * @param ctx           本帧世界状态（玩家 / 相机）
     * @param clipLocalTime 片段本地时钟（秒）= timelineTime − clip.startTime
     */
    public static boolean evaluate(JsonObject condition, Context ctx, float clipLocalTime) {
        if (condition == null || ctx == null || !ctx.available()) return false;
        return evaluateNode(condition, ctx, clipLocalTime, 1);
    }

    /** 单个条件节点（叶子或组合器）：{@code depth} = 组合器层数（顶层 = 1），叶子不参与。 */
    private static boolean evaluateNode(JsonObject c, Context ctx, float localTime, int depth) {
        String type = str(c, "type");
        if (type == null) return false;
        return switch (type) {
            case "time" -> evalTime(c, localTime);
            case "location" -> evalLocation(c, ctx);
            case "facing" -> evalFacing(c, ctx.yaw(), ctx.pitch());
            case "biome" -> evalBiome(c, ctx);
            case "dimension" -> evalDimension(c, ctx);
            case "camera_pos" -> evalCameraPos(c, ctx);
            case "camera_facing" -> ctx.hasCamera()
                    && evalFacing(c, ctx.cameraYaw(), ctx.cameraPitch());
            case "camera_fov" -> evalCameraFov(c, ctx);
            case "all_of" -> evalCombination(c, ctx, localTime, depth, true);
            case "any" -> evalCombination(c, ctx, localTime, depth, false);
            default -> false;
        };
    }

    /**
     * 组合器（all_of = 全真 / any = 任一真，命中即短路）：list 元素 = {@code {type, conditions}}，
     * 与触发器侧同一结构；子条件为组合器时递归下钻（层数用尽即不满足）。
     */
    private static boolean evalCombination(JsonObject c, Context ctx, float localTime, int depth, boolean allOf) {
        if (c == null || !c.has("list") || !c.get("list").isJsonArray()) return false;
        JsonArray list = c.getAsJsonArray("list");
        if (list.isEmpty()) return false;
        for (int i = 0; i < list.size(); i++) {
            JsonElement el = list.get(i);
            boolean hit = el.isJsonObject() && evalChild(el.getAsJsonObject(), ctx, localTime, depth);
            if (allOf && !hit) return false;
            if (!allOf && hit) return true;
        }
        return allOf;
    }

    /** 组合器子条件：嵌套组合器走 {@code conditions} 递归，叶子条件按 {@code conditions} 数据求值。 */
    private static boolean evalChild(JsonObject sub, Context ctx, float localTime, int depth) {
        String type = str(sub, "type");
        if (type == null) return false;
        boolean nested = "all_of".equals(type) || "any".equals(type);
        if (nested) {
            if (depth >= MAX_COMBINATION_DEPTH) return false;
            if (!sub.has("conditions") || !sub.get("conditions").isJsonObject()) return false;
            return evalCombination(sub.getAsJsonObject("conditions"), ctx, localTime, depth + 1,
                    "all_of".equals(type));
        }
        JsonObject data = sub.has("conditions") && sub.get("conditions").isJsonObject()
                ? sub.getAsJsonObject("conditions") : NO_DATA;
        return switch (type) {
            case "time" -> evalTime(data, localTime);
            case "location" -> evalLocation(data, ctx);
            case "facing" -> evalFacing(data, ctx.yaw(), ctx.pitch());
            case "biome" -> evalBiome(data, ctx);
            case "dimension" -> evalDimension(data, ctx);
            case "camera_pos" -> evalCameraPos(data, ctx);
            case "camera_facing" -> ctx.hasCamera() && evalFacing(data, ctx.cameraYaw(), ctx.cameraPitch());
            case "camera_fov" -> evalCameraFov(data, ctx);
            default -> false;
        };
    }

    /**
     * {@code time}：{@code at} = 片段本地时钟 ≥ 该点；{@code from} / {@code to} = 闭区间（可单边）。
     * 两者都缺 → false（结构不全）。挂起期间本地时钟冻结，故 time 条件天然不走（设计 §4）。
     */
    private static boolean evalTime(JsonObject c, float localTime) {
        if (isNumber(c, "at")) return localTime >= c.get("at").getAsFloat();
        boolean from = isNumber(c, "from");
        boolean to = isNumber(c, "to");
        if (!from && !to) return false;
        if (from && localTime < c.get("from").getAsFloat()) return false;
        return !to || localTime <= c.get("to").getAsFloat();
    }

    /** {@code location}：dimension（全等）→ corner1/corner2 方体 → position+radius 球，与触发器同口径。 */
    private static boolean evalLocation(JsonObject c, Context ctx) {
        if (c.has("dimension")) {
            String target = str(c, "dimension");
            if (target == null || !ctx.dimensionId().equals(target)) return false;
        }
        if (c.has("corner1") && c.has("corner2")
                && c.get("corner1").isJsonObject() && c.get("corner2").isJsonObject()) {
            if (GeometryConditions.inBox(ctx.x(), ctx.y(), ctx.z(),
                    c.getAsJsonObject("corner1"), c.getAsJsonObject("corner2"))) {
                return true;
            }
        }
        if (c.has("position")) {
            if (!c.get("position").isJsonObject()) return false;
            double radius = isNumber(c, "radius") ? c.get("radius").getAsDouble() : 0.0;
            return GeometryConditions.inRadius(ctx.x(), ctx.y(), ctx.z(),
                    c.getAsJsonObject("position"), radius);
        }
        return c.has("dimension");
    }

    /** {@code facing}：玩家视线（yaw / pitch）是否落在区间内——区间数学与触发器同核（跨 ±180° 环绕）。 */
    private static boolean evalFacing(JsonObject c, float yaw, float pitch) {
        if (!isNumber(c, "yaw1") || !isNumber(c, "pitch1")
                || !isNumber(c, "yaw2") || !isNumber(c, "pitch2")) {
            return false;
        }
        return GeometryConditions.facingInRange(yaw, pitch,
                c.get("yaw1").getAsFloat(), c.get("pitch1").getAsFloat(),
                c.get("yaw2").getAsFloat(), c.get("pitch2").getAsFloat());
    }

    /** {@code biome}：本地玩家所在群系的 id 按通配匹配（镜像触发器 {@code evaluateBiome}）。 */
    private static boolean evalBiome(JsonObject c, Context ctx) {
        String pattern = str(c, "biome");
        if (pattern == null) return false;
        String biome = ctx.biomeId();
        return !biome.isEmpty() && GeometryConditions.matchesId(biome, pattern);
    }

    /** {@code dimension}：本地玩家所在维度 id 按通配匹配（镜像触发器驻留型 {@code dimension}）。 */
    private static boolean evalDimension(JsonObject c, Context ctx) {
        String pattern = str(c, "dimension");
        if (pattern == null) return false;
        String dimension = ctx.dimensionId();
        return !dimension.isEmpty() && GeometryConditions.matchesId(dimension, pattern);
    }

    /** {@code camera_pos}：相机位置落在方体 / 球内（形同 location 的区域字段，无 dimension）；无相机 → false。 */
    private static boolean evalCameraPos(JsonObject c, Context ctx) {
        if (!ctx.hasCamera()) return false;
        if (c.has("corner1") && c.has("corner2")
                && c.get("corner1").isJsonObject() && c.get("corner2").isJsonObject()) {
            return GeometryConditions.inBox(ctx.cameraX(), ctx.cameraY(), ctx.cameraZ(),
                    c.getAsJsonObject("corner1"), c.getAsJsonObject("corner2"));
        }
        if (c.has("position")) {
            if (!c.get("position").isJsonObject()) return false;
            double radius = isNumber(c, "radius") ? c.get("radius").getAsDouble() : 0.0;
            return GeometryConditions.inRadius(ctx.cameraX(), ctx.cameraY(), ctx.cameraZ(),
                    c.getAsJsonObject("position"), radius);
        }
        return false;
    }

    /** {@code camera_fov}：相机 FOV（度）落在闭区间 {@code [from, to]}（可单边）；无相机 → false。 */
    private static boolean evalCameraFov(JsonObject c, Context ctx) {
        if (!ctx.hasCamera()) return false;
        boolean from = isNumber(c, "from");
        boolean to = isNumber(c, "to");
        if (!from && !to) return false;
        float fov = ctx.cameraFov();
        if (from && fov < c.get("from").getAsFloat()) return false;
        return !to || fov <= c.get("to").getAsFloat();
    }

    private static boolean isNumber(JsonObject c, String key) {
        return c != null && c.has(key) && c.get(key).isJsonPrimitive()
                && c.get(key).getAsJsonPrimitive().isNumber();
    }

    /** 字符串字段（非字符串 / 缺失 → null：调用方按结构不全处理，不抛）。 */
    private static String str(JsonObject c, String key) {
        if (c == null || !c.has(key)) return null;
        JsonElement e = c.get(key);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    /**
     * 求值上下文：本帧世界状态（玩家 / 相机）的只读面。求值侧只读不写；探针可自建实现注入固定值。
     * 分配契约：访问器不得新建集合 / 装箱（帧内逐条件调用）。
     */
    public interface Context {

        /** 每帧开始时的刷新钩子（读一次玩家 / 相机快照）；探针实现可为空。 */
        default void beginFrame() {
        }

        /** 本帧数据源是否可用（玩家 / 世界已就绪）；false = 全部条件按不满足处理。 */
        default boolean available() {
            return true;
        }

        double x();

        double y();

        double z();

        /** 玩家 yaw（度）。 */
        float yaw();

        /** 玩家 pitch（度）。 */
        float pitch();

        /** 玩家所在维度 id（如 {@code minecraft:overworld}）；不可知时为空串。 */
        String dimensionId();

        /** 玩家所在群系 id；不可知时为空串。 */
        String biomeId();

        /** 本帧是否有活跃相机状态（无 = 相机类条件一律不满足）。 */
        boolean hasCamera();

        double cameraX();

        double cameraY();

        double cameraZ();

        /** 相机 yaw（度）。 */
        float cameraYaw();

        /** 相机 pitch（度）。 */
        float cameraPitch();

        /** 相机 FOV（度）。 */
        float cameraFov();
    }

    /**
     * 客户端上下文实现：读本地玩家（位置 / 朝向 / 维度 / 群系）与相机状态（{@link CameraManager}）。
     *
     * 分配契约：{@link #beginFrame()} 每帧一次，只读字段引用（无集合 / 无装箱）；维度与群系 id 字符串按
     * 「底层键是否变化」记忆，稳态帧零分配。线程契约：仅客户端主线程。
     */
    public static final class LocalContext implements Context {

        private LocalPlayer player;
        private ClientLevel level;
        private CameraState camera;

        /** 维度 id 记忆：键对象未变则复用字符串（{@code level.dimension().location()} 同维度恒同实例）。 */
        private ResourceLocation dimensionKey;
        private String dimensionId = "";

        /** 群系 id 记忆：群系 Holder 未变则复用字符串。 */
        private Holder<Biome> biomeHolder;
        private String biomeId = "";

        /** 群系查询用的可变坐标（复用，免每帧新建 BlockPos）。 */
        private final BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();

        @Override
        public void beginFrame() {
            Minecraft mc = Minecraft.getInstance();
            player = mc != null ? mc.player : null;
            level = mc != null ? mc.level : null;
            camera = CameraManager.INSTANCE.getCameraState();
        }

        @Override
        public boolean available() {
            return player != null && level != null;
        }

        @Override
        public double x() {
            return player != null ? player.getX() : 0.0;
        }

        @Override
        public double y() {
            return player != null ? player.getY() : 0.0;
        }

        @Override
        public double z() {
            return player != null ? player.getZ() : 0.0;
        }

        @Override
        public float yaw() {
            return player != null ? player.getYRot() : 0f;
        }

        @Override
        public float pitch() {
            return player != null ? player.getXRot() : 0f;
        }

        @Override
        public String dimensionId() {
            if (level == null) return "";
            ResourceLocation key = level.dimension().location();
            if (key != dimensionKey) {
                dimensionKey = key;
                dimensionId = key.toString();
            }
            return dimensionId;
        }

        @Override
        public String biomeId() {
            if (level == null || player == null) return "";
            biomePos.set(player.getX(), player.getY(), player.getZ());
            Holder<Biome> holder = level.getBiome(biomePos);
            if (holder != biomeHolder) {
                biomeHolder = holder;
                biomeId = holder.unwrapKey().map(key -> key.location().toString()).orElse("");
            }
            return biomeId;
        }

        @Override
        public boolean hasCamera() {
            return camera != null;
        }

        @Override
        public double cameraX() {
            return camera != null ? camera.position().x : 0.0;
        }

        @Override
        public double cameraY() {
            return camera != null ? camera.position().y : 0.0;
        }

        @Override
        public double cameraZ() {
            return camera != null ? camera.position().z : 0.0;
        }

        @Override
        public float cameraYaw() {
            return camera != null ? camera.yaw() : 0f;
        }

        @Override
        public float cameraPitch() {
            return camera != null ? camera.pitch() : 0f;
        }

        @Override
        public float cameraFov() {
            return camera != null ? camera.fov() : 0f;
        }
    }
}
