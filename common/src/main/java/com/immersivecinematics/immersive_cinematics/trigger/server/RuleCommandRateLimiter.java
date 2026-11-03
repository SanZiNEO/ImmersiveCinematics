package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.util.Clock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 规则命令的频率限制：每 {@code (玩家, 实例)} 一个令牌桶。
 *
 * <p>容量 {@value #CAPACITY}、每秒补 {@value #REFILL_PER_SECOND} 个令牌：正常的规则触发频率远低于此，
 * 超限只可能是恶意 / 失控客户端。超限请求直接丢弃（不排队补执行），由
 * {@link RuleCommandAudit} 限频记录。</p>
 *
 * <p>时间源是 {@link Clock}（秒）：生产用单调系统时钟，探针可注入假时钟推进。桶在
 * {@link #RETENTION_SECONDS} 秒未使用后随阈值触发清理，玩家退出 / 实例结束可显式 {@link #forget}。</p>
 *
 * <p>线程契约：仅服务端主线程。</p>
 */
public final class RuleCommandRateLimiter {

    /** 桶容量（突发上限）。 */
    public static final int CAPACITY = 20;

    /** 令牌补充速率（个/秒）。 */
    public static final double REFILL_PER_SECOND = 10.0;

    /** 空桶保留时长（秒）：超过即视为空闲，清理时不保留状态。 */
    private static final double RETENTION_SECONDS = 60.0;

    /** 触发清理的桶数阈值。 */
    private static final int PRUNE_THRESHOLD = 256;

    /** 生产实例（服务端主线程；单调系统时钟）。 */
    public static final RuleCommandRateLimiter INSTANCE =
            new RuleCommandRateLimiter(() -> System.nanoTime() / 1e9);

    private final Clock clock;
    private final Map<Key, Bucket> buckets = new HashMap<>();

    /**
     * @param clock 时间源（秒，单调；不得为 {@code null}）
     */
    public RuleCommandRateLimiter(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("RuleCommandRateLimiter 需要时间源");
        }
        this.clock = clock;
    }

    /**
     * 尝试消耗一个令牌。
     *
     * @return {@code true} = 本次请求放行；{@code false} = 超限丢弃
     */
    public boolean tryAcquire(UUID player, String instanceId) {
        if (player == null) return false;
        double now = clock.seconds();
        Key key = new Key(player, instanceId == null ? "" : instanceId);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            bucket = new Bucket(CAPACITY, now);
            buckets.put(key, bucket);
            if (buckets.size() > PRUNE_THRESHOLD) prune(now);
        }
        double elapsed = now - bucket.last;
        if (elapsed > 0.0) {
            bucket.tokens = Math.min(CAPACITY, bucket.tokens + elapsed * REFILL_PER_SECOND);
            bucket.last = now;
        }
        if (bucket.tokens < 1.0) return false;
        bucket.tokens -= 1.0;
        return true;
    }

    /** 丢弃某个 {@code (玩家, 实例)} 的令牌状态（玩家退出 / 实例结束；幂等）。 */
    public void forget(UUID player, String instanceId) {
        if (player == null) return;
        buckets.remove(new Key(player, instanceId == null ? "" : instanceId));
    }

    /** 丢弃某个玩家的全部令牌状态（玩家退出；幂等）。 */
    public void forgetPlayer(UUID player) {
        if (player == null) return;
        buckets.keySet().removeIf(key -> key.player().equals(player));
    }

    /** 清空全部令牌状态（服务端停止 / 脚本全量重载；幂等）。 */
    public void clear() {
        buckets.clear();
    }

    /** 当前保留的桶数（诊断 / 探针）。 */
    public int size() {
        return buckets.size();
    }

    private void prune(double now) {
        Iterator<Map.Entry<Key, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().last > RETENTION_SECONDS) {
                it.remove();
            }
        }
    }

    private record Key(UUID player, String instanceId) {}

    private static final class Bucket {
        double tokens;
        double last;

        Bucket(double tokens, double last) {
            this.tokens = tokens;
            this.last = last;
        }
    }
}
