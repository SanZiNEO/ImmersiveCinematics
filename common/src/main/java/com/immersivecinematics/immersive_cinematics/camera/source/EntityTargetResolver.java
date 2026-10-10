package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientEntitySelectorCache;
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
 * 目标实体解析与锁定：selector → 客户端实体，并按 {@code role + selector} 维护目标锁与切换平滑状态。
 *
 * 解析分两条路径：本地快路径（{@code @p} / {@code @s} / {@code @e} / {@code @e[type=…,name=…]} / {@code uuid:…}）
 * 同步求值；其余 {@code @e[…]}（{@code nbt} / {@code tag} / 反向 / 多值等原版扩展选项）交服务端解析，
 * 再按回传 UUID 映射到客户端实体。{@code @a} / {@code @r} / {@code @n} 不在支持范围（告警后按无匹配处理）。
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
     */
    private static final List<String> SELECTOR_CALLPOINTS = List.of(
            "follow", "look_at", "look_at_target", "yaw_base", "facing_origin", "facing_target");

    /** 目标锁状态：按 {@code role + selector} 维护。 */
    private final Map<String, TargetLock> targetLocks = new HashMap<>();

    /** 捕获求值隔离基线：{@link #snapshotLockState()} 留底，{@link #restoreLockState()} 回写。 */
    private final Map<String, TargetLock> lockBaseline = new HashMap<>();

    /**
     * 目标锁定状态：按 {@code role + selector} 维护。
     *
     * {@code uuid} / {@code entity} / {@code resolvedAt} = 当前锁定目标与刷新时间；{@code switchGeneration} 在
     * 目标 UUID 每次变化时 +1，供各通道检测「是否刚切换」；{@code points} = 各通道的平滑状态。
     */
    private static final class TargetLock {
        UUID uuid;
        Entity entity;
        long resolvedAt;
        long switchGeneration;
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

    /** 生命周期清理：清空目标锁与客户端选择器缓存（停止播放 / 脚本替换时调用）。 */
    public void clear() {
        targetLocks.clear();
        lockBaseline.clear();
        ClientEntitySelectorCache.clear();
    }

    /**
     * 解析目标实体：本地快路径同步求值，服务端选择器走请求 + 缓存回映射。
     *
     * @param selector 选择器字符串；{@code null} / 空串 = 无目标
     * @param origin   就近排序与请求锚点的世界坐标（调用方当前相机位置）
     * @param role     调用点名：决定策略字段与锁的键，见 {@link #SELECTOR_CALLPOINTS}
     * @param kf       策略字段来源关键帧；{@code null} = 全缺省策略
     * @return 解析到的客户端实体；无匹配 / 目标未加载 / 节流未到 = {@code null}
     */
    public Entity resolveEntity(String selector, Vec3 origin, String role, Keyframe kf) {
        Minecraft mc = Minecraft.getInstance();
        if (selector == null || selector.isEmpty()) return null;
        if ("@p".equals(selector) || "@s".equals(selector)) {
            return mc.player;
        }
        if (mc.level == null) return null;

        SelectorPolicy policy = selectorPolicy(kf, role);
        String key = targetKey(role, selector);
        TargetLock lock = targetLocks.computeIfAbsent(key, k -> new TargetLock());
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
            Entity found = resolveEntityInternal(selector, origin, refreshMs, true, lock);
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
            found = resolveEntityInternal(selector, origin, refreshMs, false, lock);
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
     * 目标点 / 位置切换平滑：按 {@code role + selector} 锁的通道（look_at / follow 位置 / 朝向基准）分别维护。
     * 只有目标 UUID 变化（{@code switchGeneration} 前进）时才启动过渡，稳定跟踪同一目标不额外延迟。
     *
     * @param channel       通道名：同一把锁下各通道互不影响
     * @param raw           本帧原始目标点（世界坐标）
     * @param smoothSeconds 过渡时长（秒）；{@code <= 0} = 立即跳到新目标
     * @return 平滑后的目标点；无对应锁（该目标未解析过）= {@code raw}
     */
    public Vec3 smoothTargetPoint(String role, String selector, String channel, Vec3 raw, float smoothSeconds) {
        if (raw == null) return null;
        TargetLock lock = targetLocks.get(targetKey(role, selector));
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
     */
    private Entity resolveEntityInternal(String selector, Vec3 origin, long refreshMs, boolean searching,
                                        TargetLock lock) {
        if (requiresServerSelector(selector)) {
            return resolveServerSelector(selector, origin, refreshMs, searching, lock);
        }
        if (searching) {
            long now = System.currentTimeMillis();
            if (now < lock.nextRetryAt) {
                return null;
            }
            lock.nextRetryAt = now + MISS_RETRY_MS;
        }
        return resolveLocalSelector(selector, origin);
    }

    /** 本地解析：只处理 {@code @e} / {@code @e[type=…,name=…]} / {@code uuid:xxx}，其余告警后按无匹配处理。 */
    private Entity resolveLocalSelector(String selector, Vec3 origin) {
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
            double bestDist = Double.MAX_VALUE;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!e.isAlive()) continue;
                if (fType != null && !fType.equals(EntityType.getKey(e.getType()).toString())) continue;
                if (fName != null
                        && (e.getCustomName() == null || !fName.equals(e.getCustomName().getString()))) continue;
                double dist = e.distanceToSqr(origin);
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
     * 是否交给服务端解析：仅 {@code @e[…]}` 形态可能为真。
     * 本地只支持普通 {@code type=xxx} / {@code name=xxx}；{@code nbt} / {@code tag} / {@code distance} /
     * {@code sort} / {@code limit} 等其他选项、实体类型 tag（{@code type=#tag}）、反向 type
     * （{@code type=!xxx} / {@code type=!#tag}）、多个 type 选项、反向 name 一律交服务端。
     */
    private static boolean requiresServerSelector(String selector) {
        if (selector == null || !selector.startsWith("@e[") || !selector.endsWith("]")) {
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
     * 跟踪态按 {@code refreshMs} 刷新（pending 期间不重复发请求）；搜索态每帧读缓存，上一份结果已消费且
     * 仍无可用目标时按 {@link #MISS_RETRY_MS} 节流重发——不等 {@code refreshMs}。
     *
     * @return 可用实体；无缓存条目 / 回传 UUID 均不可用 = {@code null}（调用方按无目标处理）
     */
    private Entity resolveServerSelector(String selector, Vec3 origin, long refreshMs, boolean searching,
                                        TargetLock lock) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        ClientEntitySelectorCache.Entry entry = ClientEntitySelectorCache.get(selector);
        if (entry == null) {
            // 首次请求：本帧无结果
            lock.nextRetryAt = now + MISS_RETRY_MS;
            ClientEntitySelectorCache.request(selector, origin.x, origin.y, origin.z);
            return null;
        }

        // 先消费已有结果：每帧都读，回包下一帧就能绑定（不受重试节流影响）
        List<UUID> uuids;
        synchronized (entry) {
            uuids = entry.uuids;
        }
        if (uuids != null) {
            for (UUID uuid : uuids) {
                Entity entity = findEntityByUuid(mc, uuid);
                if (entity != null && entity.isAlive()) {
                    return entity;
                }
            }
        }

        // 没有可用结果 → 判断是否（重新）请求
        boolean needRequest = !entry.pending
                && (searching ? now >= lock.nextRetryAt : now - entry.resolvedAt >= refreshMs);
        if (needRequest) {
            lock.nextRetryAt = now + MISS_RETRY_MS;
            ClientEntitySelectorCache.request(selector, origin.x, origin.y, origin.z);
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

    private static boolean boolForCallpoint(Keyframe kf, String field, String callpoint, boolean fallback) {
        if (callpoint != null) {
            String key = field + "_" + callpoint;
            if (kf.getData().containsKey(key)) return kf.getBool(key, fallback);
        }
        return kf.getBool(field, fallback);
    }

    /** 锁的键：{@code role + NUL + selector}（同一 selector 在不同调用点各持一份锁）。 */
    private static String targetKey(String role, String selector) {
        return role + "\u0000" + selector;
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
