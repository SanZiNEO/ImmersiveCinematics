package com.immersivecinematics.immersive_cinematics.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端播放门面（对外稳定面）：给指定玩家播放 / 停止服务端脚本表里的脚本。
 *
 * <p><b>线程契约</b>：仅服务端主线程；跨线程调用方须自行 marshal（例如 {@code server.execute}）。
 *
 * <p>实现复用与触发器动作 / {@code /icinematics play} 相同的链：脚本表查找 → 结构来源按玩家位置替换 →
 * 规则命令表编译 → 观看者授权 → 下发播放包。观看者身份只由本机签发的授权决定，客户端回执只能消费。
 *
 * <p>稳定性：本包（{@code api}）内的类型与方法<b>只增不删</b>，语义不变。
 */
public interface ServerPlaybackApi {

    /**
     * 让指定玩家播放脚本。
     *
     * @param player     目标玩家；{@code null} = 直接失败
     * @param scriptId   服务端脚本表里的脚本 id；{@code null} / 空白 = 失败
     * @param instanceId 播放实例 id；{@code null} = 空串（匿名实例）
     * @return {@code true} = 播放包已发出；{@code false} = 参数非法 / 脚本不存在 / 脚本过大未发出
     */
    boolean playFor(ServerPlayer player, String scriptId, String instanceId);

    /**
     * 让指定玩家停止脚本。
     *
     * <p>只派发停止包，不动服务端账本：观看者 / 实例记录由客户端回执
     * （{@code C2SScriptFinishedPacket}）按 {@code (scriptId, instanceId)} 回收。
     *
     * @param player     目标玩家；{@code null} = 直接失败
     * @param scriptId   脚本 id；{@code null} = 失败（空串 = 停止全部，与停止包语义一致）
     * @param instanceId 播放实例 id；{@code null} = 空串（该脚本任意实例）
     * @return {@code true} = 停止包已派发；{@code false} = 参数非法
     */
    boolean stopFor(ServerPlayer player, String scriptId, String instanceId);

    /** 当前实现。 */
    static ServerPlaybackApi get() {
        return Holder.INSTANCE;
    }

    /** 持有单例；仅供 {@link #get()} 内部使用，不要直接引用。 */
    final class Holder {
        static final ServerPlaybackApi INSTANCE = new ServerPlaybackApiImpl();

        private Holder() {}
    }
}
