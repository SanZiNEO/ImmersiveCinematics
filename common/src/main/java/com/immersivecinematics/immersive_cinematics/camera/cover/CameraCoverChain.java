package com.immersivecinematics.immersive_cinematics.camera.cover;

import com.immersivecinematics.immersive_cinematics.camera.CameraState;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆盖链本体（Base Chain）：Base 层注册表 + 每帧选择。
 *
 * 选择规则：按优先级从高到低取第一个活跃层；同优先级 = 后注册者胜（后来者居上）。
 * 无活跃层 = 无 Base（{@link #snapshot()} 返回 {@code null}，调用方按回退玩家相机处理）。
 * 切换语义：层间切换为 Cut——不混合、不过渡、不迟滞。
 * 分配契约：注册表只在注册 / 注销时改动（不在帧内）；{@link #snapshot()} 每帧零新增分配，
 * 唯一分配点 = 快照 record 的产出（工作区 {@link CameraStateBuffer} 跨帧复用）。
 * 线程契约：仅客户端主线程；注册 / 注销与每帧求值同线程。
 */
public final class CameraCoverChain {

    /** 注册顺序 = 同优先级次序（后注册者胜）。 */
    private final List<BaseCameraProvider> providers = new ArrayList<>(4);

    /** 链的唯一工作区（跨帧复用）。 */
    private final CameraStateBuffer workspace = new CameraStateBuffer();

    /** 注册一层；同一实例重复注册 = 多份（注销按对象逐个移除）。 */
    public void register(BaseCameraProvider provider) {
        providers.add(provider);
    }

    /** 注销一层；未注册过时为无操作。 */
    public void unregister(BaseCameraProvider provider) {
        providers.remove(provider);
    }

    /** 本帧链输出快照；无活跃 Base 时返回 {@code null}。 */
    public CameraState snapshot() {
        return evaluate(workspace) ? workspace.toSnapshot() : null;
    }

    /**
     * 选出本帧胜出的 Base 层并把其状态读进工作区。
     *
     * @return {@code false} = 无活跃 Base（工作区未改动）
     */
    private boolean evaluate(CameraStateBuffer out) {
        BaseCameraProvider winner = selectWinner();
        if (winner == null) return false;
        out.set(winner.provideBaseState());
        return true;
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
