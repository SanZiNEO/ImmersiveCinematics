package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.PositionData;
import com.immersivecinematics.immersive_cinematics.selector.EntitySelectorService;
import com.immersivecinematics.immersive_cinematics.selector.SelectorPolicy;
import com.immersivecinematics.immersive_cinematics.selector.SelectorSchema;
import com.immersivecinematics.immersive_cinematics.util.TimeInterpolation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 相机侧选择器适配：把关键帧的 selector 字段（含调用点专属回落链）与点源解析接到通用
 * {@link EntitySelectorService}；通用机制（解析双路 / 目标锁与切换 / 择一 / 锚点取位 / 平滑 / 捕获留底）
 * 在 selector 包，本类只做字段读取与相机上下文注入。
 *
 * 字段回落链 = 「{@code <字段>_<调用点>} → 通用 {@code <字段>} → 缺省」：策略四件套
 * （{@code selector_refresh} / {@code selector_switch_while_alive} /
 * {@code selector_switch_interval} / {@code selector_switch_smooth}，单位秒）、
 * 锚点 {@code selector_anchor}（缺省 {@code camera}）、择一 {@code selector_pick}（缺省
 * {@code first}）；调用点名与取值集见 {@link SelectorSchema}。
 *
 * 点源解析（锚点 {@code origin}，与位置基准同一口径）：固定坐标 / 结构中心 / 方块中心 / 玩家激活位置
 * （字段缺省或 {@code "player"} / {@code "@p"} / {@code "@s"}）/ 实体选择器（{@code facing_origin} 写法，
 * 按锚点 {@code camera} 解析以免绕回自身锚点）；返回世界空间方块坐标，不可解析 = {@code null}。
 *
 * 线程与频率：仅客户端主线程，位于每帧求值路径；帧内路径不新建集合、不装箱。
 * 状态所有权：目标锁与平滑状态由内部服务实例持有，生命周期 = 所属轨道播放器（停止 / 替换经 {@link #clear()}）。
 */
public final class EntityTargetResolver {

    private final EntitySelectorService service = new EntitySelectorService();

    /** 点源定位器（结构 / 方块）与玩家激活位置：与求值器共用，见 {@link #attachWorldContext}。 */
    private WorldPointLocator pointLocator;
    private Vec3 originPos;

    /**
     * 注入世界上下文（点源定位器 + 玩家激活位置）：由 {@code CameraKeyframeEvaluator} 构造时传入同一实例，
     * 两者共用定位器缓存；一次性接线，重复注入以最后一次为准。
     */
    public void attachWorldContext(WorldPointLocator locator, Vec3 originPos) {
        this.pointLocator = locator;
        this.originPos = originPos;
    }

    /** 生命周期清理：清空目标锁与客户端选择器缓存（停止播放 / 脚本替换时调用）。 */
    public void clear() {
        service.clear();
    }

    /**
     * 解析目标实体：本地快路径同步求值，服务端选择器走请求 + 缓存回映射。
     *
     * @param selector  选择器字符串；{@code null} / 空串 = 无目标
     * @param cameraPos 相机世界坐标（调用方当前视点）：锚点缺省值与全部回落分支的取值
     * @param role      调用点名：决定策略字段、锚点字段与锁的键，见 {@link SelectorSchema#CALLPOINTS}
     * @param kf        策略 / 锚点 / 择一字段来源关键帧；{@code null} = 三者全缺省
     * @return 解析到的客户端实体；无匹配 / 目标未加载 / 节流未到 = {@code null}
     */
    public Entity resolveEntity(String selector, Vec3 cameraPos, String role, Keyframe kf) {
        return resolveEntity(selector, cameraPos, role, kf, selectorAnchor(kf, role));
    }

    /**
     * 按给定锚点取值解析（锚点由调用方决定，不再读字段）：公共入口用 {@link #selectorAnchor} 的结果；
     * 点源实体分支固定传 {@link SelectorSchema#ANCHOR_CAMERA}——anchor=origin 经点源实体解析会绕回自身锚点。
     * 择一策略由 {@link #selectorPick} 按同一 role 读字段，不随锚点分支改写。
     */
    private Entity resolveEntity(String selector, Vec3 cameraPos, String role, Keyframe kf, String anchor) {
        Minecraft mc = Minecraft.getInstance();
        if (selector == null || selector.isEmpty()) return null;
        if ("@p".equals(selector) || "@s".equals(selector)) {
            return mc.player;
        }
        if (mc.level == null) return null;
        // origin 锚点坐标由本层解析（读关键帧点源字段）；其余锚点取值不触碰关键帧
        Vec3 originPoint = SelectorSchema.ANCHOR_ORIGIN.equals(anchor) ? pointSourcePos(kf, cameraPos) : null;
        return service.resolveEntity(selector, cameraPos, role, anchor,
                selectorPolicy(kf, role), selectorPick(kf, role), originPoint);
    }

    /**
     * 调用点级策略：先读 {@code <字段>_<调用点>}（如 {@code selector_refresh_look_at}），缺失回落通用字段。
     * 切换间隔缺省 = 扫描间隔（扫描到点即允许切换）；通用字段只作默认回落。
     *
     * @param kf   {@code null} = 缺省策略（扫描 1 秒 / 允许存活期切换 / 切换间隔 1 秒 / 无平滑）
     * @param role 调用点名；不在 {@link SelectorSchema#CALLPOINTS} 内 = 只用通用字段
     * @return 扫描间隔下限 0.05 秒、切换间隔与平滑不为负
     */
    public SelectorPolicy selectorPolicy(Keyframe kf, String role) {
        if (kf == null) return new SelectorPolicy(1.0f, true, 1.0f, 0f);
        String callpoint = SelectorSchema.isCallpoint(role) ? role : null;
        float scan = floatForCallpoint(kf, "selector_refresh", callpoint, 1.0f);
        boolean switchWhileAlive = boolForCallpoint(kf, "selector_switch_while_alive", callpoint, true);
        // 切换间隔缺省 = 扫描间隔（保持旧行为：扫描到点就允许切换）
        float switchInterval = floatForCallpoint(kf, "selector_switch_interval", callpoint, scan);
        float smooth = Math.max(0f, floatForCallpoint(kf, "selector_switch_smooth", callpoint, 0f));
        return new SelectorPolicy(Math.max(0.05f, scan), switchWhileAlive, Math.max(0f, switchInterval), smooth);
    }

    /**
     * 该调用点在关键帧上的有效锚点取值：先读 {@code selector_anchor_<调用点>}，缺失回落通用
     * {@code selector_anchor}，再缺省 {@link SelectorSchema#ANCHOR_CAMERA}；四值集之外的值一律按
     * {@code camera} 处理。取值定义与回落链见 {@link SelectorSchema#ANCHORS}。
     *
     * @param kf   {@code null} = 锚点 {@code camera}
     * @param role 调用点名；不在 {@link SelectorSchema#CALLPOINTS} 内 = 只用通用字段
     * @return 锚点取值：{@code camera} / {@code player} / {@code target} / {@code origin}
     */
    public String selectorAnchor(Keyframe kf, String role) {
        if (kf == null) return SelectorSchema.ANCHOR_CAMERA;
        String callpoint = SelectorSchema.isCallpoint(role) ? role : null;
        String value = stringForCallpoint(kf, "selector_anchor", callpoint, SelectorSchema.ANCHOR_CAMERA);
        return SelectorSchema.isAnchor(value) ? value : SelectorSchema.ANCHOR_CAMERA;
    }

    /**
     * 该调用点在关键帧上的有效择一策略：先读 {@code selector_pick_<调用点>}，缺失回落通用
     * {@code selector_pick}，再缺省 {@link SelectorSchema#PICK_FIRST}；三值集之外的值一律按 {@code first} 处理。
     * 取值定义见 {@link SelectorSchema#PICKS}。
     *
     * @param kf   {@code null} = {@code first}
     * @param role 调用点名；不在 {@link SelectorSchema#CALLPOINTS} 内 = 只用通用字段
     * @return 择一取值：{@code first} / {@code nearest} / {@code alive}
     */
    public String selectorPick(Keyframe kf, String role) {
        if (kf == null) return SelectorSchema.PICK_FIRST;
        String callpoint = SelectorSchema.isCallpoint(role) ? role : null;
        String value = stringForCallpoint(kf, "selector_pick", callpoint, SelectorSchema.PICK_FIRST);
        return SelectorSchema.isPick(value) ? value : SelectorSchema.PICK_FIRST;
    }

    /**
     * {@code origin} 锚点坐标 = {@code position.relative_origin} 点源（与位置基准同一解析）：
     * 固定坐标 / 结构中心 / 方块中心 / 玩家激活位置（字段缺省或 {@code "player"} / {@code "@p"} / {@code "@s"}）/
     * 实体选择器（{@code facing_origin} 写法）。{@code position} 字段缺失或点源解析失败返回 {@code null}，
     * 由调用方回落相机位置；实体点源按锚点 {@code camera} 解析（点源自引用会绕回本锚点）。
     *
     * @param kf        点源字段来源关键帧；{@code null} = 无点源
     * @param cameraPos 实体点源的解析原点（锚点 {@code camera} 的取值）
     * @return 世界空间点源坐标；无点源 / 不可解析 = {@code null}
     */
    private Vec3 pointSourcePos(Keyframe kf, Vec3 cameraPos) {
        if (kf == null) return null;
        PositionData pd = kf.getPosition();
        if (pd == null) return null;
        if (pd.isOriginCoordinate()) {
            return new Vec3(pd.getOriginX(), pd.getOriginY(), pd.getOriginZ());
        }
        if (pd.isOriginBlock()) {
            return pointLocator != null
                    ? pointLocator.resolveBlockPos(pd.getOriginBlockId(), pd.getOriginBlockRadius())
                    : null;
        }
        if (pd.isOriginSelector()) {
            Entity base = resolveEntity(pd.getOriginSelector(), cameraPos, "facing_origin", kf,
                    SelectorSchema.ANCHOR_CAMERA);
            return base != null ? TimeInterpolation.entityPosition(base) : null;
        }
        String structureId = pd.getOriginStructure();
        if (structureId != null && !structureId.isEmpty()) {
            return pointLocator != null ? pointLocator.resolveStructurePos(structureId) : null;
        }
        // 剩余形态 = 玩家基准（relative_origin 缺省 / "player" / "@p" / "@s"）：取值 = 玩家激活位置
        return originPos;
    }

    /**
     * 目标点 / 位置切换平滑：按 {@code role + 锚点 + selector} 锁的通道（look_at / follow 位置 / 朝向基准）分别维护。
     * 只有目标 UUID 变化（{@code switchGeneration} 前进）时才启动过渡，稳定跟踪同一目标不额外延迟。
     *
     * @param channel       通道名：同一把锁下各通道互不影响
     * @param raw           本帧原始目标点（世界空间方块坐标）
     * @param smoothSeconds 过渡时长（秒）；{@code <= 0} = 立即跳到新目标
     * @param kf            锚点字段来源关键帧（决定用哪把锁）；{@code null} = 锚点 {@code camera}
     * @return 平滑后的目标点；无对应锁（该目标未解析过）= {@code raw}
     */
    public Vec3 smoothTargetPoint(String role, String selector, String channel, Vec3 raw, float smoothSeconds,
                                  Keyframe kf) {
        return service.smoothTargetPoint(role, selectorAnchor(kf, role), selector, channel, raw, smoothSeconds);
    }

    /** 捕获求值隔离：把当前锁状态深拷贝留底（同一时刻只保留最近一次留底）。 */
    public void snapshotLockState() {
        service.snapshotLockState();
    }

    /** 捕获求值隔离：把锁状态整体恢复为最近一次 {@link #snapshotLockState()} 的留底（深拷贝回写）。 */
    public void restoreLockState() {
        service.restoreLockState();
    }

    /** 读「调用点专属字段 → 通用字段 → 缺省值」。 */
    private static float floatForCallpoint(Keyframe kf, String field, String callpoint, float fallback) {
        String key = SelectorSchema.callpointField(field, callpoint);
        if (key != null && kf.getData().containsKey(key)) return kf.getFloat(key, fallback);
        return kf.getFloat(field, fallback);
    }

    /** 读「调用点专属字段 → 通用字段 → 缺省值」（字符串口径，与 {@link #floatForCallpoint} 同规则）。 */
    private static String stringForCallpoint(Keyframe kf, String field, String callpoint, String fallback) {
        String key = SelectorSchema.callpointField(field, callpoint);
        if (key != null && kf.getData().containsKey(key)) return kf.getString(key, fallback);
        return kf.getString(field, fallback);
    }

    private static boolean boolForCallpoint(Keyframe kf, String field, String callpoint, boolean fallback) {
        String key = SelectorSchema.callpointField(field, callpoint);
        if (key != null && kf.getData().containsKey(key)) return kf.getBool(key, fallback);
        return kf.getBool(field, fallback);
    }
}
