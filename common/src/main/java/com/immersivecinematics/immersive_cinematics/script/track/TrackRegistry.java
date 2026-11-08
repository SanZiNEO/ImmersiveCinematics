package com.immersivecinematics.immersive_cinematics.script.track;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.script.ScriptPlayer;
import com.immersivecinematics.immersive_cinematics.script.schema.ObjectSchemaValidator;
import com.immersivecinematics.immersive_cinematics.script.schema.TrackSchemas;
import com.immersivecinematics.immersive_cinematics.script.schema.TrackTypeSchema;
import com.immersivecinematics.immersive_cinematics.spi.ObjectDef;
import com.immersivecinematics.immersive_cinematics.spi.TrackSchema;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轨道类型注册表：id → （schema + 驱动工厂 + 槽位布局）。
 *
 * <p>插入顺序 = 编辑器 / 导出的轨道顺序。内置 7 轨在 {@link #ensureBuiltins()} 首次调用时按
 * {@link BuiltinTracks#ids()} 的顺序登记（schema 来自 {@code TrackSchemas}，驱动来自
 * {@link BuiltinTrackDrivers}），并立刻跑一次 {@link ObjectSchemaValidator#validateOrThrow} 做
 * 声明错误的 fail-fast（与旧 {@code SchemaRegistry} 静态块同一口径）。</p>
 *
 * <p>第三方注册走 {@link #register}，只允许在模组初始化期（{@link #freeze()} 之前）完成；
 * 注册时逐条做结构自检，失败立即抛异常（不把坏 schema 留到运行期）。</p>
 */
public final class TrackRegistry {

    /** 内置轨驱动工厂：{@code (player, originPos, cameraManager, overlayManager, trackIndex) -> TrackDriver}。 */
    @FunctionalInterface
    public interface TrackDriverFactory {
        TrackDriver create(ScriptPlayer player, Vec3 originPos, CameraManager cameraManager,
                           OverlayManager overlayManager, int trackIndex);
    }

    /** 第三方轨播放回调工厂（每段活跃期新建一个 {@code spi.TrackPlayer}）。 */
    @FunctionalInterface
    public interface TrackPlayerFactory {
        com.immersivecinematics.immersive_cinematics.spi.TrackPlayer create();
    }

    /** 一条登记：schema + 两套工厂 + 注册期编译的路径槽位 + 是否内置。 */
    private record Entry(TrackTypeSchema schema, TrackDriverFactory driverFactory,
                         TrackPlayerFactory playerFactory, Map<String, Integer> pathSlots, boolean builtin) {}

    private static final Map<TrackTypeId, Entry> ENTRIES = new LinkedHashMap<>();

    private static volatile boolean builtinsReady;
    private static volatile boolean frozen;

    private TrackRegistry() {}

    /**
     * 登记内置 7 轨（幂等）。首次调用时按固定顺序登记并做一次全量 schema 自检。
     *
     * <p>可重入安全：内置条目先入表、{@code builtinsReady} 先置位，再做自检——自检期间若有嵌套调用
     * （例如槽位布局初始化）直接返回，不会递归。</p>
     *
     * @throws IllegalStateException 内置 schema 自检失败（声明错误在启动期暴露）
     */
    public static void ensureBuiltins() {
        if (builtinsReady) {
            return;
        }
        synchronized (TrackRegistry.class) {
            if (builtinsReady) {
                return;
            }
            Map<TrackTypeId, TrackTypeSchema> schemas = TrackSchemas.all();
            for (TrackTypeId id : BuiltinTracks.ids()) {
                TrackTypeSchema schema = schemas.get(id);
                if (schema == null) {
                    throw new IllegalStateException("内置轨道缺少 schema: " + id);
                }
                ENTRIES.put(id, new Entry(schema, BuiltinTrackDrivers.factoryFor(id), null,
                        compileSlots(schema.objects()), true));
            }
            builtinsReady = true;
            ObjectSchemaValidator.validateOrThrow(schemas);
        }
    }

    /**
     * 登记一条第三方轨道。
     *
     * @param id      轨道类型 id（不得与已登记 id 重复）
     * @param schema  对象声明表（{@link TrackSchema#builder()} 构造）
     * @param factory 播放回调工厂（每段活跃期调用一次）
     * @return 恒 {@code true}（登记成功）
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     * @throws IllegalArgumentException id / schema / factory 非法、id 已登记、或 schema 自检不通过
     */
    public static boolean register(TrackTypeId id, TrackSchema schema, TrackPlayerFactory factory) {
        ensureBuiltins();
        if (frozen) {
            throw new IllegalStateException("轨道注册已冻结：注册必须在模组初始化期完成（当前 id: " + id + "）");
        }
        if (id == null) {
            throw new IllegalArgumentException("轨道类型 id 不能为 null");
        }
        if (schema == null) {
            throw new IllegalArgumentException("轨道 schema 不能为 null: " + id);
        }
        if (factory == null) {
            throw new IllegalArgumentException("轨道播放器工厂不能为 null: " + id);
        }
        Entry existing = ENTRIES.get(id);
        if (existing != null) {
            throw new IllegalArgumentException("轨道类型已登记: " + id
                    + (existing.builtin() ? "（内置轨道 id 受保护）" : ""));
        }
        TrackTypeSchema trackTypeSchema = new TrackTypeSchema(schema.objects(), Collections.emptyMap());
        List<String> issues = ObjectSchemaValidator.validateEntry(id, trackTypeSchema);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("轨道 schema 自检失败: " + id + "\n" + String.join("\n", issues));
        }
        ENTRIES.put(id, new Entry(trackTypeSchema, null, factory,
                compileSlots(schema.objects()), false));
        return true;
    }

    /**
     * 取轨道 schema。
     *
     * @param id 轨道类型 id
     * @return schema；未登记返回 {@link TrackTypeSchema#EMPTY}（永不 {@code null}）
     */
    public static TrackTypeSchema schema(TrackTypeId id) {
        ensureBuiltins();
        Entry entry = id == null ? null : ENTRIES.get(id);
        return entry != null ? entry.schema() : TrackTypeSchema.EMPTY;
    }

    /**
     * 取注册期编译好的路径 → 槽位表。
     *
     * @param id 轨道类型 id
     * @return 不可变映射；未登记返回空表
     */
    public static Map<String, Integer> pathSlots(TrackTypeId id) {
        ensureBuiltins();
        Entry entry = id == null ? null : ENTRIES.get(id);
        return entry != null ? entry.pathSlots() : Map.of();
    }

    /**
     * 按轨道 id 创建驱动。
     *
     * <p>内置轨：{@code EVENT} 没有客户端驱动，返回 {@code null}；其余返回具体实现。
     * 第三方轨：返回包了一层 {@link ExternalTrackDriver} 的驱动。</p>
     *
     * @param id            轨道类型 id
     * @param player        所属播放器
     * @param originPos     相对模式基准位置
     * @param cameraManager 相机管理器（CAMERA 轨需要）
     * @param overlayManager 覆盖层管理器（LETTERBOX / OVERLAY 轨需要）
     * @param trackIndex    轨道索引
     * @return 驱动；内置 {@code EVENT} 轨返回 {@code null}
     * @throws IllegalArgumentException 未登记的 id
     */
    public static TrackDriver createDriver(TrackTypeId id, ScriptPlayer player, Vec3 originPos,
                                           CameraManager cameraManager, OverlayManager overlayManager,
                                           int trackIndex) {
        ensureBuiltins();
        Entry entry = id == null ? null : ENTRIES.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("未知轨道类型: " + id);
        }
        if (entry.builtin()) {
            return entry.driverFactory() == null
                    ? null
                    : entry.driverFactory().create(player, originPos, cameraManager, overlayManager, trackIndex);
        }
        return new ExternalTrackDriver(id, player, trackIndex, entry.playerFactory().create());
    }

    /**
     * 该 id 是否已登记（内置或第三方）。
     *
     * @param id 轨道类型 id
     * @return 已登记返回 {@code true}
     */
    public static boolean isKnown(TrackTypeId id) {
        ensureBuiltins();
        return id != null && ENTRIES.containsKey(id);
    }

    /**
     * 脚本名 → 轨道 id：先按精确 id 匹配（内置 + 第三方），未命中且大写化后命中<b>内置</b> id 时返回它
     * （兼容脚本里的小写写法）；否则 {@code null}。
     *
     * @param raw 脚本里的轨道类型名
     * @return 轨道 id；未知返回 {@code null}
     */
    public static TrackTypeId resolve(String raw) {
        ensureBuiltins();
        if (raw == null) {
            return null;
        }
        for (TrackTypeId id : ENTRIES.keySet()) {
            if (id.id().equals(raw)) {
                return id;
            }
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.equals(raw)) {
            return null;
        }
        for (Map.Entry<TrackTypeId, Entry> entry : ENTRIES.entrySet()) {
            if (entry.getValue().builtin() && entry.getKey().id().equals(upper)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 已登记轨道（不可变快照，插入顺序）。
     *
     * @return id 列表
     */
    public static List<TrackTypeId> registered() {
        ensureBuiltins();
        return List.copyOf(ENTRIES.keySet());
    }

    /**
     * 内置轨道（不可变快照，插入顺序）。
     *
     * @return 内置 id 列表
     */
    public static List<TrackTypeId> builtinIds() {
        ensureBuiltins();
        List<TrackTypeId> ids = new ArrayList<>(BuiltinTracks.ids().size());
        for (Map.Entry<TrackTypeId, Entry> entry : ENTRIES.entrySet()) {
            if (entry.getValue().builtin()) {
                ids.add(entry.getKey());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 已登记 id 的报错文案（{@code CAMERA/LETTERBOX/...}）。
     *
     * @return 以 {@code "/"} 连接的 id 清单
     */
    public static String idListForMessage() {
        List<TrackTypeId> ids = registered();
        StringBuilder sb = new StringBuilder();
        for (TrackTypeId id : ids) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(id.id());
        }
        return sb.toString();
    }

    /** 冻结注册表：之后 {@link #register} 一律抛 {@link IllegalStateException}（不触发任何校验）。 */
    public static void freeze() {
        frozen = true;
    }

    /** 注册表是否已冻结。 */
    public static boolean isFrozen() {
        return frozen;
    }

    /** 注册期编译路径槽位：声明顺序 → 连续下标。 */
    private static Map<String, Integer> compileSlots(Map<String, ObjectDef> objects) {
        Map<String, Integer> slots = new LinkedHashMap<>(Math.max(16, objects.size() * 2));
        int index = 0;
        for (String path : objects.keySet()) {
            slots.put(path, index++);
        }
        return Collections.unmodifiableMap(slots);
    }
}
