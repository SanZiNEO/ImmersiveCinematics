package com.immersivecinematics.immersive_cinematics.api;

/**
 * Base 层接口：产出「这一帧的基础相机状态」，每层给出整帧六参数（不做部分覆盖）。
 *
 * 优先级域：Base 与 Modifier 是两条独立优先级域，数值互不比较；链按优先级从高到低询问，
 * 同优先级 = 后注册者胜；无活跃层 = 回退玩家相机。内置档位见 {@code PRIORITY_*} 常量。
 * 切换语义：Base 层之间为 Cut（瞬时切换，无过渡）。
 * 分配契约：{@link #provideBaseState()} 必须复用本层已有的快照产出（不新建 record、不复制）。
 * 线程契约：仅客户端主线程（tick 与渲染同线程）；跨线程调用方须自行 marshal 到主线程。
 * 状态所有权：链只读 provider 返回的快照，不持有、不修改 provider 的内部状态。
 */
public interface BaseCameraProvider {

    /** 档位：外部接管。 */
    int PRIORITY_EXTERNAL = 400;

    /** 档位：飞行取景（六参数直写）。 */
    int PRIORITY_FLIGHT = 300;

    /** 档位：编辑器直控（编辑器五参数直写，位置不动）。 */
    int PRIORITY_EDITOR_DIRECT = 200;

    /** 档位：脚本导演（相机轨道播放器）。 */
    int PRIORITY_SCRIPT_DIRECTOR = 100;

    /** 本层本帧是否活跃；链按优先级从高到低询问，取第一个活跃层。 */
    boolean isBaseActive();

    /** 优先级：数值大者胜；同值 = 后注册者胜。 */
    int basePriority();

    /** 整帧六参数（世界坐标 / 度 / 倍率）；仅在 {@link #isBaseActive()} 为 true 时被调用，非 {@code null}。 */
    CameraState provideBaseState();

    /** true = 本层胜出时本帧跳过 Modifier 链，链输出 = 本层原始六参数；缺省 false。 */
    default boolean suppressesModifiers() {
        return false;
    }
}
