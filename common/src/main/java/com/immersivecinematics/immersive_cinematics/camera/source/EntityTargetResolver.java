package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.PositionData;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientEntitySelectorCache;
import com.immersivecinematics.immersive_cinematics.util.TimeInterpolation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标实体解析与锁定：selector → 客户端实体，并按 {@code role + 锚点 + selector} 维护目标锁与切换平滑状态。
 * 服务端结果按 {@link ClientEntitySelectorCache.Key}（调用点 + 锚点 + selector）分桶缓存，互不覆盖。
 * 锚点 = 就近排序（{@code sort=nearest}）的参考点，也是服务端解析请求的原点；取值与回落链见 {@link #selectorAnchor}。
 * 候选择一规则（多候选取定目标）见 {@link #selectorPick}。
 *
 * 解析分两条路径：本地快路径（{@code @p} / {@code @s} / {@code @e} / {@code @e[type=…,name=…]} / {@code uuid:…}）
 * 同步求值；其余形态交服务端用原版解析，再按回传 UUID 映射到客户端实体——带选项的 {@code @p} / {@code @a} /
 * {@code @r}（含 {@code [team=…]} 等选项，选项语义由原版 {@code EntitySelectorParser} 决定，客户端不裁剪）
 * 与 {@code @e[…]} 的原版扩展选项（{@code nbt} / {@code tag} / 反向 / 多值等）；服务端上限 512 字符 / 32 条结果。
 * 原版不存在的 {@code @n} 与未知类型 / 畸形括号告警后按无匹配处理；原版五分支与选项清单见
 * plans/0.3.6/selector-model.md 事实核查③。
 *
 * 目标丢失（死亡 / 移除 / 未加载）不等于目标结束：锁进入搜索态，保持调用方最后画面，并按
 * {@link #MISS_RETRY_MS}（毫秒）节流重找；一旦解析到任意符合规则的目标即恢复，不受存活期切换策略限制。
 *
 * 线程与频率：仅客户端主线程调用，位于每帧求值路径（每个通道各调一次）；锁与平滑状态由本实例持有，
 * 生命周期 = 所属轨道播放器（停止播放 / 脚本替换时经 {@link #clear()} 清理）。
 * 时间契约：{@code selector_refresh} / {@code selector_switch_interval} / {@code selector_switch_smooth}
 * 单位秒；内部节流时间戳用毫秒 / 纳秒。
 */
public final class EntityTargetResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/EntityTargetResolver");

    /** 搜索态重试节流间隔（毫秒）：目标丢失后本地重扫 / 服务端重发请求的最小间隔。 */
    private static final long MISS_RETRY_MS = 200L;

    /**
     * 选择器调用点（角色）：每个调用点拥有自己的策略，可单独配置，互不影响。
     * 通用字段（{@code selector_refresh} 等）只作默认回落——不写角色专属字段时行为与只用通用字段一致。
     * 名单与 {@code script/schema/TrackSchemas.camera()} 声明的调用点专属字段一一对应（改一处须同步另一处）。
     */
    private static final List<String> SELECTOR_CALLPOINTS = List.of(
            "follow", "look_at", "look_at_target", "yaw_base",
            "yaw_base_from", "yaw_base_to", "facing_origin", "facing_target");

    /**
     * 选择器锚点取值：就近排序（{@code sort=nearest}）的参考点，也是服务端解析请求的原点。
     *
     * {@code camera}（缺省）= 调用方传入的相机位置（旧行为）；{@code player} = 玩家脚底（渲染帧插值）；
     * {@code target} = 本调用点已解析锁的实体脚底，尚未解析到目标时回落 {@code camera}；
     * {@code origin} = {@code position.relative_origin} 点源坐标（固定坐标 / 结构中心 / 方块中心 /
     * 玩家激活位置 / 实体选择器，与位置基准同一解析），{@code position} 缺失或点源解析失败回落 {@code camera}。
     * 坐标空间 = 世界空间方块坐标，脚底口径。
     * 锚点取值（身份）进锁键与缓存键，锚点坐标不进键——回落只改坐标不改身份，锁不因回落换键。
     */
    private static final String ANCHOR_CAMERA = "camera";
    private static final String ANCHOR_PLAYER = "player";
    private static final String ANCHOR_TARGET = "target";
    private static final String ANCHOR_ORIGIN = "origin";

    /**
     * 选择器择一策略取值：从候选（本地扫描命中 / 服务端回传 UUID 列表）取定目标的规则。
     *
     * {@code first}（缺省）= 现状行为：本地 {@code @e} 系列取距锚点最近的可用候选（本地候选按就近择一），
     * 服务端按回传 UUID 序取首个可用候选；{@code nearest} = 客户端可用候选内取距锚点最近者
     * （服务端路径由此与本地同口径）；{@code alive} = 按候选返回序取首个存活候选、不比距离
     * （本地 = 世界迭代序，服务端 = UUID 序）。
     * 可用 = 客户端已加载且存活；距离口径 = 世界空间方块坐标的平方距离比较（不开平方）。
     * 取值与回落链见 {@link #selectorPick}。
     */
    private static final String PICK_FIRST = "first";
    private static final String PICK_NEAREST = "nearest";
    private static final String PICK_ALIVE = "alive";

    /** 点源定位器（结构 / 方块）与玩家激活位置：与求值器共用，见 {@link #attachWorldContext}。 */
    private WorldPointLocator pointLocator;
    private Vec3 originPos;

    /** 目标锁状态：按 {@code role + 锚点 + selector} 维护。 */
    private final Map<String, TargetLock> targetLocks = new HashMap<>();

    /** 捕获求值隔离基线：{@link #snapshotLockState()} 留底，{@link #restoreLockState()} 回写。 */
    private final Map<String, TargetLock> lockBaseline = new HashMap<>();

    /**
     * 目标锁定状态：按 {@code role + 锚点 + selector} 维护。
     *
     * {@code uuid} / {@code entity} / {@code resolvedAt} = 当前锁定目标与刷新时间；{@code switchGeneration} 在
     * 目标 UUID 每次变化时 +1，供各通道检测「是否刚切换」；{@code points} = 各通道的平滑状态。
     */
    private static final class TargetLock {
        UUID uuid;
        Entity entity;
        long resolvedAt;
        long switchGeneration;
        /** 客户端选择器缓存键（调用点 + 锚点 + selector）；锁建立时算一次，逐帧复用 */
        ClientEntitySelectorCache.Key cacheKey;
        /** 搜索态：目标已丢失 / 尚未找到（由 resolveEntity 维护） */
        boolean searching;
        /** 搜索态：下一次允许重试（重发请求 / 重扫）的时间戳；与 resolvedAt（扫描节流）分离 */
        long nextRetryAt;
        /** 上一次真实切换的时间：切换间隔闸门的基准（与扫描时间戳分离，快扫描 + 慢切换可用） */
        long lastSwitchAt;

        final Map<String, PointState> points = new HashMap<>();
    }

    /** 单个目标点在切换窗口内的平滑状态。 */
    private static final class PointState {
        Vec3 last;
        long generation = -1L;
        Vec3 from;
        long startNanos;
        float smooth;
    }

    /**
     * 选择器策略（调用点级）。
     *
     * @param scanSeconds      扫描间隔：多久重新扫一遍候选（影响准确性）
     * @param switchWhileAlive 目标存活时是否允许切换
     * @param switchSeconds    切换间隔：扫到新目标后，也要等这么久才真的换过去（影响稳定性）
     * @param switchSmooth     切换平滑：真的换过去时，用多少秒过渡
     */
    public record SelectorPolicy(
            float scanSeconds, boolean switchWhileAlive, float switchSeconds, float switchSmooth) {}

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
        targetLocks.clear();
        lockBaseline.clear();
        ClientEntitySelectorCache.clear();
    }

    /**
     * 解析目标实体：本地快路径同步求值，服务端选择器走请求 + 缓存回映射。
     *
     * @param selector  选择器字符串；{@code null} / 空串 = 无目标
     * @param cameraPos 相机世界坐标（调用方当前视点）：锚点缺省值与全部回落分支的取值
     * @param role      调用点名：决定策略字段、锚点字段与锁的键，见 {@link #SELECTOR_CALLPOINTS}
     * @param kf        策略 / 锚点 / 择一字段来源关键帧；{@code null} = 三者全缺省
     * @return 解析到的客户端实体；无匹配 / 目标未加载 / 节流未到 = {@code null}
     */
    public Entity resolveEntity(String selector, Vec3 cameraPos, String role, Keyframe kf) {
        return resolveEntity(selector, cameraPos, role, kf, selectorAnchor(kf, role));
    }

    /**
     * 按给定锚点取值解析（锚点由调用方决定，不再读字段）：公共入口用 {@link #selectorAnchor} 的结果；
     * 点源实体分支固定传 {@link #ANCHOR_CAMERA}——anchor=origin 经点源实体解析会绕回自身锚点。
     * 择一策略由 {@link #selectorPick} 按同一 role 读字段，不随锚点分支改写。
     */
    private Entity resolveEntity(String selector, Vec3 cameraPos, String role, Keyframe kf, String anchor) {
        Minecraft mc = Minecraft.getInstance();
        if (selector == null || selector.isEmpty()) return null;
        if ("@p".equals(selector) || "@s".equals(selector)) {
            return mc.player;
        }
        if (mc.level == null) return null;

        SelectorPolicy policy = selectorPolicy(kf, role);
        TargetLock lock = targetLocks.computeIfAbsent(targetKey(role, anchor, selector),
                k -> newLock(role, anchor, selector));
        Vec3 origin = anchorPosition(anchor, cameraPos, lock, kf);
        String pick = selectorPick(kf, role);
        long now = System.currentTimeMillis();

        // 解析间隔（= 扫描指标）：与切换决策完全独立
        long refreshMs = (long) Math.max(50.0f, policy.scanSeconds() * 1000.0f);

        Entity current = lockEntity(lock);

        // ===== 搜索态（目标丢失 / 尚未找到）=====
        // 丢失 ≠ 片段结束：保持最后画面（调用方按空片段兜底），后台持续重找；
        // 解析到任意符合规则的目标就立即恢复——不受存活期切换策略（switch_while_alive / 切换间隔）限制。
        if (current == null) {
            if (!lock.searching) {
                lock.searching = true;
                lock.nextRetryAt = 0L;   // 丢失当帧立刻重试一次（不等节流）
                if (lock.uuid != null) {
                    LOGGER.info("[selector] 目标丢失 role={} sel={} uuid={} → 进入搜索（保持最后画面，持续重找）",
                            role, describeSelector(selector), lock.uuid);
                } else {
                    LOGGER.info("[selector] 等待目标 role={} sel={}", role, describeSelector(selector));
                }
            }
            Entity found = resolveEntityInternal(selector, origin, refreshMs, true, lock, pick);
            if (found == null) {
                return null;
            }
            boolean recovered = lock.uuid != null;
            lock.searching = false;
            lock.uuid = found.getUUID();
            lock.entity = found;
            lock.resolvedAt = now;
            lock.lastSwitchAt = now;
            if (recovered) {
                // 从"保持的最后画面"平滑过渡到新目标（复用 selector_switch_smooth）
                lock.switchGeneration++;
                LOGGER.info("[selector] 搜索恢复 role={} → {} uuid={}", role, found.getType(), found.getUUID());
            } else {
                LOGGER.info("[selector] 锁定 role={} → {} uuid={}", role, found.getType(), found.getUUID());
            }
            return found;
        }

        // ===== 跟踪态（目标存活）=====
        lock.searching = false;   // 目标在手上（含重新加载回来）→ 退出搜索态
        Entity found = null;
        if (now - lock.resolvedAt >= refreshMs) {
            found = resolveEntityInternal(selector, origin, refreshMs, false, lock, pick);
        }
        if (found == null) {
            // 未到扫描时间 / 本次没扫到：继续用当前目标
            return current;
        }

        UUID newUuid = found.getUUID();
        if (lock.uuid != null && lock.uuid.equals(newUuid)) {
            lock.entity = found;
            lock.resolvedAt = now;
            return found;
        }

        // 扫到了不同的目标——是否真的切换由「切换指标」决定，与扫描频率无关
        if (!policy.switchWhileAlive()) {
            // 存活期不换：保留旧目标（旧目标失效时锁进入搜索态，上面会走恢复分支）
            lock.entity = current;
            lock.resolvedAt = now;
            return current;
        }
        // 两次真实切换之间的最小间隔：到点才换（基准是上次切换时间，与扫描频率解耦）
        long switchGateMs = (long) Math.max(0f, policy.switchSeconds() * 1000.0f);
        if (switchGateMs > 0L && now - lock.lastSwitchAt < switchGateMs) {
            // 还在切换冷却里：继续用旧目标，但记录本次已扫过（否则会每帧重扫）
            lock.entity = current;
            lock.resolvedAt = now;
            LOGGER.info("[selector](冷却中) role={} 扫到新目标 {} 但被切换间隔挡住，继续用旧目标 {}",
                    role, newUuid, lock.uuid);
            return current;
        }

        lock.switchGeneration++;
        lock.uuid = newUuid;
        lock.entity = found;
        lock.resolvedAt = now;
        lock.lastSwitchAt = now;
        LOGGER.info("[selector] 切换 role={} → {} uuid={}", role, found.getType(), found.getUUID());
        return found;
    }

    /**
     * 调用点级策略：先读 {@code <字段>_<调用点>}（如 {@code selector_refresh_look_at}），缺失回落通用字段。
     * 切换间隔缺省 = 扫描间隔（扫描到点即允许切换）；通用字段只作默认回落。
     *
     * @param kf   {@code null} = 缺省策略（扫描 1 秒 / 允许存活期切换 / 切换间隔 1 秒 / 无平滑）
     * @param role 调用点名；不在 {@link #SELECTOR_CALLPOINTS} 内 = 只用通用字段
     * @return 扫描间隔下限 0.05 秒、切换间隔与平滑不为负
     */
    public SelectorPolicy selectorPolicy(Keyframe kf, String role) {
        if (kf == null) return new SelectorPolicy(1.0f, true, 1.0f, 0f);
        String callpoint = SELECTOR_CALLPOINTS.contains(role) ? role : null;
        float scan = floatForCallpoint(kf, "selector_refresh", callpoint, 1.0f);
        boolean switchWhileAlive = boolForCallpoint(kf, "selector_switch_while_alive", callpoint, true);
        // 切换间隔缺省 = 扫描间隔（保持旧行为：扫描到点就允许切换）
        float switchInterval = floatForCallpoint(kf, "selector_switch_interval", callpoint, scan);
        float smooth = Math.max(0f, floatForCallpoint(kf, "selector_switch_smooth", callpoint, 0f));
        return new SelectorPolicy(Math.max(0.05f, scan), switchWhileAlive, Math.max(0f, switchInterval), smooth);
    }

    /**
     * 该调用点在关键帧上的有效锚点取值：先读 {@code selector_anchor_<调用点>}，缺失回落通用
     * {@code selector_anchor}，再缺省 {@link #ANCHOR_CAMERA}；四值集之外的值一律按 {@code camera} 处理。
     * 取值定义与回落链见 {@link #ANCHOR_CAMERA}。
     *
     * @param kf   {@code null} = 锚点 {@code camera}
     * @param role 调用点名；不在 {@link #SELECTOR_CALLPOINTS} 内 = 只用通用字段
     * @return 锚点取值：{@code camera} / {@code player} / {@code target} / {@code origin}
     */
    public String selectorAnchor(Keyframe kf, String role) {
        if (kf == null) return ANCHOR_CAMERA;
        String callpoint = SELECTOR_CALLPOINTS.contains(role) ? role : null;
        String value = stringForCallpoint(kf, "selector_anchor", callpoint, ANCHOR_CAMERA);
        return isKnownAnchor(value) ? value : ANCHOR_CAMERA;
    }

    /**
     * 该调用点在关键帧上的有效择一策略：先读 {@code selector_pick_<调用点>}，缺失回落通用
     * {@code selector_pick}，再缺省 {@link #PICK_FIRST}；三值集之外的值一律按 {@code first} 处理。
     * 取值定义见 {@link #PICK_FIRST}。
     *
     * @param kf   {@code null} = {@code first}
     * @param role 调用点名；不在 {@link #SELECTOR_CALLPOINTS} 内 = 只用通用字段
     * @return 择一取值：{@code first} / {@code nearest} / {@code alive}
     */
    public String selectorPick(Keyframe kf, String role) {
        if (kf == null) return PICK_FIRST;
        String callpoint = SELECTOR_CALLPOINTS.contains(role) ? role : null;
        String value = stringForCallpoint(kf, "selector_pick", callpoint, PICK_FIRST);
        return isKnownPick(value) ? value : PICK_FIRST;
    }

    /**
     * 锚点坐标（世界空间方块坐标，脚底口径）：按锚点取值取位，不可解析一律回落相机位置。
     *
     * @param anchor    锚点取值（{@link #selectorAnchor} 的结果）
     * @param cameraPos 调用方传入的相机位置：{@code camera} 分支与全部回落分支的取值，
     *                  也是 {@code origin} 分支实体点源的解析原点
     * @param lock      本调用点本锚点的目标锁（{@code target} 取锁的实体位置）
     * @param kf        {@code origin} 分支的点源字段来源关键帧
     * @return 锚点坐标；{@code camera} 分支原样返回 {@code cameraPos}
     */
    private Vec3 anchorPosition(String anchor, Vec3 cameraPos, TargetLock lock, Keyframe kf) {
        if (ANCHOR_PLAYER.equals(anchor)) {
            Entity player = Minecraft.getInstance().player;
            return player != null ? TimeInterpolation.entityPosition(player) : cameraPos;
        }
        if (ANCHOR_TARGET.equals(anchor)) {
            Entity target = lockEntity(lock);
            return target != null ? TimeInterpolation.entityPosition(target) : cameraPos;
        }
        if (ANCHOR_ORIGIN.equals(anchor)) {
            Vec3 point = pointSourcePos(kf, cameraPos);
            return point != null ? point : cameraPos;
        }
        return cameraPos;
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
            Entity base = resolveEntity(pd.getOriginSelector(), cameraPos, "facing_origin", kf, ANCHOR_CAMERA);
            return base != null ? TimeInterpolation.entityPosition(base) : null;
        }
        String structureId = pd.getOriginStructure();
        if (structureId != null && !structureId.isEmpty()) {
            return pointLocator != null ? pointLocator.resolveStructurePos(structureId) : null;
        }
        // 剩余形态 = 玩家基准（relative_origin 缺省 / "player" / "@p" / "@s"）：取值 = 玩家激活位置
        return originPos;
    }

    /** 取值是否属四值集；未知值（未过校验的脚本）按缺省 {@link #ANCHOR_CAMERA} 处理。 */
    private static boolean isKnownAnchor(String anchor) {
        return ANCHOR_CAMERA.equals(anchor) || ANCHOR_PLAYER.equals(anchor)
                || ANCHOR_TARGET.equals(anchor) || ANCHOR_ORIGIN.equals(anchor);
    }

    /** 取值是否属三值集；未知值（未过校验的脚本）按缺省 {@link #PICK_FIRST} 处理。 */
    private static boolean isKnownPick(String pick) {
        return PICK_FIRST.equals(pick) || PICK_NEAREST.equals(pick) || PICK_ALIVE.equals(pick);
    }

    /**
     * 目标点 / 位置切换平滑：按 {@code role + 锚点 + selector} 锁的通道（look_at / follow 位置 / 朝向基准）分别维护。
     * 只有目标 UUID 变化（{@code switchGeneration} 前进）时才启动过渡，稳定跟踪同一目标不额外延迟。
     *
     * @param channel       通道名：同一把锁下各通道互不影响
     * @param raw           本帧原始目标点（世界坐标）
     * @param smoothSeconds 过渡时长（秒）；{@code <= 0} = 立即跳到新目标
     * @param kf            锚点字段来源关键帧（决定用哪把锁）；{@code null} = 锚点 {@code camera}
     * @return 平滑后的目标点；无对应锁（该目标未解析过）= {@code raw}
     */
    public Vec3 smoothTargetPoint(String role, String selector, String channel, Vec3 raw, float smoothSeconds,
                                  Keyframe kf) {
        if (raw == null) return null;
        TargetLock lock = targetLocks.get(targetKey(role, selectorAnchor(kf, role), selector));
        if (lock == null) return raw;

        PointState state = lock.points.computeIfAbsent(channel, k -> new PointState());
        long now = System.nanoTime();

        if (state.last == null) {
            state.last = raw;
            state.generation = lock.switchGeneration;
            return raw;
        }

        if (state.generation != lock.switchGeneration) {
            state.generation = lock.switchGeneration;
            if (smoothSeconds > 0f) {
                state.from = state.last;
                state.startNanos = now;
                state.smooth = smoothSeconds;
            } else {
                state.startNanos = 0L;
                state.smooth = 0f;
                state.last = raw;
                return raw;
            }
        }

        if (state.startNanos == 0L || state.smooth <= 0f) {
            state.last = raw;
            return raw;
        }

        float t = (now - state.startNanos) / 1.0e9f / state.smooth;
        if (t >= 1f) {
            state.startNanos = 0L;
            state.last = raw;
            return raw;
        }

        float k = t * t * (3f - 2f * t);
        Vec3 blended = new Vec3(
                state.from.x + (raw.x - state.from.x) * k,
                state.from.y + (raw.y - state.from.y) * k,
                state.from.z + (raw.z - state.from.z) * k);
        state.last = blended;
        return blended;
    }

    /** 捕获求值隔离：把当前锁状态深拷贝留底（同一时刻只保留最近一次留底）。 */
    public void snapshotLockState() {
        copyTargetLocksInto(lockBaseline, targetLocks);
    }

    /** 捕获求值隔离：把锁状态整体恢复为最近一次 {@link #snapshotLockState()} 的留底（深拷贝回写）。 */
    public void restoreLockState() {
        copyTargetLocksInto(targetLocks, lockBaseline);
    }

    /**
     * 目标锁状态深拷贝（{@code src} → {@code dest}，dest 先清空）。
     * 捕获求值按只读语义处理：对锁的任何改动（扫描时间戳 / 目标切换 / 平滑过渡状态）都不得泄漏。
     */
    private static void copyTargetLocksInto(Map<String, TargetLock> dest, Map<String, TargetLock> src) {
        dest.clear();
        for (Map.Entry<String, TargetLock> entry : src.entrySet()) {
            TargetLock s = entry.getValue();
            TargetLock d = new TargetLock();
            d.uuid = s.uuid;
            d.entity = s.entity;
            d.resolvedAt = s.resolvedAt;
            d.switchGeneration = s.switchGeneration;
            d.cacheKey = s.cacheKey;
            d.searching = s.searching;
            d.nextRetryAt = s.nextRetryAt;
            d.lastSwitchAt = s.lastSwitchAt;
            for (Map.Entry<String, PointState> point : s.points.entrySet()) {
                PointState ps = point.getValue();
                PointState pd = new PointState();
                pd.last = ps.last;
                pd.generation = ps.generation;
                pd.from = ps.from;
                pd.startNanos = ps.startNanos;
                pd.smooth = ps.smooth;
                d.points.put(point.getKey(), pd);
            }
            dest.put(entry.getKey(), d);
        }
    }

    /**
     * 解析分发：服务端选择器走请求 + 缓存，本地选择器同步求值。
     * 搜索态（{@code searching=true}）下本地选择器按 {@link #MISS_RETRY_MS} 节流重扫；
     * 服务端选择器的请求节流在 {@link #resolveServerSelector} 内（读缓存不受节流）。
     * {@code pick} = 择一策略（{@link #selectorPick}），只影响从候选取定目标处。
     */
    private Entity resolveEntityInternal(String selector, Vec3 anchorPos, long refreshMs, boolean searching,
                                        TargetLock lock, String pick) {
        if (requiresServerSelector(selector)) {
            return resolveServerSelector(anchorPos, refreshMs, searching, lock, pick);
        }
        if (searching) {
            long now = System.currentTimeMillis();
            if (now < lock.nextRetryAt) {
                return null;
            }
            lock.nextRetryAt = now + MISS_RETRY_MS;
        }
        return resolveLocalSelector(selector, anchorPos, pick);
    }

    /**
     * 本地解析：只处理 {@code @e} / {@code @e[type=…,name=…]} / {@code uuid:xxx}，其余告警后按无匹配处理。
     * {@code @e} 系列的择一：{@code first} / {@code nearest} 按到 {@code anchorPos} 的距离取最近，
     * {@code alive} 取世界迭代序首个存活候选（候选 = 类型 / 名字过滤后的已加载存活实体）。
     */
    private Entity resolveLocalSelector(String selector, Vec3 anchorPos, String pick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Entity found = null;
        if (selector.startsWith("uuid:")) {
            try {
                UUID uuid = UUID.fromString(selector.substring(5));
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (uuid.equals(e.getUUID())) {
                        found = e;
                        break;
                    }
                }
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("无效的实体 UUID selector '{}': {}", selector, ex.getMessage());
            }
        } else if ("@e".equals(selector) || selector.startsWith("@e[")) {
            String typeId = null;
            String name = null;
            if (selector.startsWith("@e[")) {
                String inner = selector.substring(3, selector.length() - 1);
                for (String kv : inner.split(",")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String key = kv.substring(0, eq).trim();
                    String val = kv.substring(eq + 1).trim();
                    if ("type".equals(key)) typeId = val;
                    else if ("name".equals(key)) name = val;
                    // 未知选项忽略（容错，不崩溃）
                }
            }
            final String fType = typeId;
            final String fName = name;
            boolean alivePick = PICK_ALIVE.equals(pick);
            double bestDist = Double.MAX_VALUE;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!e.isAlive()) continue;
                if (fType != null && !fType.equals(EntityType.getKey(e.getType()).toString())) continue;
                if (fName != null
                        && (e.getCustomName() == null || !fName.equals(e.getCustomName().getString()))) continue;
                if (alivePick) {
                    // 存活优先：迭代序首个存活候选，不比距离
                    found = e;
                    break;
                }
                double dist = e.distanceToSqr(anchorPos);
                if (dist < bestDist) {
                    bestDist = dist;
                    found = e;
                }
            }
        } else {
            LOGGER.warn("不支持的实体 selector: {}（支持 @p/@s/@e/@e[type=…,name=…]/uuid:xxx）", selector);
        }
        return found;
    }

    /**
     * 是否交给服务端解析（判定口径见类 javadoc「解析分两条路径」）：本方法只按形态分类，不校验选项语义。
     * 带选项的 {@code @p} / {@code @a} / {@code @r}（{@code @x[…]}）与 {@code @e[…]} 的原版扩展选项为真；
     * 无选项 {@code @p} / {@code @s}、{@code @e} / {@code @e[type=…,name=…]} / {@code uuid:…} 与未知类型 /
     * 畸形括号为假（由本地路径告警 + 按无匹配处理）。纯判定，无副作用。
     */
    private static boolean requiresServerSelector(String selector) {
        if (selector == null || selector.length() < 2 || selector.charAt(0) != '@') {
            return false;
        }
        char kind = selector.charAt(1);
        // 无选项 @p 走 resolveEntity 快路径（返回本地玩家），故这里只放行无选项 @a / @r；
        // 带选项的 @p / @a / @r 一律交服务端（选项语义由原版解析，不在此裁剪）；缺 ] 的畸形形态落回本地告警。
        if (kind == 'p' || kind == 'a' || kind == 'r') {
            if (selector.length() == 2) {
                return kind != 'p';
            }
            return selector.charAt(2) == '[' && selector.endsWith("]");
        }
        if (!selector.startsWith("@e[") || !selector.endsWith("]")) {
            return false;
        }
        String inner = selector.substring(3, selector.length() - 1);
        int depth = 0;
        int start = 0;
        int typeCount = 0;
        for (int i = 0; i <= inner.length(); i++) {
            char c = i < inner.length() ? inner.charAt(i) : ',';
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String kv = inner.substring(start, i).trim();
                start = i + 1;
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = kv.substring(0, eq).trim();
                String val = kv.substring(eq + 1).trim();
                if ("type".equals(key)) {
                    typeCount++;
                    if (val.startsWith("!") || val.startsWith("#") || typeCount > 1) {
                        return true;
                    }
                } else if ("name".equals(key)) {
                    if (val.startsWith("!")) {
                        return true;
                    }
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 服务端路径：请求服务端用原版选择器求值，再把回传 UUID 映射回客户端实体。
     * 读写缓存一律用锁固化的键（调用点 + 锚点 + selector，见 {@link ClientEntitySelectorCache.Key}），
     * 同一 selector 的不同调用点 / 不同锚点各占一份条目；{@code anchorPos} 随请求发给服务端作为选择器原点。
     * 跟踪态按 {@code refreshMs} 刷新（pending 期间不重复发请求）；搜索态每帧读缓存，上一份结果已消费且
     * 仍无可用目标时按 {@link #MISS_RETRY_MS} 节流重发——不等 {@code refreshMs}。
     * 回传列表的择一：{@code first} / {@code alive} 取 UUID 序首个可用，{@code nearest} 取可用中距
     * {@code anchorPos} 最近（平方距离比较；不改变服务端排序）。
     *
     * @return 可用实体；无缓存条目 / 回传 UUID 均不可用 = {@code null}（调用方按无目标处理）
     */
    private Entity resolveServerSelector(Vec3 anchorPos, long refreshMs, boolean searching, TargetLock lock,
                                         String pick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        ClientEntitySelectorCache.Entry entry = ClientEntitySelectorCache.get(lock.cacheKey);
        if (entry == null) {
            // 首次请求：本帧无结果
            lock.nextRetryAt = now + MISS_RETRY_MS;
            ClientEntitySelectorCache.request(lock.cacheKey, anchorPos.x, anchorPos.y, anchorPos.z);
            return null;
        }

        // 先消费已有结果：每帧都读，回包下一帧就能绑定（不受重试节流影响）
        List<UUID> uuids;
        synchronized (entry) {
            uuids = entry.uuids;
        }
        if (uuids != null) {
            boolean nearestPick = PICK_NEAREST.equals(pick);
            Entity nearest = null;
            double bestDist = Double.MAX_VALUE;
            for (UUID uuid : uuids) {
                Entity entity = findEntityByUuid(mc, uuid);
                if (entity == null || !entity.isAlive()) continue;
                if (!nearestPick) {
                    // first / alive：回传 UUID 序首个可用
                    return entity;
                }
                double dist = entity.distanceToSqr(anchorPos);
                if (dist < bestDist) {
                    bestDist = dist;
                    nearest = entity;
                }
            }
            if (nearest != null) {
                return nearest;
            }
        }

        // 没有可用结果 → 判断是否（重新）请求
        boolean needRequest = !entry.pending
                && (searching ? now >= lock.nextRetryAt : now - entry.resolvedAt >= refreshMs);
        if (needRequest) {
            lock.nextRetryAt = now + MISS_RETRY_MS;
            ClientEntitySelectorCache.request(lock.cacheKey, anchorPos.x, anchorPos.y, anchorPos.z);
        }
        return null;
    }

    /** 在客户端已加载实体中按 UUID 查找；level 未加载或 UUID 为 {@code null} 返回 {@code null}。 */
    private static Entity findEntityByUuid(Minecraft mc, UUID uuid) {
        if (mc.level == null || uuid == null) {
            return null;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (uuid.equals(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }

    /** 把锁定的 UUID 映射回客户端实体；已卸载 / 死亡返回 {@code null}。 */
    private Entity lockEntity(TargetLock lock) {
        Minecraft mc = Minecraft.getInstance();
        if (lock.entity != null && lock.entity.isAlive()) {
            return lock.entity;
        }
        if (lock.uuid != null) {
            Entity e = findEntityByUuid(mc, lock.uuid);
            if (e != null && e.isAlive()) {
                lock.entity = e;
                return e;
            }
        }
        return null;
    }

    /** 读「调用点专属字段 → 通用字段 → 缺省值」。 */
    private static float floatForCallpoint(Keyframe kf, String field, String callpoint, float fallback) {
        if (callpoint != null) {
            String key = field + "_" + callpoint;
            if (kf.getData().containsKey(key)) return kf.getFloat(key, fallback);
        }
        return kf.getFloat(field, fallback);
    }

    /** 读「调用点专属字段 → 通用字段 → 缺省值」（字符串口径，与 {@link #floatForCallpoint} 同规则）。 */
    private static String stringForCallpoint(Keyframe kf, String field, String callpoint, String fallback) {
        if (callpoint != null) {
            String key = field + "_" + callpoint;
            if (kf.getData().containsKey(key)) return kf.getString(key, fallback);
        }
        return kf.getString(field, fallback);
    }

    private static boolean boolForCallpoint(Keyframe kf, String field, String callpoint, boolean fallback) {
        if (callpoint != null) {
            String key = field + "_" + callpoint;
            if (kf.getData().containsKey(key)) return kf.getBool(key, fallback);
        }
        return kf.getBool(field, fallback);
    }

    /** 锁的键：{@code role + NUL + 锚点 + NUL + selector}（同一 selector 的不同调用点 / 不同锚点各持一份锁）。 */
    private static String targetKey(String role, String anchor, String selector) {
        return role + "\u0000" + anchor + "\u0000" + selector;
    }

    /** 新建目标锁，并固化其选择器缓存键（调用点 + 锚点 + selector），使缓存访问不再逐帧拼键。 */
    private static TargetLock newLock(String role, String anchor, String selector) {
        TargetLock lock = new TargetLock();
        lock.cacheKey = new ClientEntitySelectorCache.Key(role, anchor, selector);
        return lock;
    }

    /** 诊断用：把长选择器压成短标签（长度 / 派系 / 排除项），避免刷屏。 */
    private static String describeSelector(String selector) {
        if (selector == null) return "null";
        String faction = "?";
        Matcher m = Pattern
                .compile("FactionID':\\\\?'?([a-zA-Z]+)").matcher(selector);
        if (m.find()) faction = m.group(1);
        boolean noPlayer = selector.contains("type=!minecraft:player");
        boolean noProjectile = selector.contains("type=!#minecraft:impact_projectiles");
        boolean noSummon = selector.contains("BetterEvE:Summoned");
        return "len=" + selector.length() + " faction=" + faction
                + (noPlayer ? " +noPlayer" : " !noPlayer")
                + (noProjectile ? " +noProjectile" : " !noProjectile")
                + (noSummon ? " +noSummon" : " !noSummon");
    }
}
