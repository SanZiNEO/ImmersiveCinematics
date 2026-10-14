package com.immersivecinematics.immersive_cinematics.camera.cover;

/**
 * 规则动作 Modifier（{@code camera_set}，0.3.6 C6-3）：与 {@link ClipModifier} <b>同值形态</b>
 * （8 值槽 + 通道掩码 + 合成模式 + 应用序），区别只在建出时机与存活期——
 * 值形态由规则动作在<b>触发那一刻</b>建出，注册进覆盖链（{@code PlaybackInstance}），
 * 片段活跃窗末 / 规则引擎重建 / 停播时注销（注册与回收由 {@code RuleEngine} 的工件生命周期驱动）。
 *
 * 语义 = <b>持续叠加</b>直到片段结束或被同类动作覆盖（设计 §5「参数调整」族）：注册期逐帧参与合成，
 * {@link #isActiveThisFrame()} 恒真——活跃窗由注册 / 注销驱动，不在本类内再判一次。
 * {@code blend} 过渡 / {@code look_at} / {@code follow} 偏移叠加留 v2（v1 = 瞬时叠加）。
 * 分配契约：值数组在解析期建一次（触发帧可分配），逐帧 {@link #writeValue(CameraStateBuffer)} 只做字段直写（零分配）。
 * 线程契约：仅客户端主线程；注册 / 注销与每帧合成同线程。
 */
public final class RuleModifier implements CameraModifier {

    /** 值形态本体（与片段级静态声明同一份实现：槽位序 / 掩码过滤 / 合成语义唯一定义点）。 */
    private final ClipModifier values;

    /**
     * @param priority 应用序（升序，小者先应用）
     * @param mask     生效通道位掩码（{@link CameraChannels} 的位；{@code 0} = 不生效）
     * @param mode     合成方式；{@code null} = 逐通道缺省
     * @param values   通道值（长度 {@link ClipModifier#VALUE_COUNT}，槽位序见 {@code ClipModifier.SLOT_*}）
     */
    public RuleModifier(int priority, int mask, ComposeMode mode, double[] values) {
        this.values = new ClipModifier(priority, mask, mode, values);
    }

    @Override
    public int modifierPriority() {
        return values.modifierPriority();
    }

    @Override
    public int channelMask() {
        return values.channelMask();
    }

    @Override
    public ComposeMode composeMode() {
        return values.composeMode();
    }

    @Override
    public void writeValue(CameraStateBuffer out) {
        values.writeValue(out);
    }
}
