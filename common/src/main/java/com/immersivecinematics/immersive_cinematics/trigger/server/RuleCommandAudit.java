package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * 规则命令审计：记录被拒绝的请求与实际执行的动作，<b>不记命令原文</b>（避免日志二次泄露）。
 *
 * <p>记录口径：玩家、脚本、实例、动作引用（binding / clip / rule / action 索引）、结果。
 * 拒绝走 {@link ErrorLog#logRateLimited}（限频，避免恶意客户端刷屏）；执行成功只进 debug
 * （正常规则触发是高频事件，不应污染服务端日志）。审计不参与判定，只用于运营排查。</p>
 *
 * <p>线程契约：仅服务端主线程。</p>
 */
public final class RuleCommandAudit {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 同一拒绝原因的日志窗口（毫秒）。 */
    private static final long LOG_INTERVAL_MS = 5000L;

    private RuleCommandAudit() {}

    /**
     * 记录一次被拒绝的规则命令请求。
     *
     * @param reason 拒绝原因（简短、稳定，用作日志限频键；不得包含命令原文）
     */
    public static void reject(ServerPlayer player, String scriptId, String instanceId, String bindingId,
                              int clipIndex, int ruleIndex, int actionIndex, String reason) {
        String who = who(player);
        String ref = ref(bindingId, clipIndex, ruleIndex, actionIndex);
        LOGGER.debug("规则命令拒绝（{}）：玩家={} 脚本={} 实例={} 动作={}", reason, who, scriptId, instanceId, ref);
        ErrorLog.logRateLimited("RuleCommand", "reject:" + reason, LOG_INTERVAL_MS,
                "规则命令被拒绝（" + reason + "）：玩家=" + who + " 脚本=" + scriptId
                        + " 实例=" + instanceId + " 动作=" + ref);
    }

    /**
     * 记录一次实际执行的规则命令。
     *
     * @param segments 实际尝试执行的命令段数（{@code &&} 拆分后）
     * @param failed   抛异常的命令段数
     */
    public static void executed(ServerPlayer player, String scriptId, String instanceId, String bindingId,
                                int clipIndex, int ruleIndex, int actionIndex, int segments, int failed) {
        String who = who(player);
        String ref = ref(bindingId, clipIndex, ruleIndex, actionIndex);
        if (failed > 0) {
            LOGGER.debug("规则命令部分失败：玩家={} 脚本={} 实例={} 动作={} 段数={} 失败={}",
                    who, scriptId, instanceId, ref, segments, failed);
            ErrorLog.logRateLimited("RuleCommand", "execute:failed", LOG_INTERVAL_MS,
                    "规则命令有片段执行失败：玩家=" + who + " 脚本=" + scriptId + " 实例=" + instanceId
                            + " 动作=" + ref + " 段数=" + segments + " 失败=" + failed);
            return;
        }
        LOGGER.debug("规则命令已执行：玩家={} 脚本={} 实例={} 动作={} 段数={}",
                who, scriptId, instanceId, ref, segments);
    }

    private static String who(ServerPlayer player) {
        return player == null ? "<无玩家>" : player.getGameProfile().getName() + "/" + player.getUUID();
    }

    private static String ref(String bindingId, int clipIndex, int ruleIndex, int actionIndex) {
        String binding = bindingId == null || bindingId.isEmpty() ? "<旧 clip.rules>" : bindingId;
        return "binding=" + binding + " clip=" + clipIndex + " rule=" + ruleIndex + " action=" + actionIndex;
    }
}
