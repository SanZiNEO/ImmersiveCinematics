package com.immersivecinematics.immersive_cinematics.camera.cover;

import com.immersivecinematics.immersive_cinematics.api.BaseCameraProvider;
import com.immersivecinematics.immersive_cinematics.api.CameraState;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆盖链本体：Base 选择 + Modifier 合成（每帧 {@link #snapshot()} 或 {@link #evaluate(CameraStateBuffer)}）。
 *
 * 组合次序固定：Base Chain → Modifier Chain → 最终状态。
 * Base 选择：按优先级从高到低取第一个活跃层；同优先级 = 后注册者胜（后来者居上）；无活跃层 = {@code null}。
 * Modifier 应用：按优先级升序逐条合成（同值 = 先注册者先应用），本帧不活跃者
 * （{@link CameraModifier#isActiveThisFrame()} 为 false）跳过；胜出 Base 层
 * {@link BaseCameraProvider#suppressesModifiers()} 为 true 时整条跳过。
 * 切换语义：层间切换为 Cut——不混合、不过渡、不迟滞；链上不做 clamp / normalize。
 * 分配契约：注册表只在注册 / 注销时改动（不在帧内）；每帧零新增分配，唯一分配点 = 快照 record 的产出
 * （工作区 {@link CameraStateBuffer} 跨帧复用）。
 * 线程契约：仅客户端主线程；注册 / 注销与每帧求值同线程。
 */
public final class CameraCoverChain {

    /** Base 注册表：注册顺序 = 同优先级次序（后注册者胜）。 */
    private final List<BaseCameraProvider> providers = new ArrayList<>(4);

    /** Modifier 注册表：优先级升序（同值 = 注册先后），注册时插入排序。 */
    private final List<CameraModifier> modifiers = new ArrayList<>(8);

    /** Base 状态 → 最终状态的工作区（跨帧复用）。 */
    private final CameraStateBuffer workspace = new CameraStateBuffer();

    /** Modifier 值工作区：每条 Modifier 写这里、随即被合成进工作区（跨条、跨帧复用）。 */
    private final CameraStateBuffer modifierValues = new CameraStateBuffer();

    /** 本帧是否有 Modifier 参与合成（{@link #evaluate} 刷新；无活跃 Base 时为 {@code false}）。 */
    private boolean modifiersApplied = false;

    /** 本帧胜出 Base 层的档位（{@link #evaluate} 刷新；无活跃 Base 时为 {@link Integer#MIN_VALUE}）。 */
    private int winnerPriority = Integer.MIN_VALUE;

    /** 注册一层 Base；同一实例重复注册 = 多份（注销按对象逐个移除）。 */
    public void register(BaseCameraProvider provider) {
        providers.add(provider);
    }

    /** 注销一层 Base；未注册过时为无操作。 */
    public void unregister(BaseCameraProvider provider) {
        providers.remove(provider);
    }

    /** 注册一条 Modifier：按优先级升序插入（同值排在先注册者之后）；同一实例重复注册 = 多份。 */
    public void register(CameraModifier modifier) {
        int index = modifiers.size();
        for (int i = 0; i < modifiers.size(); i++) {
            if (modifiers.get(i).modifierPriority() > modifier.modifierPriority()) {
                index = i;
                break;
            }
        }
        modifiers.add(index, modifier);
    }

    /** 注销一条 Modifier；未注册过时为无操作。 */
    public void unregister(CameraModifier modifier) {
        modifiers.remove(modifier);
    }

    /** 本帧链输出快照；无活跃 Base 时返回 {@code null}。 */
    public CameraState snapshot() {
        return evaluate(workspace) ? workspace.toSnapshot() : null;
    }

    /**
     * 求出本帧链输出并读进 {@code out}（Base 选择 → Modifier 合成）——内部组合入口（探针 / 测试直调），不进 api 面。
     *
     * @param out 输出工作区；无活跃 Base 时不改动
     * @return {@code false} = 无活跃 Base（{@code out} 未改动）
     */
    public boolean evaluate(CameraStateBuffer out) {
        BaseCameraProvider winner = selectWinner();
        if (winner == null) {
            modifiersApplied = false;
            winnerPriority = Integer.MIN_VALUE;
            return false;
        }
        out.set(winner.provideBaseState());
        winnerPriority = winner.basePriority();
        modifiersApplied = !winner.suppressesModifiers() && composeModifiers(out);
        return true;
    }

    /**
     * 本帧链上是否有 Modifier 参与合成（胜出 Base 层未抑制且至少一条活跃）；{@link #evaluate} 每次刷新。
     * 消费方 = 主相机那份的取用点（主相机 lane 据此判定「链输出是否已被 Modifier 改写」）。
     */
    public boolean modifiersApplied() {
        return modifiersApplied;
    }

    /**
     * 本帧胜出 Base 层的档位（{@code PRIORITY_*}；无活跃 Base 时为 {@link Integer#MIN_VALUE}）；
     * {@link #evaluate} 每次刷新。消费方 = 主画面 lane 的取用点（链胜出层非脚本导演时画面取链输出）。
     */
    public int winnerPriority() {
        return winnerPriority;
    }

    /**
     * 按注册表次序（优先级升序）逐条合成：每条先把值写进值工作区、再按掩码与模式就地并入（零分配）；
     * 本帧不活跃（{@link CameraModifier#isActiveThisFrame()} 为 false）者整条跳过。
     *
     * @return 本帧是否至少合成了一条
     */
    private boolean composeModifiers(CameraStateBuffer out) {
        boolean applied = false;
        for (int i = 0; i < modifiers.size(); i++) {
            CameraModifier modifier = modifiers.get(i);
            if (!modifier.isActiveThisFrame()) continue;
            modifier.writeValue(modifierValues);
            out.compose(modifier.channelMask(), modifier.composeMode(), modifierValues);
            applied = true;
        }
        return applied;
    }

    /** 逆序扫描（后注册者先问）、严格大于才替换 → 高优先级胜、同优先级后注册者胜。 */
    private BaseCameraProvider selectWinner() {
        BaseCameraProvider winner = null;
        int winnerPriority = Integer.MIN_VALUE;
        for (int i = providers.size() - 1; i >= 0; i--) {
            BaseCameraProvider provider = providers.get(i);
            if (!provider.isBaseActive()) continue;
            if (winner == null || provider.basePriority() > winnerPriority) {
                winner = provider;
                winnerPriority = provider.basePriority();
            }
        }
        return winner;
    }
}
