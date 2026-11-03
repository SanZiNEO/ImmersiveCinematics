package com.immersivecinematics.immersive_cinematics.trigger.server;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一次服务端播放请求的观看者授权：{@code (scriptId, instanceId)} 的目标玩家集合。
 *
 * <p>授权只能由“服务端发送播放包”的路径创建（{@link ScriptEventManager#createGrant}），
 * 不能由客户端播放回执创建：回执只能确认“已开始”，{@link ScriptEventManager#addViewer} 据此
 * 在授权集合内放行观看者。授权不是动作凭证——它只决定谁能进入播放账本，命令本身仍只来自
 * 服务端脚本定义。</p>
 *
 * <p>{@link #targets()} 是服务端生命周期内可清理的内部集合（玩家退出 / 实例结束时移除），
 * 不对外暴露修改入口。</p>
 *
 * @param scriptId    脚本 id
 * @param instanceId  播放实例 id（空串 = 无实例 id 的来源）
 * @param targets     目标玩家 UUID 集合
 * @param createdTick 创建时的服务端 tick（诊断用）
 */
public record PlaybackGrant(String scriptId, String instanceId, Set<UUID> targets, int createdTick) {

    public PlaybackGrant {
        scriptId = scriptId == null ? "" : scriptId;
        instanceId = instanceId == null ? "" : instanceId;
        targets = targets == null ? new HashSet<>() : new HashSet<>(targets);
    }
}
