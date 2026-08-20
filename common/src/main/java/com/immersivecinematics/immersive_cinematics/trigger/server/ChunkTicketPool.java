package com.immersivecinematics.immersive_cinematics.trigger.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * 区块临时强加载票据池（借鉴 ChunkLoaders 的全局票据 + 引用计数）：
 * <ul>
 *   <li>所有请求方（far 相机区 / lookahead PREWARM / 将来多人）共享一张全局 TicketType</li>
 *   <li>{@code Map<ChunkPos, Integer>} 引用计数：有人要 +1，没人要（count 0）才真正 removeRegionTicket</li>
 *   <li>far 接管 prewarm 的块时只是"所有权转移"，票不撤、count 不变 → 无重复票/无残留</li>
 *   <li>释放从未请求的块直接抛 {@link IllegalStateException}（有错即报，不 try/catch）</li>
 * </ul>
 */
public final class ChunkTicketPool {

    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("immersive_cinematics_pool", Comparator.comparingLong(ChunkPos::toLong));

    /** 按区块、按 ticket distance 分别引用计数；distance=1 → block ticking，distance=2 → entity ticking */
    private final Map<ChunkPos, Map<Integer, Integer>> refs = new HashMap<>();

    public void request(ServerLevel level, ChunkPos pos) {
        request(level, pos, 1);
    }

    public void request(ServerLevel level, ChunkPos pos, int distance) {
        Map<Integer, Integer> byDistance = refs.computeIfAbsent(pos, k -> new HashMap<>());
        int count = byDistance.getOrDefault(distance, 0);
        if (count == 0) {
            level.getChunkSource().addRegionTicket(TICKET, pos, distance, pos);
        }
        byDistance.put(distance, count + 1);
    }

    public void release(ServerLevel level, ChunkPos pos) {
        release(level, pos, 1);
    }

    public void release(ServerLevel level, ChunkPos pos, int distance) {
        Map<Integer, Integer> byDistance = refs.get(pos);
        if (byDistance == null) {
            throw new IllegalStateException("ChunkTicketPool.release 释放一个从未请求的区块: " + pos);
        }
        Integer count = byDistance.get(distance);
        if (count == null || count <= 0) {
            throw new IllegalStateException("ChunkTicketPool.release 释放一个从未请求的 ticket distance " + distance + " 区块: " + pos);
        }
        if (count == 1) {
            level.getChunkSource().removeRegionTicket(TICKET, pos, distance, pos);
            byDistance.remove(distance);
        } else {
            byDistance.put(distance, count - 1);
        }
        if (byDistance.isEmpty()) {
            refs.remove(pos);
        }
    }

    public int refs(ChunkPos pos) {
        Map<Integer, Integer> byDistance = refs.get(pos);
        if (byDistance == null) return 0;
        int sum = 0;
        for (int count : byDistance.values()) sum += count;
        return sum;
    }
}
