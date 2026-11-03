package com.immersivecinematics.immersive_cinematics.trigger.network;

import com.immersivecinematics.immersive_cinematics.trigger.server.ScriptEventManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 → 服务端：规则动作 {@code emit_command} 的动作引用。
 *
 * <p><b>不携带命令原文</b>。客户端只说明“执行我这条脚本里这个实例的这个动作”，服务端按自己加载的
 * 脚本解析出命令（{@code RuleCommandTable}），再以服务端配置的权限上限执行：伪造 / 猜中一个动作引用
 * 不等于获得执行权限，也不能引入脚本外命令。</p>
 *
 * <p>动作引用两种形态（与脚本规则形态一一对应）：
 * <ul>
 *   <li>脚本级规则：{@code bindingId} 非空，{@code clipIndex} / {@code ruleIndex} 传 -1；</li>
 *   <li>旧 {@code clip.rules}：{@code bindingId} 为空串，{@code clipIndex} = 平铺片段索引
 *       （轨道序 → 轨道内片段序，与 {@code ClipExecutionLedger} 同口径），{@code ruleIndex} =
 *       片段内规则下标。</li>
 * </ul>
 *
 * <p>授权：服务端在发送播放包时按 {@code (scriptId, instanceId)} 签发观看者授权
 * （{@link ScriptEventManager#createGrant}）；本包只在授权集合内的玩家手里才被处理。
 * 旧原文协议不保留兼容：旧布局包按解码异常丢弃（{@code PacketGuard} 兜底）。</p>
 *
 * {@code instanceId} 为 {@link S2CPlayScriptPacket#getInstanceId()} 的原样回传；
 * 无实例 id 的来源（本地播放）回传空串。
 */
public class C2SRuleCommandPacket implements CinematicC2SPacket {

    private final String scriptId;
    /** 播放实例 id；空串=无实例 id 的来源 */
    private final String instanceId;
    /** 规则绑定 id；空串 = 旧 {@code clip.rules} 形态 */
    private final String bindingId;
    /** 平铺片段索引（轨道序 → 轨道内片段序）；脚本级规则 = -1 */
    private final int clipIndex;
    /** 片段内规则下标；脚本级规则 = -1 */
    private final int ruleIndex;
    /** 动作列表下标 */
    private final int actionIndex;

    public C2SRuleCommandPacket(String scriptId, String instanceId, String bindingId,
                                int clipIndex, int ruleIndex, int actionIndex) {
        this.scriptId = scriptId == null ? "" : scriptId;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.bindingId = bindingId == null ? "" : bindingId;
        this.clipIndex = clipIndex;
        this.ruleIndex = ruleIndex;
        this.actionIndex = actionIndex;
    }

    public C2SRuleCommandPacket(FriendlyByteBuf buf) {
        this.scriptId = buf.readUtf();
        this.instanceId = buf.readUtf();
        this.bindingId = buf.readUtf();
        this.clipIndex = buf.readInt();
        this.ruleIndex = buf.readInt();
        this.actionIndex = buf.readInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(scriptId);
        buf.writeUtf(instanceId);
        buf.writeUtf(bindingId);
        buf.writeInt(clipIndex);
        buf.writeInt(ruleIndex);
        buf.writeInt(actionIndex);
    }

    @Override
    public void handle(ServerPlayer player) {
        ScriptEventManager.INSTANCE.handleRuleCommand(
                player, scriptId, instanceId, bindingId, clipIndex, ruleIndex, actionIndex);
    }

    public String getScriptId() { return scriptId; }
    public String getInstanceId() { return instanceId; }
    public String getBindingId() { return bindingId; }
    public int getClipIndex() { return clipIndex; }
    public int getRuleIndex() { return ruleIndex; }
    public int getActionIndex() { return actionIndex; }
}
