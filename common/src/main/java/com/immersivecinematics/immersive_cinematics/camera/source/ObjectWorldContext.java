package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectCallpoints;
import com.immersivecinematics.immersive_cinematics.script.object.ObjectSlotLayout;
import com.immersivecinematics.immersive_cinematics.script.object.SampledClip;
import com.immersivecinematics.immersive_cinematics.script.object.position.PointSourceSpec;
import com.immersivecinematics.immersive_cinematics.script.object.position.WorldContext;
import com.immersivecinematics.immersive_cinematics.selector.EntitySelectorService;
import com.immersivecinematics.immersive_cinematics.selector.SelectorPolicy;
import com.immersivecinematics.immersive_cinematics.selector.SelectorSchema;
import com.immersivecinematics.immersive_cinematics.util.TimeInterpolation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 新格式相机求值的世界查询实现：把 {@link WorldContext} 的点源查询接到
 * {@link EntitySelectorService}（选择器解析 / 锁 / 平滑）、{@link WorldPointLocator}（方块 / 结构）
 * 与玩家激活位置。
 *
 * <p>策略 / 锚点 / 择一从当前采样的 {@link SampledClip} 槽位读，回落链 = 调用点专属
 * {@code <字段>_<调用点>} → 通用 {@code <字段>} → 缺省，与 {@link EntityTargetResolver#selectorPolicy} /
 * {@link EntityTargetResolver#selectorAnchor} / {@link EntityTargetResolver#selectorPick} 同口径：
 * 「写了才覆盖」用 {@link ClipValueView#declared(String)} 判定（schema 缺省值不算声明），
 * 超界关键帧由采样器过滤后 {@link SampledClip#present(int)} 为假，同样不参与。
 * 调用点名单 = {@link ObjectCallpoints#NEW_CALLPOINTS}；旧名 {@code facing_origin} 归一为
 * {@code position_origin}（新格式只登记后者）。</p>
 *
 * <p>平滑：{@code look_at} 实体点走通道 {@code point}、{@code position_origin} 实体点走通道 {@code pos}
 * （与旧链路同名），时长取该调用点的 {@code selector_switch_smooth}；其余调用点不引入额外平滑。
 * 实体取点数学复用 {@link EntityTargetResolver#entityPoint}（包围盒底面中心 + 每轴百分比，
 * {@code FOOT} / {@code CENTER} 与旧算式逐位等值）。</p>
 *
 * <p>未解析一律空值：点 {@code null}、角度 {@link Double#NaN}。绑定是逐次求值的：调用方在每次
 * {@code evaluate} 前 {@link #bind} 当前片段视图 / 采样缓冲 / 视点 / 位置点源，本类不跨调用缓存求值结果。
 * 仅客户端主线程使用；锁与平滑状态归 {@link EntitySelectorService}，生命周期 = 所属轨道播放器。</p>
 */
public final class ObjectWorldContext implements WorldContext {

    /** 选择器策略字段（顺序 = 槽位表列）。 */
    private static final String[] FIELDS = {
            "selector_refresh", "selector_switch_while_alive", "selector_switch_interval",
            "selector_switch_smooth", "selector_anchor", "selector_pick"
    };
    private static final int REFRESH = 0;
    private static final int SWITCH_ALIVE = 1;
    private static final int SWITCH_INTERVAL = 2;
    private static final int SWITCH_SMOOTH = 3;
    private static final int ANCHOR = 4;
    private static final int PICK = 5;

    /** 调用点名 → 槽位表行；旧名 {@code facing_origin} 与 {@code position_origin} 同一行。 */
    private static final Map<String, Integer> CALLPOINTS = callpointIndex();

    private final EntitySelectorService service;
    private final WorldPointLocator pointLocator;
    private final Vec3 playerActivation;

    /** 通用字段槽位（{@code -1} = 未登记）。 */
    private final int[] genericSlots = new int[FIELDS.length];
    /** 调用点专属字段槽位：行 = {@link ObjectCallpoints#NEW_CALLPOINTS} 顺序。 */
    private final int[][] callpointSlots;

    /** 当前求值片段的 value-only 视图 / 采样缓冲（{@link #bind} 写入）。 */
    private ClipValueView values;
    private SampledClip sample;
    /** 实体解析原点（锚点缺省 / 全部回落分支的取值）。 */
    private Vec3 cameraPos;
    /** 当前位置点源（锚点 {@code origin} 与 {@code auto} 回落用）。 */
    private PointSourceSpec positionOrigin;

    /**
     * @param service          通用选择器服务（与 {@link EntityTargetResolver} 共用同一实例，锁 / 留底同源）
     * @param pointLocator     方块 / 结构定位器（与轨道播放器共用缓存）
     * @param playerActivation 玩家激活位置（脚本激活点；不可用 = {@code null}）
     */
    public ObjectWorldContext(EntitySelectorService service, WorldPointLocator pointLocator,
                              Vec3 playerActivation) {
        this.service = service;
        this.pointLocator = pointLocator;
        this.playerActivation = playerActivation;

        ObjectSlotLayout layout = ObjectSlotLayout.of(TrackType.CAMERA);
        for (int field = 0; field < FIELDS.length; field++) {
            genericSlots[field] = layout.slotOf(FIELDS[field]);
        }
        int callpointCount = ObjectCallpoints.NEW_CALLPOINTS.size();
        this.callpointSlots = new int[callpointCount][FIELDS.length];
        for (int row = 0; row < callpointCount; row++) {
            String callpoint = ObjectCallpoints.NEW_CALLPOINTS.get(row);
            for (int field = 0; field < FIELDS.length; field++) {
                callpointSlots[row][field] = layout.slotOf(ObjectCallpoints.field(FIELDS[field], callpoint));
            }
        }
    }

    /**
     * 绑定本次求值的片段上下文：策略字段 / 采样值 / 视点 / 位置点源。
     *
     * @param values         当前片段的 value-only 视图
     * @param sample         当前片段的采样缓冲（与 {@code values} 同一片段、同一 {@code t}）
     * @param cameraPos      实体解析原点（相机当前位置；{@code null} = 玩家激活位置）
     * @param positionOrigin 当前位置点源规格（{@code null} = 无点源）
     */
    public void bind(ClipValueView values, SampledClip sample, Vec3 cameraPos, PointSourceSpec positionOrigin) {
        this.values = values;
        this.sample = sample;
        this.cameraPos = cameraPos != null ? cameraPos : playerActivation;
        this.positionOrigin = positionOrigin;
    }

    // ── WorldContext ──

    @Override
    public Vec3 playerActivation() {
        return playerActivation;
    }

    @Override
    public Vec3 entityPoint(String selector, PointSource.EntityPoint point) {
        return entityPoint(null, selector, point);
    }

    @Override
    public Vec3 entityPoint(String callpoint, String selector, PointSource.EntityPoint point) {
        String role = normalize(callpoint);
        Vec3 raw = entityPointRaw(role, selector, point, anchor(role));
        if (raw == null) {
            return null;
        }
        String channel = smoothChannel(role);
        if (channel == null) {
            return raw;
        }
        float smooth = policy(role).switchSmooth();
        if (smooth <= 0f) {
            return raw;
        }
        // 目标切换 / 丢失后恢复的过渡：与旧链路同通道名、同时长来源
        return service.smoothTargetPoint(role, anchor(role), selector, channel, raw, smooth);
    }

    @Override
    public double entityBodyYaw(String selector) {
        return entityBodyYaw(null, selector);
    }

    @Override
    public double entityBodyYaw(String callpoint, String selector) {
        String role = normalize(callpoint);
        Entity entity = resolve(role, selector, anchor(role));
        return entity != null ? TimeInterpolation.entityBodyYaw(entity) : Double.NaN;
    }

    @Override
    public double entityLookPitch(String selector) {
        return entityLookPitch(null, selector);
    }

    @Override
    public double entityLookPitch(String callpoint, String selector) {
        String role = normalize(callpoint);
        Entity entity = resolve(role, selector, anchor(role));
        return entity != null ? TimeInterpolation.entityPitch(entity) : Double.NaN;
    }

    @Override
    public Vec3 blockCenter(String blockId, int radius) {
        return pointLocator != null ? pointLocator.resolveBlockPos(blockId, radius) : null;
    }

    @Override
    public Vec3 structureCenter(String structureId) {
        return pointLocator != null ? pointLocator.resolveStructurePos(structureId) : null;
    }

    // ── 捕获求值隔离：转发到共用选择器服务 ──

    /** 捕获求值隔离：把选择器锁状态深拷贝留底（与 {@link EntityTargetResolver#snapshotLockState()} 同一份）。 */
    public void snapshotLockState() {
        service.snapshotLockState();
    }

    /** 捕获求值隔离：把选择器锁状态恢复为最近一次留底。 */
    public void restoreLockState() {
        service.restoreLockState();
    }

    // ── 内部 ──

    /** 实体解析 + 包围盒取点（{@code anchor} 显式给出，供锚点 {@code origin} 的绕回防护）。 */
    private Vec3 entityPointRaw(String callpoint, String selector, PointSource.EntityPoint point, String anchor) {
        Entity entity = resolve(callpoint, selector, anchor);
        if (entity == null) {
            return null;
        }
        return EntityTargetResolver.entityPoint(TimeInterpolation.entityPosition(entity),
                entity.getBbWidth(), entity.getBbHeight(), point);
    }

    /** 实体解析：锚点 {@code origin} 的参考点 = 位置点源（实体形态固定按 {@code camera} 解析，避免绕回自身）。 */
    private Entity resolve(String callpoint, String selector, String anchor) {
        if (selector == null || selector.isEmpty()) {
            return null;
        }
        Vec3 originPoint = SelectorSchema.ANCHOR_ORIGIN.equals(anchor) ? originPoint() : null;
        return service.resolveEntity(selector, cameraPos, callpoint, anchor,
                policy(callpoint), pick(callpoint), originPoint);
    }

    /** 锚点 {@code origin} 的点源坐标 = 当前位置点源（无偏移；实体形态按 {@code camera} 锚点解析）。 */
    private Vec3 originPoint() {
        PointSourceSpec spec = positionOrigin;
        if (spec == null || spec.kind() == null) {
            return null;
        }
        return switch (spec.kind()) {
            case WORLD -> playerActivation;
            case COORDINATE -> spec.coordinate();
            case SELECTOR -> spec.selector() == null || spec.selector().isEmpty()
                    ? null
                    : entityPointRaw("position_origin", spec.selector(), spec.entityPoint(),
                            SelectorSchema.ANCHOR_CAMERA);
            case BLOCK -> spec.blockId() == null || spec.blockId().isEmpty()
                    ? null : blockCenter(spec.blockId(), spec.blockRadius());
            case STRUCTURE -> spec.structureId() == null || spec.structureId().isEmpty()
                    ? null : structureCenter(spec.structureId());
        };
    }

    /** 需要切换平滑的通道：{@code look_at} → {@code point}、{@code position_origin} → {@code pos}；其余 = 不平滑。 */
    private static String smoothChannel(String callpoint) {
        if ("look_at".equals(callpoint)) {
            return "point";
        }
        if ("position_origin".equals(callpoint)) {
            return "pos";
        }
        return null;
    }

    /** 调用点级策略（扫描 / 存活期切换 / 切换间隔 / 平滑）；切换间隔缺省 = 扫描间隔。 */
    private SelectorPolicy policy(String callpoint) {
        int row = rowOf(callpoint);
        float scan = selectorFloat(REFRESH, row, 1.0f);
        boolean switchWhileAlive = selectorBool(SWITCH_ALIVE, row, true);
        float switchInterval = selectorFloat(SWITCH_INTERVAL, row, scan);
        float smooth = Math.max(0f, selectorFloat(SWITCH_SMOOTH, row, 0f));
        return new SelectorPolicy(Math.max(0.05f, scan), switchWhileAlive, Math.max(0f, switchInterval), smooth);
    }

    /** 调用点级锚点：专属 → 通用 → {@code camera}；取值集外按 {@code camera}。 */
    private String anchor(String callpoint) {
        String value = selectorString(ANCHOR, rowOf(callpoint), SelectorSchema.ANCHOR_CAMERA);
        return SelectorSchema.isAnchor(value) ? value : SelectorSchema.ANCHOR_CAMERA;
    }

    /** 调用点级择一：专属 → 通用 → {@code first}；取值集外按 {@code first}。 */
    private String pick(String callpoint) {
        String value = selectorString(PICK, rowOf(callpoint), SelectorSchema.PICK_FIRST);
        return SelectorSchema.isPick(value) ? value : SelectorSchema.PICK_FIRST;
    }

    /** 数值字段：专属（已采样）→ 通用（作者声明且已采样）→ 缺省。 */
    private float selectorFloat(int field, int row, float fallback) {
        if (sample == null) {
            return fallback;
        }
        if (row >= 0) {
            int slot = callpointSlots[row][field];
            if (slot >= 0 && sample.present(slot)) {
                return sample.f(slot);
            }
        }
        int slot = genericSlots[field];
        if (slot >= 0 && declared(FIELDS[field]) && sample.present(slot)) {
            return sample.f(slot);
        }
        return fallback;
    }

    /** 布尔字段：回落链同 {@link #selectorFloat}。 */
    private boolean selectorBool(int field, int row, boolean fallback) {
        if (sample == null) {
            return fallback;
        }
        if (row >= 0) {
            int slot = callpointSlots[row][field];
            if (slot >= 0 && sample.present(slot)) {
                return sample.b(slot);
            }
        }
        int slot = genericSlots[field];
        if (slot >= 0 && declared(FIELDS[field]) && sample.present(slot)) {
            return sample.b(slot);
        }
        return fallback;
    }

    /** 字符串字段：回落链同 {@link #selectorFloat}。 */
    private String selectorString(int field, int row, String fallback) {
        if (sample == null) {
            return fallback;
        }
        if (row >= 0) {
            int slot = callpointSlots[row][field];
            if (slot >= 0 && sample.present(slot)) {
                return sample.s(slot);
            }
        }
        int slot = genericSlots[field];
        if (slot >= 0 && declared(FIELDS[field]) && sample.present(slot)) {
            return sample.s(slot);
        }
        return fallback;
    }

    /** 作者是否显式声明该路径（schema 缺省值不算声明）。 */
    private boolean declared(String path) {
        return values != null && values.declared(path);
    }

    /** 调用点 → 槽位表行；不在名单内 = {@code -1}（只用通用字段）。 */
    private static int rowOf(String callpoint) {
        if (callpoint == null) {
            return -1;
        }
        Integer row = CALLPOINTS.get(callpoint);
        return row != null ? row : -1;
    }

    /** 旧名 {@code facing_origin} 归一为 {@code position_origin}（新格式只登记后者）。 */
    private static String normalize(String callpoint) {
        return "facing_origin".equals(callpoint) ? "position_origin" : callpoint;
    }

    private static Map<String, Integer> callpointIndex() {
        Map<String, Integer> index = new HashMap<>();
        for (int row = 0; row < ObjectCallpoints.NEW_CALLPOINTS.size(); row++) {
            index.put(ObjectCallpoints.NEW_CALLPOINTS.get(row), row);
        }
        Integer positionOrigin = index.get("position_origin");
        if (positionOrigin != null) {
            index.put("facing_origin", positionOrigin);
        }
        return Map.copyOf(index);
    }
}
